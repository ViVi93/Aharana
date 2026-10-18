package com.vivi.aharana

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** A cached record and the independent metadata/file identities used to validate it. */
internal data class LibraryFacetIndexEntry(
    val bookSignature: String,
    val fileSignature: String,
    val record: LibraryFacetRecord
)

/**
 * Portable LFIP v1 payload: magic/version, length-prefixed UTF-8 path, category/value
 * counts and strings, then SHA-256 of every preceding byte (including the path).
 * Invalid writes throw IOException; malformed reads are cache misses, never partial records.
 * Checksums detect accidental corruption, not adversarial rewriting of both data and digest.
 */
internal object LibraryFacetPayload {
    const val MAX_STRING_BYTES = 65_536
    const val MAX_PAYLOAD_BYTES = 524_288
    private const val MAX_VALUES = 4096
    private const val DIGEST_BYTES = 32

    fun encode(record: LibraryFacetRecord): ByteArray {
        if (record.facets.size > LibraryFilters.categories.size) throw IOException("Too many facet categories")
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x4c464950) // LFIP
            output.writeInt(1)
            writeString(output, record.path)
            output.writeInt(record.facets.size)
            for ((key, values) in record.facets) {
                if (key !in LibraryFilters.categories || values.size > MAX_VALUES) throw IOException("Invalid facet category")
                writeString(output, key)
                output.writeInt(values.size)
                values.forEach {
                    if (it.isBlank()) throw IOException("Blank facet value")
                    writeString(output, it)
                }
            }
            if (output.size() > MAX_PAYLOAD_BYTES - DIGEST_BYTES) throw IOException("Facet payload too large")
        }
        val body = buffer.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    fun decode(bytes: ByteArray): LibraryFacetRecord? = try {
        if (bytes.size < 48 || bytes.size > MAX_PAYLOAD_BYTES) null else {
            val bodySize = bytes.size - DIGEST_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes, 0, bodySize)
            if (!MessageDigest.isEqual(digest.digest(), bytes.copyOfRange(bodySize, bytes.size))) null else {
                DataInputStream(ByteArrayInputStream(bytes, 0, bodySize)).use { input ->
                    if (input.readInt() != 0x4c464950 || input.readInt() != 1) return null
                    val path = readString(input)
                    val facets = linkedMapOf<String, Set<String>>()
                    repeat(readCount(input, LibraryFilters.categories.size)) {
                        val key = readString(input)
                        if (key !in LibraryFilters.categories || key in facets) throw IOException("Invalid facet category")
                        val values = linkedSetOf<String>()
                        repeat(readCount(input, MAX_VALUES)) {
                            val value = readString(input)
                            if (value.isBlank() || !values.add(value)) throw IOException("Invalid facet value")
                        }
                        facets[key] = values
                    }
                    if (input.read() != -1) null else LibraryFacetRecord(path, facets)
                }
            }
        }
    } catch (_: IOException) {
        null
    }

    /** Validate bounded database columns before exposing a cached entry to the caller. */
    fun decodeEntry(path: String, bookSignature: String, fileSignature: String,
                    payload: ByteArray, checksum: ByteArray): LibraryFacetIndexEntry? = try {
        if (checksum.size != DIGEST_BYTES || !MessageDigest.isEqual(checksum,
                rowChecksum(path, bookSignature, fileSignature, payload))) null else {
            decode(payload)?.takeIf { it.path == path }?.let {
                LibraryFacetIndexEntry(bookSignature, fileSignature, it)
            }
        }
    } catch (_: IOException) {
        null
    }

    /** LFIR v1 frames identity columns and payload; concatenated signatures cannot alias. */
    fun rowChecksum(path: String, bookSignature: String, fileSignature: String, payload: ByteArray): ByteArray {
        if (payload.size > MAX_PAYLOAD_BYTES) throw IOException("Facet payload too large")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(0x4c464952).putInt(1).array())
        for (bytes in listOf(stringBytes(path), stringBytes(bookSignature), stringBytes(fileSignature), payload)) {
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest()
    }

    private fun writeString(output: DataOutputStream, text: String) {
        val bytes = stringBytes(text)
        if (bytes.size + 4 > MAX_PAYLOAD_BYTES - DIGEST_BYTES - output.size()) throw IOException("Facet payload too large")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun stringBytes(text: String): ByteArray {
        if (text.length > MAX_STRING_BYTES) throw IOException("Facet string too large")
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        if (encoded.remaining() > MAX_STRING_BYTES) throw IOException("Facet string too large")
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun readString(input: DataInputStream): String {
        val size = readCount(input, MAX_STRING_BYTES)
        if (size > input.available()) throw IOException("Truncated facet string")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun readCount(input: DataInputStream, maximum: Int): Int {
        val count = input.readInt()
        if (count < 0 || count > maximum) throw IOException("Invalid facet count")
        return count
    }
}
