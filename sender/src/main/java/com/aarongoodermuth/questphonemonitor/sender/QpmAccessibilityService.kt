package com.aarongoodermuth.questphonemonitor.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Injects input on this phone for the Quest receiver (v0.3 — control).
 *
 * The Quest (or a browser on the viewer page) sends pointer events over
 * the same local connection as the stream; this service performs them
 * with [dispatchGesture] / [performGlobalAction]. No root, no ADB.
 *
 * This is a gesture-only service: [onAccessibilityEvent] is a no-op —
 * it never reads the screen, window content, or notifications. It only
 * acts when a control command arrives while it is enabled.
 *
 * The user enables it once in Settings → Accessibility → Quest Phone
 * Monitor. Until then every [tap]/[swipe]/[key] call returns false and
 * the sender UI says control is off. The service is found through the
 * static [instance] set in [onServiceConnected]; the HTTP layer calls
 * the companion helpers and never holds the service itself.
 */
class QpmAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QPM-Input"

        /** Set when the user has enabled the service; null otherwise. */
        @Volatile
        var instance: QpmAccessibilityService? = null
            private set

        /** True when the user has enabled this service in Settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = ComponentName(context, QpmAccessibilityService::class.java)
            return enabled.split(':').any { it == component.flattenToString() }
        }

        /**
         * Tap at phone-screen pixels (the captured frame's pixel space).
         * Returns false when the service is not enabled.
         */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        /**
         * Swipe from (x1, y1) to (x2, y2) over [durationMs]. Returns
         * false when the service is not enabled.
         */
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path, 0, durationMs.coerceIn(50, 3_000)
                    )
                )
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        /** Back / Home / Recents. Returns false when not enabled. */
        fun key(action: String): Boolean {
            val service = instance ?: return false
            val globalAction = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> return false
            }
            return service.performGlobalAction(globalAction)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — control available")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Gesture-only service: no events are consumed or read.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
