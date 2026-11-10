"""Host Kotlin queue behavior tests; Android wiring contracts are checked separately."""
from pathlib import Path
import os
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
main = repo / 'app/src/main/java/com/vivi/aharana'
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def artifact(group, name, version):
    return str(next((cache / group / name / version).glob('*/*.jar')))
stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
compiler = [artifact('org.jetbrains.kotlin', name, '1.9.22') for name in (
    'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
             artifact('org.jetbrains', 'annotations', '13.0')]
policy = main / 'QueueJobPolicy.kt'
assert policy.exists(), 'Missing queue policy: stopped scheduler work must remain retryable, not cancelled'
sources = [policy, repo / 'app/src/test/java/com/vivi/aharana/QueueJobPolicyTest.kt']
with tempfile.TemporaryDirectory(prefix='QueuePolicyTests_', dir=os.environ['TMPDIR']) as output:
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', output,
                    *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', f'{output}:{stdlib}', 'com.vivi.aharana.QueueJobPolicyTest'], check=True)

# Execute the real Room UPDATE SQL on SQLite (not an imitation of the state machine).
import re
import sqlite3
dao = (main / 'data/local/DownloadJobDao.kt').read_text()
def query_for(method):
    match = re.search(r'@Query\(((?:"[^"]*"|\s|\+)+)\)\s*suspend fun ' + method + r'\(', dao, re.S)
    assert match, 'Missing guarded persistence method: ' + method
    return ''.join(re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', match.group(1)))
connection = sqlite3.connect(':memory:')
connection.execute('CREATE TABLE download_jobs (id INTEGER PRIMARY KEY, status TEXT, bookId INTEGER, '
                   'outputPath TEXT, error TEXT, finishedAt INTEGER, resultJson TEXT, workId TEXT)')
worker_sql = query_for('updateWorkerState')
for status in ['queued', 'waiting', 'running', 'failed', 'success', 'cancelled', 'superseded']:
    connection.execute('DELETE FROM download_jobs')
    connection.execute('INSERT INTO download_jobs VALUES (1, ?, 5, NULL, ?, NULL, ?, ?)', (status, 'original reason', 'saved warnings', 'work-name'))
    connection.execute(worker_sql, dict(id=1, workId='work-name', status='failed', bookId=99, outputPath='/stale',
                                       error='scheduler interrupted', finishedAt=123, resultJson=None))
    row = connection.execute('SELECT status, bookId, error, resultJson FROM download_jobs').fetchone()
    if status in ['success', 'cancelled', 'superseded']:
        assert row == (status, 5, 'original reason', 'saved warnings'), (status, row)
    else:
        assert row == ('failed', 99, 'scheduler interrupted', None), (status, row)
connection.execute('DELETE FROM download_jobs')
connection.execute(worker_sql, dict(id=1, workId='work-name', status='running', bookId=0, outputPath=None, error=None, finishedAt=None, resultJson=None))
assert connection.execute('SELECT COUNT(*) FROM download_jobs').fetchone()[0] == 0, 'Do not resurrect removed rows'
print('PASS Queue SQL: late worker writes preserve success/cancel/removal and warnings')

cancel_sql = query_for('cancelActive')

for status in ['queued', 'waiting', 'running', 'failed', 'success', 'cancelled', 'superseded']:
    connection.execute('DELETE FROM download_jobs')
    connection.execute('INSERT INTO download_jobs VALUES (1, ?, 5, NULL, ?, NULL, ?, ?)', (status, 'original reason', 'warnings', 'work-name'))
    connection.execute(cancel_sql, dict(id=1, workId='work-name', includeInterrupted=0, reason='Cancelled by user. Retry to resume.', now=456))
    actual = connection.execute('SELECT status, error FROM download_jobs').fetchone()
    assert actual == (('cancelled', 'Cancelled by user. Retry to resume.') if status in ['queued','waiting','running'] else (status,'original reason'))
print('PASS Queue SQL: explicit cancel preserves terminal states and unrelated failures')

# Narrow Android wiring contracts; not substitutes for on-device scheduler/Room tests.
worker = (main / 'FanFicFareWorker.kt').read_text()
repository = (main / 'BookRepository.kt').read_text()
queue = (main / 'DownloadQueueActivity.kt').read_text()
adapter = (main / 'adapter/DownloadJobAdapter.kt').read_text()
assert 'status = "cancelled"' not in worker, 'Scheduler stops must never poison a row as explicitly cancelled'
assert 'jobDao.update(' not in worker and 'dao.update(' not in worker, 'All worker row writes must be guarded'
assert 'cleanFailedRowsForLibraryBooks' not in repository, 'Library matches must not hide genuine failures'
assert 'supersedeOlderJobsForSameStory(jobDao, url, job.id)' not in worker, 'Success must not retire unrelated/failed jobs'
assert 'repo.cancelJob(job)' not in queue, 'Retry retirement is not explicit cancellation'
assert 'QueueJobPolicy.retryReplacement' in repository and 'downloadJobDao.claimRetry(' in repository
retry_source = repository.split('suspend fun retryJob(', 1)[1].split('private suspend fun libraryBookForUrl', 1)[0]
assert retry_source.index('claimRetry(') < retry_source.index('cancelUniqueWork(') < retry_source.index('scheduleUserWork(')
assert 'updateFromWorker(replacement.copy(status = "failed"' in retry_source, 'Failed submission must keep the same row actionable'
cancel_body = repository.split('private suspend fun cancelJobAwaited(', 1)[1].split('fun hasRunningJob', 1)[0]
assert cancel_body.index('cancelActive(') < cancel_body.index('cancelUniqueWork('), 'Persist user intent BEFORE WorkManager stop'
assert 'getWorkInfosForUniqueWork(name)' in cancel_body and '!it.state.isFinished' in cancel_body
assert 'current.workId != job.workId' in cancel_body, 'Stale cancellation must not target a later Retry'
cancel_all = repository.split('fun cancelCurrentDownload()', 1)[1].split('suspend fun retryJob(', 1)[0]
assert '"failed"' in cancel_all, 'Cancel All must consider interrupted work scheduled to resume'
assert 'QueueJobPolicy.failureReason(job.status, job.error)' in adapter, 'Failed/cancelled reasons need presentation including legacy rows'
outer = worker.split('override suspend fun doWork(): Result {', 1)[1].split('private suspend fun doWorkUnlocked', 1)[0]
assert outer.index('ENTER') < outer.index('setForeground(') < outer.index('storage_wait')
assert outer.index('try {') < outer.index('TYPE_METADATA'), 'Metadata must share lifecycle cancellation catch'
assert 'NonCancellable' in outer and 'throw e' in outer, 'Persist stop reason outside cancelled context and allow scheduler propagation'
for event in ['storage_wait', 'storage_acquired', 'storage_released', 'engine_wait', 'engine_acquired', 'engine_released', 'bridge_return', 'cancellation']:
    assert event in worker, 'Missing lifecycle diagnostic: ' + event
for field in ['job_id=', 'work_name=', 'request_id=', 'run_attempt=', 'stage=', 'stop_reason=']:
    assert field in worker, 'Missing correlation: ' + field
assert 'SystemClock.elapsedRealtime()' in worker
assert 'Build.VERSION_CODES.S' in worker and 'stopReason' in worker
assert 'raw_prefix=' not in worker and 'result_error=$errorMsg' not in worker, 'Never log raw error bodies'
retry_body = worker.split('private suspend fun callBridgeWithRetry(',1)[1].split('private fun retryableHttpStatus',1)[0]
assert retry_body.index('setDownloadDiagnosticContext(') < retry_body.index('val raw = call()')
for key in ['job_id', 'work_name', 'request_id', 'run_attempt', 'operation', 'bridge_attempt']:
    assert '.put("' + key + '"' in retry_body
assert 'ensureWorkerActive("bridge_return")' in retry_body, 'Every bridge operation including metadata must check stop after Python returns'
assert 'QueueJobPolicy.shouldLogHeartbeatStop' in worker, 'Heartbeat cancellation must be logged without labelling normal cleanup as scheduler stop'
assert 'heartbeat_cancelled' in worker and 'normalCleanup.set(true)' in worker
assert 'DiagnosticFiles.sanitize(' in worker.split('private fun logWorker(', 1)[1].split('private suspend fun markInterruptedJob', 1)[0], 'Sanitize logcat as well as persisted diagnostics'
assert 'python_start_raced' in worker and 'if (!com.chaquo.python.Python.isStarted()) throw e' in worker
assert 'private suspend fun completeBook(' in worker, 'Commit library and job together, conditional on fresh row status'
completion = worker.split('private suspend fun completeBook(',1)[1].split('override suspend fun getForegroundInfo',1)[0]
assert 'database.withTransaction' in completion and 'QueueJobPolicy.canWorkerWrite' in completion
assert completion.index('QueueJobPolicy.canWorkerWrite') < completion.index('upsertBook(')
assert 'ensureWorkerActive("commit_book_result")' in completion
assert 'existing == null && workId.isNotBlank()' in worker, 'Removed named requests must not recreate queue rows'
metadata_enqueue = repository.split('suspend fun enqueueMetadata(url: String)',1)[1].split('/** Compatibility entry point',1)[0]
assert 'scheduleUserWork(jobId, requestWorkId, work)' in metadata_enqueue, 'Metadata retry must await a named, discoverable WorkManager submission'
bulk_cancel = repository.split('fun cancelBulkMetadata()',1)[1].split('private suspend fun scheduleUserWork',1)[0]
assert 'cancelJobAwaited(row)' in bulk_cancel, 'Metadata cancel must persist user intent through the same guarded path'
assert 'QueueJobPolicy.failureReason(job.status, job.error)' in adapter, 'Legacy failed/cancelled rows need an actionable reason even when error was not persisted'
assert 'if (e is CancellationException) throw e' in worker, 'File-copy cancellation must propagate, not be swallowed as generic failure'
print('PASS Queue/worker source wiring: cancellation order, retry, lifecycle correlation, no raw responses, atomic completion')

# Run actual coroutine method bodies, not a parallel model of their behavior.
# Android elapsedRealtime, Room lookup and the synchronous bridge are collaborators.
def extract_method(marker):
    start = worker.index(marker)
    if marker == 'private fun retryableHttpStatus(':
        return worker[start:worker.index('private suspend fun handleDownload(', start)]
    opening = worker.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (worker[end] == '{') - (worker[end] == '}')
        end += 1
    return worker[start:end]
methods = '\n'.join(extract_method(marker) for marker in [
    'private suspend fun <T> withEngineHeartbeat(',
    'private suspend fun callBridgeWithRetry(',
    'private fun retryableHttpStatus(',
    'private suspend fun ensureWorkerActive(',
    'private suspend fun markInterruptedJob(',
    'override suspend fun doWork(']).replace('override suspend fun doWork(', 'suspend fun doWork(')
coroutines = artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
json_runtime = artifact('org.json', 'json', '20160810')
runtime = ':'.join([stdlib, coroutines, json_runtime])
harness = '''package com.vivi.aharana
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
data class HostJob(val status: String, val id: Long = 19L,
    val error: String? = null, val finishedAt: Long? = null, val workId: String? = "logical-work-name")
object AppDatabase {
    var row: HostJob? = HostJob("running")
    fun getInstance(context: Unit) = this
    fun downloadJobDao() = this
    suspend fun getById(id: Long) = row?.takeIf { it.id == id }
    suspend fun findByWorkId(name: String) = row?.takeIf { it.workId == name }
    suspend fun updateFromWorker(job: HostJob): Boolean {
        if (!QueueJobPolicy.canWorkerWrite(row?.status) || row?.workId != job.workId) return false
        row = job
        return true
    }
}
object BookRepository { suspend fun reconcileOrganisedPaths(context: Unit) = Unit }
class Result(val success: Boolean) {
    companion object { fun success() = Result(true); fun failure() = Result(false) }
}
class PythonBridge {
    val contexts = CopyOnWriteArrayList<String>()
    fun setDownloadDiagnosticContext(context: String): String { contexts.add(context); return "{}" }
}
class HostData(val operation: String) {
    fun getString(key: String): String = if (key == "workId") "logical-work-name" else operation
}
class WorkerLifecycleHarness(operation: String = "metadata") {
    // CoroutineWorker exposes this deprecated dispatcher member too. It is NOT
    // the currently executing coroutine's context, and must not be used as one.
    val coroutineContext: CoroutineDispatcher = Dispatchers.Default
    val events = CopyOnWriteArrayList<String>()
    @Volatile var isStopped = false
    private var workerStage = "initial"
    val id: UUID = UUID.randomUUID()
    private val runAttemptCount = 2
    private var diagnosticJobId = 19L
    private val inputData = HostData(operation)
    private val applicationContext = Unit
    private val KEY_WORK_ID = "workId"
    private val KEY_TYPE = "type"
    private val TYPE_METADATA = "metadata"
    private val SERVER_RETRY_DELAYS_MS = longArrayOf(1L, 1L)
    private val RETRYABLE_HTTP_STATUS = setOf(429, 500, 502, 503, 504)
    private val HEARTBEAT_MS = 10_000L
    private fun logWorker(tag: String, message: String) { events.add("$tag stopped=$isStopped $message") }
    private fun updateForegroundNotification(text: String) = Unit
    private suspend fun setPhase(status: String) = Unit
    private fun stopReasonLabel() = "3"
    private fun getForegroundInfo() = Unit
    private suspend fun setForeground(info: Unit) = Unit
    private suspend fun doWorkUnlocked(): Result {
        ensureWorkerActive("host_operation")
        return Result.success()
    }
    suspend fun <T> heartbeat(block: suspend () -> T): T = withEngineHeartbeat("download", block)
    suspend fun bridge(bridge: PythonBridge, call: () -> String): String = callBridgeWithRetry("metadata", bridge, call)
''' + methods + '\n}\n'
with tempfile.TemporaryDirectory(prefix='WorkerLifecycleTests_', dir=os.environ['TMPDIR']) as output:
    source = Path(output) / 'WorkerLifecycleHarness.kt'
    source.write_text(harness)
    subprocess.run(['java', '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', runtime, '-d', output,
                    str(policy), str(main / 'LibraryStorageGate.kt'), str(source),
                    str(repo / 'app/src/test/java/com/vivi/aharana/WorkerLifecycleHostTest.kt')], check=True)
    subprocess.run(['java', '-cp', f'{output}:{runtime}', 'com.vivi.aharana.WorkerLifecycleHostTest'], check=True)
