// server behavior end to end over real sockets with a fake downloader
package com.mochyfork.mftelegram.stream

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamServerTest {
    @get:Rule var tmp = TemporaryFolder()

    private lateinit var store: ChunkStore
    private val id = "song1"
    // 300 bytes of known data; chunk size small so multi-chunk logic really runs
    private val cs = 64
    private val data = ByteArray(300) { (it * 7 % 251).toByte() }
    private lateinit var source: FakeSource
    private lateinit var server: StreamServer

    @Before fun start() {
        store = ChunkStore(tmp.root, cs)
        source = FakeSource(store, data, cs)
        server = StreamServer(store, source)
        server.start()
    }

    @After fun stop() {
        server.stop()
    }

    private fun connection(path: String, range: String? = null, method: String = "GET"): HttpURLConnection {
        val c = URL("${server.url(id)}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 8000
        range?.let { c.setRequestProperty("Range", it) }
        return c
    }

    private fun headers(c: HttpURLConnection): Map<String, String> =
        c.headerFields.entries.associate { (k, v) -> (k ?: "").lowercase() to v.joinToString(",") }

    // plain socket for full control over closing behavior
    private fun rawRequest(range: String?, grabFirstBytes: Boolean = true): Pair<BufferedReader, Socket> {
        val socket = Socket("127.0.0.1", server.port)
        val request = buildString {
            append("GET ${java.net.URI(server.url(id)).rawPath} HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            range?.let { append("Range: $it\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        if (grabFirstBytes) reader.readLine()
        return reader to socket
    }

    private fun readHeaders(reader: BufferedReader): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            map[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
        }
        return map
    }

    @Test fun fullGetServesEverything() {
        val c = connection("")
        assertEquals(200, c.responseCode)
        assertEquals("bytes", headers(c)["accept-ranges"])
        assertEquals("audio/mpeg", headers(c)["content-type"])
        assertEquals(data.size.toLong(), c.contentLengthLong)
        assertEquals(data.toList(), c.inputStream.use { it.readBytes() }.toList())
    }

    @Test fun headHasHeadersButNoBody() {
        val c = connection("", method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals(data.size.toLong(), c.contentLengthLong)
    }

    @Test fun parsedRangeServesTheSameSlice() {
        val c = connection("", "bytes=10-29")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 10-29/" + data.size, headers(c)["content-range"])
        assertEquals(20, c.contentLengthLong)
        assertEquals(data.sliceArray(10..29).toList(), c.inputStream.use { it.readBytes() }.toList())
    }

    @Test fun openRangeServesTail() {
        val c = connection("", "bytes=280-")
        assertEquals(206, c.responseCode)
        assertEquals(20, c.contentLengthLong)
        assertEquals(data.sliceArray(280..299).toList(), c.inputStream.use { it.readBytes() }.toList())
    }

    @Test fun suffixRangeServesLastN() {
        val c = connection("", "bytes=-10")
        assertEquals(206, c.responseCode)
        assertEquals(10, c.contentLengthLong)
        assertEquals(data.sliceArray(290..299).toList(), c.inputStream.use { it.readBytes() }.toList())
    }

    @Test fun rangeBeyondEofIs416() {
        val c = connection("", "bytes=500-600")
        assertEquals(416, c.responseCode)
    }

    @Test fun unknownIdAndWrongTokenAre404() {
        val ok = server.url(id)
        assertEquals(404, (URL(ok.replaceAfterLast('/', "nope")).openConnection() as HttpURLConnection).responseCode)
        // wrong token: the /s/<token>/ segment swapped
        val wrong = ok.replace("/s/", "/s/wrongtoken")
        assertEquals(404, (URL(wrong).openConnection() as HttpURLConnection).responseCode)
    }

    @Test fun seekAheadPullsChunkAtTheRightOffset() {
        // only the first chunk preloaded, then a request deep in the file
        store.obtain(id).writeChunk(0, data.copyOfRange(0, cs))
        val c = connection("", "bytes=200-260")
        assertEquals(206, c.responseCode)
        assertEquals(data.sliceArray(200..260).toList(), c.inputStream.use { it.readBytes() }.toList())
        // downloader asked per uncovered chunk, aligned to chunk boundaries
        val froms = source.downloadCalls.map { it.first }
        assertTrue(froms.all { it % cs == 0L })
        assertTrue(froms.contains(192L))
    }

    @Test fun rapidSeeksServeCorrectWindowsOneByOne() {
        for (start in listOf(0L, 50L, 140L, 299L)) {
            val last = minOf(start + 20, 299)
            val c = connection("", "bytes=$start-$last")
            assertEquals(206, c.responseCode)
            assertEquals(data.sliceArray(start.toInt()..last.toInt()).toList(), c.inputStream.use { it.readBytes() }.toList())
        }
    }

    @Test fun stalledDownloadDoesNotHangTheServerForever() {
        source.fail = true
        val c = connection("", "bytes=0-29")
        val startedAt = System.nanoTime()
        try {
            c.connect()
            c.inputStream.use { it.readBytes() }
        } catch (_: java.io.IOException) {
            // broken body after headers-out is the expected abort path
        }
        assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(12))
    }

    @Test     fun cancelRunsWhenTheReaderWalksAwayMidTransfer() {
        source.blockForever = true
        val tuple = rawRequest("bytes=0-", grabFirstBytes = false)
        val reader = tuple.first
        val socket = tuple.second
        assertEquals(true, reader.readLine().contains("206"))
        readHeaders(reader)
        assertTrue(source.downloadStarted.await(5, TimeUnit.SECONDS))
        socket.close()
        source.releaseDownload.countDown()
        assertTrue("server never noticed the gone reader", source.cancelObserved.await(5, TimeUnit.SECONDS))
    }
}

/** fake downloader that writes real bytes into the store, plus stall and cancel hooks */
class FakeSource(
    private val store: ChunkStore,
    private val data: ByteArray,
    private val cs: Int,
) : FileSource {
    val downloadCalls = mutableListOf<Pair<Long, Long>>()
    val cancelCalls = mutableListOf<String>()
    var fail = false
    var blockForever = false
    val downloadStarted = CountDownLatch(1)
    val cancelObserved = CountDownLatch(1)
    val releaseDownload = CountDownLatch(1)

    override suspend fun meta(uniqueId: String): FileSource.Meta? {
        // unknown ids look unknown; the known id gives the true file size
        return if (uniqueId == "song1") FileSource.Meta("audio/mpeg", data.size.toLong()) else null
    }

    override suspend fun download(uniqueId: String, from: Long, untilExclusive: Long): Boolean {
        if (fail) return false
        downloadCalls += from to untilExclusive
        if (blockForever) {
            downloadStarted.countDown()
            releaseDownload.await()
        }
        val fc = store.obtain(uniqueId)
        var at = from
        while (at < untilExclusive) {
            val i = (at / cs).toInt()
            if (!fc.covers(i * cs.toLong(), (i + 1) * cs.toLong())) {
                val chunkStart = i * cs.toLong()
                val chunkEnd = minOf(chunkStart + cs, data.size.toLong())
                if (chunkStart < data.size) {
                    fc.writeChunk(i, data.copyOfRange(chunkStart.toInt(), chunkEnd.toInt()))
                }
            }
            at = (i + 1) * cs.toLong()
        }
        return true
    }

    override fun cancel(uniqueId: String) {
        cancelCalls += uniqueId
        cancelObserved.countDown()
    }
}
