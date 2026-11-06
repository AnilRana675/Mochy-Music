// the engine the app talks to. wires tdlib, the index, the matcher and the loopback stream
// server together behind one facade. phase 3 adds the SourceModule adapter; phase 4+ uses
// streamUrl/preload for the hybrid swap
package com.mochyfork.mftelegram

import android.content.Context
import androidx.room.Room
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyfork.mftelegram.db.ChannelEntity
import com.mochyfork.mftelegram.db.MatchEntity
import com.mochyfork.mftelegram.db.TelegramDb
import com.mochyfork.mftelegram.db.TrackEntity
import com.mochyfork.mftelegram.stream.ChunkStore
import com.mochyfork.mftelegram.stream.StreamServer
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

/** keys come from local.properties via buildconfig; blank ones mean the feature is off */
data class EngineConfig(val apiId: Int, val apiHash: String) {
    val disabled: Boolean get() = apiId == 0 || apiHash.isBlank()
}

class TelegramEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val db by lazy {
        Room.databaseBuilder(context, TelegramDb::class.java, DbName)
            .fallbackToDestructiveMigration()
            .build()
    }
    private var port: TdPort? = null
    private var authMachineRef: AuthMachine? = null
    private var indexerRef: ChannelIndexer? = null
    private var streamServerRef: StreamServer? = null
    private var chunksRef: ChunkStore? = null
    private var fileSourceRef: TdFileSource? = null
    private val started = AtomicBoolean(false)
    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Starting)
    private val authErrorFlow = MutableStateFlow<String?>(null)

    val auth: StateFlow<AuthState> = authStateFlow
    val authError: StateFlow<String?> = authErrorFlow
    val channels: Flow<List<ChannelEntity>> get() = db.dao().activeChannels()
    val syncState = MutableStateFlow<SyncState>(SyncState.Idle)

    /** start the engine; false = keys missing, the feature stays off and nothing is built */
    fun start(config: EngineConfig = defaultConfig()): Boolean {
        if (config.disabled) return false
        if (!started.compareAndSet(false, true)) return true
        dir().mkdirs()
        val chunks = ChunkStore(dir().resolve("cache"))
        chunksRef = chunks
        port = TdRealPort()
        val tdSource = TdFileSource(port!!, db.dao(), chunks, syncState)
        fileSourceRef = tdSource
        streamServerRef = StreamServer(chunks, tdSource).also { it.start() }
        authMachineRef = AuthMachine(port!!, scope).also { machine ->
            machine.start()
            scope.launch { machine.state.collect { authStateFlow.value = it } }
            scope.launch { machine.lastError.collect { authErrorFlow.value = it } }
        }
        indexerRef = ChannelIndexer(port!!, db.dao(), scope, syncState).also { it.start() }
        scope.launch(Dispatchers.IO) { initTdlib(config) }
        return true
    }

    private fun dir(): File = context.filesDir.resolve(RootDir)

    // tdlib init: parameters once, with an encryption key for its own database (clause 1.1)
    private suspend fun initTdlib(config: EngineConfig) {
        val td = dir().resolve("td")
        td.mkdirs()
        try {
            port!!.send(
                TdApi.SetTdlibParameters(
                    false,
                    td.absolutePath,
                    dir().resolve("files").absolutePath,
                    loadOrCreateKey(dir().resolve("key")),
                    true,
                    true,
                    true,
                    false,
                    config.apiId,
                    config.apiHash,
                    "en",
                    "Mochy fork",
                    "Android",
                    "1.0",
                ),
            )
        } catch (e: TdError) {
            syncState.value = SyncState.Error(if (e.retryAfter > 0) "rate limited ${e.retryAfter}s" else e.message)
        }
    }

    // the key file is app-private storage, no ui ever sees it; unchanged until logout wipes it
    private fun loadOrCreateKey(file: File): ByteArray {
        if (file.exists()) return file.readBytes()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(key)
        return key
    }

    // auth passthrough

    /** suspend until tdlib reports ready (login done) or the timeout hits */
    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean {
        val machine = authMachineRef ?: return false
        return try {
            withTimeout(timeoutMs) { machine.state.first { it == AuthState.Ready } }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    suspend fun login(action: AuthInput): Boolean = authMachineRef?.input(action) ?: false

    /** logs out the account and wipes tdlib storage; the room index survives for re-login */
    suspend fun logout() {
        authMachineRef?.logout()
        port = null
        authMachineRef = null
        authStateFlow.value = AuthState.Closed
        authErrorFlow.value = null
        indexerRef = null
        streamServerRef?.stop(); streamServerRef = null
        chunksRef?.wipeAll()
        dir().resolve("td").deleteRecursively()
        dir().resolve("files").deleteRecursively()
        dir().resolve("key").delete()
        started.set(false)
    }

    fun stop() {
        streamServerRef?.stop()
    }

    fun isAuthenticated(): Boolean = authStateFlow.value == AuthState.Ready

    /** engine up: keys present, room and stream server running */
    fun isStarted(): Boolean = started.get() && streamServerRef != null

    // channel index passthrough

    suspend fun channelCandidates(limit: Int = 200): List<Channel> =
        indexerRef?.listCandidates(limit)?.map { Channel(it.id, it.title) } ?: emptyList()

    suspend fun addChannel(chatId: Long): ChannelEntity? = indexerRef?.add(chatId)

    /** joining a channel is a visible account action; the ui confirms before calling this */
    suspend fun joinByInviteLink(inviteLink: String): ChannelEntity? = indexerRef?.joinByInviteLink(inviteLink)

    suspend fun channelsList(): List<ChannelEntity> = db.dao().channels()

    suspend fun resync(chatId: Long): SyncState {
        val state = indexerRef?.scan(chatId) ?: SyncState.Error("engine not started")
        syncState.value = state
        return state
    }

    suspend fun setChannelActive(chatId: Long, active: Boolean) {
        if (active) {
            val ch = db.dao().channel(chatId) ?: return
            db.dao().upsertChannel(ch.copy(active = true))
        } else {
            db.dao().deactivate(chatId)
        }
    }

    // matching (local index only, never network)

    /** match or die. writes/updates the stored match token -> track for instant next plays */
    suspend fun match(token: String, query: TrackQuery): MatchResult? {
        val dao = db.dao()
        val stored = dao.match(token)
        val best = TelegramMatch.best(query, dao.indexedTracks()) ?: return null
        if (stored == null || stored.uniqueId != best.track.uniqueId || best.score > stored.confidence) {
            dao.upsertMatch(
                MatchEntity(
                    token = token,
                    uniqueId = best.track.uniqueId,
                    chatId = best.track.chatId,
                    confirmedAt = System.currentTimeMillis(),
                    confidence = best.score,
                ),
            )
        }
        return MatchResult(best.track, best.score)
    }

    suspend fun storedMatch(token: String): MatchEntity? = db.dao().match(token)

    suspend fun track(uniqueId: String): TrackEntity? = db.dao().track(uniqueId)

    class MatchResult(val track: TrackEntity, val confidence: Float)

    data class Channel(val id: Long, val title: String)

    // streaming

    /** loopback url serving a telegram file; null when the engine is down */
    fun streamUrl(uniqueId: String): String? = streamServerRef?.url(uniqueId)

    /** cheap local check: is [from, untilExclusive) fully on disk? no network */
    suspend fun hasBytes(uniqueId: String, from: Long, untilExclusive: Long): Boolean =
        chunksRef?.obtain(uniqueId)?.covers(from, untilExclusive) ?: false

    /** pull a byte range into our cache; prefetch and swap readiness use this */
    suspend fun preload(uniqueId: String, from: Long, untilExclusive: Long): Boolean =
        fileSourceRef?.download(uniqueId, from, untilExclusive) ?: false

    /** per-song failure bookkeeping for the backoff table */
    suspend fun noteMatchFailure(token: String, backoffSeconds: Long = DefaultBackoffSeconds) =
        db.dao().noteFailure(token, System.currentTimeMillis() + backoffSeconds * 1000)

    suspend fun noteMatchSuccess(token: String) = db.dao().clearFailures(token)

    companion object {
        const val RootDir = "mftelegram"
        const val DbName = "mftelegram"
        const val DefaultBackoffSeconds = 15L * 60

        fun defaultConfig(): EngineConfig =
            EngineConfig(BuildConfig.MF_TELEGRAM_API_ID, BuildConfig.MF_TELEGRAM_API_HASH)
    }
}
