# How drivecast-app was built, and how it works

A plain-English explainer for the drivecast Fire TV client.

## The problem

[drivecast](https://github.com/AdinathChaudhari/drivecast) runs on a Mac and
streams video straight out of Google Shared Drives — a menubar server with a
web UI. That's great on a laptop or phone, but on a TV it falls short in two
ways:

1. **Navigation.** A browser UI is miserable to drive with a TV remote.
2. **Formats.** The web player can only play what the browser can decode
   (MP4/WebM family). A large share of real-world files are MKV, which the
   web UI has to hand off to external apps.

So instead of bending the web UI to fit a TV, this repo is a **native Android
TV app** — Fire OS is Android underneath — that talks to the same drivecast
server every other client uses.

## The key insight: the server already had everything

drivecast's server exposes a clean HTTP surface:

- `GET /api/library` — every movie/show as JSON (titles, seasons, episodes,
  posters, durations)
- `GET /api/continue` — the Continue Watching shelf
- `GET /api/watched-map` — per-file progress and watched flags
- `POST /api/progress` — clients report playback position; history syncs
  across every device
- `GET /stream/{file_id}` — the video itself, as a **range-aware byte relay**:
  the server forwards your player's `Range` requests straight to Google Drive
  and pipes the bytes back. No transcoding, no temp files. Seeking is just a
  new range request.

That means the TV app needed **zero video infrastructure**. It is a pure API
client: browse JSON, point a player at `/stream/{id}`, report progress.

Only two small endpoints were added to the server for the TV's sake:

- `GET /api/ping` — an unauthenticated "is a drivecast server here?" probe, so
  the app can find the Mac by scanning the local network (TVs don't have
  cameras, so QR pairing was out; this removes the worst part of manual setup).
- `GET /api/subtitles/{file_id}` — the server already knew how to find
  subtitles (local cache → a sibling `.srt` in the same Drive folder →
  OpenSubtitles), but only handed them to local players as file paths. This
  endpoint serves the same resolved file over HTTP so remote players can use
  it. No format conversion needed — ExoPlayer parses SRT/VTT/ASS natively.

## What the app is made of

| Piece | Choice | Why |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose for TV | Modern TV toolkit: focus handling, D-pad navigation, 10-foot components |
| Playback | Media3 / ExoPlayer | Plays MKV, HEVC, etc. in hardware — the whole reason a native app beats the browser |
| Networking | One shared OkHttp client + Retrofit | A single interceptor appends `?token=` to **every** request — API calls, poster images, the video stream, subtitle fetches |
| Storage | DataStore | Just two values: the server URL and the access token |

Screens: **Setup** (find server → enter token → pair) → **Home** (Continue
Watching + one row per library section) → **Detail** (movie or
season/episode list with watched checkmarks) → **Player**.

## How a play actually happens

1. You pick an episode. The app does a quick `HEAD /api/subtitles/{id}` probe
   — if subtitles exist, they're attached as a track; if not (or the lookup is
   slow), playback starts anyway. Subtitles never block video.
2. ExoPlayer opens `http://<mac>:8737/stream/{id}?token=…` and starts pulling
   bytes. The Mac relays them from Google Drive. Seeking, pausing, resuming —
   all just HTTP range requests.
3. If you'd watched part of it before, the app seeks to the saved position
   first (anything between 1% and 90% watched resumes; past 90% counts as
   finished and starts over).
4. Every 10 seconds — plus on pause, on exit, and at the end — the app POSTs
   your position to `/api/progress`. That's the same call the web player
   makes, so the Mac, your phone, and the TV all share one Continue Watching
   shelf. Dismiss a tile on the TV and it disappears on the web too.
5. When an episode ends, a 5-second "Up next" overlay counts down to the next
   episode (cancelable with the remote).
6. **Still-watching handshake.** The Mac has to be awake to relay bytes, so the
   server holds a macOS power assertion while a stream is active and, after ~2
   minutes with no bytes flowing, opens a 30-second window before letting the
   Mac sleep. Once this device has played anything, the app polls
   `GET /api/awake/status` (every 15s, tightening to 5s as the window closes,
   and only while the app is foregrounded). While ExoPlayer is actually playing
   the server stays in its "active" phase, so nothing shows; but if playback is
   paused/stopped long enough to reach the "prompt" phase, the app pops an
   **"Are you still watching?"** dialog with a live countdown. **Yes** calls
   `POST /api/awake/extend` (a fresh grace window); **No** calls
   `POST /api/awake/release` (sleep now) and backs out of the player. So a TV
   left paused doesn't pin the Mac awake, while a TV mid-movie always does.

## Security model

- The server binds to the network only when Remote Access is explicitly
  enabled in drivecast; every request (except the discovery ping, which
  reveals nothing but the app's presence) requires the access token.
- The token lives only on the device, entered once at pairing. Nothing is
  hardcoded in this repo.
- Traffic is plain HTTP on your home LAN. For watching away from home, run
  Tailscale on both the Mac and the Fire TV — the tailnet encrypts everything
  with WireGuard and gives the Mac a stable address.

## Honest limitations

- **The Mac has to be awake.** It's the streaming relay; no Mac, no video.
- **No transcoding.** The stick must hardware-decode whatever the file is.
  H.264 is universal and HEVC works on 4K sticks, but exotic audio tracks
  (DTS, TrueHD) may not decode on some models.
- **Google Drive quotas are real.** Drive limits daily downloads per file;
  when it happens the app says so plainly instead of spinning forever.

## How it was built

The project was planned and written with Claude Code: the drivecast server
was mapped first (streaming path, auth, API shapes), the architecture was
chosen against alternatives (browser-on-TV and a WebView wrapper both lose on
navigation and formats), and the server endpoints, the Android app, and the
build toolchain were then implemented and verified — the acceptance bar being
a clean `./gradlew assembleDebug` producing an installable APK.

See the [README](README.md) for install and pairing instructions.
