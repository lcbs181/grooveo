# Architecture

High-level overview of how the app is put together. See the
[README](../README.md#architecture) for a shorter summary; this goes one
level deeper into the package layout.

The whole app lives in a single Gradle module, `:app`
(`dev.schlubbe.musicagent`), with no backend required for the core
experience — see [Optional backend link](../README.md#optional-backend-link)
for the one part that does talk to a server.

## Package map

```
app/src/main/java/dev/schlubbe/musicagent/
├── data/
│   ├── extract/          extraction clients (see below)
│   ├── local/             Room database: track cache, likes, playlists, downloads
│   ├── remote/            DTOs + Retrofit client for the optional backend link
│   ├── repository/        the repositories UI/ViewModels actually depend on
│   └── backup/            local export/import of likes+playlists
├── di/                    Hilt modules
├── download/              download queue/worker (WorkManager)
├── playback/              MediaSessionService, PlayerController, queue state
├── update/                background update-check worker (see UpdateRepository)
├── widget/                home-screen widget (Jetpack Glance)
└── ui/
    ├── navigation/        NavGraph, single-Activity navigation
    ├── theme/             Canopy design system: colors, type, tokens
    ├── components/        shared composables (buttons, cards, etc.)
    ├── icons/              Phosphor icon lookup
    ├── home/, search/, library/, playlist/, artist/, player/,
    │   account/, settings/, onboarding/, auth/, update/, whatsnew/
    │                       one package per screen/feature area
    └── util/               small Compose/UI helpers
```

## Extraction (`data/extract/`)

The app talks to SoundCloud and YouTube Music directly from the device — no
server-side proxy for search or streaming.

- **`soundcloud/`** — a hand-rolled OkHttp client against SoundCloud's
  undocumented `api-v2.soundcloud.com`, following the same approach yt-dlp's
  SoundCloud extractor uses (client_id rotation, transcoding resolution).
  `SoundCloudStreamResolver` detects tracks served exclusively via
  DRM-encrypted HLS and reports them as unplayable rather than silently
  failing or retrying forever.
- **`youtube/`** — [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
  for search, artist channels, and stream resolution. This is also *why* the
  project is GPL-3.0-or-later (see [`LICENSE`](../LICENSE)) — NewPipeExtractor
  is GPL-3.0, and its copyleft applies transitively to any distributed build
  of this app.

Both clients use a plain `OkHttpClient` from `data/extract/di/`, kept
deliberately separate from the backend-link client (`di/`) so no credentials
for the optional backend ever leak into an extraction request, and so the
backend's `DynamicBaseUrlInterceptor` (which rewrites request hosts to the
user's configured backend address) can't accidentally redirect an
extraction/update-check call there.

## Playback

`playback/PlaybackService.kt` is a `MediaSessionService` wrapping
Media3/ExoPlayer. Every screen — and the home-screen widget — talks to
playback through a single shared `MediaController` wrapper,
`playback/PlayerController.kt`, rather than holding its own player instance.
This is what keeps playback state (now-playing, queue, position) consistent
across the whole app and the widget without manual synchronization.

## Visualizer

The Player screen's five audio-reactive visualizers (`ui/player/Visualizer.kt`) and the
pulse variant's window-level confetti (`ui/player/AudioConfetti.kt`) are driven by real
FFT data tapped from the audio ExoPlayer is actually decoding — not a decorative
animation.

**Why not `android.media.audiofx.Visualizer`:** that platform effect requires the
`RECORD_AUDIO` permission for *any* session, not just the mix output. Asking a music
player for microphone access to draw an overlay is a bad trade, so this app taps the
PCM pipeline directly instead, via a Media3 `TeeAudioProcessor` spliced into the audio
sink (`playback/PlaybackService.kt`) — no extra permission required.

The pipeline, in `playback/AudioVisualizerController.kt` and
`playback/visualizer/PcmRingBuffer.kt`:

1. **Audio thread** — `TeeAudioProcessor.AudioBufferSink.handleBuffer` decodes each
   buffer to mono float and copies it into a lock-free single-producer/single-consumer
   ring (`PcmRingBuffer`). Nothing else runs here: no transform, no allocation, no
   lock — that thread has a hard real-time deadline, and anything blocking it risks an
   audible glitch.
2. **Analysis thread** — a dedicated daemon thread drains the ring and runs a
   2048-point FFT, reduced to 256 log-spaced frequency bands, an adaptive per-band
   normalizer, and a time-domain kick detector (a cascaded low-pass + fast/slow
   envelope-rise trigger — chosen over spectral flux because a 46ms FFT window is far
   longer than a kick's ~10ms attack and smears the transient past recovery). The
   result — spectrum plus bass/onset/level scalars — publishes as a `VisualizerFrame`.
3. **Frame queue** — spectra are produced in audio-time bursts (however the audio sink
   happens to buffer) but consumed once per display frame, through a small queue
   (`pumpNextFrame`) that a `PlaybackService` coroutine drains at ~60Hz. Analysis and
   display rate are deliberately decoupled by this queue.

Each `Visualizer.kt` variant reads the published `VisualizerFrame` in a `Canvas` draw
lambda. The particle sphere maps every point to its own frequency band (a Fibonacci
lattice, so index is monotonic in latitude — bass at one pole, treble at the other) and
batches its draw calls by colour/brightness bucket into a handful of native
`drawPoints` calls, rather than one `drawCircle` per point, to stay under one
display-frame's time budget at high point counts.

## Local storage

Room (`data/local/`) backs the track metadata cache, likes, playlists, and
download records. DataStore backs user settings (backend URL/API key,
Datensparmodus, equalizer preset, etc.).

## Update channel

`update/UpdateCheckWorker.kt` + `data/repository/UpdateRepository.kt` poll a
GitHub repo's Releases API directly (unauthenticated — see the doc comment
on `UpdateRepository` for why it needs to be a *public* repo) and can
download + launch the installer for a newer APK, without a Play Store
listing. See [`RELEASING.md`](RELEASING.md) for how a release actually gets
cut.

## Design system

`ui/theme/` implements "Canopy" — the forest-green/coral design system (both
light and dark palettes) used throughout the app (and the widget, via
duplicated plain `Color` constants in `widget/PlaybackWidget.kt`, since
Glance widgets can't reference Compose's `MaterialTheme`). Icons come from
Phosphor Icons (`ui/icons/`, `com.adamglin:phosphor-icon`).
