"""Exercise diagnostic storage/redaction on a real JVM, without Android stubs."""
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
    runtime = stdlib + ':' + artifact('org.json', 'json', '20160810')
    compiler = [artifact('org.jetbrains.kotlin', n, '1.9.22') for n in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                 artifact('org.jetbrains', 'annotations', '13.0')]
    production = repo / 'app/src/main/java/com/vivi/aharana/DiagnosticFiles.kt'
    assert production.is_file(), 'Missing bounded, redacted diagnostic storage'
    with tempfile.TemporaryDirectory(prefix='DiagnosticFiles_', dir=os.environ['TMPDIR']) as output:
        subprocess.run(['java', '-cp', ':'.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', runtime, '-d', output, str(production),
                        str(repo / 'tests/DiagnosticFilesTest.kt')], check=True)
        subprocess.run(['java', '-cp', output + ':' + runtime,
                        'com.vivi.aharana.DiagnosticFilesTest'], check=True)


if __name__ == '__main__':
    main()
