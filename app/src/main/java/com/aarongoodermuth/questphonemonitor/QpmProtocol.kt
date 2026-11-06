package com.aarongoodermuth.questphonemonitor

/**
 * Wire protocol shared by the phone sender and the Quest receiver.
 *
 * Keep these values in sync with the matching file in the `sender` module
 * (`com.aarongoodermuth.questphonemonitor.sender.QpmProtocol`). They are
 * duplicated on purpose: the two modules are separate APKs with no shared
 * library, so a single source of truth would cost a third module.
 */
object QpmProtocol {
    /** mDNS service type advertised by the sender, discovered by the Quest. */
    const val SERVICE_TYPE = "_qpm._tcp"

    /** Human-readable service name. NsdManager may suffix it ("(2)", …)
     *  if another device on the network already uses it. */
    const val SERVICE_NAME = "Quest Phone Monitor"

    /** TCP port the MJPEG server listens on. */
    const val PORT = 8080

    /** MJPEG stream endpoint. Response is
     *  `multipart/x-mixed-replace; boundary=qpmframe` with one
     *  `image/jpeg` part per frame, each carrying a Content-Length. */
    const val STREAM_PATH = "/stream"

    /** Boundary string used between MJPEG parts (no leading dashes here;
     *  the wire format adds `--` in front of it). */
    const val BOUNDARY = "qpmframe"

    /** Info page endpoint — plain-text status, handy for debugging
     *  from a browser on the same network. */
    const val INFO_PATH = "/"
}
