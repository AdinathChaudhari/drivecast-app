# drivecast for Fire TV

A native Android TV / Amazon Fire TV client for a
[drivecast](https://github.com/AdinathChaudhari/drivecast) media server. It's a pure HTTP client: it browses your server's library, plays video over the
server's range-aware stream endpoint, tracks watch progress, resumes where you left off,
and autoplays the next episode — all from your couch with the remote.

Built with Kotlin, Jetpack Compose for TV (`androidx.tv`), and Media3 / ExoPlayer.

## What it does

- **Setup / pairing** — scans your local network for the server, or lets you type its IP,
  then validates the access token.
- **Home** — a "Continue Watching" shelf (with progress bars and a dismiss action) plus one
  shelf per library section, with posters.
- **Detail** — movie play / start-over; for shows, season tabs and an episode list with
  watched and in-progress markers.
- **Player** — resumes from your last position, sideloads subtitles when the server has them,
  reports progress back to the server, and offers a cancelable "Up next" autoplay.

## Requirements

- A running drivecast server with **Remote Access enabled** (Settings → Remote Access in
  drivecast). That screen shows the server's access **token** — you'll need it to pair.
- The Fire TV and the server on the **same local network** (for auto-discovery; you can also
  type the server's IP manually).

## Pairing flow

1. Launch the app. It scans your `/24` network on port `8737` for the server.
2. Pick the discovered server, or choose **enter the server IP** and type it (e.g.
   `192.168.1.50`).
3. Enter the **access token** from the drivecast Remote Access screen and press **Pair**.
   - "Remote access is off" → turn on Remote Access in drivecast, then retry.
   - "Wrong token" → re-check the token in drivecast.
4. On success the pairing is saved and you land on Home. It persists across launches.

## Installing on a Fire TV Stick

### 1. Enable developer options on the Fire TV

1. **Settings → My Fire TV → About** and click the device name (e.g. "Fire TV Stick") seven
   times until it says you're a developer.
2. Back out to **Settings → My Fire TV → Developer options** and turn on:
   - **ADB debugging** → On
   - **Apps from Unknown Sources** → On (needed for the Downloader method below)
3. Note the device's IP under **Settings → My Fire TV → About → Network**.

### 2a. Install over ADB (recommended for development)

From a machine with the Android SDK / `adb` installed, on the same network:

```bash
# Connect to the Fire TV (accept the on-screen "Allow USB debugging" prompt the first time)
adb connect <fire-tv-ip>:5555

# Build and install the debug APK in one step
./gradlew installDebug

# …or install a prebuilt APK directly
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears under **Your Apps & Channels** on the Fire TV home screen. Launch it with the
remote.

To build the APK yourself:

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### 2b. Sideload with the Downloader app (no computer needed)

1. On the Fire TV, install **Downloader** from the Amazon Appstore.
2. Get the APK somewhere the Fire TV can reach over HTTP: grab it from this repo's
   [Releases page](https://github.com/AdinathChaudhari/drivecast-app/releases) if one is
   published, or host your own build (any local web server works — e.g. from the repo:
   `python3 -m http.server 8000 -d app/build/outputs/apk/debug/` and use
   `http://<your-computer-ip>:8000/app-debug.apk`).
3. Open Downloader, enter the APK's URL, and let it download.
4. Choose **Install** when prompted. (This is why "Apps from Unknown Sources" must be on.)

## Building from source

Requirements: JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0). Create a
`local.properties` with your SDK path:

```
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:assembleDebug
```

## Project layout

- `app/src/main/java/com/drivecast/tv/`
  - `api/` — Retrofit interface, DTOs, token interceptor
  - `data/` — DataStore config, library repository, LAN discovery
  - `ui/setup` · `ui/home` · `ui/detail` · `ui/player` · `ui/common` · `ui/theme`
  - `di/AppContainer.kt` — manual dependency container
  - `MainActivity.kt` — Compose navigation host

## Notes

- The app talks to the server over plain HTTP on the LAN (`usesCleartextTraffic`). Keep the
  server on a trusted network.
- The access token is sent as a `?token=` query parameter on every request, matching the
  server's remote-access contract.
