"""Stable identity regression tests; JVM checks execute real Kotlin snippets/mappers.

Android UI boundaries are source contracts, not instrumentation coverage.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/vivi/aharana'


def run_kotlin(main, sources=(), stubs=''):
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def artifact(group, name, version):
        return str(next((cache / group / name / version).glob('*/*.jar')))

    stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22')
    compiler = [artifact('org.jetbrains.kotlin', n, '1.9.22') for n in (
        'kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')]
    compiler += [stdlib, artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 artifact('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                 artifact('org.jetbrains', 'annotations', '13.0')]
    scratch = Path(os.environ.get('TMPDIR', str(Path.home() / '.hermes/cache/scratch')))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='StableIdentityTests_', dir=scratch) as directory:
        directory = Path(directory)
        test = directory / 'StableIdentityTest.kt'
        test.write_text(main)
        paths = [test, *sources]
        if stubs:
            stub = directory / 'RoomAnnotations.kt'
            stub.write_text(stubs)
            paths.append(stub)
        compile_result = subprocess.run([
            'java', '-cp', os.pathsep.join(compiler),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
            '-classpath', stdlib, '-d', str(directory), *map(str, paths)
        ], capture_output=True, text=True)
        if compile_result.returncode:
            raise AssertionError('Kotlin compilation failed:\n' + compile_result.stderr)
        result = subprocess.run(['java', '-cp', str(directory) + os.pathsep + stdlib,
                                 'StableIdentityTestKt'], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)


class StableBookIdentityTest(unittest.TestCase):
    maxDiff = 2000

    def test_detail_and_child_mutations_keep_intent_identity(self):
        detail = (SRC / 'BookDetailActivity.kt').read_text()
        self.assertTrue('bookId = intent.getLongExtra("book_id", 0L)' in detail)
        self.assertEqual(detail.count('putExtra("book_id", bookId)'), 3)
        self.assertEqual(detail.count('LibraryBookMutations.runBlocking(applicationContext, bookPath, expectedBookId = bookId)'), 2)
        for name in ['EditMetadataActivity.kt', 'MetadataPreviewActivity.kt']:
            child = (SRC / name).read_text()
            with self.subTest(name=name):
                self.assertTrue('bookId = intent.getLongExtra("book_id", 0L)' in child)
                self.assertTrue('LibraryBookMutations.runBlocking(applicationContext, epubPath, expectedBookId = bookId)' in child)

    def test_update_force_and_detail_never_fall_back_from_missing_positive_id(self):
        worker = (SRC / 'FanFicFareWorker.kt').read_text()
        detail = (SRC / 'BookDetailActivity.kt').read_text()
        selections = {}
        for name in ['handleUpdate', 'handleForceDownload']:
            body = worker.split('private suspend fun ' + name + '(', 1)[1]
            selections[name] = body.split('val existing = ', 1)[1].split('val path = ', 1)[0]
        selections['detail'] = detail.split('private fun downloadExistingBook(', 1)[1].split('val existing = ', 1)[1].split('val path = ', 1)[0]
        functions = '\n'.join('''
fun %s(bookId: Long, bookDao: Dao, recoveredInputPath: String): Row? {
    val resolvedPath = recoveredInputPath
    val existing = %s
    return existing
}
''' % (name, selection) for name, selection in selections.items())
        run_kotlin('''
data class Row(val id: Long, val filePath: String)
class Dao(val rows: List<Row>) {
    var pathReads = 0
    fun findById(id: Long) = rows.find { it.id == id }
    fun findByFilePath(path: String): Row? { pathReads++; return rows.find { it.filePath == path } }
}
''' + functions + '''
fun main() {
    for (select in listOf(::handleUpdate, ::handleForceDownload, ::detail)) {
        val replacement = Row(99L, "/reused.epub")
        val dao = Dao(listOf(replacement))
        check(runCatching { select(41L, dao, "/reused.epub") }.isFailure) {
            "Missing positive identity selected a replacement book"
        }
        check(dao.pathReads == 0) { "Missing positive ID must not even query by path" }
        val moved = Row(41L, "/Author/Title.epub")
        val movedDao = Dao(listOf(moved, replacement))
        check(select(41L, movedDao, "/reused.epub") == moved) { "Must use canonical path of same Room ID" }
        check(movedDao.pathReads == 0)
        check(select(0L, dao, "/reused.epub") == replacement) { "Legacy path lookup must stay supported" }
        check(select(0L, dao, "/missing.epub") == null)
    }
}
''')

    def test_story_preview_and_apply_revalidate_the_same_room_identity(self):
        activity = (SRC / 'StoryRefreshActivity.kt').read_text()
        self.assertTrue('intent.getLongExtra("book_id", 0L)' in activity)
        self.assertTrue('model.initialize(path, selection, bookId)' in activity)
        vm = (SRC / 'StoryRefreshViewModel.kt').read_text()
        self.assertTrue('store.snapshot(path, expectedBookId = bookId)' in vm)
        self.assertTrue('store.apply(path, preview, selected, withCover, expectedBookId = bookId)' in vm)
        store = (SRC / 'StoryRefreshStore.kt').read_text()
        self.assertEqual(store.count('LibraryBookMutations.run(context,'), 2)
        self.assertEqual(store.count('expectedBookId = expectedBookId)'), 2)
        apply = store.split('suspend fun apply(', 1)[1].split('private suspend fun applyAtPath', 1)[0]
        self.assertLess(apply.index('LibraryBookMutations.run'), apply.index('FanFicFareGate.run'))
        self.assertTrue('applyAtPath(mutation.path,' in apply)

    def test_recovered_success_cannot_register_or_retarget_a_replacement(self):
        source = (SRC / 'FanFicFareWorker.kt').read_text().split('private suspend fun recoverExistingSuccess(', 1)[1].split('private suspend fun <T> withEngineHeartbeat', 1)[0]
        self.assertTrue('if (existingJob.bookId > 0)' in source,
                        'Success recovery must validate positive identity before its upsert branches')
        guard = source.split('if (existingJob.bookId > 0)', 1)[1].split('val file = File(outputPath)', 1)[0]
        run_kotlin('''
data class Row(val id: Long, val filePath: String)
data class Job(val bookId: Long)
class Dao(val rows: List<Row>) { fun findById(id: Long) = rows.find { it.id == id } }
object Result { fun failure() = false }
fun recovered(existingJob: Job, bookDao: Dao, outputPath: String): Boolean {
    if (existingJob.bookId > 0) ''' + guard + '''
    return true
}
fun main() {
    val replacement = Row(99L, "/reused.epub")
    check(!recovered(Job(41L), Dao(listOf(replacement)), "/reused.epub"))
    check(!recovered(Job(41L), Dao(listOf(Row(41L, "/moved.epub"), replacement)), "/reused.epub"))
    check(recovered(Job(41L), Dao(listOf(Row(41L, "/moved.epub"))), "/moved.epub"))
    check(recovered(Job(0L), Dao(emptyList()), "/legacy.epub"))
}
''')

    def test_main_ui_always_hands_off_room_identity(self):
        source = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('.putExtra("book_id", book.libraryId)', source)
        self.assertNotRegex(source, r'enqueue(?:Update|ForceDownload)\(0,')
        self.assertEqual(source.count('enqueueUpdate(book.libraryId, book.uriString)'), 3)
        self.assertIn('enqueueForceDownload(book.libraryId, book.uriString)', source)
        self.assertIn('LibraryBookMutations.run(applicationContext, book.uriString, expectedBookId = book.libraryId)', source)

    def test_room_identity_survives_ui_mapping_copy_and_save(self):
        run_kotlin('''
import com.vivi.aharana.data.local.*
import com.vivi.aharana.model.BookItem
fun main() {
    val original = BookEntity(41L, "Title", "Author", "https://story", "/old.epub",
        null, 20L, 30L, null, 4, 10L)
    val ui = original.toBookItem()
    val edited = ui.copy(title = "Edited", uriString = "/Author/Title.epub").toEntity(99L)
    check(edited.id == original.id) { "Room identity was lost by UI mapping/copy/save: ${edited.id}" }
    check(edited.addedAt == 10L)
    check(edited.filePath == "/Author/Title.epub")
    check(BookItem("New", "Author", "/new.epub", 20L, 30L).toEntity(99L).id == 0L)
}
''', [SRC / 'model/BookItem.kt', SRC / 'data/local/BookMappers.kt', SRC / 'data/local/BookEntity.kt'], '''
package androidx.room
annotation class Entity(val tableName: String, val indices: Array<Index>)
annotation class Index(val value: Array<String>, val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo
''')


if __name__ == '__main__':
    unittest.main()
