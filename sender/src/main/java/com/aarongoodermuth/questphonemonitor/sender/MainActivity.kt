package com.aarongoodermuth.questphonemonitor.sender

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * The phone side of Quest Phone Monitor.
 *
 * One button: Start sharing → asks Android for screen-capture permission
 * (the system dialog — this app cannot grant it to itself) → the
 * [ScreenCaptureService] captures the screen and serves it as MJPEG on
 * the local Wi-Fi, advertised over mDNS so the Quest finds it with no
 * typing. Stop sharing tears it all down.
 *
 * The stream URL is shown for debugging (any browser on the same network
 * can open it); the Quest app normally needs no URL at all.
 *
 * v0.3 adds control: the Quest (or a browser on the viewer page) sends
 * taps/swipes/keys to POST /control and [QpmAccessibilityService]
 * performs them. Control needs that service enabled once in
 * Settings → Accessibility; this Activity shows whether it is on and
 * offers to open the settings page when it is off.
 *
 * Note: this uses the classic [startActivityForResult] / [onActivityResult]
 * for the capture permission instead of the androidx Activity Result API,
 * deliberately — the sender module is dependency-free (see build.gradle.kts)
 * and plain [Activity] has no `registerForActivityResult`. The old API is
 * deprecated but still fully functional on API 34.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "QPM-Sender"
        private const val REQUEST_CAPTURE = 1
    }

    private var statusText: TextView? = null
    private var urlText: TextView? = null
    private var toggleButton: Button? = null
    private var controlText: TextView? = null
    private var enableControlButton: Button? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ScreenCaptureService.ACTION_STATUS) return
            val running = intent.getBooleanExtra(ScreenCaptureService.EXTRA_RUNNING, false)
            val status = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
            val url = intent.getStringExtra(ScreenCaptureService.EXTRA_URL)
            statusText?.text = status
            urlText?.text = url ?: ""
            toggleButton?.text = if (running) "Stop sharing" else "Start sharing"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install first: if anything below crashes, the trace lands in
        // Downloads/quest-phone-monitor-crash.txt instead of vanishing.
        CrashHandler.install(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        urlText = findViewById(R.id.url_text)
        toggleButton = findViewById(R.id.toggle_button)
        controlText = findViewById(R.id.control_text)
        enableControlButton = findViewById(R.id.enable_control_button)

        toggleButton?.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                stopSharing()
            } else {
                requestCapturePermission()
            }
        }

        enableControlButton?.setOnClickListener {
            // The user flips the switch here; there is no programmatic
            // way to enable an accessibility service.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // Android 13+ (API 33) requires an explicit export flag for
        // dynamically-registered receivers; targeting API 34, the old
        // two-arg call throws SecurityException on launch. The broadcast
        // is app-private (the service sends it with
        // setPackage(packageName)), so NOT_EXPORTED is correct. The
        // three-arg overload and the flag constant both need API 33, and
        // our minSdk is 29, so guard by SDK version.
        // Wrapped so a registration failure shows on screen instead of
        // killing the app — the crash handler still records the trace.
        try {
            val filter = IntentFilter(ScreenCaptureService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receiver registration failed", e)
            statusText?.text = "Startup error:\n${e.javaClass.simpleName}: ${e.message}"
        }
        refreshUiFromServiceState()
        refreshControlStatus()
    }

    /** Shows whether the Quest can control this phone yet. Checked on
     *  every resume because the user may have just flipped the switch
     *  in Accessibility settings. */
    private fun refreshControlStatus() {
        if (QpmAccessibilityService.isEnabled(this)) {
            controlText?.text = "Control ready — the Quest can tap and swipe this phone."
            enableControlButton?.visibility = View.GONE
        } else {
            controlText?.text = "Control off — turn on the Quest Phone Monitor service " +
                "in Accessibility settings to let the Quest tap this phone."
            enableControlButton?.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered — harmless.
        }
    }

    @Deprecated("Used deliberately to keep the sender dependency-free; see class doc.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startSharing(resultCode, data)
        } else {
            statusText?.text = "Screen-capture permission was not granted.\n" +
                "Tap Start sharing and allow it to stream to the Quest."
        }
    }

    private fun refreshUiFromServiceState() {
        val running = ScreenCaptureService.isRunning
        statusText?.text = ScreenCaptureService.statusText
        urlText?.text = ScreenCaptureService.viewUrl ?: ""
        toggleButton?.text = if (running) "Stop sharing" else "Start sharing"
    }

    @Suppress("DEPRECATION")
    private fun requestCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun startSharing(resultCode: Int, resultData: Intent) {
        val (width, height) = displaySize()
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_COMMAND, ScreenCaptureService.COMMAND_START)
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, height)
            putExtra(ScreenCaptureService.EXTRA_DENSITY_DPI, resources.displayMetrics.densityDpi)
        }
        startForegroundService(intent)
        statusText?.text = "Starting…"
    }

    private fun stopSharing() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_COMMAND, ScreenCaptureService.COMMAND_STOP)
        }
        startService(intent)
        statusText?.text = "Stopping…"
    }

    /** Real display size in pixels (not the activity window size). */
    private fun displaySize(): Pair<Int, Int> {
        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            size.x to size.y
        }
        // A zero/negative size (seen in odd windowing modes such as DeX)
        // would make the service reject the start request outright; fall
        // back to the screen size instead of passing it through.
        if (size.first <= 0 || size.second <= 0) {
            val dm = resources.displayMetrics
            return dm.widthPixels to dm.heightPixels
        }
        return size
    }
}
