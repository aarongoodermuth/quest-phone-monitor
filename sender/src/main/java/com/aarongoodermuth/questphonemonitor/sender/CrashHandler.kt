package com.aarongoodermuth.questphonemonitor.sender

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture (v0.2.2 diagnostic).
 *
 * If the app dies, the stock Android dialog only says "this app has a
 * bug" — no reason. This handler writes the real stack trace where the
 * user can find it: Downloads/quest-phone-monitor-crash.txt (visible in
 * the Files app) plus the app's private files dir as a backup. It then
 * lets the crash proceed normally, so the system dialog still appears.
 *
 * No permissions needed: writing your own file to Downloads via
 * MediaStore and to getExternalFilesDir() both work without any runtime
 * permission on our minSdk (29).
 */
object CrashHandler {
    private const val TAG = "QPM-Crash"
    const val FILE_NAME = "quest-phone-monitor-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "Quest Phone Monitor — Sender crash\n" +
                    "$stamp — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                    Log.getStackTraceString(throwable)
                writeDownloads(appContext, text)
                writePrivate(appContext, text)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeDownloads(context: Context, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writePrivate(context: Context, text: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        FileOutputStream(File(dir, FILE_NAME)).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
