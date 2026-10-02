---
name: grooveo-release
description: Cut a new Grooveo release on GitHub (lcbs181/grooveo) with the Android APK, Windows and Linux desktop builds and the source tarball. Use when the user asks to release, publish or ship a new Grooveo version.
---

# Grooveo release

One release = one GitHub release on `lcbs181/grooveo` with these assets:

| Asset | Built where |
|---|---|
| `Grooveo-<version>.apk` (Android, the only `.apk`) | locally, `:app:assembleRelease` |
| `Grooveo-<version>-windows-x64.msi` + `.zip` (portable) | GitHub Actions, `release.yml` (Windows runner) |
| `Grooveo-<version>-linux-x64.tar.gz` + `.deb` | GitHub Actions, `release.yml` (Ubuntu runner) |
| `grooveo-<version>-source.tar.gz` | locally, `git archive` |

The release notes also explain how to build the Linux app from source.

## Rules that must not break

- **Tag = `v<versionCode>`** (e.g. `v22`). The Android updater parses the
  version code from the tag (`UpdateRepository.parseVersionCode`).
- **Title = `<versionName>`** (e.g. `0.6.0`). The desktop updater compares the
  release *title* with its `APP_VERSION` (`UpdateChecker`).
- **Exactly one `.apk` asset.** The Android updater downloads the first `.apk`.
- **The APK is built on this machine.** `release` is signed with
  `~/.android/debug.keystore`; any other key breaks in-place updates. Never
  upload that keystore anywhere, and never ship `assembleDebug`.
- Create the release as a **draft** and publish only after all assets are
  uploaded: both updaters read `releases/latest` and would otherwise offer a
  release without files.
- Push and publish only when the user asked for it.

## Steps

`JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1` (no system JDK on this machine).

1. **Version**: in `gradle.properties` raise `grooveo.versionCode` by 1 and set
   `grooveo.versionName` (semver; minor for features, patch for fixes). Both
   apps read them from there.
2. **Tests**:
   ```
   ./gradlew :app:testDebugUnitTest :desktop:test
   GROOVEO_NETWORK_TESTS=1 ./gradlew :desktop:test --tests '*NetworkSmokeTest*'
   ```
3. **Notes**: write the What's-New text in German, matching earlier releases
   (`gh release view v<prev> --json body`): `##` headings per feature,
   casual "du" tone, a "Kleinere Fixes" list, then the fixed footer from the
   template below. Collect changes with `git log v<prev>..HEAD --oneline`.
4. **Commit, merge, push**: commit on the working branch, fast-forward
   `master`, push both. Releases are cut from `master`.
5. **Android APK**:
   ```
   ./gradlew :app:assembleRelease
   cp app/build/outputs/apk/release/app-release.apk dist/Grooveo-<version>.apk
   ```
6. **Source tarball** (tracked files only, no `local.properties`):
   ```
   git archive --format=tar.gz --prefix=grooveo-<version>/ -o dist/grooveo-<version>-source.tar.gz master
   ```
7. **Tag and draft release**. A draft does not create its tag, but the
   workflow checks the tag out, so push the tag first:
   ```
   git tag v<code> master && git push origin v<code>
   gh release create v<code> dist/Grooveo-<version>.apk dist/grooveo-<version>-source.tar.gz \
     --repo lcbs181/grooveo --verify-tag --draft --title "<version>" --notes-file dist/notes.md
   ```
8. **Desktop builds**:
   ```
   gh workflow run release.yml --repo lcbs181/grooveo --ref master -f tag=v<code>
   gh run watch --repo lcbs181/grooveo $(gh run list --workflow release.yml --repo lcbs181/grooveo -L1 --json databaseId -q '.[0].databaseId')
   ```
   On failure: `gh run view <id> --log-failed`, fix, push, rerun.
9. **Check and publish**: `gh release view v<code> --json assets,isDraft`
   lists six assets, all `uploaded`. Then
   `gh release edit v<code> --draft=false --latest`.
10. Report the release URL to the user.

## Notes template

```markdown
## <Feature headline>

<one or two sentences, German, "du">

## Kleinere Fixes

- ...

---

### Downloads

| System | Datei |
|---|---|
| Android | `Grooveo-<version>.apk` – öffnen und installieren (Update über die alte Version) |
| Windows | `Grooveo-<version>-windows-x64.msi` (Installer) oder `…-windows-x64.zip` (ohne Installation, `Grooveo.exe` starten) |
| Linux | `Grooveo-<version>-linux-x64.deb` (Debian/Ubuntu) oder `…-linux-x64.tar.gz` (entpacken, `Grooveo/bin/Grooveo` starten) |
| Quellcode | `grooveo-<version>-source.tar.gz` |

### Linux selbst bauen

Nur JDK 21 nötig (ohne Android SDK wird die Android-App einfach übersprungen):

    tar xzf grooveo-<version>-source.tar.gz && cd grooveo-<version>
    ./gradlew :desktop:createDistributable   # App unter desktop/build/compose/binaries/main/app/Grooveo
    ./gradlew :desktop:packageDeb            # oder packageRpm / packageAppImage
```
