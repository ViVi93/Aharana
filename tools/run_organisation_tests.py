"""Execute production organisation path mapping with the cached Kotlin compiler."""
from pathlib import Path
import os
import subprocess
import tempfile
import sys
import json
import importlib

repo = Path(__file__).resolve().parents[1]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'


def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))


stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
             artifact('org.jetbrains', 'annotations', '13.0')]
sources = [repo / 'app/src/main/java/com/vivi/aharana/OrganisationPaths.kt',
           repo / 'tests/OrganisationPathsTest.kt',
           repo / 'tests/OrganisationDatabaseConflictTest.kt',
           repo / 'tests/OrganisationPrivatePathTest.kt']
sqlite = artifact('org.xerial', 'sqlite-jdbc', '3.41.2.2')
with tempfile.TemporaryDirectory(prefix='OrganisationPaths_', dir=os.environ['TMPDIR']) as output:
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', output,
                    *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', f'{output}:{stdlib}',
                    'com.vivi.aharana.OrganisationPathsTest'], check=True)
    subprocess.run(['java', '-Djava.io.tmpdir=' + os.environ['TMPDIR'], '-cp', f'{output}:{stdlib}:{sqlite}',
                    'com.vivi.aharana.OrganisationDatabaseConflictTest'], check=True)
    subprocess.run(['java', '-Djava.io.tmpdir=' + os.environ['TMPDIR'], '-cp', f'{output}:{stdlib}',
                    'com.vivi.aharana.OrganisationPrivatePathTest'], check=True)
    # Cross the actual production Kotlin path seam into real Python filesystem work.
    sys.path[:0] = [str(repo / 'app/src/main/python'), str(repo / 'app/src/main/python/tests')]
    organiser = importlib.import_module('library_organizer')
    epub = importlib.import_module('test_library_organizer').epub
    with tempfile.TemporaryDirectory(prefix='AndroidPrivateAlias_', dir=os.environ['TMPDIR']) as fixture:
        base = Path(fixture)
        actual = base / 'data/data/com.vivi.aharana.debug/files'
        actual.mkdir(parents=True)
        user = base / 'data/user'
        user.mkdir(parents=True)
        (user / '0').symlink_to(base / 'data/data', target_is_directory=True)
        files_dir = user / '0/com.vivi.aharana.debug/files'

        def private_path(child):
            return subprocess.check_output(['java', '-cp', f'{output}:{stdlib}',
                'com.vivi.aharana.OrganisationPrivatePathTest', '--path', str(files_dir), child], text=True)

        root = base / 'library'
        root.mkdir()
        source = epub(root / 'old.epub')
        original = source.read_bytes()
        plan = organiser.preview(str(root))
        assert plan['ok'], plan
        old_result = organiser.apply(plan, str(files_dir / 'organisation'))
        assert not old_result['ok'] and "Not a directory: '0'" in old_result['error'], old_result
        assert source.read_bytes() == original
        journal = private_path('organisation')
        result = json.loads(organiser.json_call('apply', json.dumps(plan), journal))
        assert result['ok'], result
        assert Path(journal).parent == actual
        assert organiser.recovery(journal)['ok']
        assert organiser.undo_preview(journal)['ok']
        assert organiser.undo(journal)['ok']
        assert source.read_bytes() == original
        generated = epub(actual / 'generated.epub')
        copied = organiser.copy_download(private_path(generated.name), str(root))
        assert copied['ok'], copied
        prepared = organiser.delete_prepare(copied['path'], journal, 51)
        assert prepared['ok'], prepared
        Path(prepared['delete_path']).unlink()
        assert organiser.delete_finish(prepared['token'], True, journal)['ok']
        assert organiser.recovery(journal)['ok']
        # Canonicalising the trusted base must not bless a linked journal child or library root.
        (actual / 'unsafe-journal').symlink_to(base / 'outside', target_is_directory=True)
        unsafe = organiser.recovery(private_path('unsafe-journal'))
        assert not unsafe['ok'], unsafe
        assert not (base / 'outside').exists()
        linked_root = base / 'library-alias'
        linked_root.symlink_to(root, target_is_directory=True)
        assert not organiser.preview(str(linked_root))['ok']
        print('PASS: reproduced old Errno20 at 0; production Kotlin path fixes Python apply/recovery/undo/copy/delete; unsafe child/library aliases still rejected')
