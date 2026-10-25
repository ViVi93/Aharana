"""Run storage safety and mutual-exclusion tests against real JVM filesystem code."""
from pathlib import Path
import os
import subprocess
import tempfile


def main():
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def artifact(group, name, version):
        return str(next((cache / group / name / version).glob('*/*.jar')))

    stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
    coroutines = artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
    compiler = [artifact('org.jetbrains.kotlin', n, '1.9.22') for n in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                 artifact('org.jetbrains', 'annotations', '13.0')]
    repo = Path(__file__).resolve().parents[1]
    production = repo / 'app/src/main/java/com/vivi/aharana'
    sources = [production / 'LibraryEpubStorage.kt', repo / 'tests/LibraryDownloadStorageTest.kt']
    gate = production / 'LibraryStorageGate.kt'
    if gate.exists():
        sources.append(gate)
    scratch = Path(os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch')))
    scratch.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join([stdlib, coroutines])
    with tempfile.TemporaryDirectory(prefix='DownloadStorageTests_', dir=scratch) as output:
        subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', cp, '-d', output, *map(str, sources)], check=True)
        subprocess.run(['java', '-Djava.io.tmpdir=' + str(scratch), '-cp',
                        output + os.pathsep + cp, 'com.vivi.aharana.LibraryDownloadStorageTest'], check=True)


if __name__ == '__main__':
    main()
