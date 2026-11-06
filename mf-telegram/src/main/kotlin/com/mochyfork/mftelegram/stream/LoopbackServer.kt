// a tiny http/1.1 server on raw server sockets, bound to the loopback only. exoplayer is the
// only client, so request shapes stay narrow: one request per line-set, optional range header
// and consistent keep-alive when the client asks for it
package com.mochyfork.mftelegram.stream

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** one http request, already cooked */
class HttpR(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/** response sink handed to the handler; body handed over after headers are written */
class HttpW(val socket: Socket) {
    fun respond(code: Int, reason: String, headers: List<Pair<String, String>>, body: ByteArray? = null) {
        val out = socket.getOutputStream()
        val sb = StringBuilder().append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        synchronized(socket) { out.write(sb.toString().toByteArray()) }
        if (body != null) synchronized(socket) { out.write(body) }
    }

    fun startBody() {
        socket.getOutputStream().flush()
    }

    @Throws(IOException::class)
    fun writeBody(bytes: ByteArray) {
        synchronized(socket) { socket.getOutputStream().write(bytes) } // blocking on the client
    }

    /** false once the client tore the connection down without reading */
    fun alive(): Boolean = socket.isConnected && !socket.isClosed && !socket.isOutputShutdown

    companion object {
        const val PollMs = 40L
    }
}

class LoopbackServer(
    // null port = any free port; the bound port is back in localPort after start()
    val port: Int = 0,
    private val backlog: Int = 4,
    private val handler: (HttpR, HttpW) -> Unit,
) {
    val openSocket: ServerSocket = ServerSocket()
    var threadPool: ExecutorService? = null
    var acceptThread: Thread? = null
    val localPort: Int get() = openSocket.localPort

    /** bind to the loopback only and start taking connections; caller owns the lifetime */
    fun start() {
        openSocket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), backlog)
        val pool = Executors.newFixedThreadPool(4) { r: Runnable ->
            Thread(r, "mf-stream").apply { isDaemon = true }
        }
        threadPool = pool
        acceptThread = Thread(
            {
                while (true) {
                    val socket = try {
                        openSocket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    socket.tcpNoDelay = true
                    socket.soTimeout = 10_000
                    pool.execute { serve(socket) }
                }
            },
            "mf-stream-accept",
        ).apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        // loop while the client wants keep-alive: exoplayer reuses one connection
        while (true) {
            val request = readRequest(reader, socket) ?: break
            handleSafe(request, socket)
            if (request.header("connection").equals("close", ignoreCase = true)) break
        }
        socket.close()
    }

    private fun readRequest(reader: java.io.BufferedReader, socket: Socket): HttpR? {
        // exoplayer always sends: request line, headers, blank line
        val first = reader.readLine() ?: return null
        if (first.isBlank()) return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = try { reader.readLine() } catch (_: Throwable) { return null }
            if (line.isNullOrEmpty()) break
            val sep = line.indexOf(':')
            if (sep > 0) headers[line.substring(0, sep).trim().lowercase(Locale.ROOT)] = line.substring(sep + 1).trim()
        }
        return HttpR(parts[0].uppercase(Locale.ROOT), parts[1], headers)
    }

    private fun handleSafe(request: HttpR, socket: Socket) {
        try {
            handler(request, HttpW(socket))
            socket.getOutputStream().flush()
        } catch (_: IOException) {
            // the player went away mid-transfer: handler-side cancel already ran, just close
            socket.close()
        } catch (_: Throwable) {
            socket.close()
        }
    }

    fun stop() {
        try { openSocket.close() } catch (e: IOException) { /* nothing */ }
        threadPool?.shutdown()
        acceptThread = null
        threadPool = null
    }
}
