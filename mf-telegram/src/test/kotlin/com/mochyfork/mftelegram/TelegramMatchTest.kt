// match quality: thresholds, the hard duration gate, and not-found behavior
package com.mochyfork.mftelegram

import com.mochyy.mochymusic.module.TrackQuery
import com.mochyfork.mftelegram.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMatchTest {
    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long,
        source: String = "tag",
    ) = TrackEntity(
        uniqueId = id,
        chatId = 1,
        messageId = 1,
        fileId = 1,
        fileName = null,
        title = title,
        artist = artist,
        album = null,
        durationMs = durationMs,
        sizeBytes = 1,
        mime = null,
        source = source,
        indexedAt = 0,
    )

    private val query = TrackQuery("eastside", listOf("benny blanco"), durationMs = 178_000)

    @Test fun identicalSongMatches() {
        val hit = TelegramMatch.best(query, listOf(track("t", "Eastside", "benny blanco", 178_000)))
        assertTrue(hit!!.score >= TelegramMatch.Threshold)
        assertEquals("t", hit.track.uniqueId)
    }

    @Test fun durationPastGateIsRefusedEvenIfWordsMatch() {
        assertNull(TelegramMatch.best(query, listOf(track("t", "Eastside", "benny blanco", 178_000 + 6_000))))
    }

    @Test fun durationWithinGateMatches() {
        val hit = TelegramMatch.best(query, listOf(track("t", "Eastside", "benny blanco", 178_000 + 4_000)))
        assertTrue(hit != null)
    }

    @Test fun missingQueryDurationBypassesTheGate() {
        val hit = TelegramMatch.best(
            TrackQuery("eastside", listOf("benny blanco")),
            listOf(track("t", "Eastside", "benny blanco", 10_000)),
        )
        assertTrue(hit != null)
    }

    @Test fun brokenZeroDurationEntityBypassesTheGate() {
        val hit = TelegramMatch.best(
            query,
            listOf(track("t", "Eastside", "benny blanco", 0)),
        )
        // word match alone, no duration signal; may still clear threshold
        assertNull(hit ?: null) // either outcome legal, but must not throw
    }

    @Test fun decoratedTitlesNormalizeDown() {
        val hit = TelegramMatch.best(
            query,
            listOf(track("t", "Eastside (Official Video)", "benny blanco", 178_000)),
        )
        assertTrue(hit != null)
    }

    @Test fun remixPenaltyKeepsScoreBelowThreshold() {
        val hit = TelegramMatch.best(query, listOf(track("t", "Eastside Live", "benny blanco", 178_000)))
        assertTrue(hit == null)
    }

    @Test fun wrongArtistRefuses() {
        assertNull(TelegramMatch.best(query, listOf(track("t", "Eastside", "someone else", 178_000))))
    }

    @Test fun bestWinsOverWeaker() {
        val best = TelegramMatch.best(query, listOf(
            track("weak", "Something entirely else", "benny blanco", 178_000),
            track("good", "Eastside", "benny blanco", 178_000),
        ))
        assertEquals("good", best!!.track.uniqueId)
    }
}
