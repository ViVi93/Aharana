"""Run actual Kotlin warning JSON/presentation tests without Android stubs."""
from pathlib import Path
import os
import subprocess
import tempfile
import re

cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))

stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
runtime = stdlib + ':' + artifact('org.json', 'json', '20160810')
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
             artifact('org.jetbrains', 'annotations', '13.0')]
repo = Path(__file__).resolve().parents[1]
sources = [repo / 'app/src/main/java/com/vivi/aharana/DownloadWarnings.kt',
           repo / 'app/src/test/java/com/vivi/aharana/DownloadWarningsTest.kt']
assert sources[0].is_file(), 'Missing production warning parsing/presentation helper'
main = repo / 'app/src/main/java/com/vivi/aharana'
worker = (main / 'FanFicFareWorker.kt').read_text()
expressions = re.findall(r'resultJson = ([^\n]*?),\s*\n?\s*(?:error =|finishedAt =)',
                         worker.split('private suspend fun handleMetadata(', 1)[0])
assert len(expressions) == 5, 'Exercise all five successful download/update/force branches'
if (main / 'DownloadOutcome.kt').exists():
    sources.append(main / 'DownloadOutcome.kt')
with tempfile.TemporaryDirectory(prefix='DownloadWarningsTests_', dir=os.environ.get('TMPDIR', str(repo / 'app/build'))) as output:
    harness = Path(output) / 'WorkerOutcomeHarness.kt'
    harness.write_text('''package com.vivi.aharana
import org.json.JSONObject
object WorkerOutcomeHarness {
    fun persist(result: JSONObject): List<String> {
        val path = "/library/Story.epub"
        val finalPath = path
        return listOf(''' + ',\n'.join(expressions) + ''')
    }
}''')
    sources.append(harness)
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime, '-d', output,
                    *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', f'{output}:{runtime}',
                    'com.vivi.aharana.DownloadWarningsTest'], check=True)

# Narrow integration contracts: only the result consumers/persistence we change.
main = repo / 'app/src/main/java/com/vivi/aharana'
worker = (main / 'FanFicFareWorker.kt').read_text()
for method, following in [('handleDownload', 'handleUpdate'),
                           ('handleUpdate', 'handleForceDownload'),
                           ('handleForceDownload', 'handleMetadata')]:
    body = worker.split('private suspend fun ' + method + '(', 1)[1].split('private suspend fun ' + following + '(', 1)[0]
    expected = 1 if method == 'handleDownload' else 2  # saved and skipped branches
    assert body.count('resultJson = DownloadOutcome.compact(result)') == expected, method + ': compact every success branch without losing warnings'
    assert 'DownloadWarnings.parse(result).completionMessage(' in body, method + ': log partial completion'
queue = (main / 'DownloadQueueActivity.kt').read_text()
assert 'DownloadWarnings.showInQueue(it.type, it.status, it.resultJson)' in queue, 'Queue must retain successful warning rows'
adapter = (main / 'adapter/DownloadJobAdapter.kt').read_text()
assert 'DownloadWarnings.parse(job.resultJson)' in adapter, 'Adapter must reload persisted warnings'
assert 'warnings.completionMessage(context.getString(R.string.status_complete))' in adapter
assert 'warnings.messages.joinToString' in adapter, 'Adapter must show warning details'
assert 'val showRetry = job.status == "failed" || job.status == "cancelled"' in adapter, 'Do not add warning retries'
activity = (main / 'MainActivity.kt').read_text()
assert activity.count('DownloadWarnings.parse(latest.resultJson).completionMessage("✓ Complete")') == 2, 'Both direct observers must handle warnings'
viewmodel = (main / 'LibraryViewModel.kt').read_text()
assert 'DownloadWarnings.parse(latest.resultJson).completionMessage(humanizeJobStatus(latest.status))' in viewmodel, 'State phase must not overwrite warning text'
detail = (main / 'BookDetailActivity.kt').read_text()
assert 'DownloadWarnings.parse(result).completionMessage(if (force)' in detail, 'Detail toast must consume warnings'
assert 'DownloadWarnings.parse(response).completionMessage(' in detail, 'Detail diagnostics must consume warnings'
print('PASS DownloadWarnings: worker persistence/logging, queue, Main observers/state, Detail wiring')
