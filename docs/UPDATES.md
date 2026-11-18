# Updates

Release 1 came out on 2026-10-28. Release 2 (2026-11-16, version 0.1.459170 and later) folds in everything added since, grouped by area below. FEATURES.md is the full user guide and ALL-FEATURES.txt the one-list summary. The full dated list of every change (one line each) is kept in archive/UPDATES-history-2026-10-28-to-2026-11-16.md. New changes are added at the end of this file, one line each.

## Release 2 (2026-11-16): what changed since Release 1

**Radio, channels and chat**
- Catch-up per channel (Channels page, Request catch-ups, off for Public). Coming back to the app after 30 s asks for what was missed, including voice recordings (chat kind 8); listen-only channels ask but never answer. The commander gets a pop-up to resend for someone back after 2 min offline.
- One-touch PTT for listened channels (pin on the Channels page splits the big PTT; colours show on air, listened, last heard, talking). Text as voice beside PTT (read aloud on every phone, not in Stealth).
- Received burst voice waits for a clear channel (up to 5 min, call-waiting beep); optional clean-up of received bursts (rumble cut, hiss down, quiet speech up).
- Missed calls show unread in Chat and remind every 5 minutes for up to an hour. Exit says goodbye at once and can say "back in N hours" (others are told if overdue).
- Chat filter: kinds of item (status notices hidden by default) and Channel (on air, all, A, B, Public, Cloud group). Every message shows its source (· A, · B, · PUBLIC, · CLOUD with a cloud icon).
- Cloud group: a community channel like Public with its own Cloud Server (a relay user that many phones share), share link and QR code, Scan QR to join in one step, a receive-only code, and "Share my location with public and cloud groups" (off by default). The cloud channel is signed: every phone in a group needs Release 2. On the map home row Cloud shows as a small cloud icon.
- The status line names what else is listened to ("and 3 teams", "and 2 private contacts"); Channel array teams are listened to only while that menu is on.
- Channel array for leaders, team setup QR codes, printed cards, Report to leaders with approved codes, Tasks and notes, Private contacts by QR.

**Internet links**
- Tunnel relay in the bridge on the LAN storage server (one forwarded port, a DRYAD row key per person, QR codes on the Bridge tab), with Direct / Via cloud relay / Both.
- Internet relay (relay/server, ycc-relay.jar) on a small cloud server: links MANETs and remote phones by channel tag, never holding channel keys; many users on one port, each with its own key; gateway election, link-down alerts, site status. Relay admin screen (a specialised menu) adds, renames and removes sites, remotes and cloud groups with signed, encrypted admin commands. Relay settings travel in the setup QR. PI-SETUP.md covers a Raspberry Pi cache server.
- With WiFi gone but mobile data on, the app offers to talk through the relay, and back on WiFi offers to return.

**Power**
- Settings > Power and sleep: Normal, Power saver, Ultra low and Offline (record offline messages, sent when someone is heard), battery levels that ask to switch, Mission mode (everyone stays awake 1 to 24 h), max sleep time, rest GPS when still, motion / plug / proximity check-ins, slower check-ins on a quiet channel, opt-in Deep sleep, Away with automatic check-ins, a mains-powered anchor phone, burst sleep windows and secret radio-silence check-in windows.
- Live map layers keep running with the screen on in every power mode (and the map says if they are paused).

**Maps and navigation**
- Full map: icon buttons (two rows of five, a column in landscape), eye to hide the tools, Layer (street, satellite, topographic, T.REX), scale bar, Ruler, speed, Search (offline place and street lists from GeoNames and OpenStreetMap, online search when there is internet), Copy and Share of the + position. The + line shows lat / lon on one line and the whole grid reference (MGRS) on the next.
- Map points: turn, size and direction arrow; shapes (rectangle, circle, oval, line, arrow, text) with geofence alerts; personal points kept on this phone; Bulk add and Export (text, .csv, QR codes, with a Style column); Resync and a per-channel Points filter.
- Send a POI: the position line reads "at …" with the grid reference; tap it to type a position, either GPS (decimal or degrees minutes seconds) or a grid reference (full, square and figures, or 6 / 8 / 10 figures in the map's square). It then reads "GPS: …" or "mil: …".
- Data Tile Management: stored tiles here and on the LAN maps cache (and its named sets), prefetch by radius (1 km to World) or the current screen with zoom and map-type ticks, super slow mode, a background job with a notification, a secondary map server and server-to-server sync.
- Offline street directions (driving or walking): plan first, close roads, + Go via here (a small dot), + Planned rest / meeting stop (cup: name, time, length, note), + Add a waypoint (flag: name, note), the route editor (route notes, start, each point in order, destination, closed roads; it replaced Undo), save and share with every stop and note (older versions still get the plain route), Routes list, remembered closed roads, tiles along the route, a 500 m off-route warning, stops announced ahead and on arrival.
- Live layers: emergency alerts (CAP: VicEmergency, NOAA, custom), aircraft (ADS-B, adsb.lol) and ships (AIS) with a Test button, live trains from any GTFS Realtime feed (Melbourne preset), ARTC rail overlay.
- T.REX Arms Topo Map (a specialised menu): import a baked .mbtiles file, shared through the cache server's own folder, a Layer choice.
- Fake GPS has no built-in centre and is off by default; Obscure location (0 to 10 km, 2 km default) shifts every real position sent.

**Cameras, video and power stations**
- Share my camera: up to 15 pictures a second when tiny, optional audio, a telemetry overlay, keeps running in the background with a notification; Torch and Blackout on the camera screen.
- Reolink cameras on the LAN (offline): scan and sign in, dual lenses, picture tiles, full screen with live video where the phone can play it, Listen and Light, motion / person / vehicle / animal triggers that bring cameras to the front (several at once), linked floodlights, channel alerts (motion, offline, back online) with an optional picture; port-9000-only cameras through the camera's own LAN protocol. Tactical videos auto blackout.
- Battery power (a specialised menu): several batteries at once, read-only: Bluetti over Bluetooth, Tesla Powerwall 2 gateway pages, Anker SOLIX and EcoFlow (untested on real units); background watch with channel alerts; the commander sees each phone's battery station %.

**Tools, notes and screens**
- Tools page (Settings > Tools): make a QR code from text, scan any QR code, open in browser.
- Calendar and notes (a specialised menu): my calendar, team calendar on the channel, reminders, notes with pictures. Protected notes and calendar entries: a padlock and password, encrypted on this phone only (PBKDF2-HMAC-SHA1, AES-256-CBC + HMAC-SHA256), hidden every time the screen opens, 3 wrong passwords erase them, change password or erase all, Protected Meeting reminders that show no details; never shared, synced or backed up, and the lost-phone wipe deletes them.
- DRYAD Enigma CW (Morse): CW mode, generate, send, receive and decode, CW files, VOX lead-in tone, CW into Chat.
- Settings split into pages; setup wizard can be run again; Advanced or simple note; metric or imperial units; Specialised menus (off by default, set by setup QR codes); open straight into the map or TV mode; screen orientation and invert; Cast screen to TV; TV Menu and TV mode.
- Blackout has no light at all (a legacy lowest-backlight setting for older devices); turning the phone while black no longer brings the screen back, it keeps its orientation until the double tap.
- Update from URL defaults to the GitHub release and puts newer apps on the LAN storage for the other phones.

**Fixes worth knowing**
- ADS-B / AIS layers started off and stayed stopped after a power mode change; Android 4.1 to 4.4 cannot reach the online feeds (use Share from a newer phone).
- Public filters found nothing when the Public key had been changed (a signed channel).
- Blackout on older Android froze touch at zero brightness (legacy setting); rotation lifted the black cover.

## Changes after Release 2

- 2026-11-17: Fix (LAN storage server): the Sync tab did not copy the **T.REX Arms Topo Map** folder to other stores (e.g. the Pi). It is now one of the sync folders, and destinations saved before it existed get it too (unless it is unticked later). Needs the server's sync.py updated.
- 2026-11-18: New **Anchored button** in the title bar (radio home, map home and full map). While the device is plugged in it shows a grey crossed anchor (not anchored) or a gold anchor (anchored); one tap asks, then sets or clears Anchored (the same setting as Settings > Power and sleep). If an anchored device loses power the gold anchor gets a red dot, and the button stays so it can be turned off. It is never set automatically by plugging in (a power bank is not stable power).
- 2026-11-18: **Tactical videos is now Video feed** (menu, screen title, Reolink and Share my camera texts). New: when someone starts sharing their camera (or starts again after a minute's break), a small notice "X has started their video feed · Tap to watch" shows at the top of the screen for 30 seconds; tap it to open Video feed on that person. No sound, it doesn't block anything, and it isn't shown over a black screen or on the Video feed screen itself.
