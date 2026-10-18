package com.vivi.aharana

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Pure JVM file safety primitives; exercised without Android or mocked storage. */
object StoryRefreshFiles {
    fun saveNewCopy(source: File, directory: File, name: String): File {
        check(source.isFile && source.canRead() && source.length() > 0) { "Refresh source is missing or empty" }
        check(name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !File(name).isAbsolute) { "Invalid output filename" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create output folder" }
        val target = File(directory, name)
        check(target.createNewFile()) { "Output filename is already in use" }
        var staging: File? = null
        try {
            staging = File.createTempFile(".story_refresh_", ".part", directory)
            staging.outputStream().use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            staging.inputStream().use { verifyCopy(source, it) }
            check(staging.renameTo(target)) { "Cannot finalize refreshed EPUB" }
            target.inputStream().use { verifyCopy(source, it) }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            staging?.delete()
        }
    }

    fun verifyCopy(source: File, destination: InputStream) {
        fun hash(input: InputStream): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest()
        }
        val expected = source.inputStream().use { hash(it) }
        check(MessageDigest.isEqual(expected, hash(destination))) { "Saved EPUB verification failed" }
    }
}
