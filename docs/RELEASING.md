# Releasing (maintainers)

The app checks a GitHub repo's Releases API directly for updates — see
`data/repository/UpdateRepository.kt`. That only works unauthenticated
(no token embedded in the APK) if the repo is **public**, which is why this
lived on a separate `lcbs181/music-agent-releases` repo back when the source
repo was private.

As of the source repo going public, releases are cut from **this repo**
(`lcbs181/grooveo`) instead — one fewer repo to keep in sync,
and the update-check code has been repointed accordingly
(`RELEASES_OWNER`/`RELEASES_REPO` in `UpdateRepository.kt`).

> Devices already running a build that still points at the old
> `music-agent-releases` repo won't see any release published here — that's
> an inherent one-time discontinuity of the switch, not something a release
> note can fix retroactively. It only affects builds older than the one that
> made this switch.

## Cutting a release

A release carries both Android APKs (`Grooveo-<version>.apk` and
`DawnMusic-<version>.apk`), the Windows build (MSI installer and
portable zip), the Linux build (`.deb` and portable `tar.gz`), the source
tarball and `SHA256SUMS` over all of them. The full checklist, including the release-notes template, is the
Claude Code skill [`.claude/skills/grooveo-release/SKILL.md`](../.claude/skills/grooveo-release/SKILL.md).
In short:

1. Bump `grooveo.versionCode` and `grooveo.versionName` in `gradle.properties`
   (both apps read them) and set `grooveo.testBuild=0`. `grooveo.versionCode`
   counts releases; the Android versionCode is 100 times it (release 29 is
   `2900`), and the tag is `v<Android versionCode>` (`v2900`): the Android
   updater compares that number. The desktop updater compares the release
   **title**, which must be the `versionName`.
2. Clear `ITEMS` in
   [`WhatsNewScreen.kt`](../app/src/main/java/dev/schlubbe/musicagent/ui/whatsnew/WhatsNewScreen.kt)
   down to what is new in *this* release (the full history belongs in the
   GitHub release notes). With an empty list the Home banner stays hidden.
3. Build the APKs locally with `./gradlew :app:assembleGrooveoRelease` and
   `./gradlew :app:assembleDawnRelease` (Dawn Music flavor, released as "Dawn Music"). The `release`
   build type is signed with this machine's debug keystore
   (`~/.android/debug.keystore`) so it installs as an in-place update over the
   earlier releases. Build releases on the same machine, or copy that keystore -
   a different key means users must uninstall first. Never ship
   `assembleDebug`: a debuggable APK runs largely in the ART interpreter and was
   measured at ~3x slower cold start with heavy UI jank.
4. Create the release as a **draft** with both APKs (upload `Grooveo-*.apk`
   first: updaters up to 0.7.0-test.9 take the first `.apk`; newer ones take
   the asset starting with their own `APK_PREFIX`) and the `git archive`
   source tarball, tag `v<versionCode>`, title `<versionName>`.
5. Run `gh workflow run release.yml -f tag=v<versionCode>`: it builds the
   Windows and Linux packages on GitHub runners and uploads them to the draft.
   A last job downloads all assets, writes `SHA256SUMS` and uploads it too.
6. Check that all eight assets (seven files plus `SHA256SUMS`) are `uploaded`,
   then publish the draft. Both updaters read `releases/latest`, so publish
   only once everything is there. The desktop updater verifies every download
   against `SHA256SUMS` and only offers the browser when it is missing; if an
   asset is replaced later, rerun the workflow so the sums match again.

## Test builds

Test builds (alpha versions) use no release number. With `grooveo.testBuild=N`
(1..99) the APK gets versionCode `(release - 1) * 100 + N` and the name
`<versionName>-test.N`: the test builds before release 29 are 2801, 2802, …,
and release 29 (2900) installs over all of them. Raise `grooveo.testBuild` for
each test APK and set it back to 0 for the release.

To offer a test build through the updater, publish it as a GitHub
**pre-release** with the tag `v<its versionCode>` and its versionName as title:

    gh release create v2803 dist/Grooveo-0.7.0-test.3.apk --repo lcbs181/grooveo \
      --prerelease --title "0.7.0-test.3" --notes "..."

`releases/latest` never returns a pre-release, so only devices with
"Testversionen" switched on (Einstellungen, at the bottom; on by default in a
test build) see it. The desktop app only follows releases.

## How devices pick it up

On Android, the update check happens via `UpdateCheckWorker` (periodic) or
whenever the update dialog is triggered manually — it hits
`https://api.github.com/repos/lcbs181/grooveo/releases/latest`
unauthenticated, compares the tag's version code against the installed
app's, and offers the `browser_download_url` of the APK whose name starts with
its own edition's prefix (`BuildConfig.APK_PREFIX`) if there is one.
With "Testversionen" on it reads `/releases` instead and takes the highest
version code among releases and pre-releases.
The desktop app polls the same endpoint and compares the release title with
its own version (`desktop/.../data/UpdateChecker.kt`).
