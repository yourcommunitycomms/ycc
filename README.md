# Your Community Communications

Download YCC.apk from the newest release: https://github.com/yourcommunitycomms/ycc/releases/latest/download/YCC.apk

*For MANET and offline messaging.* Release 1 (2026-10-28).

An Android app that turns phones and tablets into encrypted, full-duplex, many-to-many push-to-talk
radios, with chat, maps, reports and field codes (DRYAD, pigpen), over an OpenMANET mesh or any
shared WiFi. No internet, no accounts, no app store: the APK is sideloaded. Runs on Android 4.1 to 14.
An optional LAN storage server (Docker, e.g. on Project NOMAD) keeps shared map tiles, app updates,
missions and the group's settings, and sets up new phones with one QR code.

Licensed under the Apache License 2.0 (`LICENSE`, `NOTICE`). Written by Claude Code (Anthropic) from
the prompt in `docs/RECREATE-PROMPT.md`, which anyone can use to build their own compatible copy.

## What is here

| Folder | What |
|---|---|
| `app/` | The Android app (plain Java, no AndroidX, minSdk 16, targetSdk 34) |
| `server/` | The LAN storage server: Python 3, no add-ons, Dockerfile, `/webadmin` page |
| `relay/` | `UdpRelay.java`, a tiny UDP relay to join emulators or separate networks |
| `docs/` | User guide, all-features list, server and desktop setup, protocol, rebuild prompt, getting-started guide |
| `website/` | A static website for the project, with downloads (APK, docs, the Pi expansion board) |
| `logo/` | The app icon (SVG) |
| `tools/` | `publish-github-release.ps1`: publish the APK as a GitHub release |

Start with `docs/YourCommunityCommunications-GettingStarted.docx`, then `docs/FEATURES.md`
(user guide), `docs/ALL-FEATURES.txt` (every feature in one list) and `docs/NOMAD-SETUP.md` (the
server, and the commander view on an Ubuntu desktop). `docs/PROTOCOL.md` is the exact wire format
and encryption, with test vectors.

## Building the app

Needs JDK 17 and the Android SDK (platform 34, build-tools 34). Put the SDK path in
`local.properties` (`sdk.dir=...`), set `JAVA_HOME`, then:

```
./gradlew assembleDebug          (Windows: gradlew.bat assembleDebug)
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper downloads Gradle 8.7
once (checked against its SHA-256); for a fully offline machine, point `distributionUrl` in
`gradle/wrapper/gradle-wrapper.properties` at a local copy of `gradle-8.7-bin.zip`. The version
code is the minutes since 2026-01-01, so every build is newer than the last.

After changing the docs, run `docs/build-about.ps1` (rebuilds `docs/RECREATE-PROMPT.md`, the app's
About text and the server's copies) and `docs/guide/makedoc.ps1 -out docs/YourCommunityCommunications-GettingStarted.docx`.
Add one line per change to `docs/UPDATES.md`.

## Getting the app and updates

Download `YCC.apk` from the newest release:
https://github.com/yourcommunitycomms/ycc/releases/latest/download/YCC.apk

The app's Settings > Update from URL uses that address by default (Edit update URL switches to the
LAN storage instead). A phone that updates from GitHub also puts the new app on the group's LAN
storage, so phones without internet update from there. Very old Android 4.x phones may not manage
GitHub's secure connection; they update from the LAN storage.

To publish a release (maintainers): build the APK, then
`powershell -ExecutionPolicy Bypass -File tools/publish-github-release.ps1 -Repo yourcommunitycomms/ycc -TokenFile <your token file>`.
It creates release `v0.1.<version code>` with `YCC.apk` and `version.txt` attached. The token needs
Contents read and write on this repository only; keep it outside the repository.
## Running the server

See `server/README.md` and `docs/NOMAD-SETUP.md`. In short: `docker build -t ycc-files:1.1 server`,
run it with port 47490 published, a volume on `/data` and `YCC_PASSWORD` set, then open `/webadmin`.

## Testing with emulators

Emulators cannot see each other's broadcasts. Run `java relay/UdpRelay.java 47400 0.0.0.0` on the
PC and set Extra peers `10.0.2.2:47400` in each emulator (and `<PC address>:47400` on real phones
that should hear them).
