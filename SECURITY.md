# Security

## Supported versions

Only the latest release of each app gets security fixes: Grooveo and Dawn
Music on Android, and the Grooveo desktop app for Linux and Windows. Test
builds (pre-releases) are covered until the next release is out.

## Reporting a vulnerability

**Please do not open a public issue for security problems.** Report them
privately through GitHub's
[private vulnerability reporting](https://github.com/lcbs181/grooveo/security/advisories/new)
("Security" tab → "Report a vulnerability").

Include what is affected (app, edition, version), how to reproduce it, and
what an attacker could do with it. Leave out real tokens, pairing codes and
personal data; a description of where they leak is enough. You should get a
first answer within a week. Once a fix is released, the advisory is
published and you are credited unless you'd rather not be.

## What is in scope

- **Phone ↔ PC sync and the "KI" tab**: pairing, discovery, encryption and
  authentication (`data/backup/SyncProtocol.kt`, `desktop/.../SyncServer.kt`).
- **Updaters**: anything that gets a device to install an APK or desktop
  build that did not come from this repo's releases, or skips the
  `SHA256SUMS` check on desktop.
- **Backups**: export/import files that can be crafted to read or overwrite
  data outside the app's own storage.
- **Credential handling**: the optional backend link (API key, service
  account) leaking to SoundCloud, YouTube, LRCLIB or any other third party.
- **Local data exposure**: other apps or users on the same machine reading
  the library, the pairing key or settings.

Out of scope: SoundCloud/YouTube behaviour itself, DRM-protected tracks not
playing, and issues that need a rooted/compromised device or physical access
to an unlocked one.

## Guidelines for contributors

These are the rules the code already follows. Keep them when you change it.

### Secrets

- Nothing secret is committed. `local.properties` (SDK path, optional
  service-account login) stays local, and so does the release keystore
  (`~/.android/debug.keystore`): never upload it, attach it to an issue or
  copy it into CI.
- No API keys, tokens, pairing codes or passwords in source, tests, logs,
  crash messages, screenshots or issue text. Tests use obviously fake values.
- Release notes, README screenshots and bug reports must not show real
  account names, profile names, playlist names, IP addresses or hostnames.

### Network

- Extraction (SoundCloud, YouTube, LRCLIB, update check) uses its own plain
  `OkHttpClient` (`data/extract/di/`). The backend-link client with its auth
  interceptors must never be reused for third-party hosts, and
  `DynamicBaseUrlInterceptor` must never apply to them.
- HTTPS for everything that leaves the local network. Cleartext HTTP is only
  acceptable for the sync with the user's own PC, which is encrypted and
  authenticated on top (see below).
- Treat every response as untrusted: parse defensively, cap sizes, and never
  build file paths, intents or SQL from response fields without validating
  them.

### Phone ↔ PC sync

- The pairing key never travels over the network. Both sides derive separate
  keys from it (PBKDF2) for authentication and encryption; every request and
  reply is encrypted and signed, and discovery replies prove knowledge of the
  key before anything is sent.
- The PC answers only peers in the local network and stores the key in an
  owner-only file. New endpoints must go through the same authenticated
  channel — no unauthenticated "just for debugging" routes.
- Changes to `SyncProtocol` need tests on both sides (`app/src/test`,
  `desktop/src/test`) and must stay compatible with the previous release or
  fail closed.

### Updates

- The Android updater only installs an APK from this repo's GitHub releases
  whose name starts with its own edition's prefix, after checking the
  downloaded package's version code. Android itself rejects an APK signed
  with a different key.
- The desktop updater verifies every download against `SHA256SUMS` and falls
  back to opening the browser when it can't. Don't weaken either check.

### Local data and permissions

- Keep data in app-private storage. Exports to shared storage
  (`MediaStore`) contain music files only, never settings or keys.
- Ask for the fewest permissions possible. The visualizers tap the decoded
  audio instead of using `RECORD_AUDIO`; keep it that way.
- Backup rules (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`)
  must not start copying secrets into cloud or device-to-device backups.

### Dependencies

- Add a dependency only when it pulls its weight, pin exact versions in
  `gradle/libs.versions.toml`, and prefer well-maintained projects.
- Watch the licence: the app is GPL-3.0-or-later, so new dependencies must be
  compatible with it.
