"""Compile selected production Kotlin for host tests, never invokes Gradle/Android build."""
from pathlib import Path
import os
import subprocess
import tempfile


def run_host(sources, generated, main, coroutines=False):
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def artifact(group, name, version):
        return str(next((cache / group / name / version).glob('*/*.jar')))

    stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
    annotations = artifact('org.jetbrains', 'annotations', '13.0')
    compiler = [artifact('org.jetbrains.kotlin', n, '1.9.22') for n in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), annotations]
    runtime = [stdlib, annotations]
    if coroutines:
        runtime.append(artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'))
    with tempfile.TemporaryDirectory(prefix='LibraryMemoryHost_', dir=os.environ['TMPDIR']) as output:
        paths = list(sources)
        for name, content in generated.items():
            path = Path(output) / name
            path.write_text(content)
            paths.append(path)
        result = subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                                 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                                 '-nowarn', '-classpath', os.pathsep.join(runtime), '-d', output,
                                 *map(str, paths)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        result = subprocess.run(['java', '-cp', os.pathsep.join([output, *runtime]), main],
                                capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
