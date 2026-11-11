package com.aarongoodermuth.questphonemonitor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * v0.3 — Quest receiver.
 *
 * Discovers the phone sender over mDNS ([QpmProtocol.SERVICE_TYPE]),
 * connects to its MJPEG stream, and plays it live in the phone-shaped
 * view. When no stream is connected, the view is the v0.1 placeholder
 * and tapping it still cycles the test colors — the tap-to-test fallback.
 *
 * v0.3 adds control: tapping / dragging the live stream sends the
 * gesture to the phone (POST /control), which performs it through its
 * accessibility service. The phone must have that service enabled —
 * until then taps do nothing on the phone (the sender app says so).
 * Back / Home / Recents buttons send the matching global action.
 *
 * Connection states (shown in [connectionStatus]):
 * - "Searching…" — discovery running, no sender found yet.
 * - "Connected to <name> (<ip>)" — live stream.
 * - "Disconnected — searching…" — the stream dropped; discovery keeps
 *   running and the app retries the last known sender a few times.
 *
 * Discovery stays up while the Activity lives (teardown is in
 * [onDestroy], not onPause — Quest 2D panels pause when the user looks
 * away, and tearing the stream down on every pause would drop it
 * constantly). A [WifiManager.MulticastLock] is held while discovering:
 * without it, mDNS discovery on Android is unreliable.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "QPM-Receiver"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
    }

    private val testColors = intArrayOf(
        Color.parseColor("#111827"), // dark — placeholder "phone off" state
        Color.parseColor("#0E7490"), // teal
        Color.parseColor("#1D4ED8"), // blue
        Color.parseColor("#7C3AED"), // purple
    )
    private var colorIndex = 0

    private var phoneContainer: FrameLayout? = null
    private var phoneImage: ImageView? = null
    private var connectionStatus: TextView? = null
    private var statusText: TextView? = null

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    private var player: MjpegStreamPlayer? = null
    private var currentBitmap: Bitmap? = null
    private var connectedServiceName: String? = null
    private var lastHost: String? = null
    private var lastPort: Int = QpmProtocol.PORT
    private var lastServiceName: String? = null
    private var reconnectAttempts = 0

    // Touch state for control (v0.3): down point/time, to tell a tap
    // (quick, barely moved) from a swipe.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        val host = lastHost
        if (host != null && player == null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            Log.i(TAG, "Reconnect attempt $reconnectAttempts to $host")
            connectionStatus?.text = "Connection lost — retrying ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)…"
            startPlayer(host, lastPort, lastServiceName ?: "phone")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val deviceInfo = findViewById<TextView>(R.id.device_info)
        deviceInfo.text = listOf(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Quest Phone Monitor v0.3 — live phone stream with control",
            "Panel resizes with Horizon OS: grab a corner."
        ).joinToString("\n")

        phoneContainer = findViewById(R.id.phone_screen_container)
        phoneImage = findViewById(R.id.phone_screen_image)
        connectionStatus = findViewById(R.id.connection_status)
        statusText = findViewById(R.id.status_text)

        phoneContainer?.setOnClickListener {
            // Tap-to-test fallback: only while no stream is connected.
            if (player == null) {
                colorIndex = (colorIndex + 1) % testColors.size
                phoneContainer?.setBackgroundColor(testColors[colorIndex])
                statusText?.text = if (colorIndex == 0) {
                    "No phone connected yet.\nStart sharing on your phone — the Quest finds it automatically."
                } else {
                    "Input works — panel responded (tap $colorIndex).\nStart sharing on your phone to see its screen here."
                }
            }
        }

        // v0.3 control: touch the live stream to tap / swipe the phone.
        phoneImage?.setOnTouchListener { _, event ->
            if (player == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - touchDownTime
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val start = viewToPhone(touchDownX, touchDownY)
                    val end = viewToPhone(event.x, event.y)
                    if (start != null && end != null) {
                        val host = lastHost
                        if (host != null) {
                            if (dt < 300 && kotlin.math.hypot(dx, dy) < 24f) {
                                ControlClient.tap(host, lastPort, start.first, start.second)
                            } else {
                                ControlClient.swipe(
                                    host, lastPort,
                                    start.first, start.second, end.first, end.second,
                                    dt.coerceIn(100, 1_000),
                                )
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        findViewById<Button>(R.id.control_back)?.setOnClickListener { sendKey("back") }
        findViewById<Button>(R.id.control_home)?.setOnClickListener { sendKey("home") }
        findViewById<Button>(R.id.control_recents)?.setOnClickListener { sendKey("recents") }

        startDiscovery()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reconnectRunnable)
        stopPlayer()
        stopDiscovery()
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }

    // ---- Discovery ----------------------------------------------------------

    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("qpm-discovery").apply {
            setReferenceCounted(true)
            acquire()
        }
        multicastLock = lock

        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discovering = true
                Log.i(TAG, "Discovery started")
                runOnUiThread {
                    if (player == null) {
                        connectionStatus?.text = "Searching for your phone…"
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
                Log.i(TAG, "Discovery stopped")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "Found sender: ${service.serviceName}")
                // First sender wins; ignore extras while connected.
                if (player == null) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Lost sender: ${service.serviceName}")
                if (service.serviceName == connectedServiceName) {
                    onStreamDisconnected()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
                runOnUiThread {
                    connectionStatus?.text = "Could not search for your phone (error $errorCode)."
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to stop: $errorCode")
            }
        }
        discoveryListener = listener
        manager.discoverServices(QpmProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        if (discovering) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery failed", e)
            }
        }
        discovering = false
        nsdManager = null
        if (multicastLock?.isHeld == true) {
            try {
                multicastLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "MulticastLock release failed", e)
            }
        }
        multicastLock = null
    }

    private fun resolveService(service: NsdServiceInfo) {
        val manager = nsdManager ?: return
        manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                Log.i(TAG, "Resolved ${resolved.serviceName} at $host:$port")
                runOnUiThread {
                    if (player == null) {
                        startPlayer(host, port, resolved.serviceName)
                    }
                }
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${service.serviceName}: $errorCode")
                // Transient most of the time; discovery will re-announce
                // the service and onServiceFound will fire again.
            }
        })
    }

    // ---- Stream playback ------------------------------------------------------

    private fun startPlayer(host: String, port: Int, serviceName: String) {
        lastHost = host
        lastPort = port
        lastServiceName = serviceName
        connectedServiceName = serviceName
        val streamPlayer = MjpegStreamPlayer(
            host = host,
            port = port,
            onFrame = { bitmap -> showFrame(bitmap) },
            onDisconnected = { onStreamDisconnected() },
        )
        player = streamPlayer
        connectionStatus?.text = "Connected to $serviceName ($host)"
        statusText?.text = "Live — your phone's screen.\nTap the screen to control it."
        streamPlayer.start()
    }

    // ---- Control (v0.3) -----------------------------------------------------

    private fun sendKey(key: String) {
        val host = lastHost ?: return
        if (player == null) return
        ControlClient.key(host, lastPort, key)
    }

    /**
     * Maps a touch point in the ImageView's coordinates to phone-screen
     * pixels. The ImageView is fitCenter, so the bitmap is letterboxed:
     * scale by the smaller fit factor and subtract the centering offset.
     * Returns null when the point falls outside the displayed image or
     * no frame has arrived yet.
     */
    private fun viewToPhone(x: Float, y: Float): Pair<Float, Float>? {
        val bmp = currentBitmap ?: return null
        val view = phoneImage ?: return null
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val scale = minOf(viewWidth / bmp.width, viewHeight / bmp.height)
        val offsetX = (viewWidth - bmp.width * scale) / 2f
        val offsetY = (viewHeight - bmp.height * scale) / 2f
        val phoneX = (x - offsetX) / scale
        val phoneY = (y - offsetY) / scale
        if (phoneX < 0 || phoneY < 0 || phoneX > bmp.width || phoneY > bmp.height) return null
        return phoneX to phoneY
    }

    private fun stopPlayer() {
        player?.stop()
        player = null
    }

    private fun showFrame(bitmap: Bitmap) {
        runOnUiThread {
            if (player == null) {
                bitmap.recycle()
                return@runOnUiThread
            }
            val old = currentBitmap
            currentBitmap = bitmap
            phoneImage?.apply {
                visibility = View.VISIBLE
                setImageBitmap(bitmap)
            }
            old?.recycle()
        }
    }

    private fun onStreamDisconnected() {
        runOnUiThread {
            val wasConnected = player != null
            stopPlayer()
            phoneImage?.apply {
                setImageDrawable(null)
                visibility = View.GONE
            }
            currentBitmap?.recycle()
            currentBitmap = null
            connectedServiceName = null
            if (wasConnected) {
                connectionStatus?.text = "Disconnected — searching…"
                statusText?.text = "Connection lost.\nMake sure sharing is still on, or restart it on your phone."
                // The sender may still be advertised (Wi-Fi blip): retry
                // the last known address a few times before giving up
                // and waiting on discovery alone.
                reconnectAttempts = 0
                mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
            }
        }
    }
}
