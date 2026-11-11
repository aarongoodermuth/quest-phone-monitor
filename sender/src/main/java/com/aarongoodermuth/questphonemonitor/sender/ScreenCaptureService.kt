package com.aarongoodermuth.questphonemonitor.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Owns the whole streaming pipeline: MediaProjection capture → JPEG
 * frames → MJPEG HTTP server → mDNS advertisement.
 *
 * Why a foreground service (not just the Activity):
 * - Android 10+ (our minSdk is 29) requires a foreground service of type
 *   `mediaProjection` before [MediaProjectionManager.getMediaProjection]
 *   may be called, and the capture dies with the Activity otherwise.
 * - The service keeps streaming while the phone's screen is off or the
 *   user switches apps.
 *
 * Foreground-service notifications are exempt from Android 13's
 * POST_NOTIFICATIONS runtime permission — the system posts the FGS
 * notification itself — so this service does not request it.
 *
 * The Activity talks to the service with [Intent] commands (see
 * [EXTRA_COMMAND]) and reads live state from the companion object and
 * from status broadcasts ([ACTION_STATUS]).
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "QPM-Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "qpm_sender"

        const val ACTION_STATUS = "com.aarongoodermuth.questphonemonitor.sender.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_URL = "url"
        const val EXTRA_RUNNING = "running"

        const val EXTRA_COMMAND = "command"
        const val COMMAND_START = "start"
        const val COMMAND_STOP = "stop"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY_DPI = "density_dpi"

        /** Live state the Activity reads on resume. */
        @Volatile var isRunning = false
            private set
        @Volatile var streamUrl: String? = null
            private set
        @Volatile var statusText: String = "Not streaming"
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var server: MjpegServer? = null
    private var frameHub: FrameHub? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture from the system UI (or the
            // projection died). Tear everything down.
            Log.i(TAG, "MediaProjection stopped by system")
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote to foreground FIRST, before any validation below.
        // Android kills the whole app (ForegroundServiceDidNotStartInTime)
        // if a service started with startForegroundService() does not call
        // startForeground() within a few seconds — and every check below
        // can bail out with stopSelf(). The crash on Nov 11 was exactly
        // this: a validation failure stopped the service before it ever
        // promoted. Promoting here makes every exit path safe, including
        // a stop command arriving when the service is not yet promoted.
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        return when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_STOP -> {
                stopCapture()
                stopSelf()
                START_NOT_STICKY
            }
            COMMAND_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val width = intent.getIntExtra(EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, 0)
                if (resultData == null || width <= 0 || height <= 0) {
                    Log.w(TAG, "Start called without capture permission data")
                    stopSelf()
                } else {
                    startCapture(resultCode, resultData, width, height, densityDpi)
                }
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        if (isRunning) return
        stopping = false

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: SecurityException) {
            // Stale/expired capture permission data — tell the user to
            // try again instead of crashing the service.
            Log.w(TAG, "MediaProjection permission rejected", e)
            statusText = "Screen-capture permission was rejected — try starting again"
            broadcastStatus()
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val hub = FrameHub()
        frameHub = hub
        val mjpegServer = MjpegServer(hub)
        server = mjpegServer
        val screenCapturer = ScreenCapturer(
            mediaProjection = projection,
            frameHub = hub,
            hasClients = { mjpegServer.hasClients() },
        )
        capturer = screenCapturer

        // Already promoted in onStartCommand (see there) — do not
        // promote again here.
        if (!mjpegServer.start()) {
            // Port 8080 is taken (another sender running?). Unwind without
            // ever starting capture: the projection is released, the
            // foreground notification is removed, and the Activity shows
            // the error instead of a fake "streaming" state.
            try {
                projection.unregisterCallback(projectionCallback)
                projection.stop()
            } catch (_: Exception) {
            }
            mediaProjection = null
            server = null
            capturer = null
            frameHub = null
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            statusText = "Could not start the stream server — port ${QpmProtocol.PORT} may be in use"
            broadcastStatus()
            stopSelf()
            return
        }
        screenCapturer.start(width, height, densityDpi)
        registerNsdService()

        val ip = NetworkUtils.localIpv4Address()
        streamUrl = "http://$ip:${QpmProtocol.PORT}${QpmProtocol.STREAM_PATH}"
        statusText = "Streaming — open the Quest app to view"
        isRunning = true
        updateNotification()
        broadcastStatus()
        Log.i(TAG, "Streaming started: $streamUrl")
    }

    private fun stopCapture() {
        if (stopping) return
        stopping = true
        try {
            capturer?.stop()
        } catch (_: Exception) {
        }
        capturer = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        frameHub?.clear()
        frameHub = null
        unregisterNsdService()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        isRunning = false
        streamUrl = null
        statusText = "Not streaming"
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        broadcastStatus()
        Log.i(TAG, "Streaming stopped")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---- mDNS advertisement -------------------------------------------------

    private fun registerNsdService() {
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = QpmProtocol.SERVICE_NAME
            serviceType = QpmProtocol.SERVICE_TYPE
            port = QpmProtocol.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${info.serviceName} on :${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterNsdService() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unregister failed", e)
        }
        nsdManager = null
    }

    // ---- Notification ---------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen sharing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the phone screen is being shared to the Quest"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(url: String?): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_COMMAND, COMMAND_STOP)
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Quest Phone Monitor")
            .setContentText(url ?: "Sharing your screen to the Quest")
            .setSmallIcon(R.drawable.ic_screen_share)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPending).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(streamUrl))
    }

    // ---- Status for the Activity ----------------------------------------------

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, statusText)
            putExtra(EXTRA_URL, streamUrl)
            putExtra(EXTRA_RUNNING, isRunning)
        }
        sendBroadcast(intent)
    }
}
