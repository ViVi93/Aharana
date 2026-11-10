"""Run Settings-specific real JVM regressions without Gradle or Android stubs."""
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
    source = repo / 'app/src/main/java/com/vivi/aharana'
    preview = source / 'SettingsDebugPreview.kt'
    assert preview.is_file(), 'Settings lacks a direct bounded diagnostic preview (currently reads entire Python log)'
    production = [source / 'DiagnosticFiles.kt', preview]
    requests = source / 'SettingsRequests.kt'
    if requests.is_file():
        production.append(requests)
    with tempfile.TemporaryDirectory(prefix='SettingsDiagnostics_', dir=os.environ['TMPDIR']) as output:
        subprocess.run(['java', '-cp', ':'.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', runtime, '-d', output, *map(str, production),
                        str(repo / 'tests/SettingsDiagnosticsTest.kt')], check=True)
        subprocess.run(['java', '-Djava.io.tmpdir=' + os.environ['TMPDIR'], '-cp', output + ':' + runtime,
                        'com.vivi.aharana.SettingsDiagnosticsTest'], check=True)


if __name__ == '__main__':
    main()
