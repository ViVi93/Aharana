package com.vivi.aharana

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Incremental private-files-directory cache. Call only from an IO dispatcher.
 * Source paths are opaque keys: this class never opens, stats, or modifies an EPUB.
 * Each write stages, flushes and fsyncs a file before a same-directory rename.
 * Linux/Android renameTo replaces atomically without requiring API 26 java.nio.file.
 * Synchronization is per instance; independent instances see complete old/new files.
 */
class LibraryFacetDiskStore(private val directory: File) {
    @Synchronized
    fun read(path: String, fingerprint: String): Pair<LibraryFacetRecord, Boolean>? =
        readFile(recordFile(path), MAX_RECORD_BYTES, RECORD_KIND) { input ->
            val savedPath = readString(input)
            val savedFingerprint = readString(input)
            if (savedPath != path || savedFingerprint != fingerprint) throw IOException()
            val readable = when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> throw IOException()
            }
            Pair(LibraryFacetRecord(savedPath, readMap(input)), readable)
        }

    @Synchronized
    fun write(path: String, fingerprint: String, record: LibraryFacetRecord, readable: Boolean) {
        if (record.path != path) throw IOException("Facet cache write failed")
        writeFile(recordFile(path), MAX_RECORD_BYTES, RECORD_KIND) { output ->
            writeString(output, path)
            writeString(output, fingerprint)
            output.writeByte(if (readable) 1 else 0)
            writeMap(output, record.facets)
        }
    }

    @Synchronized
    fun readSelection(): Map<String, Set<String>> =
        readFile(File(directory, "selection.bin"), MAX_SELECTION_BYTES, SELECTION_KIND) { input ->
            validSelection(readMap(input))
        } ?: emptyMap()

    @Synchronized
    fun writeSelection(selection: Map<String, Set<String>>) {
        writeFile(File(directory, "selection.bin"), MAX_SELECTION_BYTES, SELECTION_KIND) { output ->
            writeMap(output, validSelection(selection))
        }
    }

    /** Drop inactive/unsupported criteria; retain exact nonblank values (including zero-count ones). */
    private fun validSelection(selection: Map<String, Set<String>>): Map<String, Set<String>> =
        selection.filterKeys { it in LibraryFilters.categories }.mapValues { (_, values) ->
            values.filter { it.isNotBlank() }.toSet()
        }.filterValues { it.isNotEmpty() }

    private fun recordFile(path: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val hex = CharArray(hash.size * 2)
        val digits = "0123456789abcdef"
        hash.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 15]
        }
        return File(directory, String(hex) + ".facet")
    }

    private fun <T> readFile(file: File, limit: Int, kind: Int, decode: (DataInputStream) -> T): T? = try {
        // Size the opened inode, not the path: rename may replace it between stat/open.
        val bytes = FileInputStream(file).use { stream ->
            val size = stream.channel.size()
            if (size < HEADER_BYTES || size > limit) throw IOException()
            ByteArray(size.toInt()).also {
                DataInputStream(stream).readFully(it)
                if (stream.read() != -1) throw IOException()
            }
        }
        val header = DataInputStream(ByteArrayInputStream(bytes))
        if (header.readInt() != MAGIC) throw IOException()
        val version = header.readInt()
        if (header.readInt() != kind) throw IOException()
        val contentSize = when (version) {
            VERSION -> {
                if (bytes.size < HEADER_BYTES + DIGEST_BYTES) throw IOException()
                val end = bytes.size - DIGEST_BYTES
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(bytes, 0, end)
                if (!MessageDigest.isEqual(digest.digest(), bytes.copyOfRange(end, bytes.size))) throw IOException()
                end
            }
            // Upgrade compatibility is selection-only. Unchecked bulk metadata must rebuild.
            LEGACY_VERSION -> if (kind == SELECTION_KIND) bytes.size else throw IOException()
            else -> throw IOException()
        }
        DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, contentSize - HEADER_BYTES)).use { input ->
            val result = decode(input)
            if (input.read() != -1) throw IOException()
            result
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun writeFile(file: File, limit: Int, kind: Int, encode: (DataOutputStream) -> Unit) {
        var staging: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            staging = File.createTempFile("facet-", ".tmp", directory)
            FileOutputStream(staging).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val checked = DigestOutputStream(BoundedOutput(stream, limit), digest)
                val output = DataOutputStream(BufferedOutputStream(checked))
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(kind)
                encode(output)
                output.flush()
                checked.on(false)
                output.write(digest.digest())
                output.flush()
                stream.fd.sync()
            }
            if (!staging.renameTo(file)) throw IOException()
        } catch (_: IOException) {
            throw IOException("Facet cache write failed")
        } catch (_: SecurityException) {
            throw IOException("Facet cache write failed")
        } finally {
            try { staging?.delete() } catch (_: SecurityException) { /* Best-effort cleanup. */ }
        }
    }

    private fun readMap(input: DataInputStream): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        repeat(readCount(input, MAX_KEYS)) {
            val key = readString(input)
            if (result.containsKey(key)) throw IOException()
            val values = linkedSetOf<String>()
            repeat(readCount(input, MAX_VALUES)) {
                if (!values.add(readString(input))) throw IOException()
            }
            result[key] = values
        }
        return result
    }

    private fun writeMap(output: DataOutputStream, values: Map<String, Set<String>>) {
        if (values.size > MAX_KEYS) throw IOException()
        output.writeInt(values.size)
        for ((key, items) in values) {
            writeString(output, key)
            if (items.size > MAX_VALUES) throw IOException()
            output.writeInt(items.size)
            for (value in items) writeString(output, value)
        }
    }

    private fun readCount(input: DataInputStream, max: Int): Int {
        val count = input.readInt()
        if (count < 0 || count > max) throw IOException()
        return count
    }

    private fun readString(input: DataInputStream): String {
        val size = readCount(input, MAX_STRING_BYTES)
        if (size > input.available()) throw IOException()
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun writeString(output: DataOutputStream, value: String) {
        if (value.length > MAX_STRING_BYTES) throw IOException()
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw IOException()
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private class BoundedOutput(private val delegate: OutputStream, private val limit: Int) : OutputStream() {
        private var count = 0
        override fun write(value: Int) {
            reserve(1)
            delegate.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            delegate.write(bytes, offset, length)
        }
        private fun reserve(length: Int) {
            if (length > limit - count) throw IOException()
            count += length
        }
    }

    private companion object {
        const val MAGIC = 0x4c464453 // LFDS
        const val LEGACY_VERSION = 1
        const val VERSION = 2
        const val HEADER_BYTES = 12
        const val DIGEST_BYTES = 32 // SHA-256 of header and payload, appended as a trailer.
        const val RECORD_KIND = 1
        const val SELECTION_KIND = 2
        const val MAX_SELECTION_BYTES = 2 * 1024 * 1024
        const val MAX_STRING_BYTES = 1024 * 1024
        const val MAX_RECORD_BYTES = 16 * 1024 * 1024
        const val MAX_KEYS = 4096
        const val MAX_VALUES = 65536
    }
}
