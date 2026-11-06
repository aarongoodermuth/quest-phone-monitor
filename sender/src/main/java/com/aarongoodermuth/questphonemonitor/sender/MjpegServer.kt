package com.aarongoodermuth.questphonemonitor.sender

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP server for the MJPEG stream — plain [ServerSocket], no
 * dependencies. Two endpoints (see [QpmProtocol]):
 *
 * - `GET /stream` — `multipart/x-mixed-replace` MJPEG, one JPEG part per
 *   frame. Clients wait on the [FrameHub] for each new frame, so a client
 *   gets frames at the capture rate (max 10 fps) and nothing when the
 *   screen is idle.
 * - `GET /` — plain-text status page (handy from a browser on the LAN).
 *
 * One thread per client; the accept loop runs on its own thread. All
 * threads are daemon-style and stopped by [stop].
 */
class MjpegServer(
    private val frameHub: FrameHub,
    private val port: Int = QpmProtocol.PORT,
) {
    companion object {
        private const val TAG = "QPM-Server"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    private val clientCount = AtomicInteger(0)

    /** True while at least one client is watching the stream. */
    fun hasClients(): Boolean = clientCount.get() > 0

    /** Starts listening. Returns false if the port could not be bound. */
    fun start(): Boolean {
        if (running) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not bind port $port", e)
            return false
        }
        running = true
        serverSocket = socket
        Log.i(TAG, "MJPEG server listening on port $port")
        acceptThread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    clientCount.incrementAndGet()
                    Thread({ handleClient(client) }, "qpm-client").start()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "Accept failed", e)
                }
            }
        }, "qpm-accept").apply { start() }
        return true
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 0 // block on reads; writes fail fast if gone
            val input = client.getInputStream()
            val requestLine = readRequestLine(input) ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            // Drain the request headers so the client isn't left half-sent.
            readHeaders(input)
            when (path) {
                QpmProtocol.STREAM_PATH -> serveStream(client)
                QpmProtocol.INFO_PATH -> serveInfo(client)
                else -> serveNotFound(client)
            }
        } catch (e: IOException) {
            // Client went away mid-stream — normal, not an error.
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            clientCount.decrementAndGet()
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun serveStream(client: Socket) {
        val out = BufferedOutputStream(client.getOutputStream())
        val header = (
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=${QpmProtocol.BOUNDARY}\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        out.write(header)
        out.flush()
        var lastSequence = -1L
        while (running && !client.isClosed) {
            val (frame, sequence) = frameHub.awaitFrame(lastSequence, 1000L)
                ?: continue // timeout: no new frame yet, re-check connection
            lastSequence = sequence
            val partHeader = (
                "--${QpmProtocol.BOUNDARY}\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII)
            out.write(partHeader)
            out.write(frame)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun serveInfo(client: Socket) {
        val body = (
            "Quest Phone Monitor — sender\n" +
                "Stream: http://${NetworkUtils.localIpv4Address() ?: "<phone-ip>"}:$port${QpmProtocol.STREAM_PATH}\n" +
                "Clients connected: ${clientCount.get()}\n"
            ).toByteArray(Charsets.UTF_8)
        val header = (
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun serveNotFound(client: Socket) {
        val out = BufferedOutputStream(client.getOutputStream())
        out.write("HTTP/1.0 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** Reads the request line (`GET /stream HTTP/1.1`). Null on EOF. */
    private fun readRequestLine(input: InputStream): String? = readLine(input)

    /** Reads header lines until the blank line. Returns the lines read. */
    private fun readHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            lines.add(line)
        }
        return lines
    }

    /** Reads one CRLF-terminated line, without the line ending. Null on EOF. */
    private fun readLine(input: InputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }
}
