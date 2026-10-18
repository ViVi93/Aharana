"""Portable facet payload tests; uses only the cached Kotlin compiler and JDK."""
from pathlib import Path
import os
import re
import sqlite3
import subprocess
import tempfile


def sql_tests(repo):
    """Run the wrapper's exact SQL on host SQLite, not an Android database mock."""
    source = (repo / 'app/src/main/java/com/vivi/aharana/LibraryFacetIndexDatabase.kt').read_text()

    def sql(name):
        match = re.search(r'const val ' + name + r'\s*=\s*"""(.*?)"""', source, re.S)
        if not match:
            raise AssertionError(f'Missing production SQL: {name}')
        return match.group(1)

    with sqlite3.connect(':memory:') as db:
        db.execute(sql('CREATE_TABLE_SQL'))
        columns = db.execute('PRAGMA table_info(library_facet_index)').fetchall()
        assert [row[1] for row in columns] == ['path', 'book_signature', 'file_signature', 'payload', 'payload_sha256']
        assert columns[0][5] == 1  # Opaque source path is the only primary key.
        original = ('content://library/a', 'b1', 'f1', b'old', bytes(32))
        updated = ('content://library/a', 'b2', 'f2', b'new', bytes(range(32)))
        db.execute(sql('UPSERT_SQL'), original)
        db.execute(sql('UPSERT_SQL'), updated)
        assert db.execute('SELECT * FROM library_facet_index').fetchall() == [updated]
        db.execute(sql('INVALIDATE_SQL'), ("' OR 1=1 --",))
        assert db.execute('SELECT COUNT(*) FROM library_facet_index').fetchone() == (1,)
        db.execute(sql('INVALIDATE_SQL'), (updated[0],))
        assert db.execute('SELECT COUNT(*) FROM library_facet_index').fetchone() == (0,)
        print(f'PASS: SQLite {sqlite3.sqlite_version} schema replacement and bound invalidation', flush=True)
        good = ('valid', 'b', 'f', bytes(48), bytes(32))
        db.execute(sql('UPSERT_SQL'), good)
        invalid_fields = {
            0: [b'blob-path', 'x' * 65_537, 'p\0' + 'x' * 65_536],
            1: [b'blob-signature', 'x' * 65_537, 'é' * 32_769, 'b\0' + 'x' * 65_536],
            2: [b'blob-signature', 'x' * 65_537, 'é' * 32_769],
            3: ['x' * 48, bytes(47), bytes(524_289)],
            4: ['x' * 32, bytes(31), bytes(33)],
        }
        for column, values in invalid_fields.items():
            for index, value in enumerate(values):
                row: list[object] = list(good)
                row[0] = f'bad-{column}-{index}'
                row[column] = value
                db.execute(sql('UPSERT_SQL'), row)
        assert db.execute(sql('READ_ALL_SQL')).fetchall() == [good]
        boundary = ('p' * 65_536, 'é' * 32_768, 'f' * 65_536, bytes(524_288), bytes(32))
        db.execute(sql('UPSERT_SQL'), boundary)
        assert set(row[0] for row in db.execute(sql('READ_ALL_SQL'))) == {good[0], boundary[0]}
        # All source paths remain data, even when they contain quotes or SQL-like punctuation.
        db.execute(sql('INVALIDATE_SQL'), (boundary[0],))
        bulk = [(f'book-{i}', 'b', 'f', bytes(48), bytes(32)) for i in range(3500)]
        db.executemany(sql('UPSERT_SQL'), bulk)
        reads = []
        db.set_trace_callback(reads.append)
        rows = db.execute(sql('READ_ALL_SQL')).fetchall()
        db.set_trace_callback(None)
        assert len(rows) == 3501 and len(reads) == 1
        print('PASS: bulk single-query SQL filters oversized and wrong-type rows before projection', flush=True)
        extra = ['Case', 'case', "quoted' OR 1=1 --", 'p\0path']
        db.executemany(sql('UPSERT_SQL'), [(path, 'b', 'f', bytes(48), bytes(32)) for path in extra])
        db.execute('CREATE TABLE source_library (path TEXT PRIMARY KEY)')
        db.execute('INSERT INTO source_library VALUES (?)', ('untouched.epub',))
        db.commit()
        keep = [f'book-{i}' for i in range(0, 3500, 2)] + extra[:1] + extra[2:]

        def prune(paths):
            with db:
                db.execute(sql('CREATE_KEEP_SQL'))
                db.execute(sql('CLEAR_KEEP_SQL'))
                db.executemany(sql('INSERT_KEEP_SQL'), [(path,) for path in paths])
                db.execute(sql('REMOVE_EXCEPT_SQL'))
                db.execute(sql('CLEAR_KEEP_SQL'))

        prune(keep)
        assert {row[0] for row in db.execute('SELECT path FROM library_facet_index')} == set(keep)
        assert db.execute('SELECT COUNT(*) FROM library_facet_keep').fetchone() == (0,)
        # A failure midway through a batch must not publish earlier replacements.
        before = db.execute('SELECT * FROM library_facet_index WHERE path = ?', (keep[0],)).fetchone()
        try:
            with db:
                db.execute(sql('UPSERT_SQL'), (keep[0], 'changed', 'changed', bytes(48), bytes(32)))
                db.execute(sql('UPSERT_SQL'), ('invalid', None, 'f', bytes(48), bytes(32)))
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError('invalid row unexpectedly committed')
        assert db.execute('SELECT * FROM library_facet_index WHERE path = ?', (keep[0],)).fetchone() == before
        prune([])
        assert db.execute('SELECT COUNT(*) FROM library_facet_index').fetchone() == (0,)
        assert db.execute('SELECT * FROM source_library').fetchall() == [('untouched.epub',)]
        print('PASS: large exact-path pruning empty pruning rollback and unrelated-table isolation', flush=True)


def main():
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def artifact(group, name, version):
        matches = sorted((cache / group / name / version).glob('*/*.jar'))
        if not matches:
            raise RuntimeError(f'Missing cached compiler artifact: {group}:{name}:{version}')
        return str(matches[0])

    stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
    compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                 artifact('org.jetbrains', 'annotations', '13.0')]
    repo = Path(__file__).resolve().parents[1]
    production = repo / 'app/src/main/java/com/vivi/aharana'
    sources = [production / 'LibraryFilters.kt', production / 'LibraryFacetPayload.kt',
               repo / 'tests/LibraryFacetPayloadTest.kt']
    scratch = Path(os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch')))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='LibraryIndexPayloadTests_', dir=scratch) as output:
        subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '1.8', '-classpath', stdlib, '-d', output,
                        *map(str, sources)], check=True)
        subprocess.run(['java', '-Djava.io.tmpdir=' + str(scratch), '-cp',
                        output + os.pathsep + stdlib, 'com.vivi.aharana.LibraryFacetPayloadTest'],
                       check=True)
    sql_tests(repo)


if __name__ == '__main__':
    main()
