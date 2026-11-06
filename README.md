# Quest Phone Monitor

A phone-screen monitor for **Meta Quest 3** — your phone as a big, resizable
floating screen inside the headset.

**Status: v0.2 — live phone stream over Wi-Fi.** The phone sender app
captures the screen with MediaProjection and serves it as MJPEG on the
local network (advertised over mDNS); the Quest app discovers it
automatically and plays it live in the phone-shaped view. No internet
needed — just the same Wi-Fi network. Wired USB mode and PC remote
desktop are next.

## What it will do

- Show your phone's screen on a resizable panel in Quest 3 (grab the panel
  edge in Horizon OS to resize / move it, like any 2D app).
- **Wireless mode:** phone and Quest on the same Wi-Fi network. The phone
  streams its screen over the local network (no internet needed, just the
  same router / hotspot). Target: MJPEG first for simplicity, WebRTC later
  for low latency.
- **Wired mode:** no Wi-Fi at all. Phone connected by USB-C — either
  directly to the Quest, or phone → PC → Quest — with the stream tunnelled
  over USB via `adb reverse` / USB tethering, so it works completely offline.
- Quest 3 first (`quest3`), with Quest 2 / Pro / 3S compatibility kept where
  it costs nothing.

## Why native Android, not Unity (for now)

Horizon OS is Android (AOSP). A native 2D panel app is the lightest thing
that runs on Quest 3, installs with one `adb install`, and is already
resizable by the OS — which is exactly what this app needs first. A video
stream is just a view on a panel. If we later want a curved 3D screen,
passthrough anchoring, or hand-tracked grabbing, that is the point to move
the receiver to Unity / Godot + OpenXR, keeping the phone sender and the
stream protocol unchanged.

## Project layout

```
app/                          Quest receiver app (Kotlin, native Android)
  src/main/AndroidManifest.xml   Quest manifest: minSdk 29, targetSdk 34,
                                 supportedDevices quest2|questpro|quest3|quest3s
  src/main/java/.../MainActivity.kt        mDNS discovery + stream UI
  src/main/java/.../MjpegStreamPlayer.kt   MJPEG client
  src/main/java/.../QpmProtocol.kt         wire protocol (mirrors sender)
sender/                       Phone sender app (Kotlin, native Android)
  src/main/java/.../sender/MainActivity.kt          start/stop UI
  src/main/java/.../sender/ScreenCaptureService.kt  foreground service:
                                                    MediaProjection +
                                                    MJPEG server + mDNS
  src/main/java/.../sender/ScreenCapturer.kt        ImageReader → JPEG
  src/main/java/.../sender/MjpegServer.kt           HTTP server (port 8080)
  src/main/java/.../sender/FrameHub.kt              latest-frame holder
  src/main/java/.../sender/QpmProtocol.kt           wire protocol
  src/main/java/.../sender/NetworkUtils.kt          LAN IP lookup
.github/workflows/build-apk.yml  CI: builds both debug APKs on every push
docs/                         (planned) protocol + wired-mode notes
```

## Build

### Option A — GitHub Actions (no Android Studio needed)

Push to `main`. The `build-apk` workflow builds the debug APKs and uploads
the Quest receiver APK as an artifact named `quest-phone-monitor-debug-apk`.
Download it from the Actions run, then install as below.

> **CI note (v0.2):** `gradle assembleDebug` already builds *both* modules,
> so `sender/build/outputs/apk/debug/sender-debug.apk` is produced by every
> run — but the workflow only uploads the `app` APK. To publish the sender
> APK too, add a second `upload-artifact` step with
> `path: sender/build/outputs/apk/debug/sender-debug.apk`. Workflow files
> can only be edited via the GitHub website (the App API blocks them), so
> that step is still to do — the local workflow file is unchanged in v0.2.

### Option B — local (Android Studio or command line)

1. Install Android Studio (or JDK 17 + Android SDK command-line tools,
   platform-tools, platforms `android-34`, build-tools `34.0.0`).
2. `export ANDROID_HOME=~/Android/Sdk` (or wherever your SDK lives).
3. Open the project in Android Studio and build, or from the command line:
   `gradle wrapper --gradle-version 8.7` once (generates the wrapper jar),
   then `./gradlew assembleDebug`
4. APK: `app/build/outputs/apk/debug/app-debug.apk` (Quest receiver)
   and `sender/build/outputs/apk/debug/sender-debug.apk` (phone sender)

## Install on Quest 3

One-time setup:

1. Register as a developer (free) at <https://developer.oculus.com> —
   sign up / create an organization.
2. In the **Meta Horizon** phone app: Menu → Devices → your Quest 3 →
   Developer Mode → **ON**.
3. Connect the Quest 3 to your computer with a **USB-C data cable**
   (the charging cable in the box is charge-only and won't work).
4. Put the headset on and accept **Allow USB debugging**.

Install:

```bash
adb install -r app-debug.apk
```

Or drag the APK onto SideQuest / Meta Quest Developer Hub.

Find it in the headset under **Apps → Unknown Sources → Quest Phone Monitor**.

Resize it in Horizon OS like any 2D panel: grab the bottom bar to move it,
grab a corner to resize it.

## Using v0.2

1. Install the sender APK on your Android phone (`adb install -r sender-debug.apk`).
2. Install the receiver APK on the Quest 3 (see below).
3. Put both on the same Wi-Fi network.
4. On the phone, open **Quest Phone Monitor — Sender**, tap **Start sharing**,
   and allow screen capture in the system dialog. A notification shows the
   stream URL while sharing is on.
5. On the Quest, open **Quest Phone Monitor**. It finds the phone by itself
   and the live screen appears in the phone-shaped view. Tap the view while
   disconnected to cycle the test colors (the v0.1 fallback).

Stream details: MJPEG over HTTP, port 8080, mDNS service `_qpm._tcp`
("Quest Phone Monitor"), max 10 fps, JPEG quality 70, longer side capped
at 1080 px. Any browser on the same network can open
`http://<phone-ip>:8080/stream` to check the stream. Known limits: the
capture size is fixed when sharing starts (rotate the phone and the image
may letterbox until you restart sharing); DRM/secure video surfaces show
black (Android FLAG_SECURE); the Quest connects to the first sender it
finds if several phones share at once.

## Roadmap

- [x] v0.1 — Hello World 2D panel, Quest 3 manifest, CI APK build
- [x] v0.2 — Phone sender app (MediaProjection capture, MJPEG over Wi-Fi)
- [x] v0.2 — Quest receiver plays the MJPEG stream in the phone-shaped view
- [ ] v0.3 — Wired mode: `adb reverse` / USB tethering, no Wi-Fi needed
- [ ] v0.4 — Low-latency WebRTC, quality / orientation controls
- [ ] Later — PC remote desktop in this same app (see below)
- [ ] Later — optional Unity/OpenXR receiver: curved screen, passthrough,
      hand-tracked resize

## Remote desktop (PC) — same project, not a separate one

A remote-desktop client through the Quest belongs in **this** repo, as
another source in the same app — one receiver, one panel, a source picker:

- **Phone** — the v0.2 MJPEG stream (this build).
- **PC via Moonlight–Sunshine** — first PC mode. Moonlight is an
  established open protocol (NVIDIA GameStream-compatible): the PC runs
  Sunshine, the Quest app is the Moonlight client. Low latency, high
  quality, made for exactly this.
- **PC via VNC** — later, for PCs without Sunshine. Plain RFB/VNC is
  simpler to implement and needs no server install beyond any VNC server,
  but it is laggier than Moonlight and has no hardware video decode path.

The main difference from phone mirroring is **input back to the PC**:
a remote desktop needs mouse/keyboard (and eventually Quest controller
or hand-tracking pointer) sent to the PC, while phone mirroring is
view-only for now. That input path is the real work in the PC modes;
the panel, discovery, and stream plumbing are shared.

## Showing up as a cast target — what is and isn't possible

Aaron wants the Quest to appear as a wireless-display / cast target to
other devices on the same network. Honest limits, checked Nov 5, 2026:

- **Chromecast receiver: not feasible for an indie app.** The Cast SDK
  only supports Receiver apps for Web and Android TV, and a receiver needs
  an App ID from the Cast Developer Console on a Cast-enabled device.
  Clone "Cast receiver" APKs exist but are unreliable and don't handle
  protected content.
- **Miracast sink: not feasible from a regular Quest app.** Android's
  Miracast sink APIs were deprecated in Android 6, the `WifiDisplay` APIs
  are hidden, and the `CONFIGURE_WIFI_DISPLAY` permission is system-level
  (the app would have to be signed with the system key); a full sink needs
  AOSP patches or root.
- **What can show up on the network:**
  - **DLNA renderer** — a normal app can advertise via SSDP; Windows
    "Cast to Device" and DLNA-capable apps can push videos, photos, and
    music to it. Media files only, not a live screen mirror.
  - **Our own sender app** — the only way to get a true live phone screen
    onto the Quest, with one tap in the sender app.
  - **Moonlight client** — PCs running Sunshine show up in the app and
    stream their desktop/games to the Quest (established open protocol,
    low latency).

## Requirements

- Meta Quest 3 (target), Quest 2 / Pro / 3S should also install
- Developer Mode on (see above)
- Android phone for the sender (v0.2)
- minSdk 29 (Android 10), targetSdk 34, ARM64 only
