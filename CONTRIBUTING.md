# Contributing to Grooveo

Thanks for considering a contribution. This is a small, on-device Android app
(no backend required to build or run it) that ships in two editions, Grooveo
and Dawn Music, plus a desktop app — most changes only need Android
Studio/Gradle and a device or emulator.

## Before you start

- For anything beyond a small fix (new feature, refactor, architecture
  change), please open an issue first to discuss the approach. It's much
  easier to agree on direction before code is written than to rework a
  finished PR.
- Check open issues and PRs so you don't duplicate work.
- One logical change per pull request. Unrelated cleanups make review harder
  and are more likely to conflict with other in-flight work — split them out.

## Project layout

See [`README.md`](README.md#architecture) for the high-level architecture and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for a more detailed module
map. [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) covers local setup and the
build/run loop.

## Building

```
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 37, `minSdk`
30). `local.properties` needs the usual `sdk.dir`; everything else builds
with a working default — see [Optional backend
link](README.md#optional-backend-link) if you're also touching the
backend-integration code path (analytics / update channel), which is the one
part that needs extra config.

Before opening a PR, make sure the app builds and the unit tests pass:

```
./gradlew :app:assembleDebug :app:testGrooveoDebugUnitTest :app:testDawnDebugUnitTest
```

There is no instrumented suite, so also exercise the change on a device or
emulator (screen recording or a description of what you tested in the PR) —
in both editions when the code is shared. Gate edition-specific behaviour on
`BuildConfig.DAWN` instead of copying screens.

## Code style

- Kotlin, following the surrounding file's conventions (this codebase doesn't
  run ktlint/detekt yet — match existing formatting rather than introducing a
  new style within one file).
- Compose UI: keep new screens/components consistent with the existing
  Canopy design system (`ui/theme/`, `ui/components/`) rather than
  introducing ad-hoc styling. If you're implementing a design that doesn't
  exist as a token yet, add the token rather than hardcoding a one-off value.
- Comments should explain *why*, not *what* — only add one where the reasoning
  isn't obvious from the code itself (a workaround, a non-obvious constraint,
  a gotcha you hit). Don't restate what a well-named function already says.
- User-facing strings are German, matching the existing app.

## User-visible changes need a What's New entry

Any change a user would actually notice (new feature, fixed bug, changed
behavior) should get an entry in
[`WhatsNewScreen.kt`](app/src/main/java/dev/schlubbe/musicagent/ui/whatsnew/WhatsNewScreen.kt)
as part of the same PR — a `WhatsNewItem` in the `ITEMS` list (icon name from
Phosphor Icons, short title, one-sentence description, in German). The list
only holds the upcoming release and is cleared when a release is cut, so keep
entries short. Internal refactors, CI/build changes, and other non-user-visible
work don't need one.

## Security

Please read [`SECURITY.md`](SECURITY.md) before touching networking, sync,
backups, the updater or anything that handles credentials — and never report a
vulnerability in a public issue.

## Commit messages / PRs

- Write commit messages that explain *why* a change was made, not just what
  changed (the diff already shows the "what").
- Keep the PR description focused: what changed, why, and how you tested it.
- Bump `grooveo.versionCode`/`grooveo.versionName` in `gradle.properties` only
  if you're also cutting a release — regular PRs shouldn't touch them.

## Releasing

Releases are cut from this repo now (see
[`docs/RELEASING.md`](docs/RELEASING.md)) — contributors don't need to do
this themselves, but it's documented there for maintainers.

## Reporting bugs / requesting features

Please use the issue templates — they ask for the information needed to
actually act on a report (repro steps, version, logs) instead of a blank
text box.

## License

By contributing, you agree that your contribution is licensed under this
project's license, GPL-3.0-or-later (see [`LICENSE`](LICENSE)) — inherited
from the [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
dependency's copyleft terms.
