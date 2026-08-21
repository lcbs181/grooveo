# Development setup

## Requirements

- JDK 17
- Android SDK: `compileSdk` / `targetSdk` 37, `minSdk` 30 (installed
  automatically by Android Studio, or via `sdkmanager` if you're building
  from the command line only)
- Android Studio is not required, but is the easiest way to get the SDK,
  an emulator, and Compose previews set up.

## First-time setup

1. Clone the repo and open the `android/` directory as the project root in
   Android Studio (this is the Gradle root — `settings.gradle.kts` lives
   here).
2. Let Android Studio generate `local.properties` (it sets `sdk.dir`
   automatically). If you're building from the CLI only, create it
   yourself:
   ```
   sdk.dir=/path/to/Android/sdk
   ```
3. Build:
   ```
   ./gradlew :app:assembleDebug
   ```
   The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

That's it for the standalone experience (search, streaming, downloads,
likes, playlists, widget) — no backend, no account, no extra config.

## Running on a device/emulator

```
./gradlew :app:installDebug
```

or use Android Studio's Run button with a connected device/emulator
(`minSdk` 30, so anything Android 11+).

## Optional: the backend link

The app works completely standalone. The only feature that needs a server is
the analytics/update-channel integration with the companion FastAPI backend
(a separate part of this project — see its own README). If you're working on
that path specifically:

1. In-app **Settings**: set "Backend-URL" and "API-Key" to point at your
   backend instance.
2. Add to `local.properties` (never committed):
   ```
   serviceAccountEmail=you@example.com
   serviceAccountPassword=your-password
   ```
   This is a real account on the backend (created via its own
   register/invite flow), used for a silent background login — the app
   never shows a login screen for this. Leaving these blank simply no-ops
   analytics and the (legacy) backend-mediated update path; it does not
   affect the GitHub-based update check described in
   [`RELEASING.md`](RELEASING.md).

## Testing your change

There's no unit/instrumented test suite for the app module yet. The current
bar for a PR is:

1. `./gradlew :app:assembleDebug` succeeds.
2. You've actually run the change on a device or emulator and exercised the
   relevant screen(s) — including any edge cases the change affects (empty
   states, errors, etc.).

See [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the rest of the PR checklist,
including the What's New entry requirement for user-visible changes.

## Where things live

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the package map.
