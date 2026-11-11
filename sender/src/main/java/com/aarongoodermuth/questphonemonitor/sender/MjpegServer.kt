package com.aarongoodermuth.questphonemonitor.sender

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Minimal HTTP server for the MJPEG stream — plain [ServerSocket], no
 * dependencies. Endpoints (see [QpmProtocol]):
 *
 * - `GET /stream` — `multipart/x-mixed-replace` MJPEG, one JPEG part per
 *   frame. Clients wait on the [FrameHub] for each new frame, so a client
 *   gets frames at the capture rate (max 10 fps) and nothing when the
 *   screen is idle.
 * - `GET /` — interactive viewer page (HTML): the stream plus click/drag
 *   control from a browser.
 * - `GET /info` — plain-text status page (handy from a browser on the LAN).
 * - `POST /control` — JSON control commands (tap / swipe / key), performed
 *   by [QpmAccessibilityService]. See [QpmProtocol.CONTROL_PATH].
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
        private const val MAX_CONTROL_BODY_BYTES = 8_192

        // NOTE: the script uses plain string concatenation — no Kotlin
        // `${...}` and no JS template literals, so nothing here
        // interpolates inside the raw string.
        private const val VIEWER_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>Quest Phone Monitor</title>
<style>
body { background:#0B0F14; color:#D1D5DB; font-family:sans-serif; margin:0; padding:16px; text-align:center; }
img { max-width:540px; width:100%; touch-action:none; user-select:none; -webkit-user-select:none; display:block; margin:0 auto; background:#111827; }
button { background:#1F2937; color:#fff; border:1px solid #374151; border-radius:8px; padding:10px 18px; margin:12px 4px 0; font-size:16px; }
#status { margin-top:12px; color:#9CA3AF; font-size:14px; min-height:1.2em; }
</style>
</head>
<body>
<img id="screen" src="/stream" alt="Phone screen">
<div>
<button id="back">Back</button>
<button id="home">Home</button>
<button id="recents">Recents</button>
</div>
<div id="status">Tap or drag the screen to control the phone.</div>
<script>
var img = document.getElementById('screen');
var statusEl = document.getElementById('status');
var downX = 0, downY = 0, downT = 0, down = false;
function phonePoint(clientX, clientY) {
  var r = img.getBoundingClientRect();
  var x = (clientX - r.left) * img.naturalWidth / r.width;
  var y = (clientY - r.top) * img.naturalHeight / r.height;
  return { x: Math.round(x), y: Math.round(y) };
}
function post(obj) {
  fetch('/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) })
    .then(function (resp) { return resp.json(); })
    .then(function (data) {
      if (!data.ok) statusEl.textContent = data.error || 'Control failed.';
    })
    .catch(function () { statusEl.textContent = 'Control request failed.'; });
}
img.addEventListener('pointerdown', function (e) {
  e.preventDefault();
  var p = phonePoint(e.clientX, e.clientY);
  downX = p.x; downY = p.y; downT = Date.now(); down = true;
});
img.addEventListener('pointerup', function (e) {
  if (!down) return;
  down = false;
  var p = phonePoint(e.clientX, e.clientY);
  var dt = Date.now() - downT;
  var dist = Math.hypot(p.x - downX, p.y - downY);
  if (dt < 300 && dist < 24) {
    post({ type: 'tap', x: p.x, y: p.y });
  } else {
    post({ type: 'swipe', x1: downX, y1: downY, x2: p.x, y2: p.y, durationMs: Math.min(Math.max(dt, 100), 1000) });
  }
});
img.addEventListener('pointercancel', function () { down = false; });
document.getElementById('back').addEventListener('click', function () { post({ type: 'key', key: 'back' }); });
document.getElementById('home').addEventListener('click', function () { post({ type: 'key', key: 'home' }); });
document.getElementById('recents').addEventListener('click', function () { post({ type: 'key', key: 'recents' }); });
</script>
</body>
</html>
"""
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
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            // Read the headers so the client isn't left half-sent; the
            // map is needed for POST (Content-Length).
            val headers = readHeaders(input)
            when {
                method == "GET" && path == QpmProtocol.STREAM_PATH -> serveStream(client)
                method == "GET" && path == QpmProtocol.VIEW_PATH -> serveViewer(client)
                method == "GET" && path == QpmProtocol.INFO_PATH -> serveInfo(client)
                method == "POST" && path == QpmProtocol.CONTROL_PATH -> serveControl(client, input, headers)
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
                "Viewer: http://${NetworkUtils.localIpv4Address() ?: "<phone-ip>"}:$port${QpmProtocol.VIEW_PATH}\n" +
                "Control: ${if (QpmAccessibilityService.instance != null) "ready" else "off — enable the accessibility service"}\n" +
                "Clients connected: ${clientCount.get()}\n"
            ).toByteArray(Charsets.UTF_8)
        respond(client, 200, "text/plain; charset=utf-8", body)
    }

    /** Interactive viewer: the live stream as a clickable image. Pointer
     *  down/up on it sends tap/swipe to POST /control, mapped from the
     *  image's displayed size to its natural size (the phone's pixels).
     *  Same origin, so no CORS concerns. */
    private fun serveViewer(client: Socket) {
        val body = VIEWER_HTML.toByteArray(Charsets.UTF_8)
        respond(client, 200, "text/html; charset=utf-8", body)
    }

    private fun serveControl(client: Socket, input: InputStream, headers: Map<String, String>) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_CONTROL_BODY_BYTES) {
            respondJson(client, 400, ok = false, error = "bad Content-Length")
            return
        }
        val bodyBytes = readExact(input, length)
            ?: run {
                respondJson(client, 400, ok = false, error = "short body")
                return
            }
        val body = String(bodyBytes, Charsets.UTF_8)
        val result = try {
            val json = JSONObject(body)
            when (json.optString("type")) {
                "tap" -> {
                    val x = json.optDouble("x", Double.NaN).toFloat()
                    val y = json.optDouble("y", Double.NaN).toFloat()
                    if (x.isNaN() || y.isNaN()) {
                        ControlResult(false, "tap needs x and y")
                    } else if (QpmAccessibilityService.tap(x, y)) {
                        ControlResult(true)
                    } else {
                        ControlResult(false, "control not enabled — turn on the accessibility service")
                    }
                }
                "swipe" -> {
                    val x1 = json.optDouble("x1", Double.NaN).toFloat()
                    val y1 = json.optDouble("y1", Double.NaN).toFloat()
                    val x2 = json.optDouble("x2", Double.NaN).toFloat()
                    val y2 = json.optDouble("y2", Double.NaN).toFloat()
                    val duration = json.optLong("durationMs", 300)
                    if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                        ControlResult(false, "swipe needs x1, y1, x2, y2")
                    } else if (QpmAccessibilityService.swipe(x1, y1, x2, y2, duration)) {
                        ControlResult(true)
                    } else {
                        ControlResult(false, "control not enabled — turn on the accessibility service")
                    }
                }
                "key" -> {
                    val key = json.optString("key")
                    if (key.isEmpty()) {
                        ControlResult(false, "key needs a key name")
                    } else if (QpmAccessibilityService.key(key)) {
                        ControlResult(true)
                    } else {
                        ControlResult(false, "control not enabled, or unknown key: $key")
                    }
                }
                else -> ControlResult(false, "unknown control type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad control request: $body", e)
            ControlResult(false, "bad JSON")
        }
        if (result.ok) {
            respondJson(client, 200, ok = true)
        } else {
            // 503 when the service is off (retryable once enabled), else 400.
            val status = if (result.error?.contains("not enabled") == true) 503 else 400
            respondJson(client, status, ok = false, error = result.error)
        }
    }

    private data class ControlResult(val ok: Boolean, val error: String? = null)

    private fun respondJson(client: Socket, status: Int, ok: Boolean, error: String? = null) {
        val json = JSONObject().apply {
            put("ok", ok)
            if (error != null) put("error", error)
        }
        respond(client, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun respond(client: Socket, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = (
            "HTTP/1.0 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
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

    /** Reads header lines until the blank line. Keys lower-cased. */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    /** Reads exactly [length] bytes, or null if the stream ends first. */
    private fun readExact(input: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n == -1) return null
            read += n
        }
        return bytes
    }

    /** Reads one CRLF-terminated line, without the line ending. Null on EOF. */
    private fun readLine(input: InputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            if (b != '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }
}
