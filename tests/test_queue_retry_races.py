"""Execute production queue SQL with deterministic retry/resumption interleavings."""
from pathlib import Path
import re
import sqlite3
import unittest

DAO = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/vivi/aharana/data/local/DownloadJobDao.kt'


def query_for(method):
    source = DAO.read_text()
    match = re.search(r'@Query\(((?:"[^"]*"|\s|\+)+)\)\s*suspend fun ' + method + r'\(', source, re.S)
    assert match, 'Missing atomic queue transition: ' + method
    return ''.join(re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', match.group(1)))


class RetryRaceTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE download_jobs (id INTEGER PRIMARY KEY, workId TEXT, status TEXT, '
                        'bookId INTEGER, type TEXT, inputUrl TEXT, inputPath TEXT, outputPath TEXT, '
                        'error TEXT, finishedAt INTEGER, createdAt INTEGER, resultJson TEXT)')
        self.db.execute("INSERT INTO download_jobs VALUES (1, 'old-name', 'failed', 0, 'download', "
                        "'https://example.invalid/story', NULL, NULL, 'interrupted', 5, 1, NULL)")

    def claim(self, expected='old-name', replacement='new-name'):
        return self.db.execute(query_for('claimRetry'), dict(id=1, expectedWorkId=expected,
            workId=replacement, type='download', bookId=0, inputUrl='https://example.invalid/story',
            inputPath=None, now=10)).rowcount

    def worker(self, work_name, status='running'):
        return self.db.execute(query_for('updateWorkerState'), dict(id=1, workId=work_name,
            status=status, bookId=0, outputPath=None, error=None, finishedAt=None, resultJson=None)).rowcount

    def test_retry_claim_prevents_double_submission_and_late_old_worker(self):
        self.assertEqual(self.claim(), 1)
        self.assertEqual(self.claim(expected='new-name', replacement='third-name'), 0)
        self.assertEqual(self.worker('old-name'), 0)
        self.assertEqual(self.worker('new-name'), 1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM download_jobs').fetchone()[0], 1)
        self.assertEqual(self.db.execute('SELECT workId,status FROM download_jobs').fetchone(), ('new-name', 'running'))

    def test_automatic_resume_wins_before_retry_claim_without_duplicate(self):
        self.assertEqual(self.worker('old-name', 'waiting'), 1)
        self.assertEqual(self.claim(), 0)

    def test_old_attempt_cannot_poison_failed_replacement(self):
        self.assertEqual(self.claim(), 1)
        self.assertEqual(self.worker('new-name', 'failed'), 1)
        self.assertEqual(self.worker('old-name', 'success'), 0)
        self.assertEqual(self.db.execute('SELECT workId,status FROM download_jobs').fetchone(), ('new-name', 'failed'))

    def test_explicit_cancel_can_stop_an_interrupted_request_with_unfinished_work(self):
        sql = query_for('cancelActive')
        parameters = dict(id=1, workId='old-name', includeInterrupted=0, reason='Cancelled by user', now=20)
        self.assertEqual(self.db.execute(sql, parameters).rowcount, 0)
        parameters['includeInterrupted'] = 1
        self.assertEqual(self.db.execute(sql, parameters).rowcount, 1)
        self.assertEqual(self.worker('old-name'), 0)

    def test_stale_cancel_does_not_stop_a_new_retry_or_success(self):
        self.assertEqual(self.claim(), 1)
        sql = query_for('cancelActive')
        params = dict(id=1, workId='old-name', includeInterrupted=1, reason='Cancelled by user', now=20)
        self.assertEqual(self.db.execute(sql, params).rowcount, 0)
        self.assertEqual(self.worker('new-name', 'success'), 1)
        params['workId'] = 'new-name'
        self.assertEqual(self.db.execute(sql, params).rowcount, 0)

    def test_stale_remove_cannot_delete_a_new_retry(self):
        remove = query_for('removeFinished')
        self.assertEqual(self.claim(), 1)
        self.assertEqual(self.db.execute(remove, dict(id=1, workId='old-name', status='failed')).rowcount, 0)
        self.assertEqual(self.db.execute('SELECT workId,status FROM download_jobs').fetchone(), ('new-name', 'queued'))
        self.assertEqual(self.db.execute(remove, dict(id=1, workId='new-name', status='queued')).rowcount, 0)

    def test_remove_requires_matching_terminal_identity_and_state(self):
        remove = query_for('removeFinished')
        self.assertEqual(self.worker('old-name', 'success'), 1)
        self.assertEqual(self.db.execute(remove, dict(id=1, workId='old-name', status='failed')).rowcount, 0)
        self.assertEqual(self.db.execute(remove, dict(id=1, workId='old-name', status='success')).rowcount, 1)
