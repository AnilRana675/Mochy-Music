// the real FileSource over tdlib: resolve the fresh file id via the message (ids churn on
// restart), download chunk-aligned (1 mib, tdlib's own cap) and copy bytes into our chunk
// store. device-only: server and tests use fakes; the exact layout assumptions get verified
// on a real phone in phase 3 before this serves its first swap
// ponytail: copy-from-tdlib-local-file kept simple; costs one read+write per chunk, drop when
// proven safe to serve tdlib's own local path directly
package com.mochyfork.mftelegram

import com.mochyfork.mftelegram.db.TelegramDao
import com.mochyfork.mftelegram.db.TrackEntity
import com.mochyfork.mftelegram.stream.ChunkStore
import com.mochyfork.mftelegram.stream.FileChunks
import com.mochyfork.mftelegram.stream.FileSource
import com.mochyfork.mftelegram.stream.FileSource.Meta
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

class TdFileSource(
    private val port: TdPort,
    private val dao: TelegramDao,
    private val chunks: ChunkStore,
    private val syncState: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.Idle),
) : FileSource {

    private val permits = Semaphore(MaxParallelDownloads)
    private val active = ConcurrentHashMap<String, Int>()

    override suspend fun meta(uniqueId: String): Meta? {
        val track = dao.track(uniqueId) ?: return null
        // a left/deactivated channel is no source at all (playback stays on youtube)
        if (track.sizeBytes <= 0) return null
        if (dao.channel(track.chatId)?.active != true) return null
        return Meta(track.mime, track.sizeBytes)
    }

    override suspend fun download(uniqueId: String, from: Long, untilExclusive: Long): Boolean {
        val track = dao.track(uniqueId) ?: return false
        if (dao.channel(track.chatId)?.active != true) return false
        return try {
            permits.withPermit {
                // fresh fileId every time; the stored one is only a restart hint
                val fileId = freshFileId(track) ?: return false
                val store = chunks.obtain(uniqueId)
                active[uniqueId] = fileId
                val cs = chunks.chunkSize.toLong()
                var index = from / cs
                while (index * cs < untilExclusive) {
                    if (!store.covers(index * cs, (index + 1) * cs)) {
                        pullChunk(fileId, index, track, store)
                    }
                    index++
                }
                true
            }
        } catch (e: TdError) {
            // flood wait and friends: stop early, the swap falls back to youtube
            if (e.retryAfter > 0) syncState.value = SyncState.Error("rate limited ${e.retryAfter}s")
            false
        } finally {
            active.remove(uniqueId)
        }
    }

    /** one chunk = one tdlib downloadFile call at the chunk's own offset; nothing ahead */
    private suspend fun pullChunk(fileId: Int, index: Long, track: TrackEntity, store: FileChunks) {
        val cs = chunks.chunkSize
        val offset = index * cs
        try {
            port.send(TdApi.DownloadFile(fileId, DownloadPriority, offset, cs.toLong(), false))
            val file = awaitChunk(fileId, offset, cs)
            if (file == null) throw TdError(504, "chunk did not arrive")
            copyChunk(file.local.path, index, store)
        } finally {
            // stop tdlib from steamrolling past the chunk; only if it is still pending
            runCatching { port.send(TdApi.CancelDownloadFile(fileId, true)) }
        }
    }

    private suspend fun awaitChunk(fileId: Int, offset: Long, limit: Int): TdApi.File? =
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (waited < ChunkTimeoutMs) {
                kotlinx.coroutines.delay(PollMs)
                waited += PollMs
                val file = port.send(TdApi.GetFile(fileId)) as? TdApi.File ?: continue
                if (chunkReady(file, offset, limit)) return@withContext file
            }
            null
        }

    // local_file docs: download_offset is where downloading started, downloaded_prefix_size is
    // the ready prefix counted from that same offset, so chunk ok = prefix >= limit from our
    // offset (or the file completed); tdlib keeps one download at a time per file id
    private fun chunkReady(file: TdApi.File, offset: Long, limit: Int) =
        file.local.isDownloadingCompleted || file.local.downloadOffset >= offset &&
            file.local.downloadedPrefixSize >= limit

    private suspend fun copyChunk(path: String, index: Long, store: FileChunks) =
        withContext(Dispatchers.IO) {
            val cs = chunks.chunkSize
            val buf = ByteArray(cs)
            val rf = RandomAccessFile(File(path), "r")
            val read = try {
                rf.seek(index * cs)
                rf.read(buf).coerceAtLeast(0)
            } finally {
                rf.close()
            }
            if (read > 0) store.writeChunk(index.toInt(), buf.copyOf(read))
        }

    override fun cancel(uniqueId: String) {
        val fileId = active.remove(uniqueId) ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { port.send(TdApi.CancelDownloadFile(fileId, false)) }
        }
    }

    /** re-fetch the message for a fresh file id; cheap local, no history rewriting (1.4) */
    private suspend fun freshFileId(track: TrackEntity): Int? {
        val message = port.send(TdApi.GetMessage(track.chatId, track.messageId)) as? TdApi.Message
            ?: return null
        return (message.content as? TdApi.MessageAudio)?.audio?.audio?.id?.takeIf { it > 0 }
    }

    companion object {
        // low parallelism keeps request traffic genteel (clause 4)
        const val MaxParallelDownloads = 3
        const val DownloadPriority = 2
        const val PollMs = 500L
        const val ChunkTimeoutMs = 45_000L
    }
}
