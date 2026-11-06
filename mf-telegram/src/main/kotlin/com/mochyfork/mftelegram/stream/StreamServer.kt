// serves telegram audio over the loopback with full range support. bytes come from the chunk
// store; anything missing is pulled by the downloader at the request's offset only (never the
// whole file), so a seek past the downloaded prefix restarts tdlib at that offset
package com.mochyfork.mftelegram.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.math.min

// constant, not config: tuning knob for the swap gap. covers ~20s of 128kbps audio
const val ReadaheadBytes = 2 * 1024 * 1024
const val MaxWaitPerGapMs = 30_000L
const val PollMs = 50L

/**
 * One source of telegram bytes: metadata lookup plus an at-offset downloader. The tdlib
 * implementation maps download(id, from, until) onto tdapi downloadFile aligned to chunks;
 * tests replace it with fakes. cancellation of a partially pulled range is the implementer's
 * job once [download] throws a CancellationException.
 */
interface FileSource {
    data class Meta(val mimeType: String?, val size: Long)

    suspend fun meta(uniqueId: String): Meta?
    suspend fun download(uniqueId: String, from: Long, untilExclusive: Long): Boolean
    fun cancel(uniqueId: String)
}

class StreamServer(
    private val store: ChunkStore,
    private val source: FileSource,
) {
    // random per-session token; without it any local app could read our cache off the loopback.
    // binding is loopback-only, this just keeps the url also unguessable (clause 1.1)
    private val token = hexToken(24)
    private val server = LoopbackServer(0, backlog = 4, handler = ::serve)
    private val chunkSize = store.chunkSize

    fun start(): Int {
        server.start()
        return server.localPort
    }

    fun stop() = server.stop()

    val port: Int get() = server.localPort

    fun url(uniqueId: String): String = "http://127.0.0.1:$port/s/$token/$uniqueId"

    private fun serve(request: HttpR, out: HttpW) {
        // path shape: /s/<token>/<uniqueId>
        val parts = request.path.split('/')
        val uniqueId = parts.getOrNull(3) ?: return respond404(out)
        if (parts.size != 4 || parts[1] != "s" || parts[2] != token) return respond404(out)
        val meta = runBlocking { source.meta(uniqueId) } ?: return respond404(out)
        val size = meta.size
        val type = meta.mimeType ?: "application/octet-stream"
        val base = listOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to type,
            "Connection" to "keep-alive",
        )
        when (val range = ByteRange.parse(request.header("range"), size)) {
            ByteRange.Parsed.Bad, ByteRange.Parsed.Full -> {
                out.respond(200, "OK", base + ("Content-Length" to size.toString()))
                if (request.method == "HEAD") return
                runStream(out, uniqueId, size, 0, size - 1)
            }
            ByteRange.Parsed.NotSatisfiable -> {
                val headers = listOf("Content-Range" to "bytes */$size", "Content-Length" to "0")
                out.respond(416, "Range Not Satisfiable", headers)
            }
            is ByteRange.Parsed.Partial -> {
                val headers = base + listOf(
                    "Content-Range" to "bytes ${range.start}-${range.last}/$size",
                    "Content-Length" to (range.last - range.start + 1).toString(),
                )
                out.respond(206, "Partial Content", headers)
                if (request.method == "HEAD") return
                runStream(out, uniqueId, size, range.start, range.last)
            }
        }
    }

    private fun runStream(out: HttpW, uniqueId: String, size: Long, start: Long, end: Long) {
        val chunks = store.obtain(uniqueId)
        // streaming runs inline in this one coroutine; a dead socket (player closed) unwindss
        // and takes any in-flight download down with it
        runBlocking {
            var downloadJob: Job? = null
            var gapStartMs = 0L
            try {
                var position = start
                while (position <= end) {
                    val remaining = min(end - position + 1, size - position)
                    val read = chunks.read(position, min(BufferBytes.toLong(), remaining).toInt())
                    if (read != null) {
                        downloadJob?.cancel()
                        downloadJob = null
                        out.writeBody(read)
                        position += read.size
                        continue
                    }
                    if (!out.alive()) {
                        // player closed between reads: no point keeping bytes coming
                        source.cancel(uniqueId)
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (downloadJob == null) {
                        gapStartMs = now
                        val from = (position / chunkSize) * chunkSize
                        val until = readAheadCeil(end, size)
                        downloadJob = launch(Dispatchers.IO) {
                            runCatching { source.download(uniqueId, from, until) }
                        }
                    } else if (now - gapStartMs > MaxWaitPerGapMs) {
                        // dead network or flood wait: give up, exoplayer retries or falls back
                        source.cancel(uniqueId)
                        break
                    }
                    kotlinx.coroutines.delay(PollMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // socket died mid-write, most common close path
                source.cancel(uniqueId)
            } finally {
                downloadJob?.cancel()
            }
        }
    }

    /** the range end plus one constant readahead, snapped up to a chunk edge */
    private fun readAheadCeil(end: Long, size: Long): Long {
        val want = (end + ReadaheadBytes).coerceAtMost(size)
        return ((want - 1) / chunkSize) * chunkSize + chunkSize
    }

    private fun respond404(out: HttpW) = out.respond(404, "Not Found", listOf("Content-Length" to "0"))

    private fun hexToken(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    companion object {
        const val BufferBytes = 64 * 1024
    }
}
