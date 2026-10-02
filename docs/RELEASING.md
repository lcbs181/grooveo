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

A release carries the Android APK, the Windows build (MSI installer and
portable zip), the Linux build (`.deb` and portable `tar.gz`) and the source
tarball. The full checklist, including the release-notes template, is the
Claude Code skill [`.claude/skills/grooveo-release/SKILL.md`](../.claude/skills/grooveo-release/SKILL.md).
In short:

1. Bump `grooveo.versionCode` and `grooveo.versionName` in `gradle.properties`
   (both apps read them). `versionCode` must increase: the Android updater
   compares it, parsed from the tag `v<versionCode>`. The desktop updater
   compares the release **title**, which must be the `versionName`.
2. Build the APK locally with `./gradlew :app:assembleRelease`. The `release`
   build type is signed with this machine's debug keystore
   (`~/.android/debug.keystore`) so it installs as an in-place update over the
   earlier releases. Build releases on the same machine, or copy that keystore -
   a different key means users must uninstall first. Never ship
   `assembleDebug`: a debuggable APK runs largely in the ART interpreter and was
   measured at ~3x slower cold start with heavy UI jank.
3. Create the release as a **draft** with the APK (the only `.apk` asset) and
   `git archive` source tarball, tag `v<versionCode>`, title `<versionName>`.
4. Run `gh workflow run release.yml -f tag=v<versionCode>`: it builds the
   Windows and Linux packages on GitHub runners and uploads them to the draft.
5. Check that all six assets are `uploaded`, then publish the draft. Both
   updaters read `releases/latest`, so publish only once everything is there.

## How devices pick it up

On Android, the update check happens via `UpdateCheckWorker` (periodic) or
whenever the update dialog is triggered manually — it hits
`https://api.github.com/repos/lcbs181/grooveo/releases/latest`
unauthenticated, compares the tag's version code against the installed
app's, and offers the newer APK's `browser_download_url` if there is one.
The desktop app polls the same endpoint and compares the release title with
its own version (`desktop/.../data/UpdateChecker.kt`).
