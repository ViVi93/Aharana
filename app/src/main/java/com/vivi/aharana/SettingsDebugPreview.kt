package com.vivi.aharana

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/** Settings-only preview. Byte budget applies across ALL rotations, before decoding. */
object SettingsDebugPreview {
    const val MAX_LINES = 30
    const val MAX_BYTES = 32 * 1024
    const val MAX_LINE_CHARS = 1024
    data class Preview(val text: String, val bytesRead: Int, val truncated: Boolean)

    /** Sources are newest first; unlike export, never reads the full retained log. */
    fun read(newestFirst: List<File>): Preview {
        val reversed = mutableListOf<String>()
        var bytesRead = 0
        var clipped = false
        for (file in newestFirst) {
            if (reversed.size >= MAX_LINES || bytesRead >= MAX_BYTES) break
            if (!file.exists()) continue
            try {
                RandomAccessFile(file, "r").use { input ->
                    val length = input.length()
                    val count = minOf(length, (MAX_BYTES - bytesRead).toLong()).toInt()
                    val offset = length - count
                    input.seek(offset)
                    val bytes = ByteArray(count)
                    // read() tolerates concurrent rotation/truncation; no retry or unbounded scan.
                    var actual = 0
                    while (actual < count) {
                        val read = input.read(bytes, actual, count - actual)
                        if (read <= 0) break
                        actual += read
                    }
                    bytesRead += actual
                    clipped = clipped || offset > 0
                    var end = actual
                    if (end > 0 && bytes[end - 1] == '\n'.code.toByte()) end--
                    while (end > 0 && reversed.size < MAX_LINES) {
                        var start = end - 1
                        while (start >= 0 && bytes[start] != '\n'.code.toByte()) start--
                        // A cut-off prefix may contain a credential label. Redacting
                        // only its suffix can expose the value, so omit that partial entry.
                        val partial = start < 0 && offset > 0
                        var line = if (partial) "[Partial oversized log entry omitted]" else
                            String(bytes, start + 1, end - start - 1, Charsets.UTF_8).trimEnd('\r')
                        line = DiagnosticFiles.sanitize(line)
                        if (line.length > MAX_LINE_CHARS) {
                            line = "…" + line.takeLast(MAX_LINE_CHARS - 1)
                            clipped = true
                        }
                        reversed.add(line)
                        end = start
                    }
                    if (end > 0) clipped = true
                }
            } catch (e: FileNotFoundException) {
                // Rotation may remove a source after exists(); genuine access errors stay visible.
                if (file.exists()) throw e
            }
        }
        return Preview(reversed.asReversed().joinToString("\n"), bytesRead, clipped)
    }
}
