package com.aarongoodermuth.questphonemonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays the sender's MJPEG stream (`GET /stream`) and delivers decoded
 * frames to [onFrame]. Runs entirely on its own thread; callbacks arrive
 * on that thread, so the caller must post to the main thread for UI work.
 *
 * Parsing: the stream is `multipart/x-mixed-replace` and every part
 * carries a `Content-Length` (the sender guarantees this), so each frame
 * is read as an exact byte count and decoded with
 * [BitmapFactory.decodeByteArray]. A corrupt frame is skipped, not fatal.
 *
 * Any I/O failure (connect refused, EOF, timeout) ends the stream and
 * calls [onDisconnected] exactly once — the caller decides whether to
 * reconnect. There is no retry inside this class: the caller owns
 * discovery and knows whether the sender is still advertised.
 */
class MjpegStreamPlayer(
    private val host: String,
    private val port: Int = QpmProtocol.PORT,
    private val onFrame: (Bitmap) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "QPM-Player"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "qpm-player").apply { start() }
    }

    fun stop() {
        running = false
        // Closing the connection unblocks the thread if it is stuck in
        // a socket read (Thread.interrupt() alone cannot do that).
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
        thread = null
    }

    private fun runLoop() {
        try {
            val url = URL("http://$host:$port${QpmProtocol.STREAM_PATH}")
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "multipart/x-mixed-replace")
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Stream HTTP ${conn.responseCode}")
                return
            }
            readFrames(BufferedInputStream(conn.inputStream))
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Stream I/O failed", e)
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Stream failed", e)
        } finally {
            val unexpected = running
            running = false
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
            connection = null
            // Notify only if the stream ended on its own (not via stop()).
            if (unexpected) {
                mainHandler.post { onDisconnected() }
            }
        }
    }

    private fun readFrames(input: InputStream) {
        // Top-level response headers, up to the blank line.
        readHeaders(input)
        while (running) {
            // Boundary line: "--qpmframe". Anything else is skipped.
            val boundary = readLine(input) ?: return // EOF
            if (!boundary.startsWith("--")) continue
            // Part headers, up to the blank line.
            val partHeaders = readHeaders(input)
            val contentLength = partHeaders
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toIntOrNull()
                ?: continue // no length: can't frame the part, skip it
            val jpeg = readExactly(input, contentLength) ?: return // EOF
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bitmap != null) {
                onFrame(bitmap)
            } else {
                Log.d(TAG, "Skipped a corrupt frame (${jpeg.size} bytes)")
            }
        }
    }

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
            val b = try {
                input.read()
            } catch (_: IOException) {
                return null
            }
            if (b == -1) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    /** Reads exactly [count] bytes, or null on EOF before that. */
    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                input.read(buffer, offset, count - offset)
            } catch (_: IOException) {
                return null
            }
            if (read == -1) return null
            offset += read
        }
        return buffer
    }
}
