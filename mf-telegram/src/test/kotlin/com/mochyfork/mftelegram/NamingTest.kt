// artist/title extraction from file names and captions
package com.mochyfork.mftelegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    private fun name(s: String): Naming.Parsed = Naming.parseName(s)
    private fun both(s: String): Naming.Parsed.Both = name(s) as Naming.Parsed.Both

    @Test fun artistDashTitle() {
        assertEquals("Artist", both("Artist - Title.mp3").artist)
        assertEquals("Title", both("Artist - Title.mp3").title)
    }

    @Test fun dashVariantsSplit() {
        assertEquals("A", both("A – B.m4a").artist)
        assertEquals("B", both("A – B.m4a").title)
    }

    @Test fun extrasAfterKeepTitleWhole() {
        assertEquals("Artist", both("Artist - Title (2020 remaster).mp3").artist)
        assertEquals("Title (2020 remaster)", both("Artist - Title (2020 remaster).mp3").title)
    }

    @Test fun trackNumbersDrop() {
        assertEquals("A", both("01 - A - B.flac").artist)
        assertEquals("B", both("01 - A - B.flac").title)
    }

    @Test fun noArtistIsTitleOnly() {
        assertTrue(name("SoloSong.mp3") is Naming.Parsed.TitleOnly)
    }

    @Test fun urlNoiseDrops() {
        assertEquals("B", both("A - B [www.freemp3z.example].mp3").title)
        assertEquals("A", both("A - B [www.freemp3z.example].mp3").artist)
    }

    @Test fun captionFollowsTheSameRulesWithoutExtensionStripping() {
        assertEquals("X", (Naming.parseCaption("X - Y") as Naming.Parsed.Both).artist)
        // a caption with a dot keeps its tail: no extension strip on captions
        assertEquals("Song 3.31", (Naming.parseCaption("A - Song 3.31") as Naming.Parsed.Both).title)
    }

    @Test fun junkIsEmpty() {
        assertEquals(Naming.Parsed.Nothing, name(""))
        assertEquals(Naming.Parsed.Nothing, name("   "))
    }
}
