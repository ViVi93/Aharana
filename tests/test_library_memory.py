"""Host regressions: execute production SQL/Kotlin; Android bindings are contracts.

These do not reproduce Android heap pressure or replace device profiling.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/vivi/aharana'


def query_for(method):
    dao = (SRC / 'data/local/BookDao.kt').read_text()
    match = re.search(r'@Query\("([^"\n]+)"\)\s*(?:suspend )?fun ' + method + r'\(', dao)
    assert match, 'Missing query: ' + method
    return match.group(1)


def database():
    db = sqlite3.connect(':memory:')
    db.execute('CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, author TEXT, url TEXT, '
               'filePath TEXT UNIQUE, sourcePath TEXT, lastModified INTEGER, sizeBytes INTEGER, '
               'coverData TEXT, chapters INTEGER, addedAt INTEGER)')
    return db


class LibraryMemorySqlTest(unittest.TestCase):
    def test_lightweight_mapping_keeps_identity_without_a_cover_payload(self):
        from test_stable_book_identity import run_kotlin
        row = SRC / 'data/local/LibraryBookRow.kt'
        self.assertTrue(row.exists(), 'Missing lightweight Room row/mapping')
        run_kotlin('''
import com.vivi.aharana.data.local.*
fun main() {
    val row = LibraryBookRow(41L, "Title", "Author", "https://story", "/book.epub",
        "/source.epub", 12L, 40L, 3, 7L)
    val ui = row.toBookItem()
    check(ui.libraryId == 41L && ui.addedAt == 7L)
    check(ui.uriString == "/book.epub" && ui.sourceUriString == "/source.epub")
    check(ui.chapters == 3 && ui.coverUriString == null)
}
''', [row, SRC / 'model/BookItem.kt'])

    def test_metadata_and_path_updates_preserve_stored_cover_and_identity(self):
        db = database()
        self.addCleanup(db.close)
        payload = 'data:image/png;base64,original'
        db.execute('INSERT INTO books VALUES (41, ?, ?, ?, ?, ?, 12, 40, ?, 3, 7)',
                   ('Title', 'Author', 'https://story', '/book.epub', '/source.epub', payload))
        values = dict(id=41, title='Edited', author='Author', url='https://story', filePath='/book.epub',
                      sourcePath='/source.epub', lastModified=13, sizeBytes=44, chapters=4, coverData=None)
        update = query_for('updateMetadata')
        for cover in [None, '', 'data:image/png;base64,new']:
            values['coverData'] = cover
            db.execute(update, values)
            expected = cover or payload
            self.assertEqual(db.execute('SELECT id, addedAt, coverData FROM books').fetchone(), (41, 7, expected))
        db.execute(query_for('updatePaths'), dict(id=41, filePath='/Author/Title.epub', sourcePath='/source.epub'))
        self.assertEqual(db.execute('SELECT id, addedAt, filePath, sourcePath, coverData FROM books').fetchone(),
                         (41, 7, '/Author/Title.epub', '/source.epub', 'data:image/png;base64,new'))
        self.assertEqual(db.execute(query_for('getCoverData'), dict(id=41)).fetchone()[0], 'data:image/png;base64,new')

    def test_bulk_persistence_does_not_clear_or_fetch_cover_entities(self):
        source = (SRC / 'BookRepository.kt').read_text()
        for start, end in [('fun setBooks(', '/** Caller holds the storage gate'),
                           ('suspend fun saveLibrary()', 'private suspend fun upsertBookEntity')]:
            body = source.split(start, 1)[1].split(end, 1)[0]
            self.assertNotIn('bookDao.clear()', body, 'Lightweight shelf must never clear/reinsert stored covers')
            self.assertNotIn('deleteById(', body, 'Omitted snapshot rows are not an authoritative deletion request')
        upsert = source.split('private suspend fun upsertBookEntity', 1)[1].split('private suspend fun migrateIfNeeded', 1)[0]
        self.assertIn('updateMetadata(', upsert)
        self.assertNotIn('bookDao.findBy', upsert, 'Bulk persistence must use cover-free identity lookups')
        self.assertIn('bookDao.findRowById(book.libraryId)', upsert)
        self.assertIn('existing.id', upsert)
        recovery = source.split('suspend fun reconcileOrganisedPaths', 1)[1].split('private const val BULK_METADATA_TAG', 1)[0]
        self.assertIn('bookDao.updatePaths(', recovery)
        self.assertNotIn('bookDao.update(book.copy(', recovery)
        self.assertNotIn('bookDao.findById(', recovery, 'Recovery deletion only needs ID/path, not the cover')
        scan = source.split('suspend fun importOrganisedLibrary(', 1)[1].split('suspend fun loadLibrary()', 1)[0]
        self.assertNotIn('findByFilePath(', scan)
        self.assertIn('updateMetadata(', scan)

    def test_process_value_publishes_once_under_concurrent_screen_creation(self):
        from test_stable_book_identity import run_kotlin
        helper = SRC / 'ProcessValue.kt'
        self.assertTrue(helper.exists(), 'Missing process-shared initialization coordinator')
        run_kotlin('''
import com.vivi.aharana.ProcessValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
fun main() {
    val shared = ProcessValue<Any>()
    val starts = AtomicInteger()
    val gate = CountDownLatch(1)
    val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
    val threads = (1..32).map { Thread {
        gate.await()
        results.add(shared.get { starts.incrementAndGet(); Thread.sleep(2); Any() })
    }.also { it.start() } }
    gate.countDown(); threads.forEach { it.join() }
    check(starts.get() == 1 && results.size == 32)
    check(results.all { it === results.first() })
    val retry = ProcessValue<String>()
    check(runCatching { retry.get { error("failed init") } }.isFailure)
    check(retry.get { "recovered" } == "recovered")
}
''', [helper])
        source = (SRC / 'BookRepository.kt').read_text()
        self.assertIn('processState.started.get {', source)
        self.assertIn('private val _books = processState.books', source)
        self.assertIn('private val _latestJobs = processState.jobs', source)
        self.assertNotIn('MediatorLiveData', source)
        for name in ['AddFromPageActivity.kt', 'ShareReceiverActivity.kt', 'DownloadQueueActivity.kt']:
            self.assertNotRegex((SRC / name).read_text(), r'BookRepository\(this')

    def test_cover_cache_is_byte_bounded_and_sampling_caps_both_dimensions(self):
        from test_stable_book_identity import run_kotlin
        helper = SRC / 'CoverMemory.kt'
        self.assertTrue(helper.exists(), 'Missing bounded cover policy/cache')
        run_kotlin('''
import com.vivi.aharana.*
fun main() {
    val cache = CoverMemoryCache<String, String>(10) { it.length.toLong() }
    cache.put("a", "aaaa"); cache.put("b", "bbbb")
    check(cache.get("a") == "aaaa")
    cache.put("c", "cccc")
    check(cache.get("b") == null && cache.get("a") != null)
    check(cache.weightBytes == 8L)
    cache.put("a", "xx"); check(cache.weightBytes == 6L)
    cache.put("huge", "x".repeat(11)); check(cache.weightBytes <= 10L)
    check(cache.get("huge") == null)
    cache.clear(); check(cache.weightBytes == 0L && cache.get("a") == null)
    for ((w, h) in listOf(8000 to 12000, 20000 to 1, 1 to 20000, 320 to 240, 321 to 321)) {
        val sample = CoverDecodePolicy.sampleSize(w, h, 320)
        check(sample > 0 && sample.countOneBits() == 1)
        check(w / sample <= 320 && h / sample <= 320)
    }
    check(CoverDecodePolicy.sampleSize(0, -1, 320) == 1)
    val input = AsciiSliceInputStream("data:image/png;base64,aGVsbG8=", 22)
    check(String(java.util.Base64.getDecoder().wrap(input).readBytes()) == "hello")
}
''', [helper])

    def test_visible_cover_loading_is_off_main_cancellable_and_sampled(self):
        adapter = (SRC / 'adapter/BookAdapter.kt').read_text()
        self.assertNotIn('Base64.decode(', adapter, 'Do not decode cover bytes on the main bind path')
        self.assertNotIn('BitmapFactory', adapter)
        self.assertIn('coverJob?.cancel()', adapter)
        self.assertIn('bindingVersion == version', adapter, 'Recycled rows must reject stale images/placeholders')
        self.assertIn('coverLoader.load(book)', adapter)
        loader = (SRC / 'LibraryCoverLoader.kt').read_text()
        self.assertIn('withContext(Dispatchers.IO)', loader)
        self.assertIn('decodeMutex.withLock', loader, 'Bound concurrent full-cover materialization to one')
        self.assertIn('getCoverData(book.libraryId)', loader)
        self.assertIn('inJustDecodeBounds = true', loader)
        self.assertIn('inSampleSize = CoverDecodePolicy.sampleSize', loader)
        self.assertIn('Base64InputStream', loader)
        self.assertNotIn('decodeByteArray', loader)
        self.assertNotIn('OutOfMemoryError', loader)
        self.assertIn('8L * 1024 * 1024', loader)
        self.assertIn('currentCoroutineContext().ensureActive()', loader)

    def test_sampling_caps_rounded_decoder_dimensions(self):
        from test_stable_book_identity import run_kotlin
        run_kotlin('''
import com.vivi.aharana.CoverDecodePolicy
fun main() {
    for ((w, h) in listOf(641 to 1, 1 to 641, 10241 to 100)) {
        val sample = CoverDecodePolicy.sampleSize(w, h, 320)
        check((w.toLong() + sample - 1) / sample <= 320)
        check((h.toLong() + sample - 1) / sample <= 320)
    }
}
''', [SRC / 'CoverMemory.kt'])

    def test_real_cover_loader_serializes_reads_and_fences_invalidation(self):
        # Execute the real coroutine/cache boundary with Room/graphics collaborators.
        # Fake BitmapFactory is NOT evidence about Android decoding or heap behavior.
        from library_memory_host import run_host
        generated = {
            'Context.kt': '''package android.content
class Context { val applicationContext get() = this }
''',
            'Graphics.kt': '''package android.graphics
import java.io.InputStream
class Bitmap(val allocationByteCount: Int = 320 * 320 * 4)
object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false; var outWidth = 8000; var outHeight = 12000
        var inSampleSize = 1; var inScaled = true
    }
    fun decodeStream(input: InputStream, rect: Any?, options: Options): Bitmap? {
        check(String(input.readBytes()) == "hello")
        if (options.inJustDecodeBounds) return null
        check(options.inSampleSize >= 32 && !options.inScaled)
        return Bitmap()
    }
}
''',
            'Base64.kt': '''package android.util
import java.io.InputStream
object Base64 { const val DEFAULT = 0 }
class Base64InputStream(input: InputStream, flags: Int) :
    java.io.FilterInputStream(java.util.Base64.getDecoder().wrap(input))
''',
            'Database.kt': '''package com.vivi.aharana.data.local
import android.content.Context
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
class Dao {
    val reads = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
    suspend fun getCoverData(id: Long): String? {
        reads.incrementAndGet()
        val current = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, current) }
        try { delay(40); return "data:image/png;base64,aGVsbG8=" }
        finally { active.decrementAndGet() }
    }
    suspend fun getCoverDataByPath(path: String) = getCoverData(0)
}
object AppDatabase {
    val dao = Dao()
    fun getInstance(context: Context) = this
    fun bookDao() = dao
}
''',
            'CoverLoaderHost.kt': '''
import android.content.Context
import com.vivi.aharana.*
import com.vivi.aharana.model.BookItem
import com.vivi.aharana.data.local.AppDatabase
import kotlinx.coroutines.*
fun main() = runBlocking {
    val loader = LibraryCoverLoader.getInstance(Context())
    val dao = AppDatabase.dao
    val book = BookItem("Title", "Author", "/book.epub", 12, 40, libraryId = 41)
    val results = (1..24).map { async(Dispatchers.Default) { loader.load(book) } }.awaitAll()
    check(results.all { it === results.first() } && dao.reads.get() == 1)
    check(dao.maximum.get() == 1)
    loader.invalidate()
    val first = async { loader.load(book) }
    while (dao.active.get() == 0) yield()
    val cancelledWaiter = async { loader.load(book.copy(libraryId = 42)) }
    cancelledWaiter.cancelAndJoin()
    loader.invalidate() // A decode from the old generation must not repopulate current cache.
    first.await()
    check(dao.reads.get() == 2)
    loader.load(book)
    check(dao.reads.get() == 3)
    loader.invalidate()
    (1..12).map { async { loader.load(book.copy(libraryId = it.toLong())) } }.awaitAll()
    check(dao.maximum.get() == 1)
}
'''}
        run_host([SRC / name for name in ['LibraryCoverLoader.kt', 'CoverMemory.kt', 'ProcessValue.kt',
                                         'model/BookItem.kt']], generated, 'CoverLoaderHostKt', coroutines=True)

    def test_save_is_a_pending_write_barrier_not_a_stale_shelf_rewrite(self):
        source = (SRC / 'BookRepository.kt').read_text()
        save = source.split('suspend fun saveLibrary()', 1)[1].split('private suspend fun upsertBookEntity', 1)[0]
        self.assertNotIn('upsertBookEntity(', save, 'A stale shelf flush must not overwrite newer worker/move metadata')
        self.assertIn('processState.writes.awaitPending()', save)
        from library_memory_host import run_host
        helper = SRC / 'LibraryWrites.kt'
        self.assertTrue(helper.exists())
        run_host([helper, SRC / 'LibraryStorageGate.kt'], {'WriteBarrierHost.kt': '''
import com.vivi.aharana.*
import kotlinx.coroutines.*
fun main() = runBlocking {
    val writes = LibraryWrites()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    var committed = false
    val holder = launch { LibraryStorageGate.run { entered.complete(Unit); release.await() } }
    entered.await()
    writes.launch(scope) { LibraryStorageGate.run { committed = true } }
    val flush = async { writes.awaitPending() }
    delay(40); check(!committed && !flush.isCompleted)
    release.complete(Unit); holder.join(); flush.await()
    check(committed)
    writes.awaitPending()
    scope.cancel()
}
'''}, 'WriteBarrierHostKt', coroutines=True)

    def test_real_repository_upsert_and_flush_keep_identity_cover_and_newer_rows(self):
        from library_memory_host import run_host
        source = (SRC / 'BookRepository.kt').read_text()

        def method(marker):
            start = source.index(marker)
            opening = source.index('{', start)
            end, depth = opening + 1, 1
            while depth:
                depth += (source[end] == '{') - (source[end] == '}')
                end += 1
            return source[start:end]

        methods = '\n'.join(method(marker) for marker in [
            'suspend fun saveLibrary()', 'private suspend fun upsertBookEntity(',
            'private suspend fun findByExisting('])
        generated = {'Room.kt': '''package androidx.room
annotation class Entity(val tableName: String, val indices: Array<Index>)
annotation class Index(val value: Array<String>, val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo
''', 'RepositoryHost.kt': '''
import com.vivi.aharana.*
import com.vivi.aharana.data.local.*
import com.vivi.aharana.model.BookItem
import kotlinx.coroutines.*
class Dao {
    val rows = mutableMapOf<Long, BookEntity>()
    fun row(entity: BookEntity) = LibraryBookRow(entity.id, entity.title, entity.author, entity.url,
        entity.filePath, entity.sourcePath, entity.lastModified, entity.sizeBytes, entity.chapters, entity.addedAt)
    suspend fun findRowById(id: Long) = rows[id]?.let(::row)
    suspend fun findRowByFilePath(path: String) = rows.values.find { it.filePath == path }?.let(::row)
    suspend fun findRowByUrl(url: String) = rows.values.find { it.url == url }?.let(::row)
    suspend fun count() = rows.size
    suspend fun insert(book: BookEntity) { rows[book.id] = book }
    suspend fun updateMetadata(id: Long, title: String, author: String, url: String?, filePath: String,
        sourcePath: String?, lastModified: Long, sizeBytes: Long, chapters: Int, coverData: String?) {
        val old = rows.getValue(id)
        rows[id] = old.copy(title = title, author = author, url = url, filePath = filePath,
            sourcePath = sourcePath, lastModified = lastModified, sizeBytes = sizeBytes,
            chapters = chapters, coverData = coverData?.takeIf { it.isNotBlank() } ?: old.coverData)
    }
}
class Database { suspend fun <T> withTransaction(block: suspend () -> T): T = block() }
internal class State { val writes = LibraryWrites() }
internal class RepositoryHost(val bookDao: Dao) {
    val database = Database()
    val processState = State()
    suspend fun upsert(book: BookItem) = upsertBookEntity(book)
''' + methods + '''
}
fun main() = runBlocking {
    val dao = Dao()
    val original = BookEntity(41, "Title", "Author", "https://story", "/book.epub", "/source.epub",
        12, 40, "data:image/png;base64,original", 3, 7)
    dao.rows[41] = original
    dao.rows[99] = original.copy(id = 99, title = "Worker inserted", filePath = "/new.epub")
    val repo = RepositoryHost(dao)
    repo.upsert(original.toBookItem().copy(title = "Edited", coverUriString = null))
    check(dao.rows.getValue(41).id == 41L && dao.rows.getValue(41).addedAt == 7L)
    check(dao.rows.getValue(41).coverData == original.coverData)
    // Legacy ID 0 must update the existing row ID, not an @Update entity with ID 0.
    repo.upsert(original.toBookItem().copy(libraryId = 0, title = "Legacy", coverUriString = ""))
    check(dao.rows.getValue(41).title == "Legacy" && dao.rows.size == 2)
    dao.rows[41] = dao.rows.getValue(41).copy(title = "Newer worker", filePath = "/Author/Title.epub")
    check(repo.saveLibrary())
    check(dao.rows.getValue(41).title == "Newer worker")
    check(dao.rows.getValue(41).filePath == "/Author/Title.epub")
    check(dao.rows.getValue(99).title == "Worker inserted")
    val removed = original.toBookItem().copy(libraryId = 42)
    check(runCatching { repo.upsert(removed) }.isFailure)
    check(dao.rows.size == 2 && dao.rows.getValue(41).coverData == original.coverData)
}
'''}
        run_host([SRC / name for name in ['LibraryWrites.kt', 'LibraryStorageGate.kt', 'model/BookItem.kt',
                                         'data/local/BookEntity.kt', 'data/local/BookMappers.kt',
                                         'data/local/LibraryBookRow.kt']], generated, 'RepositoryHostKt', coroutines=True)

    def test_library_queries_do_not_materialize_inline_covers(self):
        db = database()
        self.addCleanup(db.close)
        payload = 'data:image/png;base64,' + 'A' * (1024 * 1024)
        for i in range(12):
            db.execute('INSERT INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
                       (i + 1, 'Title', 'Author', 'https://story', f'/{i}.epub', None, i, 40, payload, 3, 5))
        for method in ['getAll', 'observeAll']:
            cursor = db.execute(query_for(method))
            columns = [c[0] for c in cursor.description]
            self.assertNotIn('coverData', columns, 'Shelf queries must exclude inline image payloads')
            rows = cursor.fetchall()
            self.assertEqual(len(rows), 12)
            self.assertLess(sum(len(str(value)) for row in rows for value in row), 4096)
        self.assertEqual(db.execute('SELECT coverData FROM books WHERE id=1').fetchone()[0], payload)


if __name__ == '__main__':
    unittest.main()
