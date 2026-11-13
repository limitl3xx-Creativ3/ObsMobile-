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

## Control API (new in v0.2)
While streaming, the app exposes:

**Wi-Fi REST API** — `http://<phone-ip>:8081`
- `GET  /api/status` — state + endpoint URLs
- `POST /api/capture/stop` — stop capture
- `POST /api/switch` — scene switching (v0.2 sources)
- `GET/POST /api/custom` — list/add **custom APIs** (any HTTP endpoint becomes a callable action):
  ```json
  POST /api/custom
  {"name":"lights-on","method":"POST","url":"http://192.168.1.50/api/light/on","body":"{}"}
  ```
- `POST /api/custom/lights-on/call` — execute it (add `?key=value` for `{{key}}` substitution)
- `DELETE /api/custom/<name>` — remove it

**Bluetooth (SPP)** — pair any device (PC, macro pad, Arduino, second phone), open a
serial connection to the "ObsMobile" service, send line commands:
`PING` · `STATUS` · `LIST` · `CALL lights-on` · `STOP`

So other apps/devices — or your own automations (Tasker, macro pads, smart-home
webhooks) — can drive and extend OBS Mobile.

## Building
The Gradle project is ready; build on a PC (Android Studio) or via CI:
```
./gradlew :app:assembleDebug
```
(Termux alone can't easily host the full Android SDK.)

## Roadmap
- v0.3: Camera2 source + scene switcher (screen / camera / image), PiP overlay output
- v0.4: RTMP output (stream to YouTube/Twitch), audio mixing
- v0.5: Scene compositor (OpenGL) — camera PiP on top of screen share
- v0.6 (optional): PC companion that auto-registers as an OBS source

## Known limitations (by design of Android)
- Android has **no public API** to appear as a system camera for other apps —
  the RTSP → OBS "Media Source → Start Virtual Camera" path is the intended
  bridge to Zoom/Discord/etc.
