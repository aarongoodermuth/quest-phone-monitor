package com.aarongoodermuth.questphonemonitor.sender

/**
 * Wire protocol shared by the phone sender and the Quest receiver.
 *
 * Keep these values in sync with the matching file in the `app` module
 * (`com.aarongoodermuth.questphonemonitor.QpmProtocol`). They are
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
    const val INFO_PATH = "/info"

    /** Interactive viewer page (HTML): the stream plus click/drag
     *  control from a browser. This is the URL the sender shows. */
    const val VIEW_PATH = "/"

    /** Control endpoint. `POST /control` with a JSON body:
     *  - `{"type":"tap","x":<px>,"y":<px>}` — tap at phone-screen pixels
     *    (the captured frame's pixel space).
     *  - `{"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":..}`
     *  - `{"type":"key","key":"back"|"home"|"recents"}`
     *  Responds `{"ok":true}` or `{"ok":false,"error":"..."}`.
     *  No auth: same trust level as the stream itself — anyone on the
     *  local Wi-Fi can already watch the screen. */
    const val CONTROL_PATH = "/control"
}
