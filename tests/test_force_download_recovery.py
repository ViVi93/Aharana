"""Force recovery: real filesystem policy plus Android boundary wiring contracts."""
from pathlib import Path
import unittest

from test_stable_book_identity import run_kotlin, SRC


class ForceDownloadRecoveryTests(unittest.TestCase):
    def test_production_worker_and_detail_copy_keep_room_identity_date_and_provenance(self):
        def copy_expression(text):
            start = text.index('existing.copy(')
            depth = 0
            for index in range(start + len('existing.copy'), len(text)):
                if text[index] == '(':
                    depth += 1
                elif text[index] == ')':
                    depth -= 1
                    if depth == 0:
                        return text[start:index + 1]
            raise AssertionError('Unterminated production copy expression')
        worker = (SRC / 'FanFicFareWorker.kt').read_text().split('private suspend fun handleForceDownload(', 1)[1]
        worker_copy = copy_expression(worker.split('val updatedEntity =', 1)[1])
        update = (SRC / 'FanFicFareWorker.kt').read_text().split('private suspend fun handleUpdate(', 1)[1]
        update_copy = copy_expression(update.split('val updated =', 1)[1])
        detail = (SRC / 'BookDetailActivity.kt').read_text().split('private fun downloadExistingBook(', 1)[1]
        detail_copy = copy_expression(detail.split('bookDao.update(', 1)[1])
        run_kotlin('''
import com.vivi.aharana.data.local.BookEntity
import com.vivi.aharana.LibraryEpubStorage
import java.io.File
class Payload(val cover: String? = null) {
    fun optString(key: String, fallback: String = ""): String = when(key) {
        "title" -> "Recovered title"
        "url" -> "https://example.invalid/story"
        "cover" -> cover ?: fallback
        else -> fallback
    }
    fun optLong(key: String, fallback: Long): Long = if(key == "modified") 999L else fallback
    fun optInt(key: String, fallback: Int): Int = if(key == "chapters") 9 else fallback
}
fun worker(existing: BookEntity, finalPath: String, result: Payload): BookEntity {
    val path = existing.filePath
    val title = result.optString("title", existing.title)
    return ''' + worker_copy + '''
}
fun update(existing: BookEntity, finalPath: String, result: Payload): BookEntity {
    val path = existing.filePath
    val title = result.optString("title", existing.title)
    return ''' + update_copy + '''
}
fun detail(existing: BookEntity, finalPath: String, response: Payload): BookEntity {
    val sourcePath = LibraryEpubStorage.sourceAfterReplacement(existing.filePath, existing.sourcePath, finalPath)
    return ''' + detail_copy + '''
}
fun main() {
    val generated = File.createTempFile("recovered-", ".epub", File(System.getenv("TMPDIR"))).apply { writeText("new book") }
    try {
        val original = BookEntity(41, "Old", "Author", "https://example.invalid/story", "/missing.epub",
            "content://independent/source", 50, 20, "stored-cover", 2, 1234)
        for (cover in listOf(null, "", "   ", "NEW_COVER")) {
          for (result in listOf(worker(original, generated.path, Payload(cover)), update(original, generated.path, Payload(cover)),
              detail(original, generated.path, Payload(cover)))) {
            check(result.id == 41L && result.addedAt == 1234L)
            check(result.filePath == generated.path && result.sourcePath == "content://independent/source")
            val expectedCover = cover?.takeIf { it.isNotBlank() } ?: "stored-cover"
            check(result.coverData == expectedCover) { "Empty/missing cover response erased cached art: cover=$cover" }
            check(result.title == "Recovered title" && result.chapters == 9)
          }
        }
    } finally { generated.delete() }
}
''', sources=[SRC / 'data/local/BookEntity.kt', SRC / 'LibraryEpubStorage.kt', SRC / 'StoryRefreshFiles.kt'], stubs='''
package androidx.room
annotation class Entity(val tableName: String, val indices: Array<Index>)
annotation class Index(val value: Array<String>, val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo(val name: String = "")
''')

    def test_force_save_restores_missing_path_without_clobber_and_keeps_failed_source(self):
        storage = (SRC / 'LibraryEpubStorage.kt').read_text()
        self.assertIn('fun saveForced(', storage, 'Force Download cannot yet restore a missing EPUB')
        self.assertIn('replaceAllowed', storage, 'A file appearing during missing-book download must not be overwritten')
        run_kotlin('''
import com.vivi.aharana.LibraryEpubStorage
import java.io.File
fun main() {
    val root = java.nio.file.Files.createTempDirectory(File(System.getenv("TMPDIR")).toPath(), "force-recovery-").toFile()
    try {
        val source = File(root, "generated.epub").apply { writeText("verified generated fixture") }
        val old = File(root, "Author/Title.epub")
        var replacements = 0; var recoveries = 0
        fun save(path: String) = LibraryEpubStorage.saveForced(source, path,
            replaceExisting = { input, target -> replacements++; LibraryEpubStorage.replace(input, File(target)) },
            recoveryCopy = { input -> recoveries++; File(root, "recovery-$recoveries.epub").apply { input.copyTo(this) }.path })
        check(save(old.path) == old.path && old.readText() == source.readText())
        check(replacements == 0 && recoveries == 0 && source.exists())
        source.writeText("fresh forced fixture")
        check(save(old.path) == old.path && old.readText() == "fresh forced fixture" && replacements == 1)
        val directoryAtTarget = File(root, "not-a-file.epub").apply { mkdir() }
        val recovered = save(directoryAtTarget.path)
        check(File(recovered).readText() == source.readText() && directoryAtTarget.isDirectory && recoveries == 1)
        check(save("").endsWith("recovery-2.epub"))
        // A collision arriving after the missing check must not be overwritten.
        val raced = File(root, "raced.epub")
        val safe = LibraryEpubStorage.saveForced(source, raced.path,
            replaceExisting = { _, _ -> error("must not overwrite raced file") },
            recoveryCopy = { input -> File(root, "raced-recovery.epub").apply { input.copyTo(this) }.path },
            saveMissing = { _, dir, name -> File(dir, name).writeText("other book"); error("collision") })
        check(raced.readText() == "other book" && File(safe).readText() == source.readText())
        // This file was absent when generation began but appeared before saving.
        val appeared = File(root, "appeared.epub").apply { writeText("unrelated book") }
        val elsewhere = LibraryEpubStorage.saveForced(source, appeared.path, replaceAllowed = false,
            replaceExisting = { _, _ -> error("must not replace a late arrival") },
            recoveryCopy = { input -> File(root, "appeared-recovery.epub").apply { input.copyTo(this) }.path })
        check(appeared.readText() == "unrelated book" && File(elsewhere).readText() == source.readText())
        val absent = File(root, "absent.epub")
        check(runCatching { LibraryEpubStorage.saveForced(absent, old.path,
            replaceExisting = { _, _ -> error("invalid source reached replace") },
            recoveryCopy = { error("invalid source reached copy") }) }.isFailure)
        check(old.readText() == "fresh forced fixture")
        val denied = File(root, "denied.epub")
        check(runCatching { LibraryEpubStorage.saveForced(source, denied.path,
            replaceExisting = { _, _ -> error("not present") },
            recoveryCopy = { error("cannot write configured output") },
            saveMissing = { _, _, _ -> throw java.io.IOException("permission denied") }) }.isFailure)
        check(!denied.exists() && source.exists())
        val cancelled = runCatching { LibraryEpubStorage.saveForced(source, File(root,"cancelled.epub").path,
            replaceExisting = { _, _ -> error("unused") }, recoveryCopy = { error("must not copy after cancellation") },
            saveMissing = { _, _, _ -> throw java.util.concurrent.CancellationException("stopped") }) }.exceptionOrNull()
        check(cancelled is java.util.concurrent.CancellationException)
        var document: String? = null
        check(LibraryEpubStorage.saveForced(source, "content://old/book",
            replaceExisting = { _, path -> document = path; "content://new/copy" },
            recoveryCopy = { error("use established document copy route") }) == "content://new/copy")
        check(document == "content://old/book")
    } finally { root.deleteRecursively() }
}
''', sources=[SRC / 'LibraryEpubStorage.kt', SRC / 'StoryRefreshFiles.kt'])

    def test_worker_and_details_pass_canonical_saved_url_and_use_safe_force_save(self):
        bridge = (SRC / 'PythonBridge.kt').read_text()
        self.assertIn('fallbackUrl: String? = null', bridge)
        self.assertIn('safeCall("force_download_from_epub", epubPath, outDir, fallbackUrl)', bridge)
        worker = (SRC / 'FanFicFareWorker.kt').read_text()
        force = worker.split('private suspend fun handleForceDownload(', 1)[1].split('private suspend fun handleMetadata(', 1)[0]
        self.assertIn('val fallbackUrl = existing?.url', force)
        self.assertIn('forceDownloadFromEpub("", outputDir, fallbackUrl)', force)
        self.assertIn('localFile.absolutePath, outputDir, fallbackUrl', force)
        self.assertIn('LibraryEpubStorage.saveForced(', force)
        self.assertIn('replaceAllowed = replaceAllowed', force)
        self.assertIn('id = existing.id', force)
        detail = (SRC / 'BookDetailActivity.kt').read_text()
        action = detail.split('private fun downloadExistingBook(', 1)[1].split('private fun shareBook(', 1)[0]
        self.assertIn('val fallbackUrl = existing?.url', action)
        self.assertIn('forceDownloadFromEpub("", workDir.absolutePath, fallbackUrl)', action)
        self.assertIn('LibraryEpubStorage.saveForced(', action)
        self.assertIn('replaceAllowed = replaceAllowed', action)
        self.assertIn('if (force)', action)
        update = worker.split('private suspend fun handleUpdate(', 1)[1].split('private suspend fun handleForceDownload(', 1)[0]
        self.assertNotIn('LibraryEpubStorage.saveForced(', update)
        self.assertNotIn('forceDownloadFromEpub', update)
        self.assertIn('Use Force Download', update)


if __name__ == '__main__':
    unittest.main()
