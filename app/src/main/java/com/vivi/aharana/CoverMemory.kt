package com.vivi.aharana

import java.io.InputStream
import java.util.LinkedHashMap

/** Access-ordered, byte-weighted cache. Eviction releases references, never recycles visible bitmaps. */
internal class CoverMemoryCache<K, V>(private val maxBytes: Long, private val weight: (V) -> Long) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    var weightBytes: Long = 0
        private set

    @Synchronized fun get(key: K): V? = values[key]

    @Synchronized fun put(key: K, value: V) {
        val bytes = weight(value)
        require(bytes >= 0)
        values.remove(key)?.let { weightBytes -= weight(it) }
        if (bytes > maxBytes) return
        values[key] = value
        weightBytes += bytes
        val iterator = values.entries.iterator()
        while (weightBytes > maxBytes && iterator.hasNext()) {
            weightBytes -= weight(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized fun clear() {
        values.clear()
        weightBytes = 0
    }
}

internal object CoverDecodePolicy {
    /** Cap BOTH dimensions, including panoramas, without allocating the full bitmap first. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        require(maxEdge > 0)
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > maxEdge ||
            (height.toLong() + sample - 1) / sample > maxEdge) sample *= 2
        return sample
    }
}

/** Stream the base64 suffix directly; no substring/UTF-8/decoded-byte-array copies. */
internal class AsciiSliceInputStream(private val value: CharSequence, start: Int) : InputStream() {
    private var position = start
    override fun read(): Int = if (position >= value.length) -1 else value[position++].code and 0xff

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (position >= value.length) return -1
        val count = minOf(length, value.length - position)
        for (i in 0 until count) buffer[offset + i] = value[position++].code.toByte()
        return count
    }
}
