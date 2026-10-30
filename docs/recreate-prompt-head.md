# Your Community Communications: copy-paste prompt to recreate it (compatible)

Paste everything below the line into an AI coding tool (it was built with Claude Code) in an empty
folder. It describes the whole app and includes the exact wire protocol, so the result talks to
existing phones running this app. Change the name, tagline, logo and colours in the first section
to make it your own (for example "Bass Coast Community Comms"); everything else stays compatible.
Claude will ask before installing tools (JDK, Android SDK, emulators) and accepting licences.

---

**Name, logo and colours (make these your own)**
- **Name:** "Your Community Communications". "Your" is part of the name, in the same style as the
  rest; a group building its own copy replaces it, e.g. "Bass Coast Community Comms". Use the name
  for the app label, launcher, About page, files and descriptions. Phone title bars say "Comms".
- **Tagline:** "For MANET and offline messaging".
- **Logo:** a radio broadcasting to a ring of nodes. A rounded square (corner radius 22 % of the
  side) in dark green; in the centre a gold dot with two gold arcs either side, like radio waves
  going left and right; around it a dotted pale-green ring, with six pale-green node dots spaced
  evenly on the ring (top, bottom and four diagonals). 512×512 reference: background rect rx 112;
  dotted ring r 150 (dots every ~26 units); arcs of radius 56 and 96 (stroke 18, round caps);
  centre dot r 24; node dots r 22 at (256,106), (386,181), (386,331), (256,406), (126,331),
  (126,181).
- **Colours:** dark green #1F3D2B (icon background, title bars), gold #F0C75E (arcs, centre dot,
  accents), pale green #CFE3D4 (ring, nodes, highlights), near-black green #0F1F16 (screen
  background). A red light mode overrides all colours at night.
- **About page (Settings → About):** it must read: "This app was created by [AI tool name] with
  the following prompt and updates, based initially off the "Your Community Communications"
  project." Below that, show this whole prompt, then a short list of later updates, one line per
  change, and a "Copy to clipboard" button that copies all of it. Keep that text in one bundled
  file, and keep this About requirement in any copy of this prompt you pass on.

**What to build**

A native Android app with that name: a push-to-talk radio and field tool for OpenMANET meshes or
any WiFi network that passes broadcast/multicast. It must be **fully compatible on the wire with
existing phones running this app**, so follow the "Wire protocol" section at the end exactly (byte
layouts, crypto, timings), and check your key derivation against its test vectors before building
anything on top.

**Ground rules**
- Plain Java, Android framework only (no AndroidX, no Google Play services), minSdk 16
  (Android 4.1), targetSdk 34. Version-check anything newer. Sideloaded APK, no app store.
- Fully offline: no accounts, no cloud servers, no analytics, nothing uploaded. Anything that uses
  the internet (online map tiles, radio streams, aircraft and ship feeds, the tunnel) is optional and
  off by default. The only server is the group's own optional LAN storage (described below).
- Test on two emulators (Android 4.1 and 14), a tablet emulator and any connected phone, linked by a
  tiny UDP relay on the PC (forward every datagram to every client heard in the last minute). Prove
  every feature with screenshots or logs before calling it done; start emulators with audio off when
  testing must be quiet.
- Recommended phone setup: Developer options and USB debugging on each phone, connected by USB, and
  the AI installs the app and grants every permission and setting with adb.
- Keep docs/REQUIREMENTS.md updated with every request and decision.

**Modes** (asked by the first-start wizard after the callsign; Settings > Mode)
- **Advanced:** everything below.
- **Simple modes** for Scouts, Staff, 4WDing, Friends convoy and Caravan / camping: only a private key
  (channel A, shown as "Private") and the public key; no DRYAD, Pigpen or OpenMANET. The wizard asks
  for the private key ("Make one" = random 20-character key) and the public key, then joins. The home
  screen has Private and Public (Public can be hidden) and 8 quick buttons for the activity; Channels
  opens a Keys page (both keys hidden until Show, Make one, Show / Scan QR, show Public on home).
  Quick buttons are a map point, a chat message ("{me} is going on a short break", "Meal ready in 15
  minutes, at {in15}") or "share location until stop":
  - Scouts: meeting point, evacuation point, first aid station, trail start / end, help needed,
    water, toilets, group here, all good.
  - Staff: help, medical, security (points), long break, short break, back on duty, on my way,
    cleanup needed (messages).
  - 4WDing: flat tyre, rescue needed, share until stop, bogged, hazard / washout, track closed, camp
    here, regroup point (at the map +), stopping, all clear.
  - Convoy: issue, need fuel, next toilet, share until stop, food stop, slow down, meet here, parked.
  - Camping: meal ready now, meal in 15 min, BBQ, our camp site, cabin, car parked, playground,
    toilets / showers, camp kitchen, friends' site, pool, kids come back.
  4WD and convoy alert when someone on the channel has not been heard for 2 minutes. 4WD has crash
  detection* (a ~4 g jolt opens a 30 s "Are you OK?" with I'm OK; no answer sends a rescue point,
  a message, a Call to everyone and shares location until stopped). Both switch in Settings.
- **Intercom** (a phone or tablet fixed to a wall): no title bar, only three menu dots (Settings,
  Audio, Contacts, Radio streams, Set intercom key). Big room buttons (saved callsigns, "(not
  heard)" when off air, refreshed every few seconds) plus Everyone; hold to talk or Hands-free
  toggle; talking to one room uses VOICE_TO so only that intercom plays it. History / messages
  (records all voice by default), long-press a room to message or Call it, Camera (a gate camera's
  snapshot URL refreshed every second), Blackout (auto after 60 s without a touch, on by default;
  long-press to switch auto off). Always joins its private channel (whatever Rejoin on start says),
  rejoins after 10 s off air, has no Exit, and comes back to the front a minute after being left
  (SYSTEM_ALERT_WINDOW; Android 10+ asks once for "Display over other apps"). Sideways: rooms left,
  every button in one column on the right.

**Radio**
- Full duplex, many to many: everyone hears everyone, overlapping talkers are mixed, you keep
  hearing while you talk. Echo cancelling and the call audio mode so duplex works on a speaker.
- PTT: hold the big on-screen button; toggle mode; a learned hardware key (USB PTT, headset button);
  a Bluetooth LE PTT button chosen in Settings (works with the screen off, e.g. the FalconClaw's
  "BLU-PTT"); VOX with level slider, live meter, 3 s pre-roll and 5 s hang. Optional start tone (a
  0.16 s rising beep at the start of each PTT transmission, off by default).
- Channel name = passcode (see protocol). Slots A, B, extra channels C–H (off by default), Public
  (published, editable key) and an experimental OpenMANET talk-group mode (unencrypted Opus in RTP,
  half duplex). Re-joining keeps the old channel on air until the new key is derived, then swaps.
  Rejoin on start (on by default); Leave turns it off.
- Acknowledgements: "Heard by …"; if nobody acknowledges within 5 s play a tone and say
  "Transmission failed", with Resend now and Retry in 5 min / 15 min / 1 h / at a time, 1–99 tries.
  A tap too short to send audio is never "failed".
- Dual watch: talk on one channel and also listen to every channel ticked Listen (A–H, Public).
  Listen-only channels only ever send acks (plus what the user sends there on purpose). Quiet (both
  ears, 70 %) or Stereo (one listened channel: the earlier one left ear); per-channel mute;
  optionally save their voice as WAV recordings in Chat.
- Call asks Everyone / one person / Cancel. Ringing: ~6 s of ringtone plus a loud two-tone trill and
  vibration, a full-screen red alert (over the lock screen) with ACKNOWLEDGE (sends "X heard the call
  from Y") and Stop ringing only.
- Status: Available / Away (voice heard also saved to Chat with a notification) / Stealth (nothing
  plays, rings or speaks; saved; buzzes). Volume all the way down or silent / vibrate = stealth
  (option). A Call to a turned-down phone turns it up for the ring and then restores it. Status and
  battery go out with presence; changes show as Chat lines. Record all voice (option): every
  transmission heard or sent saved to Chat.
- Possible gunshots alert* (untested, off): 3 sudden mic-clipping bangs in 10 s send an alert with
  position.
- Radio silent: receive only; nothing at all is sent. Ending it asks for confirmation.
- Network: broadcast or multicast, a network picker (WiFi, VPN, USB tethering / Ethernet), extra
  unicast peers (host:port).
- **Tunnel comms** (Settings, advanced): a relay phone (best the commander tablet) with one forwarded
  port per remote person, each keyed by its own DRYAD row; remote phones send and receive everything
  through it over the internet, UDP with TCP fallback (see protocol section 11).

**Audio**
- Audio screen: devices in use, each with a cog: sound (Automatic = loudspeaker unless a headset is
  in / Loudspeaker always / Earpiece or headset), microphone (Automatic or a chosen input, Android
  6+), ringer for Call alerts (follows media, or loudspeaker always, Android 6+) and the PTT button
  (learn / clear). Call and media volumes, hearing boost and mic level (50–400 %), meter, echo test.
- Bluetooth headset: while on air, radio audio and mic over SCO. A wired headset plugged in or pulled
  out while on air moves the audio with it.

**Home screen** (setup lives in Settings): up to 4 channel buttons (chosen with eyes on the Channels
page; the one on air yellow, listen-only ones a white-centred circle); a Channels button (no
Join / Leave on the home screen: the Channels page lists every channel with Talk here / Leave
(confirmed), Listen, eye, key = DRYAD, note, and a menu with name, swap with A/B, mute); Contacts
(n online, red at 0); status button; camera shortcut; quick map point buttons; share location 30 min;
Call; Silent; Chat (amber with an unread count); Reports; Map; Lock (blocks setup until a long
press); optional Black screen button; big PTT; a blank strip at the bottom so the phone's own buttons
do not hit PTT. ▲ hides the title bar, a small ▼ brings it back. Two styles: Radio (big PTT) or Map
(mostly map, small PTT, "Mark…" asking at the X or at me). Landscape layouts for both. Black screen
after N seconds without a touch (Settings; warn if the phone's own timeout is shorter). Every other
screen has a house button back home. Menu: Chat, Audio, Settings, Data/files, Modern DRYAD, Pigpen,
OpenMANET node (browser), Radio streams (only when Internet audio is on), Blackout, Red light mode,
Exit (off air and close). A Permissions screen (Settings, and once at first start) checks and asks for
everything with phone-maker power-saving instructions.

**Contacts:** everyone heard with status colour, last seen, battery, distance and bearing, show on
map, private note (hidden while locked); grouped into teams (team = channel) with Message / Call per
person and Message team / Call team. "Less details" = one line each.

**Tablet commander view** (tablet in landscape): side panel (Contacts / Channels, hideable) next to
the map, columns about 30 / 40 / 30 % so 7-inch tablets fit; Broadcast talks and messages to several
listened channels at once; smaller PTT; every map-point button of the profile.

**Quick map point profiles:** Friends, Security, Firefighting, Military, Custom (edit button text,
announcement with {me} {in10} {in15} {in30}, icon, duration, my position or map centre) and the
simple-mode sets above. Icon pickers list the profile's icons first.

**Map and compass:** offline z/x/y tile folders (e.g. ATAK imagery), optional online tiles
(OpenStreetMap, Google satellite, OpenTopoMap, Google terrain, Esri topo, custom URL) with a
see-through layer on top; offline with no tiles asks for 10 s "online for 5 min?". Live "Me" arrow
(GPS course on phones without a compass), accuracy circle, reason shown when there is no fix.
Everyone's last position with a compass list. Map points with icon, note, expiry; edit, Move… (drag
under the +, Save here), remove, asking "announce the change?"; Navigate / open in Google Maps or any
map app; icon buttons (Me, All, POI, Sync with Resync, Points filter, Pad, Fake, Tiles; landscape: a column on the right); long-press to drop; camera photos drop a photo point. Dragging shows the +'s
distance and bearing. A centre-on-me target button next to the corner compass; tapping the compass
opens a full-screen Silva-style baseplate compass: travel arrow, red/white needle, a bezel dragged to
set a bearing with an orienting arrow, "turn left/right N°", Set to my heading, Bearing to X, true /
magnetic with declination, and map points around the edge or as a list (tap one for a red arrow).

**Chat and reports:** text, reports, map points, files and pictures for the channels on air; private
messages (per-phone RSA keys); acknowledgement mode for range tests. Reports: SALUTE, SPOTREP,
SITREP, LACE, ACE, 9-line MEDEVAC, CASEVAC, MIST, POSREP, LOCSTAT, AAR and custom, one field per page
with quick picks, location and DTG; send now, at a time, or as a map point; Cancel. Catch-up sync
every 5 min covering 30 min. To / Message / Send in one even row.

**Data:** location, files, camera photos (shrunk to 1600 px; phone camera app, choose an app, USB
camera* or network camera* snapshot URL), screenshots and map tile packs; resilient on lossy WiFi
(NACK repair, SHA-256 check).

**Codes:** Modern DRYAD sheets (26 rows A–Z, each a random permutation of A–Z plus two chosen
symbols for space and new line), batches with validity windows, names, colours, age rules,
emergency rebuild from a phrase; share as text, QR or over the air (warn on Public); a row can
become channel A, B, any of A–H or the Public key (→ X). Keyed pigpen: write from a DRYAD row and
decode on a tap pad.

**Internet audio** (Settings, off by default): Radio streams in the menu plays public scanner feeds
(a stream URL or a Broadcastify feed number, e.g. CFA district dispatch), one at a time until Stop.

**First start and shared setup:** random callsign like TANGO38; wizard: callsign, "Quick set from a
QR code" (Scan QR code / Skip quick set; a phone setup QR code, see below), units (metric default / imperial, on the callsign step), "Advanced or simple" (a note: Advanced modes revolve around DRYAD sheets for encryption, messages and channel names, shared between trusted devices; Simple modes have fixed keys set next), mode, then for
Advanced the public key (default `publicchannelkey`), what the group is for (sets the map-point
buttons) and whether to create one never-expiring DRYAD sheet (row A → A, row B → B; "no" joins
Public to wait for settings). Settings > "Share these settings and DRYAD sheets" sends everything
except the callsign and phone-specific settings (audio devices, tunnel, PTT key…); receivers are asked
"Clear all settings and accept the new ones?".

**Protection and housekeeping:** PIN for DRYAD, pigpen, channel names / QR and Settings; lost-device
option (2 wrong PINs erase all of the app's own data, sending nothing); secret triple-tap wipe on
the callsign; red light mode; blackout (double-tap to wake). Clear mission data (chat, reports,
files, recordings, map points, contacts' positions; keeps sheets, channels, settings, maps; must
leave every channel first). Clear all data and exit (next start runs the wizard).

**More map, comms and code features**
- Maps: "Online map tiles…" for 5 min / 1 hour / 1 day / always / offline now; Android 4.x swaps
  OSM/Topo/Esri for Google road tiles (old TLS). The + as lat/lon and MGRS; ▼/▲ folds the map's
  buttons away; "+ POI" at the +; home map centres on me when opened. POI shapes (rectangle, circle,
  oval, line, arrow, text box) with colours and "Also share on Public"; photo points keep direction
  and field of view (a cone); icon picker by category with a filter (military unit and decision
  icons); Emergency services profile; Settings > My map icon. Maps redraw only for heading changes
  of 2° or more.
- Fake GPS (Settings): a random fixed spot within 2 km of a centre, used for everything; the LOCATION
  flags byte marks it, others show "NAME (Fake)"; "+ Fake being here" on the full map.
- Aircraft (ADS-B, off by default): a MANET receiver's aircraft.json, or adsb.lol / airplanes.live
  around the map centre; slow mode (5 min online, 30 s local) by default; "Share ADS-B data" sends
  them to the channel (TYPE_ADSB). Ships (AIS, off by default) from Open Waters
  (`https://ais.openwaters.io/v1/vessels?bbox=…&max_age=30m`, optional key) shared as TYPE_AIS;
  turns itself off inland.
- ATAK: import MBTiles / osmdroid SQLite as offline map sets, export the view for ATAK, an ATAK map
  source XML for the LAN tiles; "Enable ATAK compatible" (off, warned): CoT position every 30 s on
  239.2.3.1:6969, ATAK positions and GeoChat All Chat Rooms (224.10.10.1:17012) shown, Public messages
  sent to it; never private channels, voice or DRYAD.
- Commander view: ▶ slides the side buttons away leaving a small PTT and + POI on the map.
- Burst voice (Audio page): record while PTT held, send as one file on release, auto-play
  (AMR 4.75k to AAC 48k). Data Tile Management (geo/TilePrefetch: z10-17 in a circle round the +, local then LAN source and named sets then net at 200 ms gaps, uploads what the LAN lacks; TilePrefetchService foreground service type dataSync with wake and WiFi locks, pause and resume, saved job in prefs prefetch). Secondary map server (prefs lan2Url/User/Pass, never shared; read after the primary, upload fallback; TilePrefetch.beginSync walks folder listings of Maps cache and copies missing tiles, never deletes). Named LAN tile sets (LanTiles.namedSets from the Maps cache folder listing, skipping name-hash source folders and _ folders; read after the source folder, before online). Map search (MapSearch: GeoNames AU.zip / cities500.zip filtered by country, class P; Overpass csv of named highways around the centre, one per name per ~1 km; files/search/*.tsv name,kind,lat,lon,area; LAN Search/places-CC.tsv; Nominatim online with countrycodes and viewbox). Tunnel per channel (prefs tunnelSlotN; Tunnel.tags = only those channels and Tunnel.onlyTags filters what is sent when the all-channels switch is off). Bulk POIs (BulkPois: CSV Name,Icon,Lat,Lon,Notes[,Layer]; icon 47 grey dot for unknown; [layer:X] note tag and a layer filter; QR parts 'YCCPOI1 i/n'; quiet sends). Enigma CW (audio/Morse: 8 kHz tone WAVs with 5 ms ramps, PARIS timing 1200/wpm ms, optional VOX lead-in tone at 1900 Hz, outside the decoder's 300-1500 Hz Goertzel tone search and k-means dot/dash decode; Cw.java format with %R% %SheetID% %StartRow% %CipherText% %Call% and <prosigns>, parsed back by regex; CW mode = RadioEngine.cwMode: stealth, PTT/VOX/bursts off; CwReceiver records from first tone to the clear-air gap; optional CW Decoded / CW received lines to Chat; optional relay of private texts out by CW via CwPlayer, waiting while CW is heard; cw prefs keep the mode across crashes). Burst cleanup (audio/BurstCleanup, off by default): MediaExtractor/MediaCodec decode, 120 Hz high-pass, downward expander under the noise floor, speech level to about -18 dBFS (max +20 dB, hiss capped at half), soft limiter, played from a temporary WAV. Location requests: chat text 'LOCATION REQUEST: ' on private channels, answered by a prompt or automatically (Mission and data tick, off). Chat filter by category (prefs chatHidden bitmask). POI pose tag [pose:rot;size%;arrow] in the note (rotation, 50-1000% size, heading arrow; pose-only edits go [quiet]). Map menu Emergency alerts (CAP, geo/CapFeed: VicEmergency osom-geojson, NOAA api.weather.gov alerts at the map centre with zone outlines, or custom CAP XML / GeoJSON; shaded areas). Share my camera (phone or network camera, 160–320 px, 1–5 fps, 10–15 fps for 160 px; optional audio as packet 17 with chunk count 0, IMA ADPCM, played only while Tactical videos is open)
  and a Tactical videos grid. TV mode (map, chat cards, cameras; Android TV opens it by itself).
- Pinned announcements in Chat (resent to newcomers); automatic key rotation hourly / daily (not
  Public); read-only Public listen key (YCCL1-…, P-256 signed key changes).
- PTT reads "network not connected" with no WiFi/Ethernet, tunnel or extra peers. Tunnel: "Use the
  relay now" switch, and an offer to test the relay after a minute with nobody heard.
- OpenMANET (off by default, menus hidden until on): talk groups, node web page, and an internet
  gateway switch* via the node's ubus API (toggles DHCP option 3 with a note and a backup; password
  never saved). Project NOMAD screen: ask its Ollama AI (port 11434) and open its Command Center.
- DRYAD: 5-letter sheet IDs, optional random row order, AA–ZZ sheets (676 rows, not QR), Enigma
  (plain/cipher boxes, Paste/Copy, digits as words, QR YCCE1|id|row|cipher), Challenge and response,
  a "How modern DRYAD works" page; Pigpen opens from the DRYAD screen.
- Contacts > More details: Remove and Add/Edit note on each row.

**LAN storage (on the phone)**
- Settings > LAN storage location: WebDAV/HTTP base URL (e.g. `http://server:8647/YCC`), user,
  password (never shown again), Test. Maps read `Maps cache/<host-hash>/z/x/y.jpg` from it before
  going online and copy every online tile to it; "Sync tiles (LAN storage)" syncs the view both ways.
- LAN sync and updates (per phone, never shared, all off): auto update from `Updates/version.txt`
  ("<versionCode>\nYCC.apk") and the APK (installed via a notification; versionCode = minutes since
  2026-01-01); logs to `Logs/<callsign>/<yyyy-MM-dd>/`, data files to `Data/<callsign>/<yyyy-MM-dd>/`
  (yyyyMMddHHmmss_ stamps), DRYAD sheets merged both ways (`DRYAD/sheets.txt`), config backup
  `Configs/<callsign>.ucconfig`; on demand / weekly / daily / every minute; Sync now. Home network:
  on that SSID everything but logs syncs at least hourly.
- Config backups: Load defaults (the app's own, or the server's network defaults
  `Configs/_defaults.ucconfig`), Save my config / Read my config. Mission mode: files under
  `Missions/<name>/`, Clear mission data offers a final sync first; Video report records into
  Mission videos (uploaded later, never radioed).

**Phone setup QR code**
- Text `YCCSETUP1|` + compact JSON: name, url, user, pass, tunnel, tkey, pub, chA, omg (OpenMANET
  group: turns OpenMANET on), omh (OpenMANET node), home (home WiFi SSID: turns home sync on),
  defaults (1 = load network defaults). Empty fields left out.
- Scanning (wizard, or Settings > Scan QR) shows what it sets, tests the LAN login (offer "Set up
  anyway" on failure), saves the LAN storage, applies the network defaults and DRYAD sheets, then the
  tunnel server/key (left switched off), Public key, channel A, OpenMANET and home WiFi.
- Settings > "Phone setup QR code (for new phones)" makes the same code from this phone (asks
  whether to include channel A) and warns that it holds the LAN password.

**The LAN storage server** (a `server` folder beside the app)
- One Python 3 file server, no add-ons, in Docker (`python:3.12-alpine`, port 47490, `/data`
  volume, `YCC_USER` / `YCC_PASSWORD`; Project NOMAD custom app on host port 8647). GET, HEAD, PUT
  (streamed, `.part` then rename), MKCOL, OPTIONS under `/YCC`; Basic auth; `Maps cache/` and
  `Updates/YCC.apk` readable without a login; hidden files never served.
- `/webadmin` (same login; `/` and `/admin` redirect): Manage, Files, Defaults (edit the UCCONFIG1
  network defaults, dropping per-phone keys), Phone setup (the QR code, drawn by a built-in QR maker,
  saved in a hidden file), Sync (one-way copy of new/changed files to other stores: folders, every N
  minutes between hours, KB/s limit, remembers what each has, never deletes there), Updates (publish
  an APK, version code read from its binary manifest), Login, Docs (Apache-2.0 and this prompt) and
  Erase (type ERASE). Plain HTML forms, confirm() on destructive buttons, posts must carry the page's
  own Origin.
- **Bridge** (`server/bridge/`, Python, no add-ons, a startup service on the host, not in Docker): hears
  the phones' broadcasts on UDP 47474 and passes them to devices whose Extra peers point at it on UDP
  47400 (a Waydroid or emulator commander tablet on the same computer), broadcasts what they send, and
  is the tunnel relay (protocol section 11) for remote people, each on their own port with a DRYAD-row
  key; the channel crypto (PBKDF2, AES-256-CTR, HMAC) in plain Python, checked against the test vector;
  dedupe by (senderId, seq). Settings and status pass through hidden files in the data folder, shown on
  the admin page's **Bridge** tab (on/off, broadcast address, internet address, people, a tunnel-only
  setup QR code each). Setup codes without a url keep the phone's LAN login.
- The commander tablet view can run on a desktop in the Android emulator or in Waydroid (Ubuntu,
  software graphics), linked by the bridge (Waydroid: Extra peers 192.168.240.1:47400; emulator:
  10.0.2.2:47400).

**Later additions (keep them)**
- Catch-up per channel (Channels page "Request catch-ups", default on except Public; listen-only
  channels ask and take answers, never answer). Coming back to the front after 30 s asks at once, back
  to the newest message received (30 min to a day; DIGEST flags bits 1-7 say how far).
- Voice catch-up (chat kind 8, protocol section 6): shared recording ids, announce first, then send
  VOICEREC-<id>.wav files; announced clips show as "fetching…" and fill in unread; one re-ask for gaps.
- Commander pop-up for 10 s when someone back after 2 min offline missed messages, with Send.
- Setup: Quick set QR, then (after Permissions) black screen and Record all voice (with Request
  catch-ups); Settings > Run the setup wizard again.
- Settings pages: main (callsign, Update from URL / Edit update URL, wizard, permissions, mode, links),
  Channel, Audio PTT and alerts, Home screen, Network and sync, Security, Mission and data. Update from
  URL keeps a tested "updateUrl" (shared setting); a newer APK from outside the LAN is uploaded to the
  LAN storage's Updates so everyone gets it.
- Audio: Test left/right tones, Swap left and right; received bursts wait for a clear channel (0 s to
  5 min, default 1 min) with an optional call-waiting beep.
- Maps: optional arrow/zoom/centre pad (off by default, toggled on the full map), Resync POIs, a
  per-channel map point filter, Fake GPS on/off. Radio silent tick on the Channels and Data screens.
  TV: Menu and TV mode buttons in the title bar; remote arrows pan in TV mode.
- Obscure location 0–10 km (default 2 km): a fixed random offset on every real position sent (LOCATION
  and ATAK), accuracy widened to the range; also the fake GPS radius. Share location with Fake GPS on asks
  keep fake / share real (obscured or exact).

**Wire protocol (must match exactly)**

