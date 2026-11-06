# Quest Phone Monitor

A phone-screen monitor for **Meta Quest 3** — your phone as a big, resizable
floating screen inside the headset.

**Status: v0.1 Hello World.** This build proves the pipeline end to end:
a native Android 2D panel app, built for Quest 3, sideloaded with `adb install`.
It shows device info and the placeholder where your phone screen will appear.
No streaming yet — that's the next step.

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
  src/main/java/.../MainActivity.kt
.github/workflows/build-apk.yml  CI: builds the debug APK on every push
phone-sender/                 (planned) Android phone app: MediaProjection
                              screen capture + local stream server
docs/                         (planned) protocol + wired-mode notes
```

## Build

### Option A — GitHub Actions (no Android Studio needed)

Push to `main`. The `build-apk` workflow builds the debug APK and uploads
it as an artifact named `quest-phone-monitor-debug-apk`. Download it from
the Actions run, then install as below.

### Option B — local (Android Studio or command line)

1. Install Android Studio (or JDK 17 + Android SDK command-line tools,
   platform-tools, platforms `android-34`, build-tools `34.0.0`).
2. `export ANDROID_HOME=~/Android/Sdk` (or wherever your SDK lives).
3. Open the project in Android Studio and build, or from the command line:
   `gradle wrapper --gradle-version 8.7` once (generates the wrapper jar),
   then `./gradlew assembleDebug`
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

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

## Roadmap

- [x] v0.1 — Hello World 2D panel, Quest 3 manifest, CI APK build
- [ ] v0.2 — Phone sender app (MediaProjection capture, MJPEG over Wi-Fi)
- [ ] v0.2 — Quest receiver plays the MJPEG stream in the phone-shaped view
- [ ] v0.3 — Wired mode: `adb reverse` / USB tethering, no Wi-Fi needed
- [ ] v0.4 — Low-latency WebRTC, quality / orientation controls
- [ ] Later — optional Unity/OpenXR receiver: curved screen, passthrough,
      hand-tracked resize

## Requirements

- Meta Quest 3 (target), Quest 2 / Pro / 3S should also install
- Developer Mode on (see above)
- Android phone for the sender (planned)
- minSdk 29 (Android 10), targetSdk 34, ARM64 only
