// fakes shared by the engine tests: a scripted td port and an in-memory dao
package com.mochyfork.mftelegram

import com.mochyfork.mftelegram.db.ChannelEntity
import com.mochyfork.mftelegram.db.MatchEntity
import com.mochyfork.mftelegram.db.TelegramDao
import com.mochyfork.mftelegram.db.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/** td port answered from a script: handler per request, manual update emission */
class FakeTdPort : TdPort {
    override val updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    val sent = mutableListOf<TdApi.Function<*>>()
    // most specific constructor printer wins, or the first registered one
    val handlers = mutableListOf<(TdApi.Function<*>) -> TdApi.Object?>()

    inline fun <reified T : TdApi.Function<*>> on(crossinline answer: (T) -> TdApi.Object) {
        handlers += { f -> if (f is T) answer(f) else null }
    }

    fun on(clazz: Class<out TdApi.Function<*>>, answer: (TdApi.Function<*>) -> TdApi.Object) {
        handlers += { f -> if (clazz.isInstance(f)) answer(f) else null }
    }

    fun on(constructor: Int, answer: (TdApi.Function<*>) -> TdApi.Object) {
        handlers += { f -> if (f.getConstructor() == constructor) answer(f) else null }
    }

    override suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): TdApi.Object {
        sent += function
        for (handler in handlers) {
            val res = runCatching { handler(function) }.getOrNull()
            if (res != null) return res
        }
        throw TdError(400, "unexpected ${function::class.simpleName}")
    }

    suspend fun emit(update: TdApi.Update) {
        updates.emit(update)
    }
}

/** in-memory dao over plain lists, same shapes as the room one */
open class FakeDao : TelegramDao {
    val channelRows = LinkedHashMap<Long, ChannelEntity>()
    val trackRows = LinkedHashMap<String, TrackEntity>()
    val matchRows = LinkedHashMap<String, MatchEntity>()

    override suspend fun upsertChannel(channel: ChannelEntity) {
        channelRows[channel.chatId] = channel
    }

    override suspend fun channels(): List<ChannelEntity> = channelRows.values.toList()

    override fun activeChannels(): Flow<List<ChannelEntity>> =
        MutableStateFlow(channelRows.values.filter { it.active }).asStateFlow()

    override suspend fun channel(chatId: Long): ChannelEntity? = channelRows[chatId]

    override suspend fun deactivate(chatId: Long) {
        channelRows[chatId]?.let { channelRows[chatId] = it.copy(active = false) }
    }

    override suspend fun forgetChannel(chatId: Long) {
        channelRows.remove(chatId)
    }

    override suspend fun upsertTrack(track: TrackEntity) {
        trackRows[track.uniqueId] = track
    }

    override suspend fun insertTracks(tracks: List<TrackEntity>) {
        tracks.forEach { trackRows.putIfAbsent(it.uniqueId, it) }
    }

    override suspend fun deleteTrack(chatId: Long, messageId: Long) {
        trackRows.values.removeIf { it.chatId == chatId && it.messageId == messageId }
    }

    override suspend fun deleteTracksOf(chatId: Long) {
        trackRows.values.removeIf { it.chatId == chatId }
    }

    override suspend fun trackCount(chatId: Long): Int =
        trackRows.values.count { it.chatId == chatId }

    override suspend fun track(uniqueId: String): TrackEntity? = trackRows[uniqueId]

    override suspend fun indexedTracks(): List<TrackEntity> =
        trackRows.values.filter { t -> channelRows[t.chatId]?.active == true }

    override suspend fun upsertMatch(match: MatchEntity) {
        matchRows[match.token] = match
    }

    override suspend fun match(token: String): MatchEntity? = matchRows[token]

    override fun matchFlow(token: String): Flow<MatchEntity?> =
        MutableStateFlow(matchRows[token]).asStateFlow()

    override suspend fun dropMatchesFor(uniqueId: String) {
        matchRows.values.removeIf { it.uniqueId == uniqueId }
    }

    override suspend fun noteFailure(token: String, until: Long) {
        matchRows.compute(token) { _, m -> m?.copy(failures = m.failures + 1, backoffUntil = until) }
    }

    override suspend fun clearFailures(token: String) {
        matchRows.compute(token) { _, m -> m?.copy(failures = 0, backoffUntil = 0) }
    }
}
