"""Run isolated real-filesystem JVM cache tests using the cached Kotlin compiler."""
from pathlib import Path
import os
import subprocess
import tempfile


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
    sources = [production / 'LibraryFilters.kt', production / 'LibraryFacetDiskStore.kt',
               repo / 'tests/LibraryFilterDiskStoreTest.kt']
    scratch = Path(os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch')))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='LibraryCacheTests_', dir=scratch) as output:
        subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', stdlib, '-d', output, *map(str, sources)], check=True)
        subprocess.run(['java', '-Djava.io.tmpdir=' + str(scratch), '-cp',
                        output + os.pathsep + stdlib, 'com.vivi.aharana.LibraryFilterDiskStoreTest'],
                       check=True)


if __name__ == '__main__':
    main()
