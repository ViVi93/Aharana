"""Execute production snapshot transactions/coordinator against real host SQLite/JDBC."""
from pathlib import Path
import os
import subprocess
import tempfile


def main():
    repo = Path(__file__).resolve().parents[1]
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def artifact(group, name, version):
        return str(next((cache / group / name / version).glob('*/*.jar')))

    stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
    coroutines = artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
    sqlite = artifact('org.xerial', 'sqlite-jdbc', '3.41.2.2')
    compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                 artifact('org.jetbrains', 'annotations', '13.0')]
    production = repo / 'app/src/main/java/com/vivi/aharana'
    sources = [production / f'{name}.kt' for name in (
        'LibraryFilters', 'LibraryFacetPayload', 'LibraryFacetIndexDatabase',
        'LibraryFacetIndex', 'LibraryFilterState')]
    sources += sorted((repo / 'tests/cache').glob('*.kt'))
    runtime = os.pathsep.join([stdlib, coroutines, sqlite])
    scratch = os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch'))
    with tempfile.TemporaryDirectory(prefix='FacetSnapshotTests_', dir=scratch) as output:
        subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', runtime, '-d', output, *map(str, sources)], check=True)
        for suite in ('LibraryFacetSnapshotPersistenceTest', 'LibraryFacetAtomicRefreshTest'):
            subprocess.run(['java', '-Djava.io.tmpdir=' + scratch, '-cp', output + os.pathsep + runtime,
                            'com.vivi.aharana.' + suite], check=True)


if __name__ == '__main__':
    main()
