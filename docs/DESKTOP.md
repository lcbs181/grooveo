# Grooveo Desktop

`:desktop` is a Compose Multiplatform (JVM) build of Grooveo for Linux and
Windows. It has
the same features as the Android app, plus a few that only make sense on a
desktop.

## Features

* **Screens**: Start (shelves, mixes, daily pick), Suche, Bibliothek
  (Favoriten, Playlists, Folge ich, Verlauf), artist pages, local and remote
  playlists, Downloads, Einstellungen, Profil and Onboarding. The UI uses the
  Android app's Canopy design system.
* **Player**: a full-window player with five audio-reactive visualizers,
  synced lyrics (LRCLIB), and a queue panel that supports drag-and-drop
  reordering.
* **Playback**: shuffle and repeat, recommendation radio when the queue runs
  out, a sleep timer, gapless playback, and crossfade at analysed mix points.
* **Sound**: a parametric equalizer (see below) and 3D-Sound convolution
  reverb.
* **Desktop extras**:
  - right-click menus everywhere;
  - keyboard shortcuts;
  - a tray icon, with an option to minimise to the tray;
  - MPRIS media controls (Linux);
  - notifications for new uploads from artists you follow;
  - backup files you can move between Android and desktop, plus a weekly
    automatic backup.

## Requirements

You need JDK 21 to build. The packaged app bundles its own runtime. The
Android SDK is optional: without one, Gradle leaves out `:app` and builds only
the desktop app (it compiles the shared sources straight from `app/src`).

## Running

```
JAVA_HOME=/path/to/jdk-21 ./gradlew :desktop:run
```

To build a self-contained package (it includes its own Java runtime):

```
./gradlew :desktop:createDistributable  # portable folder, bin/Grooveo
./gradlew :desktop:packageDeb           # or packageRpm / packageAppImage (Linux), packageMsi (Windows)
```

The package lands in `desktop/build/compose/binaries/main/`. jpackage cannot
cross-package, so build Windows packages on Windows (the release workflow
does this on a GitHub Windows runner). The FFmpeg natives are picked for the
building OS (`nativePlatform` in `desktop/build.gradle.kts`). Nothing needs to
be installed on the system. Decoding and resampling use the bundled FFmpeg
libraries (JavaCV). The equalizer, limiter and reverb are pure Kotlin.

## Architecture

```
desktop/src/main/kotlin/
├── android/…                    stand-ins for android.util.Log / android.net.Uri
├── dev/schlubbe/musicagent/
│   ├── data/…                   desktop implementations of the Android types the
│   │                            shared sources depend on (SettingsRepository,
│   │                            LikesRepository, TrackDao/DownloadDao)
│   └── desktop/
│       ├── audio/               playback engine (see below)
│       ├── data/                LibraryStore (library.json), downloads, backup, updates
│       ├── playback/            PlayerController: queue, radio, sleep timer, crossfade policy
│       ├── mpris/               MPRIS2 D-Bus server (media keys, desktop media widgets)
│       └── ui/                  Canopy theme, shell, screens
```

### Sharing code with the Android app

The extraction clients (SoundCloud, YouTube Music via NewPipeExtractor) and
their HTTP client, the search, feed, lyrics, backup models, the convolution
reverb and the parametric equalizer are **not copied**. `desktop/build.gradle.kts` (`sharedFiles`) compiles those files
straight from `app/src/main/java`. The few Android types they reference
(`android.util.Log`, Room DAOs, the DataStore-backed `SettingsRepository`)
have desktop implementations with the same fully-qualified names. A fix in
the extractor or in the recommendation logic therefore lands in both apps.
If you add an Android-only import to one of those files, the desktop build
fails.

### Audio engine

```
FfmpegDeck A ─┐                               ┌─ SpectrumAnalyzer (visualizer, EQ overlay)
              ├─ mixer ─ EqProcessor ─ 3D-Sound ─ volume ─ soft clip ─ JavaSoundSink
FfmpegDeck B ─┘  (gapless hand-over,
                  equal-power crossfade)
```

* **Decks** (`FfmpegDeck`): libavformat/libavcodec through JavaCV decode
  HTTP(S), HLS, Opus, AAC, MP3 and FLAC. The output is resampled to
  48 kHz s16 stereo. Each deck has a bounded ring buffer, so it only decodes
  as fast as the mixer reads.
* **Parametric equalizer** (`app/.../playback/eq/`: `ParametricEq.kt`,
  `MatchedBiquad.kt`, `EqProcessor.kt`, shared with the Android app):
  the design follows the open-source state of the art, adapted for
  in-process use.
  * **Filter design**: Martin Vicanek's analog-matched biquads ("Matched
    Second Order Digital Filters", 2016), the method behind the open-source
    EQs OnlyEQ and CAGEq, and comparable to LSP's matched-transform mode
    (LSP is the EQ used by EasyEffects). Poles use impulse invariance. The
    numerator is matched to the analog prototype at DC, at the centre
    frequency and at Nyquist, and bells use Vicanek's exact peak fit. Unlike
    the RBJ bilinear transform, bells and shelves do not cramp towards
    20 kHz. The RBJ design remains as a fallback.
  * **Processing**: double precision, Direct Form I. Float biquads, as used
    by FFmpeg's auto precision, add audible noise and error below 100 Hz.
    Parameter changes interpolate the coefficients per sample, and layout
    changes crossfade, so there are no clicks (the approach of x42 fil4).
  * **Bass**:
    - a 24 dB/oct subsonic high-pass at 20 Hz;
    - a psychoacoustic bass enhancer (harmonics of the sub band, as in the
      Calf and LSP bass enhancers);
    - ISO 226 loudness compensation that follows the volume;
    - a stereo-linked look-ahead limiter (5 ms look-ahead, 50 ms hold,
      200 ms release). It never follows single bass cycles, measured below
      0.2 % THD at 10 dB gain reduction on 50 Hz.
  * **Dynamics** (`DynamicEqBand`): "Dynamischer Bass" is a low shelf
    whose lift follows the spectral balance (energy below 150 Hz against the
    full band), short-term and over ~10 s. Thin mixes are filled up towards
    -3 dB; bass-heavy tracks, which measure -3..-0.5 dB, get nothing, also in
    their breaks. "Schärfe zähmen" is a 4.5 kHz bell that dips only while
    2.5-8 kHz rises 3 dB above the track's own average (like a de-esser).
    Both thresholds were calibrated on real tracks across genres.
  * **Loudness normalisation** (`Loudness.kt`): ITU-R BS.1770-4 integrated
    loudness (K-weighting, gated 400 ms blocks), target -10 LUFS, at most
    +8 dB boost (the limiter then stays on). A track's measurement is stored
    in `loudness.json`, so a track heard before is levelled from its first
    sample; unknown tracks start at the previous track's gain and glide to
    their measured value. On the desktop each deck has its own normaliser,
    so both tracks of a crossfade are levelled on their own.
  * **Presets**: bass presets lift the low shelf and the sub band and cut the
    250 Hz "mud" range, so bass sounds full rather than boomy. Every preset
    gets an automatic preamp for headroom.
  * **Profiles**: you can import and export the Equalizer APO / AutoEQ
    `ParametricEQ.txt` format.
* **Streaming**: stream URLs come from the same resolver as on Android
  (`StreamResolverRegistry.resolveWithFallback`: downloaded copy first, then
  the source, then the same recording on YouTube Music); downloads use it too.
  If a stream breaks during playback (a network drop, or an expired signed
  URL in a preloaded next track), the player resolves a fresh URL and resumes
  at the same position, up to two times, like the Android player's retries.
* **Crossfade**: when "Übergänge analysieren" has analysed a track, the fade
  starts at that track's mix-out point and the next track starts at its
  mix-in point. Without an analysis, the fade covers the last *n* seconds.
  The analysis works on streams too, not only on downloads.

Earlier prototypes used mpv over IPC (about 3 s of EQ latency, because mpv
buffers that much audio when it writes PCM to a pipe) and then FFmpeg
libavfilter biquads (single precision, bilinear cramping). The current chain
runs in-process with one block (21 ms) of latency.

## Data

Everything lives in `$XDG_DATA_HOME/grooveo` (`~/.local/share/grooveo`), on
Windows in `%APPDATA%\Grooveo`:

| File | Contents |
|---|---|
| `library.json` | likes, playlists, follows, saved playlists, history, searches, downloads, analyses, listening stats |
| `settings.json` | settings including the EQ profile and your saved EQ presets |
| `downloads/` | offline files (the location is configurable) |
| `backups/` | backups in the Android backup format (they work in both directions) |

The JSON files from the first desktop prototype (`liked.json`,
`history.json`, …) are migrated automatically.

## Keyboard shortcuts

| Key | Action |
|---|---|
| Space | Play / pause |
| ← / → | Seek ±5 s |
| Ctrl+← / Ctrl+→ | Previous / next track |
| Ctrl+↑ / Ctrl+↓ | Volume |
| Ctrl+F | Search |
| Ctrl+L | Like |
| Ctrl+S / Ctrl+R | Shuffle / repeat |
| Ctrl+E | Equalizer |
| Alt+← | Back |
| Esc | Close the player |

Media keys work through MPRIS (`org.mpris.MediaPlayer2.grooveo`), for
example `playerctl -p grooveo play-pause`.

## Tests

```
./gradlew :desktop:test
GROOVEO_NETWORK_TESTS=1 ./gradlew :desktop:test --tests '*NetworkSmokeTest*'   # live SoundCloud/YouTube
GROOVEO_SCREENSHOTS=/tmp/shots GROOVEO_ROUTES=home,eq,search,settings \
  ./gradlew :desktop:test --tests '*ScreenshotRender*'                          # offscreen PNGs of screens
```

The suite covers the following:

* The EQ design against the analog prototypes, and the processor output
  against the curve shown in the UI.
* Click-free parameter changes, limiter distortion, and the harmonics added by
  the bass enhancer.
* The decoder: MP3, Opus, M4A and FLAC, plus seeking.
* The mixer: gapless hand-over and crossfade.
* Queue, radio, shuffle and repeat.
* The library store, including migration from the first prototype's files.
* Backup round trip, including importing a backup made on Android.
* Downloads with HTTP Range resume, against a local server.
* MPRIS mapping.
* Pure logic behind the screens.

Network tests and screenshots are opt-in, because they need live services
or a display-capable Skia.
