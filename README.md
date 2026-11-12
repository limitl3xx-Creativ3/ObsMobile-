# OBS Mobile (v0.1)

An "OBS Studio Virtual Camera" concept for Android — capture **any app's screen**
(Chrome, YouTube, a game…) with MediaProjection, and stream it to any other
device or app over **RTSP** on your Wi-Fi. No root required.

## What works in v0.1
- Foreground service capturing the screen (MediaProjection)
- Hardware H.264 encoding (MediaCodec → Annex-B)
- On-device RTSP server (RTP/AVP over TCP, RFC 6184 FU-A packetization)
- VLC / ffplay / OBS "Media Source" consume the feed:
  `rtsp://<phone-ip>:8554/live`

## How to use
1. Build & install the APK (Android Studio or CI — see Building).
2. Tap **Start capture** → grant the screen-capture prompt.
3. Press **Home/Recents** and open the app you want to share (Chrome, etc.).
   The capture mirrors whatever is on screen.
4. On a PC on the same Wi-Fi:
   `ffplay -rtsp_transport tcp rtsp://<phone-ip>:8554/live`
   or in OBS: **Sources → Media Source → untick local file → paste URL**.
   In OBS you can then use **Start Virtual Camera** so Zoom/Discord see it.

## Building
The Gradle project is ready; build on a PC (Android Studio) or via CI:
```
./gradlew :app:assembleDebug
```
(Termux alone can't easily host the full Android SDK.)

## Roadmap
- v0.2: Camera2 source + scene switcher (screen / camera / image), PiP overlay output
- v0.3: RTMP output (stream to YouTube/Twitch), audio mixing
- v0.4: Scene compositor (OpenGL) — camera PiP on top of screen share
- v0.5 (optional): PC companion that auto-registers as an OBS source

## Known limitations (by design of Android)
- Android has **no public API** to appear as a system camera for other apps —
  the RTSP → OBS "Media Source → Start Virtual Camera" path is the intended
  bridge to Zoom/Discord/etc.
