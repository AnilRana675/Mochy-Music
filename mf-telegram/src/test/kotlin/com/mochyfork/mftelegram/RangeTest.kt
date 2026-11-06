// range parsing cases; run this first, the server leans on it entirely
package com.mochyfork.mftelegram.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeTest {
    private val size = 1000L

    private fun parse(header: String?) = ByteRange.parse(header, size)

    @Test fun noHeaderIsFull() {
        assertEquals(ByteRange.Parsed.Full, parse(null))
        assertEquals(ByteRange.Parsed.Full, parse(""))
    }

    @Test fun openEndedRangeGoesToEof() {
        assertEquals(ByteRange.Parsed.Partial(0, 999), parse("bytes=0-"))
        assertEquals(ByteRange.Parsed.Partial(100, 999), parse("bytes=100-"))
    }

    @Test fun closedRangeIsInclusive() {
        assertEquals(ByteRange.Parsed.Partial(0, 9), parse("bytes=0-9"))
        assertEquals(ByteRange.Parsed.Partial(500, 999), parse("bytes=500-999"))
    }

    @Test fun suffixRangeCountsFromTheEnd() {
        assertEquals(ByteRange.Parsed.Partial(995, 999), parse("bytes=-5"))
        assertEquals(ByteRange.Parsed.Partial(0, 999), parse("bytes=-1000"))
    }

    @Test fun suffixLongerThanFileIsTheWholeFile() {
        assertEquals(ByteRange.Parsed.Partial(0, 999), parse("bytes=-2000"))
    }

    @Test fun endBeyondEofIsClamped() {
        assertEquals(ByteRange.Parsed.Partial(900, 999), parse("bytes=900-5000"))
    }

    @Test fun startAtEofIsNotSatisfiable() {
        assertEquals(ByteRange.Parsed.NotSatisfiable, parse("bytes=1000-"))
        assertEquals(ByteRange.Parsed.NotSatisfiable, parse("bytes=2000-3000"))
    }

    @Test fun emptySuffixAndJunkIsBad() {
        assertEquals(ByteRange.Parsed.Bad, parse("bytes=-"))
        assertEquals(ByteRange.Parsed.Bad, parse("bytes=abc-def"))
    }

    @Test fun nonByteUnitsAreBad() {
        assertEquals(ByteRange.Parsed.Bad, parse("items=0-9"))
    }
}
