from pathlib import Path
import os, subprocess
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))
stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
coroutines = artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
compiler = [artifact('org.jetbrains.kotlin', n, '1.9.22') for n in ['kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'), artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), artifact('org.jetbrains', 'annotations', '13.0')]
repo = Path(__file__).resolve().parents[1]
output = Path(os.environ.get('TMPDIR', str(repo / 'app/build'))) / 'BulkQueueTests'
output.mkdir(exist_ok=True)
sources = sorted((repo/'app/src/main/java/com/vivi/aharana').glob('BulkQueue*.kt')) + sorted((repo/'app/src/test/java/com/vivi/aharana').glob('BulkQueue*.kt'))
cp = ':'.join([stdlib, coroutines])
subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(output)] + list(map(str, sources)), check=True)
subprocess.run(['java', '-cp', f'{output}:{cp}', 'com.vivi.aharana.BulkQueuePolicyTest'], check=True)
