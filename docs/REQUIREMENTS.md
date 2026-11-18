# Your Community Communications: requirements (running list, Release 2, 2026-11-16)

What the owner has asked for, as built, plus what is still open. FEATURES.md is the user guide,
ALL-FEATURES.txt the one-list summary, PROTOCOL.md the wire format, UPDATES.md the change history.

## Ground rules (from the start)
- Native Android, plain Java, no AndroidX, minSdk 16 (Android 4.1) to targetSdk 34; phones, tablets, Android TV.
- Sideloaded APK, never an app store. Works fully offline; nothing is uploaded unless the user turns on an
  online feature (online map tiles, search, live feeds, internet relay, update from URL).
- No accounts: the channel name / key is the secret; anyone with the same key decrypts.
- Must stay wire-compatible with phones already running it (PROTOCOL.md, with test vectors).
- Name "Your Community Communications", tagline "For MANET and offline messaging"; About shows the
  creation prompt and the updates. Apache 2.0, credits in NOTICE.
- Quiet testing (no audio, no rings) when asked; test on an old (Android 4.1) and a new (Android 14)
  emulator before calling something done; say what is untested on real hardware.

## Radio
- Push-to-talk over OpenMANET or any WiFi passing broadcast / multicast; full duplex, many to many.
- PTT: on-screen button, latch, learned hardware key (USB, Bluetooth, media buttons with the screen off), VOX.
- Acknowledgements; "transmission failed" with Retry now / Retry in … (5 min to a set time, 1-99 tries).
- Channels A, B (C-H optional), Public (fixed default key, or a changed signed key with a listen-only key),
  OpenMANET / ATAK Vx (experimental), Cloud group (a public-like channel with its own Cloud Server).
- Listen to other channels while on one (dual watch, stereo split), one-touch PTT for pinned listened channels.
- Burst voice (record then send), live voice, catch-up per channel, missed voice recovery, missed calls.
- Text as voice; calls with a ring that overrides silent; Stealth; radio silent that blocks everything.
- Tunnel comms and the internet relay (sites, remotes, cloud groups; never holding channel keys), Relay admin.
- Power modes (Normal, Power saver, Ultra low, Offline), Mission mode, check-in windows, anchors.

## Chat, reports and team tools
- Text, reports with templates, pictures, files, map points and voice in one chat; private messages;
  pinned announcements; filter by kind and by channel (including Cloud group); every message shows its source.
- Channel array for leaders, team setup QR codes and cards, Report to leaders, Tasks and notes,
  Private contacts by QR.
- Calendar and notes (an Advanced feature): my calendar, team calendar on the channel, reminders, notes with pictures.
- Protected notes and calendar entries: password-encrypted on the device only, hidden every time the screen
  opens, never shared / synced / backed up, 3 wrong passwords erase them, change password / erase all,
  Protected Meeting reminders with no details; the lost-phone wipe deletes them.

## Maps and location
- Offline tiles (ATAK folders, prefetch, LAN maps cache, secondary server), online sources, T.REX Arms topo
  files, Layer choice, scale bar, ruler, search, compass, MGRS of the + (whole grid reference on its own line).
- Location sharing (30 minutes, longer, until stop), obscure location, Fake GPS, location requests.
- Map points with icons, turn, size, arrow, shapes, geofences, personal points, layers, bulk add, export;
  a typed position (GPS or 6 / 8 / 10 figure grid reference) when adding or editing a point.
- Offline street directions: plan first, closed roads, via points (dots), waypoints (flags), rest / meeting
  stops (cups) with times and notes, route editor and route notes, save, share (older versions get the
  plain route), stops announced, off-route warning.
- Live layers: emergency alerts (CAP), aircraft (ADS-B), ships (AIS), trains (GTFS Realtime), ARTC rail.

## Video, cameras and power
- Share my camera with telemetry and optional audio; Video feed; TV mode; Cast to TV.
- Reolink cameras on the LAN (offline), triggers, floodlights, channel alerts, port-9000-only cameras.
- Battery power: several batteries read-only (Bluetti, Tesla Powerwall 2, Anker SOLIX, EcoFlow), channel alerts.

## Codes and protection
- Modern DRYAD sheets (A-Z plus two chosen symbols), sharing by text, QR and over the air, age rules;
  keyed pigpen with a tap pad; Enigma page; CW (Morse) send, receive and decode.
- PIN lock, duress PIN wipe, triple-tap wipe, red light mode, Blackout (no light; keeps its orientation
  until the double tap), black screen timer, lock button.

## Setup and sharing
- Role-first setup wizard; device roles (Phone radio with an "I lead a team" tick, Commander, Public intercom,
  TV / display, Camera monitor, Network gateway) that only turn things on and never set Anchored; group types
  (Advanced, simple ones), switchable from the Channels page; phone setup QR that sets everything and applies
  without asking (role, group, lead, callsign with an optional lock, Anchored, features, channels, relay, LAN);
  Save / Read my config; Advanced and Region features off by default with an Advanced setup list; Tools page.
- Layout (2026-11-18): map home by default, Shortcuts page with the current state, short menu plus a More
  screen, Simple / Advanced channels pages with a shown / hidden eye per channel, Settings in twelve pages.
- LAN storage server (Docker, /webadmin: files, updates, setup QR, bridge, sync, docs); Update from URL
  (GitHub release by default) that refreshes the LAN copy for everyone.

## Open (asked for, not built yet)
- Automatic location sending after moving a set distance, a native Windows app, MeshCore support.
- Scouts / leaders plan (on hold): sealed reports from listen-key holders, team keys AA-ZZ, leader group lists,
  per-team tunnel keys, issued member codes.
- Real-hardware tests listed at the end of known-issues.md (FalconClaw PTT, cameras, gunshot alert, crash
  detection, tunnel and internet relay on real networks, cloud groups on a real relay, Powerwall / Anker /
  EcoFlow units, a real T.REX file, QR scanning with a phone camera).
