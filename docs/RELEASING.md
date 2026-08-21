# Releasing (maintainers)

The app checks a GitHub repo's Releases API directly for updates — see
`data/repository/UpdateRepository.kt`. That only works unauthenticated
(no token embedded in the APK) if the repo is **public**, which is why this
lived on a separate `lcbs181/music-agent-releases` repo back when the source
repo was private.

As of the source repo going public, releases are cut from **this repo**
(`lcbs181/music-agent-standalone`) instead — one fewer repo to keep in sync,
and the update-check code has been repointed accordingly
(`RELEASES_OWNER`/`RELEASES_REPO` in `UpdateRepository.kt`).

> Devices already running a build that still points at the old
> `music-agent-releases` repo won't see any release published here — that's
> an inherent one-time discontinuity of the switch, not something a release
> note can fix retroactively. It only affects builds older than the one that
> made this switch.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
   `versionCode` must increase — it's what the update checker compares
   against.
2. Build the release APK:
   ```
   ./gradlew :app:assembleDebug
   ```
   (There's no signed release build configured yet — `assembleDebug` is what
   gets distributed today; see `buildTypes.release.isMinifyEnabled = false`
   in `app/build.gradle.kts`.)
3. Create the GitHub release, with a tag of the form `v<versionCode>`
   (e.g. `v14`) — the update checker parses the version code back out of the
   tag name, so this format is load-bearing:
   ```
   gh release create v<versionCode> app/build/outputs/apk/debug/app-debug.apk \
     --repo lcbs181/music-agent-standalone \
     --title "<versionName>" \
     --notes "<what changed, in German, matching the in-app What's New tone>"
   ```
4. Verify it actually landed:
   ```
   gh release view v<versionCode> --repo lcbs181/music-agent-standalone --json assets,isDraft,url
   ```
   Check `isDraft: false` and that the asset's `state` is `"uploaded"`.
   Large APKs can take a while to upload — if `gh release create` seems to
   hang, give it time rather than re-running it.
5. On-device, the update check happens via `UpdateCheckWorker` (periodic) or
   whenever the update dialog is triggered manually — it hits
   `https://api.github.com/repos/lcbs181/music-agent-standalone/releases/latest`
   unauthenticated, compares the tag's version code against the installed
   app's, and offers the newer APK's `browser_download_url` if there is one.
