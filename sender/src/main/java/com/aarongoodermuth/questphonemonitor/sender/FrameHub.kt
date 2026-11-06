package com.aarongoodermuth.questphonemonitor.sender

/**
 * Holds the newest JPEG frame for the MJPEG server.
 *
 * The screen capturer publishes frames; each connected client waits for a
 * frame newer than the last one it sent. Clients block (with a timeout so
 * a dead connection is noticed) instead of polling.
 */
class FrameHub {

    private val lock = Object()
    private var frame: ByteArray? = null
    private var sequence: Long = 0

    /** Publish a fresh frame. Wakes every waiting client. */
    fun publish(bytes: ByteArray) {
        synchronized(lock) {
            frame = bytes
            sequence++
            lock.notifyAll()
        }
    }

    /**
     * Waits until a frame with a sequence newer than [lastSeenSequence]
     * arrives, or [timeoutMs] elapses. Returns the frame and its sequence,
     * or `null` on timeout (caller should re-check the connection).
     */
    fun awaitFrame(lastSeenSequence: Long, timeoutMs: Long): Pair<ByteArray, Long>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (sequence <= lastSeenSequence) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
            val bytes = frame ?: return null
            return bytes to sequence
        }
    }

    /** Clears the stored frame (e.g. when capture stops). */
    fun clear() {
        synchronized(lock) {
            frame = null
        }
    }
}
