package com.aarongoodermuth.questphonemonitor.sender

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Captures the phone screen via [MediaProjection] and publishes JPEG
 * frames to a [FrameHub].
 *
 * Design choices (v0.2 — simplicity first, speed later):
 * - **JPEG quality 70, max 10 fps, longer side capped at 1080 px.**
 *   A 1080p phone frame at q70 is roughly 50–150 KB, so ~1–2 Mbps at
 *   10 fps — comfortable on any Wi-Fi, and far more pixels than the
 *   240×480 dp view on the Quest can show. Software JPEG encoding at
 *   this size is cheap on a modern phone.
 * - **Encode only while a client is connected** ([hasClients] callback),
 *   so the phone isn't burning battery encoding frames nobody watches.
 * - [ImageReader] with `maxImages = 2` and `acquireLatestImage()`:
 *   stale frames are dropped instead of queueing up latency.
 * - Capture runs on its own [HandlerThread]; nothing here touches the
 *   main thread.
 *
 * Known limitations (see README):
 * - The capture size is fixed when streaming starts. If the phone
 *   rotates, the virtual display keeps its original dimensions and the
 *   image may be letterboxed/cropped until streaming is restarted.
 * - Secure/DRM surfaces (e.g. some video apps) render black in a
 *   MediaProjection capture — that is Android's FLAG_SECURE behaviour,
 *   not a bug in this app.
 */
class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    private val frameHub: FrameHub,
    private val hasClients: () -> Boolean,
    /** Longer side of the capture, in pixels. 1080 keeps frames small. */
    private val maxLongSide: Int = 1080,
    /** JPEG quality 0–100. 70 is a good size/quality trade-off. */
    private val jpegQuality: Int = 70,
    /** Minimum time between encoded frames (10 fps). */
    private val minFrameIntervalMs: Long = 100L,
) {
    companion object {
        private const val TAG = "QPM-Capturer"
    }

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var running = false
    private var lastEncodeMs = 0L

    /**
     * Starts capture at [width]×[height] display pixels with the given
     * [densityDpi]. The capture is scaled down so its longer side is at
     * most [maxLongSide] px.
     */
    fun start(width: Int, height: Int, densityDpi: Int) {
        if (running) return
        running = true

        val scale = minOf(1f, maxLongSide / maxOf(width, height).toFloat())
        // Keep dimensions even — some decoders dislike odd sizes.
        val captureWidth = ((width * scale).toInt() / 2) * 2
        val captureHeight = ((height * scale).toInt() / 2) * 2
        Log.i(TAG, "Starting capture ${width}x$height -> ${captureWidth}x${captureHeight}")

        val thread = HandlerThread("qpm-capture").apply { start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(
            captureWidth, captureHeight, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(reader) }, captureHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "Quest Phone Monitor",
            captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null
        )
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        frameHub.clear()
        Log.i(TAG, "Capture stopped")
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (!running) return
        // Pace the encoder: at most one frame per minFrameIntervalMs.
        val now = System.currentTimeMillis()
        if (now - lastEncodeMs < minFrameIntervalMs) {
            // Drain the stale image so the reader doesn't stall.
            reader.acquireLatestImage()?.close()
            return
        }
        // Don't spend CPU encoding when nobody is watching.
        if (!hasClients()) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            // The buffer may have per-row padding: build a bitmap wide
            // enough to hold it, then crop to the real width.
            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(
                bitmapWidth, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (bitmapWidth != image.width) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            lastEncodeMs = now
            frameHub.publish(out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Frame encode failed", e)
        } finally {
            image.close()
        }
    }
}
