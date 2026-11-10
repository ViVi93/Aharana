"""Real production SQL: historical bridge bodies must not reach bulk Room cursors."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DAO = ROOT / 'app/src/main/java/com/vivi/aharana/data/local/DownloadJobDao.kt'


def query_for(method):
    source = DAO.read_text()
    match = re.search(r'@Query\(((?:"(?:[^"\\]|\\.)*"|\s|\+|[A-Z_]+)+)\)\s*(?:suspend )?fun ' + method + r'\(', source, re.S)
    assert match, 'Missing query: ' + method
    expression = match.group(1)
    constants = dict(re.findall(r'const val ([A-Z_]+) = ("(?:[^"\\]|\\.)*")', source))
    for name, literal in constants.items():
        expression = re.sub(r'\b' + name + r'\b', lambda _: literal, expression)
    return ''.join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', expression))


class QueueHistoryMemoryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE download_jobs (id INTEGER PRIMARY KEY, workId TEXT, status TEXT, '
                        'bookId INTEGER, type TEXT, inputUrl TEXT, inputPath TEXT, outputPath TEXT, '
                        'error TEXT, finishedAt INTEGER, createdAt INTEGER, resultJson TEXT)')
        self.raw = json.dumps(dict(ok=True, cover='A' * 100_000, title='Needed metadata', chapters=5,
                                   chapter_error_count=2, warnings=['Missing chapters'], skipped=True, reason='already current'))
        self.db.executemany('INSERT INTO download_jobs VALUES (?, ?, ?, 1, ?, ?, NULL, NULL, NULL, 9, ?, ?)',
            [(i, f'w{i}', 'success', 'metadata' if i % 10 == 0 else 'download', 'url', i, self.raw) for i in range(1, 101)])

    def test_100_legacy_cover_rows_never_materialize_in_any_bulk_query_before_cleanup(self):
        for method, params in [('observeAll', {}), ('getAll', {}), ('observeByBookId', dict(bookId=1)),
                               ('observeByStatus', dict(status='success')), ('getByStatus', dict(status='success'))]:
            with self.subTest(method=method):
                rows = self.db.execute(query_for(method), params).fetchall()
                self.assertEqual(len(rows), 100)
                self.assertTrue(all(row['resultJson'] is None for row in rows),
                                'Legacy and metadata payloads reached a bulk cursor before cleanup')
        self.assertEqual(self.db.execute(query_for('getById'), dict(id=10)).fetchone()['resultJson'], self.raw,
                         'Direct metadata reads must remain lossless for AddFromPage')

    def test_legacy_cleanup_is_one_row_at_a_time_and_fenced_from_retry_remove_and_metadata(self):
        candidate = self.db.execute(query_for('nextLegacyOutcome'), dict(afterId=0)).fetchall()
        self.assertEqual(len(candidate), 1)
        self.assertNotIn('resultJson', candidate[0].keys())
        row = dict(candidate[0])
        params = dict(id=row['id'], workId=row['workId'], type=row['type'], status=row['status'],
                      finishedAt=row['finishedAt'], resultLength=row['resultLength'], start=1)
        chunk = self.db.execute(query_for('readOutcomeChunk'), params).fetchone()[0]
        self.assertEqual(chunk, self.raw[:8192])
        self.assertLessEqual(len(chunk), 8192)
        compact = '{"queue_outcome":1,"ok":true,"chapter_error_count":2,"warnings":["Missing chapters"]}'
        params['resultJson'] = compact
        # Any identity/state mutation while streaming must fence the final replacement.
        for assignment in ["workId='new-retry'", "status='cancelled'", "finishedAt=10", "type='metadata'", "resultJson='changed'"]:
            self.db.execute('SAVEPOINT race')
            self.db.execute('UPDATE download_jobs SET ' + assignment + ' WHERE id=1')
            self.assertEqual(self.db.execute(query_for('compactLegacyOutcome'), params).rowcount, 0, assignment)
            self.db.execute('ROLLBACK TO race')
            self.db.execute('RELEASE race')
        self.assertEqual(self.db.execute(query_for('compactLegacyOutcome'), params).rowcount, 1)
        self.assertEqual(self.db.execute(query_for('nextLegacyOutcome'), dict(afterId=0)).fetchone()['id'], 2)
        self.assertEqual(self.db.execute(query_for('getById'), dict(id=10)).fetchone()['resultJson'], self.raw)
        self.db.execute('DELETE FROM download_jobs WHERE id=1')
        self.assertEqual(self.db.execute(query_for('compactLegacyOutcome'), params).rowcount, 0)

    def test_path_recovery_preserves_unprojected_payload_and_new_retry_identity(self):
        params = dict(id=10, workId='w10', inputPath='/moved', outputPath='/moved')
        self.assertEqual(self.db.execute(query_for('updatePaths'), params).rowcount, 1)
        row = self.db.execute(query_for('getById'), dict(id=10)).fetchone()
        self.assertEqual(row['resultJson'], self.raw)
        self.assertEqual(row['inputPath'], '/moved')
        self.db.execute("UPDATE download_jobs SET workId='replacement' WHERE id=10")
        self.assertEqual(self.db.execute(query_for('updatePaths'), params).rowcount, 0)

    def test_startup_and_metadata_consumers_use_safe_projection_contract(self):
        main = ROOT / 'app/src/main/java/com/vivi/aharana'
        repo = (main / 'BookRepository.kt').read_text()
        self.assertIn('QueueHistory.compactLegacy(downloadJobDao)', repo)
        self.assertIn('jobDao.updatePaths(job.id, job.workId, input, output)', repo)
        self.assertNotIn('JSONObject(old.resultJson', repo, 'Global metadata bodies are deliberately absent')
        page = (main / 'AddFromPageActivity.kt').read_text()
        body = page.split('private fun applyMetadataResults(', 1)[1].split('private fun startLazyMetadata(', 1)[0]
        self.assertIn('QueueHistory.metadataPreview(repository.jobDao, job.id)', body)
        self.assertNotIn('safeJson(job.resultJson)', body)
        self.assertIn('adapter.currentList.map', body, 'Apply to current user checkbox state, not a stale snapshot')

    def test_compact_warning_outcomes_survive_projection_but_unmarked_payloads_do_not(self):
        compact = '{"queue_outcome":1,"ok":true,"chapter_error_count":2,"warnings":["Missing chapters"]}'
        self.db.execute('UPDATE download_jobs SET resultJson=? WHERE id=1', (compact,))
        rows = self.db.execute(query_for('getAll')).fetchall()
        self.assertEqual(rows[0]['resultJson'], compact)
        self.assertTrue(all(row['resultJson'] is None for row in rows[1:]))
        self.db.execute('UPDATE download_jobs SET resultJson=? WHERE id=1', ('{"queue_outcome":1,"cover":"' + 'A'*100_000 + '"}',))
        self.assertIsNone(self.db.execute(query_for('getAll')).fetchone()['resultJson'])


if __name__ == '__main__':
    unittest.main()