# Music Agent Standalone

An on-device SoundCloud + YouTube Music client for Android. No backend, no
account, no login screen — search, streaming, downloads, likes, and playlists
all run directly on the phone.

## Features

- **Search** across SoundCloud and YouTube Music — tracks, artists, playlists,
  and albums, per-source or combined.
- **Streaming**, on-device: SoundCloud resolves its own signed CDN URLs
  (native HLS via Media3's HLS extension); YouTube Music resolves through
  NewPipeExtractor. No server-side proxy.
- **Downloads** for offline playback (YouTube tracks only — see
  [Known limitations](#known-limitations)), plus a "Datensparmodus"
  (data-saver) setting that restricts playback to downloaded tracks only.
- **Likes & playlists**, stored locally (Room), with reordering, rename, and
  bulk-download-a-playlist.
- **Home**: a "Charts" shelf (global SoundCloud/YouTube trending, always
  populated) and a "Für dich" feed computed entirely on-device from local
  play history and likes — no server-side aggregation.
- **Artist pages**: bio (collapsed to 3 lines with a "Mehr anzeigen" toggle),
  top/latest track shelves, and a paginated follower list (SoundCloud).
- **Player**: waveform-style seek bar, shuffle/repeat, sleep timer
  (15/30/45/60/90 min presets), a 4-band equalizer (Flach/Bass-Boost/
  Höhen-Boost/Vocal) reachable from the Player screen's overflow menu, and a
  stream/download switch per track.
- **Home-screen widget** (Jetpack Glance): play/pause/skip and now-playing
  info, resizable, tap the artwork or title to open the app.
- **Optional update channel**: if pointed at a companion backend (see
  [Optional backend link](#optional-backend-link)), the app can check for and
  install new builds directly, without a Play Store listing.

## Architecture

- **UI**: Jetpack Compose, Material 3, single-Activity navigation
  (`ui/navigation/NavGraph.kt`).
- **DI**: Hilt.
- **Playback**: Media3/ExoPlayer via a `MediaSessionService`
  (`playback/PlaybackService.kt`), controlled through a single shared
  `MediaController` wrapper (`playback/PlayerController.kt`) that every
  screen — and the home-screen widget — plays through.
- **Extraction** (`data/extract/`):
  - `soundcloud/` — hand-rolled OkHttp client against SoundCloud's
    undocumented `api-v2.soundcloud.com`, mirroring the approach yt-dlp's
    SoundCloud extractor uses (client_id rotation, transcoding resolution).
    Tracks served exclusively via DRM-encrypted HLS are detected and reported
    honestly as unplayable, rather than silently failing (see
    `SoundCloudStreamResolver`).
  - `youtube/` — [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
    for search, artist channels, and stream resolution.
- **Local storage**: Room (`data/local/`) for the track cache, likes,
  playlists, and downloads; DataStore for settings.
- **Networking**: a plain OkHttp client for SoundCloud/YouTube extraction,
  kept deliberately separate from the optional backend-link client (see
  `data/extract/di/ExtractorModule.kt`) so no credentials ever leak to a
  third-party host.

## Building

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 37, `minSdk`
30).

```
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

`local.properties` needs the usual `sdk.dir`; everything else has a working
default (see [Optional backend link](#optional-backend-link) for the one
optional section).

## Optional backend link

The app works completely standalone. If you also run the companion FastAPI
backend (analytics + the update channel — not required for search, streaming,
downloads, likes, or playlists), two things need configuring:

1. In-app **Settings**: set "Backend-URL" and "API-Key" to your backend's
   address and its `API_KEY`.
2. `local.properties` (create once, never committed):
   ```
   serviceAccountEmail=you@example.com
   serviceAccountPassword=your-password
   ```
   This is a real account on your backend (created via its own
   register/invite flow) used for a silent background login — the app never
   shows a login screen. Analytics events and the update check simply no-op
   if left blank.

## Known limitations

- **SoundCloud downloads aren't supported** — SoundCloud tracks resolve to
  HLS, which needs segment fetch + remux to download properly; not built yet.
  Attempting one immediately shows "Fehlgeschlagen" rather than enqueueing
  doomed work.
- **Some SoundCloud tracks are DRM-only.** A subset of tracks (major-label
  content observed so far) are served exclusively through DRM-encrypted HLS
  with no working plain stream — the app detects this and reports it
  honestly instead of endlessly retrying; there's no DRM/Widevine license
  exchange implemented, and none is planned.
- **Very large SoundCloud playlists/albums** only play whatever tracks
  SoundCloud's API embeds inline in the initial response (typically the
  first handful) — resolving the rest would need a second batch endpoint
  with a different response shape than every other call in this app expects.
- **YouTube "Topic" channels** (auto-generated for artists without a
  manually managed channel) occasionally expose neither a "Tracks" nor
  "Videos" tab through NewPipeExtractor's channel model; the app falls back
  to whatever tab is available, logging what it found for diagnosis if it
  still comes up empty.
- **YouTube Music has no real "charts" endpoint** reachable through
  NewPipeExtractor (unlike SoundCloud's genuine trending API) — the Home
  Charts shelf's YouTube half falls back to the general "Trending" kiosk,
  filtered by a rough song-length heuristic. Real data, just less precisely
  curated than the SoundCloud half.
- **Artist name resolution is best-effort.** A track's "Zum Künstler" action
  only has an artist *name*, not an id, so it resolves via a 1-result search
  — usually right for well-known artists, not guaranteed on SoundCloud where
  duplicate-named accounts are common.

## License note

Depends on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
(GPL-3.0-or-later) for YouTube Music extraction. Distributing a build of this
app is therefore subject to GPL-3.0's copyleft terms.
