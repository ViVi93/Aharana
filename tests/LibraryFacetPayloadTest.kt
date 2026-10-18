package com.vivi.aharana

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

object LibraryFacetPayloadTest {
    private var passed = 0

    @JvmStatic fun main(args: Array<String>) {
        test("roundtrip preserves exact Unicode case punctuation and empty sets") {
            val record = LibraryFacetRecord("content://library/日本語/📖, One.epub", linkedMapOf(
                "authors" to linkedSetOf("Doe, Jane", "é", "e\u0301", "Case", "case", "中文 📚"),
                "tags" to linkedSetOf(" A/B: C, D; | ", "line\nbreak", "zero\u0000inside"),
                "languages" to emptySet(), "status" to setOf("Complete"),
                "site" to setOf("archiveofourown.org"), "series" to setOf("A [1]"),
                "chapters" to setOf("2–10 chapters")))
            check(LibraryFacetPayload.decode(LibraryFacetPayload.encode(record)) == record)
            val empty = LibraryFacetRecord("/opaque/path", emptyMap())
            check(LibraryFacetPayload.decode(LibraryFacetPayload.encode(empty)) == empty)
        }
        test("checksum rejects bit flips in path metadata header and checksum") {
            val record = LibraryFacetRecord("book.epub", mapOf("authors" to setOf("Jane")))
            val original = LibraryFacetPayload.encode(record)
            original.indices.forEach { offset ->
                val changed = original.copyOf()
                changed[offset] = (changed[offset].toInt() xor 1).toByte()
                check(LibraryFacetPayload.decode(changed) == null) { "accepted bit flip at $offset" }
            }
        }
        test("truncated trailing and empty input return null without throwing") {
            val original = LibraryFacetPayload.encode(LibraryFacetRecord("book.epub", emptyMap()))
            (0 until original.size).forEach { check(LibraryFacetPayload.decode(original.copyOf(it)) == null) }
            check(LibraryFacetPayload.decode(original + byteArrayOf(0)) == null)
        }
        test("encode rejects unknown categories blank values and invalid Unicode") {
            val invalid = listOf(
                LibraryFacetRecord("book", mapOf("unknown" to setOf("x"))),
                LibraryFacetRecord("book", mapOf("Authors" to setOf("x"))),
                LibraryFacetRecord("book", mapOf("tags" to setOf(""))),
                LibraryFacetRecord("book", mapOf("tags" to setOf(" \t\n"))),
                LibraryFacetRecord("book", mapOf("tags" to setOf("\u2003"))),
                LibraryFacetRecord("book", mapOf("tags" to setOf("\ud800"))),
                LibraryFacetRecord("\udc00", emptyMap()))
            invalid.forEach { record -> expectIOException { LibraryFacetPayload.encode(record) } }
        }
        test("encode enforces byte string collection and complete payload bounds") {
            val invalid = listOf(
                LibraryFacetRecord("x".repeat(65_537), emptyMap()),
                LibraryFacetRecord("book", mapOf("tags" to setOf("é".repeat(32_769)))),
                LibraryFacetRecord("book", mapOf("tags" to (0..4096).map { "tag-$it" }.toSet())),
                LibraryFacetRecord("book", mapOf("tags" to (0..8).map { "x".repeat(65_535) + it }.toSet())))
            invalid.forEach { record -> expectIOException { LibraryFacetPayload.encode(record) } }
            val boundary = LibraryFacetRecord("p".repeat(65_536), mapOf(
                "tags" to setOf("é".repeat(32_768))))
            check(LibraryFacetPayload.decode(LibraryFacetPayload.encode(boundary)) == boundary)
        }
        test("decode rejects correctly checksummed malformed structures") {
            val invalidMaps = listOf<(DataOutputStream) -> Unit>(
                { it.writeInt(-1) }, { it.writeInt(8) },
                { it.writeInt(1); string(it, "unknown"); it.writeInt(0) },
                { it.writeInt(1); string(it, "tags"); it.writeInt(-1) },
                { it.writeInt(1); string(it, "tags"); it.writeInt(4097) },
                { it.writeInt(1); string(it, "tags"); it.writeInt(1); string(it, " ") },
                { it.writeInt(2); repeat(2) { _ -> string(it, "tags"); it.writeInt(0) } },
                { it.writeInt(1); string(it, "tags"); it.writeInt(2); repeat(2) { _ -> string(it, "x") } },
                { it.writeInt(1); it.writeInt(-1) },
                { it.writeInt(1); it.writeInt(65_537) },
                { it.writeInt(1); it.writeInt(Int.MAX_VALUE) },
                { it.writeInt(1); it.writeInt(1); it.writeByte(0xff); it.writeInt(0) },
                { it.writeInt(0); it.writeByte(0) })
            invalidMaps.forEachIndexed { index, map ->
                val bytes = envelope { string(it, "book.epub"); map(it) }
                check(LibraryFacetPayload.decode(bytes) == null) { "accepted malformed map $index" }
            }
            val invalidStrings = listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
            invalidStrings.forEach { text ->
                check(LibraryFacetPayload.decode(envelope {
                    it.writeInt(text.size); it.write(text); it.writeInt(0)
                }) == null)
            }
            for (size in listOf(-1, 65_537, Int.MAX_VALUE)) {
                check(LibraryFacetPayload.decode(envelope { it.writeInt(size); it.writeInt(0) }) == null)
            }
            check(LibraryFacetPayload.decode(envelope(magic = 0) { string(it, "p"); it.writeInt(0) }) == null)
            check(LibraryFacetPayload.decode(envelope(version = 2) { string(it, "p"); it.writeInt(0) }) == null)
            val oversized = envelope {
                string(it, "book"); it.writeInt(1); string(it, "tags"); it.writeInt(8)
                repeat(8) { index -> string(it, "x".repeat(65_535) + index) }
            }
            check(oversized.size > 524_288)
            check(LibraryFacetPayload.decode(oversized) == null)
        }
        test("index entry exposes exact API and checksum frames every identity field") {
            val record = LibraryFacetRecord("content://library/book", mapOf("tags" to setOf("A, B")))
            val entry = LibraryFacetIndexEntry("book\u0000📚", "file:v1", record)
            check(entry.bookSignature == "book\u0000📚" && entry.fileSignature == "file:v1" && entry.record == record)
            val payload = LibraryFacetPayload.encode(record)
            val actual = LibraryFacetPayload.rowChecksum(record.path, entry.bookSignature, entry.fileSignature, payload)
            val framing = ByteArrayOutputStream()
            DataOutputStream(framing).use {
                it.writeInt(0x4c464952); it.writeInt(1) // LFIR; row checksum framing version
                string(it, record.path); string(it, entry.bookSignature); string(it, entry.fileSignature)
                it.writeInt(payload.size); it.write(payload)
            }
            check(actual.contentEquals(MessageDigest.getInstance("SHA-256").digest(framing.toByteArray())))
            val alternatives = listOf(
                LibraryFacetPayload.rowChecksum(record.path + "!", entry.bookSignature, entry.fileSignature, payload),
                LibraryFacetPayload.rowChecksum(record.path, entry.bookSignature + "!", entry.fileSignature, payload),
                LibraryFacetPayload.rowChecksum(record.path, entry.bookSignature, entry.fileSignature + "!", payload),
                LibraryFacetPayload.rowChecksum(record.path, entry.bookSignature, entry.fileSignature, payload + byteArrayOf(1)))
            alternatives.forEach { check(!actual.contentEquals(it)) }
            check(!LibraryFacetPayload.rowChecksum("p", "ab", "c", payload).contentEquals(
                LibraryFacetPayload.rowChecksum("p", "a", "bc", payload)))
            check(!LibraryFacetPayload.rowChecksum("ab", "c", "d", payload).contentEquals(
                LibraryFacetPayload.rowChecksum("a", "bc", "d", payload)))
            for (signature in listOf("x".repeat(65_537), "é".repeat(32_769), "\ud800")) {
                expectIOException { LibraryFacetPayload.rowChecksum("p", signature, "f", payload) }
                expectIOException { LibraryFacetPayload.rowChecksum("p", "b", signature, payload) }
            }
            expectIOException { LibraryFacetPayload.rowChecksum("p", "b", "f", ByteArray(524_289)) }
        }
        test("row validation skips signature path checksum and payload corruption") {
            val record = LibraryFacetRecord("book.epub", mapOf("authors" to setOf("Jane")))
            val bytes = LibraryFacetPayload.encode(record)
            val checksum = LibraryFacetPayload.rowChecksum(record.path, "b", "f", bytes)
            check(LibraryFacetPayload.decodeEntry(record.path, "b", "f", bytes, checksum) ==
                LibraryFacetIndexEntry("b", "f", record))
            check(LibraryFacetPayload.decodeEntry("other.epub", "b", "f", bytes, checksum) == null)
            check(LibraryFacetPayload.decodeEntry(record.path, "new", "f", bytes, checksum) == null)
            check(LibraryFacetPayload.decodeEntry(record.path, "b", "new", bytes, checksum) == null)
            check(LibraryFacetPayload.decodeEntry(record.path, "b", "f", bytes + byteArrayOf(1), checksum) == null)
            for (badChecksum in listOf(byteArrayOf(), ByteArray(31), ByteArray(33), checksum.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            })) check(LibraryFacetPayload.decodeEntry(record.path, "b", "f", bytes, badChecksum) == null)
            val wrongPathDigest = LibraryFacetPayload.rowChecksum("other.epub", "b", "f", bytes)
            check(LibraryFacetPayload.decodeEntry("other.epub", "b", "f", bytes, wrongPathDigest) == null)
            val malformed = envelope { string(it, record.path); it.writeInt(-1) }
            check(LibraryFacetPayload.decodeEntry(record.path, "b", "f", malformed,
                LibraryFacetPayload.rowChecksum(record.path, "b", "f", malformed)) == null)
            check(LibraryFacetPayload.decodeEntry("p".repeat(65_537), "b", "f", bytes, checksum) == null)
            check(LibraryFacetPayload.decodeEntry(record.path, "\ud800", "f", bytes, checksum) == null)
        }
        println("RESULT: payload_passed=$passed")
    }

    private fun envelope(magic: Int = 0x4c464950, version: Int = 1, body: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { it.writeInt(magic); it.writeInt(version); body(it) }
        val bytes = buffer.toByteArray()
        return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun string(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun expectIOException(body: () -> Unit) {
        try { body() } catch (_: IOException) { return }
        error("expected IOException")
    }

    private fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS: $name")
    }
}
