"""Run real Kotlin refresh file-safety tests with the build's cached compiler."""
from pathlib import Path
import os
import subprocess
import tempfile

cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))

stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
coroutines = artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
runtime = stdlib + ':' + coroutines
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
             artifact('org.jetbrains', 'annotations', '13.0')]
repo = Path(__file__).resolve().parents[1]
sources = sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('StoryRefreshFiles.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('StoryRefreshFilesTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('StoryRefreshLibraryMetadata.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('StoryRefreshLibraryMetadataTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('LibraryStoryStatus.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('LibraryStoryStatusTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('LibraryFilters.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('LibraryFiltersTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('LibraryFilterState.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('LibraryFilterStateTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('LibraryListPresentation.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('LibraryListPresentationTest.kt'))
sources += sorted((repo / 'app/src/main/java/com/vivi/aharana').glob('LibraryFacetIndex.kt'))
sources += sorted((repo / 'app/src/test/java/com/vivi/aharana').glob('LibraryFacetIndexTest.kt'))
with tempfile.TemporaryDirectory(prefix='StoryRefreshTests_', dir=os.environ.get('TMPDIR', str(repo / 'app/build'))) as output:
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', output,
                    *map(str, sources)], check=True)
    for suite in ('StoryRefreshFilesTest', 'StoryRefreshLibraryMetadataTest', 'LibraryStoryStatusTest', 'LibraryFiltersTest', 'LibraryFilterStateTest', 'LibraryListPresentationTest', 'LibraryFacetIndexTest'):
        subprocess.run(['java', '-Djava.io.tmpdir=' + os.environ.get('TMPDIR', str(repo / 'app/build')), '-cp', f'{output}:{runtime}', f'com.vivi.aharana.{suite}'], check=True)
