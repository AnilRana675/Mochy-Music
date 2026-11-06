// match a playing song (title/artists/duration) against the channel index, reusing the
// upstream TrackMatcher rules so every source matches alike. local index only, no network
package com.mochyfork.mftelegram

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyfork.mftelegram.db.TrackEntity
import kotlin.math.abs
import kotlin.math.abs as kabs

object TelegramMatch {
    // below this the auto-match is refused; never return a wrong song with high confidence
    const val Threshold = 0.7f

    // hard duration gate: beyond 5s it is not the same recording even if words agree. skipped
    // only when the query has no duration or the entity has broken (0) metadata
    const val DurationGateMs = 5_000L

    class Scored(val track: TrackEntity, val score: Float)

    fun ranked(query: TrackQuery, tracks: List<TrackEntity>): List<Scored> = tracks
        .filter { passesGate(query, it) }
        .map { Scored(it, TrackMatcher.score(query, toCatalog(it))) }
        .filter { it.score >= Threshold }
        .sortedByDescending { it.score }

    /** best candidate or null. null = not found, youtube keeps playing */
    fun best(query: TrackQuery, tracks: List<TrackEntity>): Scored? = ranked(query, tracks).firstOrNull()

    // duration past the gate is an automatic reject no matter the word match
    private fun passesGate(query: TrackQuery, track: TrackEntity): Boolean {
        val want = query.durationMs ?: return true
        val have = track.durationMs
        return abs(want - have) <= DurationGateMs
    }

    private fun toCatalog(track: TrackEntity): CatalogTrack = CatalogTrack(
        id = track.uniqueId,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        isrc = null,
    )
}
