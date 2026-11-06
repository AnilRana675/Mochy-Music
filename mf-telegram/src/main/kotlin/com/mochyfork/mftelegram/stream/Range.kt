// http range header parsing for the loopback stream server. exoplayer always asks one file,
// but seek timing means every form below gets hit in practice
package com.mochyfork.mftelegram.stream

object ByteRange {
    sealed interface Parsed {
        /** whole file wanted */
        data object Full : Parsed

        /** inclusive [start, last] */
        data class Partial(val start: Long, val last: Long) : Parsed

        /** range header present but nonsense for this size -> 416 */
        data object NotSatisfiable : Parsed

        /** malformed header -> we just serve the full file (exoplayer would rarely do this) */
        data object Bad : Parsed
    }

    fun parse(header: String?, size: Long): Parsed {
        if (header.isNullOrBlank()) return Parsed.Full
        val value = header.trim()
        if (!value.startsWith("bytes=")) return Parsed.Bad
        val spec = value.removePrefix("bytes=").trim()
        if (spec.length > 64 && spec.contains(',')) return Parsed.Bad // multi-range never handled
        val dash = spec.indexOf('-')
        if (dash < 0) return Parsed.Bad
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (a.isEmpty() && b.isEmpty()) return Parsed.Bad
        if (a.isEmpty()) {
            // suffix range: last n bytes ("bytes=-500")
            val n = b.toLongOrNull() ?: return Parsed.Bad
            if (n <= 0) return Parsed.NotSatisfiable
            // suffix longer than the file => whole file, legal per rfc 7233
            val start = (size - n).coerceAtLeast(0)
            if (start >= size) return Parsed.NotSatisfiable
            return Parsed.Partial(start, size - 1)
        }
        val start = a.toLongOrNull() ?: return Parsed.Bad
        if (start < 0) return Parsed.Bad
        if (start >= size) return Parsed.NotSatisfiable
        val last = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return Parsed.Bad)
        // start > last (or b < start) makes the range syntactically unsatisfiable, not the file
        if (last < start) return Parsed.NotSatisfiable
        return Parsed.Partial(start, last.coerceAtMost(size - 1))
    }
}
