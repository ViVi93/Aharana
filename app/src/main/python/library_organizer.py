"""Stdlib EPUB metadata naming and recoverable library organisation.

Public entry points return plain JSON-serialisable dictionaries. Names use the
first author (not editor/translator) in OPF document order. No ZIP extraction.

preview(root) is read-only. apply(plan, journal_dir) and undo(journal_dir)
return only moves performed by that invocation, including failures after a
filesystem operation. recovery(journal_dir) retains history and returns flattened
historic endpoint aliases -> the CURRENT file path (identity aliases omitted),
plus latest-operation root/can_undo/state/operation_id. Callers must reconcile
returned moves even if ok is false. Only the latest operation can be undone.
undo_preview(journal_dir) validates identities and returns ALL eligible reverse
moves without moving files (pending crash recovery may still be checkpointed).

Deletion protocol (also exposed by json_call):
  delete_prepare: JSON {path, book_id?} -> {ok, token}; always stores a pending
    deletion, including unorganised files. It never unlinks the file itself.
  delete_finish: JSON {token, deleted: boolean} -> {ok}; success tombstones the
    identity permanently. Failure restores it ONLY for the exact unchanged
    prepared filesystem object. Finished tokens are idempotent, not reversible.
The application MUST hold its shared storage gate continuously through prepare,
unlink, finish, and exact Room row deletion. Recovery of a pending deletion
rolls back only when the original fingerprint (including inode) is unchanged;
missing/changed/unsafe targets become tombstones, never replacement aliases.
Recovery/info includes deleted_books [{book_id, path}] for exact ID+path Room
cleanup, and retired_paths (all deleted lineage endpoints). Retired endpoints
can be occupied by NEW books; never use these lists to delete files or remove a
Room replacement row by path alone. Tombstones are retained for repeated startup
reconciliation. Without book_id, deletion still invalidates aliases/undo but
cannot request a Room row deletion.

EPUB dc:identifier and dc:source values are checkpointed at apply. Genuine
same-identity content/title updates, including atomic inode replacement, remain
undoable. Anonymous EPUBs and old journals without stored identities require
unchanged trusted content; readable replacement ZIPs are not proof of identity.

Moves prefer atomic Linux/Android renameat2(RENAME_NOREPLACE), then exclusive
hardlink/unlink, then a bounded-memory exclusive copy, fsync, SHA-256 verification,
and unlink. There is never an unchecked rename over a destination placeholder.
An interrupted incomplete fallback copy is deliberately retained alongside its
original and reported as an error for manual inspection, rather than deleting
potentially modified data. A durable ready copy is completed automatically.
Unsupported directory fsync is tolerated on emulated storage; power-loss
durability is necessarily limited by that filesystem, not simulated here.
"""
import hashlib
from contextlib import contextmanager
import ctypes
import errno
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import unicodedata
import uuid
import xml.etree.ElementTree as ET
import zipfile

DC = '{http://purl.org/dc/elements/1.1/}'
OPF = '{http://www.idpf.org/2007/opf}'


@contextmanager
def _directory(path, create=False):
    """Pin ancestors for search only; read just the requested directory.

    Android may allow traversal of /storage or /data without permission to read
    those ancestors (including /). Linux O_PATH does not request directory-read
    access. O_NOFOLLOW and dir_fd traversal retain the existing symlink guards.
    Chaquopy may omit the O_PATH constant; its Linux ABI value is 0x200000.
    """
    path = os.path.abspath(os.fspath(path))
    search_flags = getattr(os, 'O_PATH', 0x200000) | os.O_DIRECTORY | os.O_NOFOLLOW
    fd = os.open('/', search_flags)
    try:
        for part in Path(path).parts[1:]:
            if create:
                try:
                    os.mkdir(part, dir_fd=fd)
                    # O_PATH fds cannot be fsynced. Only a directory in which we
                    # actually created a child needs a readable sync descriptor.
                    sync_fd = os.open('.', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                    try:
                        _sync(sync_fd)
                    finally:
                        os.close(sync_fd)
                except FileExistsError:
                    pass
            child = os.open(part, search_flags, dir_fd=fd)
            os.close(fd)
            fd = child
        readable = os.open('.', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
        os.close(fd)
        fd = readable
        yield fd
    finally:
        os.close(fd)


@contextmanager
def _reading(path):
    with _directory(Path(path).parent) as parent:
        fd = os.open(Path(path).name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    with os.fdopen(fd, 'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError('Source is not a regular file')
        yield stream


def _fingerprint(path):
    digest = hashlib.sha256()
    with _reading(path) as stream:
        stat = os.fstat(stream.fileno())
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return {'sha256': digest.hexdigest(), 'size': stat.st_size,
            'mtime_ns': stat.st_mtime_ns, 'device': stat.st_dev, 'inode': stat.st_ino}


def _metadata(path):
    with _reading(path) as stream, zipfile.ZipFile(stream) as archive:
        container = _metadata_xml(archive, 'META-INF/container.xml')
        opf = next(node.attrib['full-path'] for node in container.iter()
                   if node.tag.rsplit('}', 1)[-1] == 'rootfile')
        package = _metadata_xml(archive, opf)
    title = next((node.text.strip() for node in package.iter(DC + 'title')
                  if node.text and node.text.strip()), '')
    if not title:
        raise ValueError('Missing EPUB title')
    roles = {node.attrib.get('refines', '').lstrip('#'): (node.text or '').strip().lower()
             for node in package.iter(OPF + 'meta') if node.attrib.get('property') == 'role'}
    authors = []
    for node in package.iter(DC + 'creator'):
        role = roles.get(node.attrib.get('id', ''), node.attrib.get(OPF + 'role', '')).lower()
        if role in ('', 'aut', 'author') and node.text and node.text.strip():
            authors.append(node.text.strip())
    author = authors[0] if authors else 'Unknown Author'
    identifier = next((node.text.strip() for node in package.iter(DC + 'identifier')
                       if node.text and node.text.strip()), '')
    tokens = re.findall(r'(?:/works/|/s/|storyid=|/story/)([A-Za-z0-9_-]+)', identifier)
    token = tokens[0][:32] if tokens else hashlib.sha256(identifier.encode('utf-8') or title.encode('utf-8')).hexdigest()[:10]
    return title, author, token


def _story_identity(path):
    """Persist embedded identifiers, never title/author-derived naming tokens."""
    with _reading(path) as stream, zipfile.ZipFile(stream) as archive:
        container = _metadata_xml(archive, 'META-INF/container.xml')
        opf = next(node.attrib['full-path'] for node in container.iter()
                   if node.tag.rsplit('}', 1)[-1] == 'rootfile')
        package = _metadata_xml(archive, opf)
    return {name: sorted({node.text.strip() for node in package.iter(DC + name)
                          if node.text and node.text.strip()})
            for name in ('identifier', 'source')}


def _verified_current(path, record):
    current = _fingerprint(path)
    identity = record.get('story_identity')
    if identity and any(identity.values()):
        valid = _story_identity(path) == identity
    else:
        # Legacy journals and anonymous books have no stable identity evidence.
        # Only the trusted apply fingerprint is safe; never bless a readable ZIP.
        valid = all(current[key] == record['fingerprint'][key] for key in ('sha256', 'size'))
    if not valid:
        raise ValueError('Ambiguous story identity at ' + path + '; refusing stale aliases or undo')
    return current


def _metadata_xml(archive, member):
    if archive.getinfo(member).file_size > 2 * 1024 * 1024:
        raise ValueError('EPUB metadata exceeds 2 MiB safety limit')
    with archive.open(member) as stream:
        content = stream.read(2 * 1024 * 1024 + 1)
    if len(content) > 2 * 1024 * 1024 or b'<!DOCTYPE' in content.upper() or b'<!ENTITY' in content.upper():
        raise ValueError('Unsafe EPUB metadata XML')
    return ET.fromstring(content)


def _component(text, budget=220):
    text = unicodedata.normalize('NFC', text)
    text = ''.join('_' if c in '/\\:*?"<>|' or unicodedata.category(c).startswith('C') else c
                   for c in text).strip(' .')
    if not text:
        text = '_'
    if re.fullmatch(r'(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?', text):
        text = '_' + text
    return text.encode('utf-8')[:budget].decode('utf-8', 'ignore').rstrip(' .') or '_'


def preview(root) -> dict:
    root = os.path.abspath(os.fspath(root))
    entries = []
    try:
        with _directory(root):
            pass
    except Exception as exc:
        return {'ok': False, 'root': root, 'entries': [], 'move_count': 0,
                'unchanged_count': 0, 'skipped_count': 0, 'error': str(exc)}
    occupied = {_key(str(p)): str(p) for p in Path(root).rglob('*')}
    for source in sorted(p for p in Path(root).rglob('*') if p.suffix.lower() == '.epub'):
        entry = {'source': str(source), 'target': '', 'title': '', 'author': '',
                 'status': 'skipped', 'fingerprint': None}
        try:
            title, author, token = _metadata(source)
            target = _target(root, source, title, author, token, occupied)
            occupied[_key(target)] = target
            folder = str(Path(target).parent)
            occupied.setdefault(_key(folder), folder)
            entry.update(target=target, title=title, author=author,
                         status='unchanged' if str(source) == target else 'move',
                         fingerprint=_fingerprint(source))
        except Exception as exc:
            entry['error'] = str(exc)
        entries.append(entry)
    return {'ok': True, 'root': root, 'entries': entries,
            'move_count': sum(e['status'] == 'move' for e in entries),
            'unchanged_count': sum(e['status'] == 'unchanged' for e in entries),
            'skipped_count': sum(e['status'] == 'skipped' for e in entries)}


def _key(path):
    return unicodedata.normalize('NFC', str(path)).casefold()


def _target(root, source, title, author, token, occupied):
    folder = str(Path(root) / _component(author))
    folder = occupied.get(_key(folder), folder)
    if os.path.lexists(folder) and (os.path.islink(folder) or not os.path.isdir(folder)):
        raise ValueError('Author folder is not a real directory')
    stem = _component(title)
    base = str(Path(folder) / (stem + '.epub'))
    # Preserve already organised collision names on later previews.
    if source is not None and _key(source.parent) == _key(folder):
        if _key(source.name) == _key(stem + '.epub'):
            return str(source)
        match = re.search(r' \[' + re.escape(token) + r'(?:-(\d+))?\]$', source.stem)
        if match:
            index = int(match.group(1) or '1')
            if _key(source.name) == _key(_collision_name(stem, token, index)):
                return str(source)
    candidate = base
    index = 0
    while _key(candidate) in occupied:
        index += 1
        candidate = str(Path(folder) / _collision_name(stem, token, index))
    return candidate


def _collision_name(stem, token, index):
    suffix = ' [' + token + (('-' + str(index)) if index > 1 else '') + ']'
    return _component(stem, 235 - len((suffix + '.epub').encode('utf-8'))) + suffix + '.epub'


def _sync(fd):
    try:
        os.fsync(fd)
    except OSError as exc:
        # Some Android emulated filesystems cannot fsync directories.
        if exc.errno not in (errno.EINVAL, errno.ENOTSUP):
            raise


@contextmanager
def _journal_lock(journal_dir):
    if not journal_dir:
        raise ValueError('A private journal directory is required')
    with _directory(journal_dir, create=True) as directory:
        fd = os.open('organizer.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            yield
        finally:
            os.close(fd)


def _load(journal_dir):
    try:
        with _reading(Path(journal_dir) / 'organizer.json') as stream:
            data = json.load(stream)
    except FileNotFoundError:
        return {'version': 1, 'operations': []}
    if data.get('version') != 1 or not isinstance(data.get('operations'), list):
        raise ValueError('Invalid organizer journal')
    for operation in data['operations']:
        root = operation['root']
        if not os.path.isabs(root) or os.path.abspath(root) != root:
            raise ValueError('Invalid journal root')
        for record in operation['records']:
            for name in ('source', 'target'):
                path = record[name]
                if not os.path.isabs(path) or os.path.abspath(path) != path or path == root or os.path.commonpath((root, path)) != root:
                    raise ValueError('Journal endpoint escapes library root')
    return data


def _save(journal_dir, data):
    with _directory(journal_dir) as directory:
        temporary = '.organizer-' + uuid.uuid4().hex
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(data, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, 'organizer.json', src_dir_fd=directory, dst_dir_fd=directory)
            _sync(directory)
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass


def _rename_noreplace(source, target, source_fd, target_fd):
    libc = ctypes.CDLL(None, use_errno=True)
    rename = getattr(libc, 'renameat2', None)
    if rename is None:
        raise OSError(errno.ENOSYS, 'renameat2 unavailable')
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(source_fd, os.fsencode(Path(source).name), target_fd, os.fsencode(Path(target).name), 1):
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code), target)


def _move(source, target, checkpoint=None, expected=None):
    if expected is not None and _fingerprint(source) != expected:
        raise ValueError('Source changed since preview: ' + source)
    with _directory(Path(source).parent) as source_fd, _directory(Path(target).parent, create=True) as target_fd:
        _destination_free(target_fd, Path(target).name)
        try:
            _rename_noreplace(source, target, source_fd, target_fd)
        except OSError as exc:
            if exc.errno not in (errno.ENOSYS, errno.EINVAL, errno.ENOTSUP, errno.EXDEV, errno.EPERM):
                raise
            try:
                os.link(Path(source).name, Path(target).name, src_dir_fd=source_fd,
                        dst_dir_fd=target_fd, follow_symlinks=False)
                _sync(target_fd)
            except OSError as link_error:
                if link_error.errno not in (errno.ENOSYS, errno.EINVAL, errno.ENOTSUP, errno.EXDEV, errno.EPERM, errno.EACCES):
                    raise
                _copy_exclusive(source, target, target_fd, checkpoint)
            if expected is not None and _fingerprint(source) != expected:
                raise ValueError('Source changed during transfer; both endpoints retained')
            os.unlink(Path(source).name, dir_fd=source_fd)
        _sync(source_fd)
        _sync(target_fd)


def _copy_exclusive(source, target, directory, checkpoint=None):
    """Bounded streaming fallback; the source survives until a verified durable copy."""
    with _reading(source) as incoming:
        original = os.fstat(incoming.fileno())
        fd = os.open(Path(target).name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     stat.S_IMODE(original.st_mode) & 0o777, dir_fd=directory)
        with os.fdopen(fd, 'wb') as outgoing:
            identity = os.fstat(outgoing.fileno())
            transfer = {'mode': 'copy', 'phase': 'copying', 'device': identity.st_dev, 'inode': identity.st_ino}
            if checkpoint:
                checkpoint(transfer)
            digest = hashlib.sha256()
            for chunk in iter(lambda: incoming.read(1024 * 1024), b''):
                outgoing.write(chunk)
                digest.update(chunk)
            outgoing.flush()
            os.utime(outgoing.fileno(), ns=(original.st_atime_ns, original.st_mtime_ns))
            os.fsync(outgoing.fileno())
            _sync(directory)
            expected = {'sha256': digest.hexdigest(), 'size': original.st_size}
            if not _same_content(target, expected) or not _same_content(source, expected):
                raise ValueError('Copy verification failed or source changed')
            transfer['phase'] = 'ready'
            if checkpoint:
                checkpoint(transfer)


def _destination_free(directory, name):
    if any(_key(existing) == _key(name) for existing in os.listdir(directory)):
        raise FileExistsError('Destination already exists: ' + name)


def apply(plan, journal_dir) -> dict:
    moves = []
    operation = None
    try:
        fresh = preview(plan['root'])
        if not plan.get('ok') or not fresh['ok'] or fresh['entries'] != plan['entries']:
            raise ValueError('Stale or invalid preview; preview the library again')
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            _reconcile(journal_dir, data)
            # A new operation must not turn an untrusted replacement into the
            # owner of an older identity's aliases.
            current_records = {current: record for current, record in _history(data).values()}
            for current, record in current_records.items():
                _verified_current(current, record)
            if not any(e['status'] == 'move' for e in plan['entries']):
                return dict(ok=True, moves=[], **_info(data))
            operation = {'id': uuid.uuid4().hex, 'root': plan['root'], 'state': 'applying', 'records': []}
            data['operations'].append(operation)
            _save(journal_dir, data)
            for entry in plan['entries']:
                if entry['status'] != 'move':
                    continue
                record = {'source': entry['source'], 'target': entry['target'],
                          'fingerprint': entry['fingerprint'],
                          'story_identity': _story_identity(entry['source']), 'state': 'pending'}
                operation['records'].append(record)
                _save(journal_dir, data)
                def checkpoint(transfer):
                    record['transfer'] = transfer
                    _save(journal_dir, data)
                _move(record['source'], record['target'], checkpoint=checkpoint, expected=record['fingerprint'])
                moves.append({'source': record['source'], 'target': record['target']})
                record['state'] = 'done'
                _save(journal_dir, data)
            operation['state'] = 'applied'
            _save(journal_dir, data)
        return dict(ok=True, moves=moves, **_info(data))
    except Exception as exc:
        if operation is None:
            return {'ok': False, 'moves': [], 'error': str(exc), 'root': '', 'can_undo': False}
        return _failure(journal_dir, operation['id'], moves, exc)


def delete_prepare(path, journal_dir, book_id=None) -> dict:
    """Durably checkpoint before unlink. Caller holds its storage gate through finish."""
    try:
        path = os.path.abspath(os.fspath(path))
        # Ordinary deletion accepts Android storage aliases, unlike bulk moves.
        # Resolve directory ancestors once, but do not follow a final file symlink.
        # Keep the lexical path for exact Room evidence; unlink uses this fixed path.
        identity_path = str(Path(os.path.realpath(Path(path).parent)) / Path(path).name)
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            _reconcile(journal_dir, data)
            history = _history(data)
            owner = history.get(identity_path)
            deletion = {'token': uuid.uuid4().hex, 'path': path, 'identity_path': identity_path,
                        'book_id': book_id, 'after_operation': len(data['operations']),
                        'aliases': sorted({path, identity_path} | {alias for alias, (current, _) in history.items()
                                                   if current == identity_path}),
                        'fingerprint': _fingerprint(identity_path), 'state': 'pending'}
            data.setdefault('deletions', []).append(deletion)
            if owner is not None and owner[0] == identity_path:
                record = owner[1]
                record['delete_from_state'] = record['state']
                record['state'] = 'delete_pending'
                record['deletion_token'] = deletion['token']
            _save(journal_dir, data)
            return {'ok': True, 'token': deletion['token'], 'delete_path': identity_path}
    except Exception as exc:
        return {'ok': False, 'error': str(exc)}


def delete_finish(token, deleted, journal_dir) -> dict:
    """Complete a prepared deletion; token comes from delete_prepare."""
    try:
        if not isinstance(deleted, bool):
            raise ValueError('deleted must be a JSON boolean')
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            deletion = next(d for d in data.get('deletions', []) if d['token'] == token)
            if deletion['state'] == 'pending':
                _resolve_deletion(data, deletion, deleted)
                _save(journal_dir, data)
            return {'ok': True}
    except Exception as exc:
        return {'ok': False, 'error': str(exc)}


def _resolve_deletion(data, deletion, deleted=False):
    # Only an exact, unchanged filesystem object proves unlink did not happen.
    # Story identity alone is insufficient after a prepared deletion.
    unchanged = False
    if not deleted:
        try:
            unchanged = _fingerprint(deletion.get('identity_path', deletion['path'])) == deletion['fingerprint']
        except (OSError, ValueError):
            pass
    deletion['state'] = 'cancelled' if unchanged else 'deleted'
    for operation in data['operations']:
        for record in operation['records']:
            if record.get('deletion_token') == deletion['token']:
                record['state'] = (record['delete_from_state'] if unchanged else 'deleted')


def _failure(journal_dir, operation_id, moves, error, reverse=False, eligible=None):
    info = {'root': '', 'can_undo': False}
    try:
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            try:
                _reconcile(journal_dir, data)
            finally:
                info = _info(data)
                operation = next(op for op in data['operations'] if op['id'] == operation_id)
                actual = [{'source': r['target'] if reverse else r['source'],
                           'target': r['source'] if reverse else r['target']}
                          for r in operation['records'] if r['state'] == ('undone' if reverse else 'done')
                          and (eligible is None or r['source'] in eligible)]
                if reverse:
                    actual.reverse()
                moves = actual
    except Exception as recovery_error:
        error = str(error) + '; recovery: ' + str(recovery_error)
    return dict(ok=False, moves=moves, error=str(error), **info)


def _info(data):
    latest = data['operations'][-1] if data['operations'] else None
    retired = {d['path'] for d in data.get('deletions', []) if d['state'] == 'deleted'}
    _history(data, retired)
    deleted_books = {(d['book_id'], d['path']): {'book_id': d['book_id'], 'path': d['path']}
                     for d in data.get('deletions', [])
                     if d['state'] == 'deleted' and d.get('book_id') is not None}
    return {'root': latest['root'] if latest else '',
            'deleted_books': list(deleted_books.values()), 'retired_paths': sorted(retired),
            'can_undo': bool(latest and any(r['state'] == 'done' for r in latest['records'])),
            'operation_id': latest['id'] if latest else '',
            'state': latest['state'] if latest else 'empty'}


def _history(data, retired=None):
    """Flatten paths while retaining the terminal record which owns each identity."""
    aliases = {}

    def record_move(source, target, record):
        for alias, (current, _) in list(aliases.items()):
            if current == source:
                aliases[alias] = (target, record)
        aliases[source] = (target, record)
        aliases[target] = (target, record)

    def retire_deletions(operation_count):
        for deletion in data.get('deletions', []):
            if (deletion['state'] in ('deleted', 'pending') and
                    deletion.get('after_operation') == operation_count):
                for alias in deletion['aliases']:
                    aliases.pop(alias, None)
                    if retired is not None and deletion['state'] == 'deleted':
                        retired.add(alias)

    retire_deletions(0)
    for operation_count, operation in enumerate(data['operations'], 1):
        for record in operation['records']:
            state = record.get('delete_from_state') if record['state'] in ('deleted', 'delete_pending') else record['state']
            if state in ('done', 'undo_pending', 'undone'):
                record_move(record['source'], record['target'], record)
        for record in reversed(operation['records']):
            state = record.get('delete_from_state') if record['state'] in ('deleted', 'delete_pending') else record['state']
            if state == 'undone':
                record_move(record['target'], record['source'], record)
        # A terminal deletion is a history barrier, not merely a skipped move.
        # Remove the entire flattened lineage BEFORE any new occupant can move.
        if retired is not None:
            retired.update(alias for alias, (_, owner) in aliases.items()
                           if owner['state'] == 'deleted')
        aliases = {alias: value for alias, value in aliases.items()
                   if value[1]['state'] not in ('deleted', 'delete_pending')}
        retire_deletions(operation_count)
    return aliases


def _aliases(data, errors):
    moves = []
    for alias, (current, record) in _history(data).items():
        # An occupied historical endpoint may now identify a different book.
        # Only absent paths are safe to reconcile, even after content updates.
        if alias == current or os.path.lexists(alias):
            continue
        try:
            _verified_current(current, record)
        except FileNotFoundError:
            continue
        except Exception as exc:
            # One unsafe endpoint must not discard independent completed moves.
            errors.append(str(exc))
            continue
        moves.append({'source': alias, 'target': current})
    return moves


def recovery(journal_dir) -> dict:
    try:
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            errors = []
            try:
                _reconcile(journal_dir, data)
            except Exception as exc:
                errors.append(str(exc))
            moves = _aliases(data, errors)
            result: dict = dict(ok=not errors, moves=moves, **_info(data))
            if errors:
                result['error'] = '; '.join(errors)
            return result
    except Exception as exc:
        return {'ok': False, 'moves': [], 'error': str(exc), 'root': '', 'can_undo': False}


def _same_content(path, fingerprint):
    current = _fingerprint(path)
    return all(current[key] == fingerprint[key] for key in ('sha256', 'size'))


def _reconcile(journal_dir, data):
    changed = False
    for deletion in data.get('deletions', []):
        if deletion['state'] == 'pending':
            _resolve_deletion(data, deletion)
            changed = True
    # Publish deletion decisions before any unrelated transfer can fail.
    if changed:
        _save(journal_dir, data)
    for operation in data['operations']:
        for record in operation['records']:
            if record['state'] not in ('pending', 'undo_pending'):
                continue
            reverse = record['state'] == 'undo_pending'
            source = record['target'] if reverse else record['source']
            target = record['source'] if reverse else record['target']
            fingerprint = record['undo_fingerprint'] if reverse else record['fingerprint']
            transfer = record.get('transfer', {})
            if os.path.lexists(source) and os.path.lexists(target):
                source_stat = os.stat(source, follow_symlinks=False)
                target_stat = os.stat(target, follow_symlinks=False)
                linked = (source_stat.st_dev, source_stat.st_ino) == (target_stat.st_dev, target_stat.st_ino)
                ready = transfer.get('phase') == 'ready' and (target_stat.st_dev, target_stat.st_ino) == (transfer.get('device'), transfer.get('inode'))
                if transfer.get('phase') == 'copying':
                    raise ValueError('Incomplete exclusive copy retained at ' + target + '; original retained at ' + source + '. Remove the incomplete copy only after checking the original, then retry recovery.')
                if linked or ready:
                    if _fingerprint(source) != fingerprint or not _same_content(target, fingerprint):
                        raise ValueError('Interrupted copy changed; both endpoints retained')
                    with _directory(Path(source).parent) as directory:
                        os.unlink(Path(source).name, dir_fd=directory)
                        _sync(directory)
            if not os.path.lexists(source) and os.path.lexists(target):
                if not _same_content(target, fingerprint):
                    raise ValueError('Interrupted move target changed; refusing to guess: ' + target)
                record['state'] = 'undone' if reverse else 'done'
            elif os.path.lexists(source):
                # No atomic rename occurred; never touch a possibly unrelated destination.
                record['state'] = 'done' if reverse else 'failed'
            else:
                raise ValueError('Both interrupted move endpoints are missing')
            changed = True
        if operation['state'] in ('applying', 'undoing'):
            operation['state'] = 'undone' if operation['state'] == 'undoing' and not any(r['state'] == 'done' for r in operation['records']) else 'applied'
            changed = True
    if changed:
        _save(journal_dir, data)


def undo_preview(journal_dir) -> dict:
    """Plan every live reverse move for Room conflict checks under the storage gate."""
    try:
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            _reconcile(journal_dir, data)
            moves = []
            if data['operations']:
                for record in reversed(data['operations'][-1]['records']):
                    if record['state'] == 'done':
                        _verified_current(record['target'], record)
                        moves.append({'source': record['target'], 'target': record['source']})
            return dict(ok=True, moves=moves, **_info(data))
    except Exception as exc:
        return {'ok': False, 'moves': [], 'error': str(exc)}


def undo(journal_dir) -> dict:
    moves = []
    operation = None
    eligible = set()
    try:
        with _journal_lock(journal_dir):
            data = _load(journal_dir)
            _reconcile(journal_dir, data)
            if not data['operations']:
                return dict(ok=True, moves=[], **_info(data))
            operation = data['operations'][-1]
            eligible = {r['source'] for r in operation['records'] if r['state'] == 'done'}
            # Identity ambiguity anywhere must stop undo before the first unlink.
            for record in operation['records']:
                if record['state'] == 'done':
                    _verified_current(record['target'], record)
            operation['state'] = 'undoing'
            for record in reversed(operation['records']):
                if record['state'] != 'done':
                    continue
                # Genuine updates may replace the inode; prove story identity first.
                record['undo_fingerprint'] = _verified_current(record['target'], record)
                record['state'] = 'undo_pending'
                record.pop('transfer', None)
                _save(journal_dir, data)
                def checkpoint(transfer):
                    record['transfer'] = transfer
                    _save(journal_dir, data)
                _move(record['target'], record['source'], checkpoint=checkpoint, expected=record['undo_fingerprint'])
                moves.append({'source': record['target'], 'target': record['source']})
                record['state'] = 'undone'
                _save(journal_dir, data)
            operation['state'] = 'undone'
            _save(journal_dir, data)
            return dict(ok=True, moves=moves, **_info(data))
    except Exception as exc:
        if operation is None:
            return {'ok': False, 'moves': [], 'error': str(exc), 'root': '', 'can_undo': False}
        return _failure(journal_dir, operation['id'], moves, exc, reverse=True, eligible=eligible)


def copy_download(source, root) -> dict:
    """Copy a freshly downloaded EPUB exclusively; never remove the download."""
    try:
        source, root = os.path.abspath(os.fspath(source)), os.path.abspath(os.fspath(root))
        with _directory(root, create=True):
            pass
        title, author, token = _metadata(source)
        for _ in range(32):
            occupied = {_key(str(p)): str(p) for p in Path(root).rglob('*')}
            target = _target(root, None, title, author, token, occupied)
            try:
                with _directory(Path(target).parent, create=True) as directory:
                    _destination_free(directory, Path(target).name)
                    _copy_exclusive(source, target, directory)
                return {'ok': True, 'path': target}
            except FileExistsError:
                continue
        raise ValueError('Download destination repeatedly changed; retry later')
    except Exception as exc:
        return {'ok': False, 'path': '', 'error': str(exc)}


def json_call(action, payload='', journal_dir='') -> str:
    """Chaquopy entry point; apply/copy_download payloads are JSON strings."""
    try:
        if action == 'preview':
            result = preview(payload)
        elif action == 'apply':
            result = apply(json.loads(payload), journal_dir)
        elif action == 'delete_prepare':
            request = json.loads(payload)
            result = delete_prepare(request['path'], journal_dir, request.get('book_id'))
        elif action == 'delete_finish':
            request = json.loads(payload)
            result = delete_finish(request['token'], request['deleted'], journal_dir)
        elif action == 'undo_preview':
            result = undo_preview(journal_dir)
        elif action == 'undo':
            result = undo(journal_dir)
        elif action == 'recovery':
            result = recovery(journal_dir)
        elif action == 'copy_download':
            request = json.loads(payload)
            result = copy_download(request['source'], request['root'])
        else:
            raise ValueError('Unknown organizer action: ' + str(action))
    except Exception as exc:
        result = {'ok': False, 'moves': [], 'error': str(exc), 'root': '', 'can_undo': False}
    return json.dumps(result, ensure_ascii=False)
