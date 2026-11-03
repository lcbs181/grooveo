## What & why

<!-- What does this change do, and why? Link any related issue. -->

## How was this tested?

<!-- ./gradlew :app:assembleDebug, plus what you actually exercised on-device/emulator. -->

## Checklist

- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] `./gradlew :app:testGrooveoDebugUnitTest :app:testDawnDebugUnitTest` passes
- [ ] I tested this on a device or emulator (both editions if the code is shared)
- [ ] No secrets, tokens, personal data or real account names in code, logs or screenshots (see [`SECURITY.md`](../SECURITY.md))
- [ ] If this is user-visible, I added an entry to
      [`WhatsNewScreen.kt`](../app/src/main/java/dev/schlubbe/musicagent/ui/whatsnew/WhatsNewScreen.kt)
- [ ] I did not bump `grooveo.versionCode`/`grooveo.versionName` (unless this PR is cutting a release)
