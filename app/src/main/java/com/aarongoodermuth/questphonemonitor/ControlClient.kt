package com.aarongoodermuth.questphonemonitor

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Sends control commands to the phone sender (v0.3).
 *
 * The sender's `POST /control` endpoint performs the tap / swipe / key
 * via its accessibility service. Every call is fire-and-forget on a
 * background thread — the receiver never blocks its UI on the network,
 * and a failed send only logs (the next tap will work when the sender
 * is reachable again). Coordinates are phone-screen pixels: the caller
 * maps from the displayed image to the captured frame's pixel space.
 */
object ControlClient {
    private const val TAG = "QPM-Control"

    fun tap(host: String, port: Int, x: Float, y: Float) {
        send(host, port, JSONObject().apply {
            put("type", "tap")
            put("x", x.toDouble())
            put("y", y.toDouble())
        })
    }

    fun swipe(host: String, port: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        send(host, port, JSONObject().apply {
            put("type", "swipe")
            put("x1", x1.toDouble())
            put("y1", y1.toDouble())
            put("x2", x2.toDouble())
            put("y2", y2.toDouble())
            put("durationMs", durationMs)
        })
    }

    fun key(host: String, port: Int, key: String) {
        send(host, port, JSONObject().apply {
            put("type", "key")
            put("key", key)
        })
    }

    private fun send(host: String, port: Int, json: JSONObject) {
        Thread({
            try {
                val url = URL("http://$host:$port${QpmProtocol.CONTROL_PATH}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2_000
                    readTimeout = 2_000
                }
                conn.outputStream.use { out ->
                    out.write(json.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Control POST failed: HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Control POST failed", e)
            }
        }, "qpm-control").start()
    }
}
