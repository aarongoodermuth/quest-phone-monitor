package com.aarongoodermuth.questphonemonitor

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * v0.1 Hello World.
 *
 * A plain 2D panel app on Horizon OS. Quest's OS lets you move and resize
 * this panel like any 2D app — that is the resize mechanism for v0.1.
 *
 * v0.2 will replace the placeholder [phoneScreenView] with a live video
 * view playing the phone's screen stream (Wi-Fi first, USB later).
 */
class MainActivity : Activity() {

    private val testColors = intArrayOf(
        Color.parseColor("#111827"), // dark — placeholder "phone off" state
        Color.parseColor("#0E7490"), // teal
        Color.parseColor("#1D4ED8"), // blue
        Color.parseColor("#7C3AED"), // purple
    )
    private var colorIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val deviceInfo = findViewById<TextView>(R.id.device_info)
        deviceInfo.text = listOf(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Quest Phone Monitor v0.1 — Hello World",
            "Panel resizes with Horizon OS: grab a corner."
        ).joinToString("\n")

        val phoneScreenView = findViewById<View>(R.id.phone_screen_placeholder)
        val statusText = findViewById<TextView>(R.id.status_text)
        val testButton = findViewById<Button>(R.id.test_button)

        testButton.setOnClickListener {
            colorIndex = (colorIndex + 1) % testColors.size
            phoneScreenView.setBackgroundColor(testColors[colorIndex])
            statusText.text = if (colorIndex == 0) {
                "Phone screen will appear here.\nStreaming not built yet — this button just proves the panel responds to input."
            } else {
                "Input works — panel responded (tap ${colorIndex}).\nPhone screen will appear here in v0.2."
            }
        }
    }
}
