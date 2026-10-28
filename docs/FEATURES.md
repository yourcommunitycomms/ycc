# Your Community Communications user guide (release 1, 2026-10-28)

Push-to-talk radio, chat, maps and field tools for OpenMANET meshes or any WiFi network. Sideloaded
APK, no accounts, works fully offline, runs on Android 4.1 (2012) to Android 14. An optional **LAN
storage server** (a small Docker app, e.g. on Project NOMAD) keeps map tiles, app updates, missions,
logs and the group's default settings, and hands new phones their whole setup in one QR code.
Written by Claude (Anthropic) in Claude Code, directed and tested by the owner. How the encryption
and the wire format work: [PROTOCOL.md](PROTOCOL.md). Every feature in one list:
[ALL-FEATURES.txt](ALL-FEATURES.txt). Server and Ubuntu desktop setup: [NOMAD-SETUP.md](NOMAD-SETUP.md).
Rebuild it yourself: [RECREATE-PROMPT.md](RECREATE-PROMPT.md). Features marked \* are untested on
real hardware.

## Installing
Download `YCC.apk` from the LAN storage (`http://<server>:8647/YCC/Updates/YCC.apk`, no login
needed) or copy the APK across, allow "install unknown apps" once, and install. With a computer:
turn on **Developer options** and **USB debugging**, plug the phone in and let the AI install it and
set everything with adb (all permissions, battery optimisation off). Otherwise the **Permissions**
screen (shown once at first start, and in Settings) checks and asks for everything and explains the
phone maker's own power saving. Phones with auto updates on (Settings > LAN sync and updates) then
update themselves from the LAN storage.

## Setting up the first phone, then the rest by QR code
1. **First phone (the leader's):** run the wizard (below), choose the mode, set the channel keys,
   then Settings > **LAN storage location** (e.g. `http://<server>:8647/YCC`, user and password,
   **Test**). Optionally OpenMANET, the tunnel and the home WiFi (Settings > LAN sync and updates).
2. **Group defaults (optional):** turn on *LAN sync: config* on that phone and **Sync now**; on the
   server's admin page, **Defaults > Use as defaults** on its backup. New phones then get the same
   settings and DRYAD sheets.
3. **Every other phone:** install, type a callsign, then **Scan QR code** in the wizard's *Quick set
   from a QR code* step and scan the **phone setup QR code**, shown either on the admin page
   (**Phone setup** tab) or by the first phone (Settings > **Phone setup QR code (for new phones)**,
   with or without channel A). The phone shows what it will set, checks the LAN storage login,
   loads the network defaults and DRYAD sheets, then sets the tunnel server (left off), Public key,
   channel A, OpenMANET and the home WiFi. **Skip quick set** carries on with the normal wizard.
   A set-up phone can scan one later with Settings > **Scan QR**.
4. Without a LAN storage: share channel names with **QR of A / QR of B**, or send everything with
   **Share these settings and DRYAD sheets with the channel** (below).

The setup QR code holds the LAN storage password (and channel A if included): show it only to your
own group.

## First start (the wizard)
A new phone gets a random callsign such as TANGO38 and a short wizard: confirm or change the
callsign; **Quick set from a QR code** (scan a phone setup QR code, or Skip quick set); **Advanced or simple** (a short note: Advanced modes use DRYAD sheets for encryption, messages and channel names, shared between trusted devices; Simple modes use fixed keys); then confirm
the **public channel key** (default `publicchannelkey`); then either **Create
sheet** (one never-expiring DRYAD sheet: row A becomes channel A, row B channel B, ready to share
with your group) or **Join Public** and wait for someone to send their settings. Then the
Permissions screen. The wizard also asks **what the group is for** (Friends and family,
Security, Firefighting, Military), which picks the map-point buttons; Share settings passes it on.

After the quick-set step it asks **how the app will be used** (see Modes): **Advanced** carries on as
above; the simple modes and the intercom instead ask for the group's **private key** ("Make one"
picks a strong random key; it becomes channel A) and the public key, then join straight away.

## Modes (first start, or Settings > Mode)
- **Advanced:** everything in this guide.
- **Simple modes: Scouts, Staff, 4WDing, Friends convoy, Caravan / camping.** One private key and the
  public key, no DRYAD, Pigpen or OpenMANET. The home screen shows **Private** (and Public, which can
  be hidden) and eight quick buttons made for the activity. **Channels** opens a simple **Keys** page:
  the private and public keys, hidden until Show, with Make one, Show QR and Scan QR, and "Show Public
  on the home screen". Quick buttons either drop a map point (announced on everyone's phone), send a
  chat message, or share your location until tapped again:
  - Scouts: meeting point, evacuation point, first aid station, trail start / end, help needed, water,
    toilets, group here, all good.
  - Staff: help, medical, security (map points at you), long break, short break, back on duty, on my
    way, cleanup needed (messages).
  - 4WDing: flat tyre, rescue needed, share location until stop, bogged, hazard / washout, track
    closed, camp here, regroup point, stopping, all clear. **Drop-out alert:** a buzz and a Chat line
    when someone on the channel has not been heard for 2 minutes. **Crash detection\*** (untested): a
    hard jolt (about 4 g) opens "Are you OK?" with a 30 s countdown and a big I'm OK; with no answer
    it sends a rescue map point, a message, a Call to everyone, and shares your location until
    stopped. Both have switches in Settings.
  - Friends convoy: issue, need fuel, next toilet, share location until stop, food stop, slow down,
    meet here, parked here (and the drop-out alert).
  - Caravan / camping: meal ready now, meal in 15 min (with the time), BBQ, our camp site, cabin, car
    parked, playground, toilets / showers, camp kitchen, friends' site, pool, kids come back.
- **Intercom** (a phone or tablet fixed to a wall, like an old home intercom): big
  buttons for each room (saved callsigns, "(not heard)" when off air) and **Everyone**; pick one and
  **hold to talk**, or **Hands-free** to talk until tapped again. Talking to one room reaches only that
  intercom. **History / messages** replays every call (all voice is recorded); long-press a room to
  message or Call it; **Camera** shows a gate or door camera from its snapshot address, refreshed every
  second; **Blackout** blacks the screen (auto after 60 s without a touch, on by default; long-press to
  switch auto off). No title bar, just the menu dots (Settings, Audio, Contacts, Radio streams, Set
  intercom key). It always joins its private channel, rejoins by itself if dropped, has no Exit, and
  comes back by itself a minute after someone leaves it (Android 10+ asks once for "Display over
  other apps"). Sideways, the rooms are on the left and every button is in a column on the right.

## Sharing a whole setup
Settings > **Share these settings and DRYAD sheets with the channel** sends everything except your
callsign, PIN and phone-specific settings (PTT key, Bluetooth button, network interface, audio
levels). Each receiving phone asks **"Clear all settings and accept the new ones?"**; accepting also
replaces its DRYAD sheets. On Public you are warned, because anyone there would get your channel
names. Typical use: new phones join Public through the wizard, one leader sends the setup, and
everyone moves to channel A.

## Getting on air
1. **Settings** (menu): callsign, channel names for **A** and **B** (each name is the passcode;
   hidden unless "Show names"), the **public channel key**, network. A DRYAD row can fill any
   channel for you.
2. Tap **Channels** on the home screen and **Talk here** on a channel.
3. Hold **PUSH TO TALK**. Everyone on the same channel name hears you; you keep hearing them.

Leaving is on the Channels page only (red **Leave**, asks first), so nothing on the home screen can
drop you off air. The A/B/… buttons at the top switch channel while on air. Re-joining (another
channel, or a changed setting) stays on the old channel until the new one is ready ("· switching…").
**Rejoin on start** (Settings, on): the app goes back on the channel it was on; Leave turns it off.

## Channels page
Every channel down the page: A, B, the extra **C–H** (switch at the bottom, off by default),
Public (with its key) and OpenMANET\*. Each row: **eye** (button on the home screen; room for 4, so
e.g. B, C, Public, OpenMANET\* or D, E, F, Public), **key** (DRYAD), **note** (kept on this phone,
moves with the channel), **⋮** (set the name, **Swap with A / B**: name, note, listening and mute move
together; **Mute**: listened to and saved, not played), **Listen** and **Talk here / Leave**.

## Home screen styles (Settings > Home screen)
- **Radio**: channel buttons and **Channels**, status,
  **Contacts (n)** (red when nobody is online; "▶ NAME" while someone talks), **status button**, a
  small **camera** button, quick map point buttons, Share my location 30 min (tap again while sharing: stop, 2 hours, until the app restarts, or always), Call, Silent, Chat,
  Reports, Map, Lock and a big PTT. Landscape puts the PTT on the right half.
- **Map**: mostly map with map points, people and photo
  thumbnails, your "Me" arrow and compass; Contacts, status, camera; Chat, Mark…, Share loc, Full
  map, Lock; Call and Silent either side of a smaller PTT. Long-press the map to drop a point.
- The title reads "Comms" on phones and in portrait. **▲** in the title bar hides it for more room; the
  small **▼** at the top brings it back (and the menu with it).
- Both leave a blank strip at the bottom, so reaching for the phone's own buttons (with gloves)
  does not hit PTT, Call or Silent. Every other screen has a gold **house** button back home.
- **Tablet commander view**: a tablet in landscape shows the map with a
  side panel switching between Contacts and Channels (changes there apply at once); the arrow next
  to it hides the panel for a bigger map, ▲ hides the title bar, and Contacts' Less details shows
  one line per person. Channel buttons, Contacts, status, shortcuts, Call, Silent and a tall PTT sit
  on the right, with a smaller PTT and every map-point button of the group's profile (plus extra ones
  of the same kind). **Broadcast** ticks teams (listened channels) that also get what you say, and can
  message them all at once; the button turns red and shows "To A+C+D" while on. Upright, a tablet shows
  the normal home screen.

## Contacts and teams
Everyone heard: callsign and status colour (green available, yellow away, red stealth, grey
offline), last seen and on which channel, battery %, distance and bearing to their last position
(tap: **Show on map**), and a **note** kept on this phone. Notes are hidden while the home screen is
locked; the app-data wipe erases contacts and notes. Contacts are grouped into **teams**: a team is the
channel they were heard on (the one you talk on first). Every person has **Message** (a private
message when they shared a key on your channel, otherwise "@NAME …" to their team) and **Call**; every
team has **Message team** and **Call team** (for channels you talk or listen on). **Less details** shows one line per person
(status, battery, distance, last seen); **More details** brings everything back. The map's list colours names the same way.

## Status: Available, Away, Stealth
- **Away:** voice heard on the channel you talk on is also saved in Chat ("… while you were away")
  with a notification, for playback.
- **Stealth** (status button, or automatically when the volume is all the way down or the phone is
  on silent/vibrate): nothing plays, rings or speaks; voice is saved in Chat and notified; buzzes
  (option, on). The button shows "Stealth (vol 0)" / "(vibrate)" / "(silent)" when automatic.
- With the volume only turned down, a received **Call** turns the ringer and volumes up for the
  ring, then puts them back (option, on). Stealth chosen on purpose only buzzes.
- Others see your status in Contacts, and a Chat line "X is now Away" when it changes.

## Talking modes
- **Hold to talk** (default), **PTT toggle mode** (tap on, tap off), **hardware PTT key** (Settings >
  Set PTT key: USB PTT, headset button, keyboard), **Bluetooth PTT button** (Settings > Bluetooth PTT
  button > Choose; works with the screen off), **VOX** (voice activated: level slider, 3 s pre-roll,
  5 s hang).
- **Acknowledgements:** after each transmission you see "Heard by …" (channels only listened to
  acknowledge too). If nobody confirms within 5 s: a tone, "Transmission failed", **Retry now** and
  **Retry in…** (5 min, 15 min, 1 h, at a time, 1–99 tries), on a full-width line.
- **Bluetooth headset** (Settings checkbox, on): while on air, radio audio and mic go to a
  connected Bluetooth headset (e.g. FalconClaw), like a phone call. A wired headset plugged in or
  pulled out while on air moves the audio with it.
- **Start tone** (Settings > Talking, off): each PTT press starts with a short rising beep (0.16 s) so
  others know a call is coming; start talking a moment after pressing.
- A PTT tap too short to send any audio is not reported as failed.

## Listening modes
- **Normal:** everyone on the channel, mixed, on the loudspeaker (or headset).
- **Dual watch** (Channels page): talk on one channel and also hear every channel ticked
  **Listen** (A–H, Public). On the home screen the listen-only ones show a white-centred circle,
  the one on air yellow. Chat shows them all (LISTEN ONLY).
  - **Quiet:** the other channels in both ears, a little quieter; per-channel **Mute**.
  - **Stereo** (one listened channel): the earlier of the pair in the left ear (use headphones).
  - Optionally save their voice as recordings in Chat.
- **Radio silent** (Silent button, Settings > Security, or the tick on the Channels page and the Data /
  files screen): receive only. Nothing is
  sent at all (voice, acks, presence, positions, messages, sync, calls, online map tiles). PTT reads
  "RADIO SILENT". Texts you type are kept and reach others by catch-up after the silence ends.
  Ending it asks for confirmation.
- **Call:** asks **Everyone**, a person online on the channel, or Cancel. The called phone rings for
  about 6 s (ringtone plus a loud trill, vibration) and shows a full-screen red alert, over the
  lock screen too, with **ACKNOWLEDGE** (stops it and sends "X heard the call from Y") and
  **Stop ringing only**. On by default (Settings > Call button).
- **Possible gunshots alert\*** (untested, off; Settings): three sudden, mic-clipping bangs within
  10 s send "POSSIBLE GUNSHOTS detected near …" with your position. Keeps the mic open on air.

## Settings pages
The main Settings page has the callsign, **Update from URL** / **Edit update URL** (download and install the app; by default the newest GitHub release, https://github.com/yourcommunitycomms/ycc/releases/latest/download/YCC.apk, or the LAN storage's Updates/YCC.apk; tested, then kept, and network defaults can set it), Run the setup wizard again, Permissions and Mode, then links to: **Channel settings** (names, QR codes, Public key, OpenMANET, key rotation, rejoin, dual watch, catch-up, Share settings), **Audio, PTT and alerts**, **Home screen** (style, map points, my icon, black screen, TV mode), **Network and sync** (WiFi, peers, ATAK, tunnel, LAN storage, config backups), **Security** (PIN, wipe, radio silent) and **Mission and data** (mission, fake GPS, clearing data; also from the Data / files screen). About and How Modern DRYAD works are at the bottom.

## Audio screen (menu > Audio)
**Devices in use**, each with a cog: **Sound** (Automatic: loudspeaker unless a headset is in;
Loudspeaker always; Earpiece or headset), **Microphone** (Automatic, or pick one: phone, headset, USB,
Bluetooth; Android 6+), **Ringer** for Call alerts (follows media audio, or loudspeaker always even
with headphones in; Android 6+), and the **PTT button** (press it to learn it, or clear it). Then the
phone call volume (radio audio) and media volume, an in-app hearing boost and microphone level
(50–400 %), a level meter, an **echo test** (talk for 5 s and hear it back as others would), **Test left / Test right** tones and **Swap left and right** for stereo listening.

## Lock and light discipline
- **Lock**: blocks channel changes, Channels and setup; PTT, map points, chat
  and reports still work. Hold the button to unlock.
- **Red light mode** and **Blackout** (black screen, app keeps running,
  double-tap to wake) in the menu.
- **Black screen button** on the home screen (Settings, off by default, with how to get out:
  double-tap) and **black screen after N seconds** without a touch (0 = never; Settings warns when
  the phone's own screen timeout is shorter).

## Chat and reports
- **Chat**: messages, reports, map points, files, pictures and saved voice for
  the channel on air and the ones listened to. The Chat button turns amber with a count when
  something arrives. **To:** a person for a private message (only their phone can read it).
- **Reports**: SALUTE, SPOTREP, SITREP, LACE, ACE, 9-line MEDEVAC, CASEVAC,
  MIST, POSREP, LOCSTAT, AAR and custom templates, one field per page (Skip to review on the way);
  the review page sends now, at a set time, or as a map point, or **Cancel** (asks first).
- **Catch-up sync:** every 5 min (covering 30 min) phones compare notes so anyone briefly away gets
  what they missed. Coming back to the app after more than 30 s away also catches up at once (not while
  radio silent; each channel's 'Request catch-ups' tick on the Channels page, on except Public, listen-only channels too); missed items show unread in Chat. Live voice that was missed cannot be fetched. **Acknowledgement mode** (range testing) auto-replies to every message.
- **Record all voice** (Settings, off; on in intercom mode): every transmission heard or sent is saved
  to Chat, even when Available.

## Map
- **Full map**: everyone's last position and a compass list (names coloured
  by status), your "Me" arrow (points where the phone faces; along your direction of travel on
  phones without a compass) and a corner compass. Opened from Contacts it centres on that person.
- **Tiles** (Data > Map options): offline tile folders; optional online OpenStreetMap, Google
  satellite, OpenTopoMap, Google terrain, Esri topo or a custom URL; a see-through **layer on top**
  (e.g. contours over satellite). With online off, an area without tiles asks for 10 s whether to
  go online for 5 minutes.
- **Bearing to the +:** after you drag the map, the bottom corner shows "+ is 1.2 km NE 45° from me".
- **Centre on me:** the round target button left of the compass (blue once there is a fix).
- **Compass** (tap the map's compass): a full-screen baseplate compass like a
  Silva: travel arrow up, red/white needle to north, a bezel you drag to set a bearing with the
  orienting arrow ("turn until the red needle sits in it"), heading and "turn left / right N°",
  **Set to my heading**, **Bearing to X** (the + you moved on the map, with distance) and True /
  Magnetic north with the local declination. **Map points** shows the points and people around the
  edge (icon and distance where each lies) and as a list; tap one for a red arrow pointing at it.
- **Mark…** on a map home screen asks **At the X** (default) or **At me**.
- **Move a point:** Edit › **Move…**, drag the map under the +, **Save here**, then Send.
- **Navigate / open in Google Maps** from a point (driving directions; any other map app works too).
- **Sync** (full map, refresh icon) offers Sync (all your active points and asks everyone for theirs) or Resync (clears others' points first).
- **Map points:** tap one to see, edit or remove it; you are asked whether to **announce** the change
  (default yes; if not, maps still update silently).
- **Quick map point profiles** (Settings > Quick map points, or "Change profile" in the Mark…/More…
  list): Friends, Security, Firefighting, Military,
  Custom (edit text, announcement, icon, duration, my position or map centre).

## Data (menu > Data / files)
Send my location, files, camera photos (also dropped as a photo map point), pictures/screenshots,
and **map tiles** around the map view (the other phone imports them into its offline maps), in even
two-button rows. **Camera:** phone camera app, choose a camera app each time (e.g. a USB camera
app), **USB camera\*** (untested; phones whose camera system lists USB cameras, usually Android 9+)
or **network camera\*** (untested; a JPEG snapshot address of an IP, night vision or thermal camera).
The camera permission is asked for first. Transfers repair themselves on lossy WiFi and are
checksum-verified.

## Codes
- **Modern DRYAD**: sheets of 26 rows, each a random order of
  A–Z plus two special symbols (space, new line). Generate batches with validity times, names and
  colours; share as text, QR or on air (tick list, All by default; Public warns first); delete with
  the same tick list; age rules; a row can become a channel (`→ A`, `→ B`, `→ X`: any of A–H, or the
  Public key).
- **Pigpen**: write with a classic or DRYAD-row key; decode on a tap pad.

## Protection and clearing up
PIN for DRYAD, pigpen, channel names/QR and Settings. Lost-device protection: 2 wrong PINs erase
all of the app's own data (including contacts and notes) and close it, sending nothing. Triple-tap
the callsign for the same wipe.
- **Clear mission data** (Settings): chat and report history, pictures and files, voice recordings,
  map points and contacts' last positions; keeps DRYAD sheets, channels, settings and offline maps.
  You must leave every channel first ("Leave and clear").
- **Clear all data and exit** (Settings): erases everything and closes; the next start runs the setup
  wizard.
- **Exit** (menu): off air, service stopped, app closed (rejoin on start still applies next time).

## Keeping it running with the screen locked
Permissions screen: battery optimisation off for this app, and the phone maker's power saving
(Samsung, ZTE, Huawei, Xiaomi…) set to let it run. On Android 6, "Screen overlay detected" when
allowing a permission means another app is drawing over the screen (a blue-light filter, chat
bubbles); this app never does.

## Networks
UDP port 47474, broadcast (default) or multicast; Advanced… picks WiFi, VPN, USB tethering or USB
Ethernet; Extra peers (host:port) and the small PC relay link separate networks or emulators.
**OpenMANET\*** (experimental) talks to OpenMANET/ATAK Vx talk groups: unencrypted, half duplex.

**Tunnel comms** (Settings, advanced; tested on emulators, not yet over a real router): a phone away
from the group's WiFi reaches it over the internet. The **relay** (the LAN storage server's bridge, set
up on its Bridge tab; or a phone, best the commander tablet, on the group network) gets one forwarded port per remote person (9991, 9992…), each keyed by its own DRYAD
row (*Tunnel relay › Add / remove* shows the address to forward to). The **remote** phone enters
`internet-address:port` and the same row; everything it sends and hears then goes through the relay.
UDP first, TCP on the same port when UDP is blocked. Only the channels the remote is on are sent to
it, and everything stays end-to-end encrypted.

**Radio streams** (menu, when Settings > Internet audio is on; off by default): listen to public
scanner feeds over the internet, e.g. CFA district dispatch on Broadcastify. Add a stream by address
or just its Broadcastify feed number; one plays at a time until Stop. Nothing is sent.

## LAN storage, sync and updates
- **LAN storage location** (Settings): a WebDAV/HTTP address such as the server's
  `http://<server>:8647/YCC`, user and password (never shown again; empty keeps it) and **Test**.
  Maps read tiles from it before going online (also while offline) and copy every online tile to it
  (`Maps cache/<source>/z/x/y.jpg`); full map > **Sync tiles (LAN storage)** syncs the view both ways.
  Not SMB: a Windows/Samba share needs WebDAV in front of it.
- **LAN sync and updates** (Settings; all off by default, per phone): **auto updates** (version.txt +
  YCC.apk in Updates; Android asks before installing), **logs** to `Logs/<callsign>/<day>/`, **data
  files** (received files, recordings, photos) to `Data/<callsign>/<day>/`, **DRYAD sheets** merged
  both ways, **config backup** to `Configs/<callsign>.ucconfig`. On demand, weekly, daily or every
  minute (testing), and **Sync now**.
- **Home network** (same dialog): on this WiFi (SSID) everything but logs syncs at least hourly.
- **Config backups** (Settings): **Load defaults** (the app's own, or the server's **network
  defaults**; callsign, keys, PIN and this phone's own settings stay), **Save my config** / **Read my
  config** (a full backup on the phone, never the PIN).
- **Mission mode** (Settings, Advanced): pick or create a mission; while set, LAN sync files go under
  `Missions/<name>/`. **Clear mission data** then offers *Final sync, then clear*, *Clear without
  syncing* or Cancel, and ends the mission. **Video report** (Reports, with a mission) records with the
  camera app into Mission videos; never sent over the radio, uploaded on the home WiFi or final sync.
- Share config sends the LAN storage location only if "share with the MANET" was chosen, and the
  receiver is asked separately before it changes.

## The LAN storage server (admin page)
A small Python file server in Docker (on Project NOMAD, any Linux box or a Raspberry Pi), see
[NOMAD-SETUP.md](NOMAD-SETUP.md). Map tiles and the APK can be read without a login (ATAK and phone
browsers cannot log in); everything else needs it. **/webadmin** (same login) has: **Manage** (folder
sizes, delete files older than N days, delete missions or idle missions, delete tile sources),
**Files** (browse, upload, rename, delete, new folder), **Defaults** (network defaults from a phone's
backup or a file; edit settings and DRYAD sheets), **Phone setup** (the setup QR code, see the top of
this guide), **Sync** (copy new files to other stores such as a Pi: chosen folders, every N minutes
between set hours, a KB/s limit, Test login, Run now / Stop; never deletes anything there),
**Bridge** (the server's bridge: joins the WiFi to a Waydroid or emulator tablet on the same computer, and is the **tunnel relay** for remote people, each with a forwarded port, a DRYAD-row key and their own QR code; on/off and live status), **Updates** (publish an APK; the version is read from it), **Login**, **Docs** (Apache-2.0 licence,
rebuilding with the AI prompt) and **Erase** (everything back to an empty store for a clean snapshot;
type ERASE). Destructive buttons ask first.

## More map features
- **Online map tiles…** (map menu, or tap the Tiles line): online for 5 min, 1 hour, 1 day,
  permanently, or offline now. Android 4.x uses Google's road map in place of OSM/Topo/Esri (their
  servers need newer TLS).
- The + position as lat/lon and **MGRS**; ▼/▲ folds the buttons and list away; **+ POI** adds a point
  at the +; the home map centres on you when opened.
- **Map point shapes** (rectangle, circle, oval, line, arrow, text box) with colours; **Also share on
  Public**. Photo points keep the photo's direction and field of view (a cone). Icon picker with all
  icons by category and a filter, including military unit and decision icons; **Emergency services**
  profile; Settings > **My map icon** (how others see you).
- **Fake GPS** (Settings > Mission and data, or the full map's **Fake GPS: on / off** button): a random
  spot within 2 km of a set centre, used for everything this phone shows and sends; others see
  "NAME (Fake)". **+ Fake being here** moves it to the +.
- **CW (Morse) on the DRYAD Enigma page**: Generate CW turns the cipher text into a Morse tone file using a message
  format (CW settings: %R% random letter, %SheetID%, %StartRow%, %CipherText%, %Call%, prosigns such as <AR>; speed 5–40 wpm,
  tone, repeats, clear air before and after). The lock enters **CW mode** (stealth: PTT, VOX and voice off) for a radio
  plugged into the phone; then **Send CW** plays a made file (newest first) and **Receive CW** listens, records and decodes
  each message (done after the set seconds of clear air), filling in the sheet ID, start row and cipher text. **CW decode**
  decodes a saved recording without a radio; ⋯ > CW files plays, decodes, joins or splits them. CW settings can also put
  received CW in Chat ("CW Decoded: …" or "CW received: …") and, in CW mode, send private texts out by CW automatically
  (encoded on the last sheet used, from its next row). CW mode stays on across restarts and crashes until the lock is tapped.
- **Clean up received bursts** (Audio page, off by default): less background noise, quiet bursts turned up and loud ones
  evened out before playing (the original recording stays in Chat).
- **Bulk add / export points** (full map menu): one point per line, `Name, Icon, Lat, Lon, Notes` (the position may be one
  field; a Name,Icon header and # lines are skipped). Type or paste it, scan it from QR codes, or open a .csv / .txt file.
  Unknown icons become a grey dot with the icon name in brackets. Then choose: send to everyone on the channel or just this
  phone, a layer name, expiry, and skip repeats. Export (all or one layer) copies the text, shares or saves a .csv, or shows
  QR codes in parts. Map > Points can show just one layer.
- **Full map buttons** are icons in two rows of five (Me, All, POI, Sync / Resync, Points filter, Pad, Fake GPS,
  Share my location, Ask to share, Tiles); long press for what each does. Landscape puts them in a column on the right.
- **Ask to share** (full map, private channels only, not Public): everyone on the channel is asked to share their location for 30
  minutes (Share / Not now), or shares by itself with Mission and data > Automatically accept location sharing (off by default).
- **Chat filter** (top right of Chat): show or hide messages, voice, pictures and files, map points, reports, status changes; and which channel
  (on air, all, A, B or Public).
- **POI rotation, size and direction arrow** (Send / Edit POI, when drawn as the icon): turn it to match a formation,
  50–1000% size, optional heading arrow. Only changing these is sent quietly (no spoken announcement).
- **Units** (wizard and the top of Settings): metric (m, km; the default) or imperial (ft, mi) for every distance shown or read aloud.
- **Obscure location** (below Fake GPS): others get a spot up to 0–10 km (2 km by default) from your
  real position, the same offset each time and shown as approximate; also the fake spot's range.
  Sharing your location while Fake GPS is on asks whether to keep the fake spot or share the real one,
  obscured or exact.
  Turning Fake GPS off (map button or Settings) with the range above 0 asks whether to keep obscuring; No sets 0 km.
- **Emergency alerts (CAP)** (map menu, off by default): VicEmergency (Victoria) or NOAA (USA, at the map centre)
  presets, or any CAP 1.2 / CAP-AU XML or GeoJSON feed. Alert areas shaded red / orange / yellow / blue by
  severity (burn areas brown); tap a marker for the full alert. Slow mode (default) checks every 10 minutes.
- **Aircraft (ADS-B)** (map menu, off by default): a MANET receiver's aircraft.json, or adsb.lol /
  airplanes.live online (sends the map centre); slow mode by default. **Share ADS-B data** sends them
  to the channel so everyone sees them. **Ships (AIS)** the same way from Open Waters (coasts only).
- **ATAK:** import MBTiles / osmdroid SQLite map files, export the view for ATAK, and an **ATAK map
  source** for the LAN tiles. Settings > **Enable ATAK compatible** (off; plain text, warned): your
  position as CoT every 30 s, ATAK positions and All Chat Rooms shown, Public messages sent to ATAK.
  Private channels, voice and DRYAD never go to ATAK.
- **Commander view** extras: ▶ slides the side buttons away, leaving a small PTT and + POI on the map.
- **Commander view:** someone back after more than 2 minutes offline who missed messages brings up a 10 s
  pop-up, "X has been offline for Y min and missed N messages on B. Send?" (Settings can switch it off).
- **Missed live voice:** phones with Record all voice send their recordings to a phone that comes back
  (one announces, the others leave those out); they arrive unread in Chat.

## More comms features
- **Burst voice** (Audio page): record while PTT is held, sent as one file on release, plays by
  itself on arrival (quality from AMR 4.75k to AAC 48k). For weak links. A burst that arrives while the channel is busy
  waits (up to 1 min by default, set on the Audio page) and plays once it clears, with an optional call-waiting beep.
- **Share my camera** (phone or network camera, small pictures 1–5 per second, or 10–15 when Tiny; optional audio, off by default, heard only on the Tactical videos screen) and **Tactical
  videos** (a grid of chosen people's cameras, tap to enlarge).
- **TV mode** (Settings): map, chat cards and cameras on a big screen; Android TV opens it by itself.
- **Pinned announcements** in Chat (resent to newcomers), **automatic key rotation** hourly or daily
  (not Public), and a read-only **Public listen key** (hear and read Public, not send).
- PTT reads "network not connected" when there is no WiFi, Ethernet, tunnel or extra peer.
- **Tunnel:** **Use the relay now** switches a set-up relay on or off; with nobody heard for a minute
  the app offers to test it.
- **OpenMANET internet gateway switch\*** (menu, OpenMANET on): turns the node's DHCP gateway option
  on or off through its web API (keeps a backup; the password is never saved).
- **Project NOMAD** screen (menu): ask its AI (Ollama) and open its Command Center.
- **Contacts > More details:** Remove a contact and add/edit its note.

## DRYAD extras
5-letter sheet IDs, optional random row order, huge AA–ZZ sheets (676 rows, not QR-shareable),
**Enigma** (plain and cipher text with Paste/Copy, Create/Read QR), **Challenge and response**, a
**How modern DRYAD works** page (bottom of Settings) and a Pigpen button on the DRYAD screen.

## Still to do
Automatic location sending after moving a set distance, a Windows app (use the commander APK in an
Android emulator, see NOMAD-SETUP.md), MeshCore integration, and testing with real hardware: a
FalconClaw, USB / network / intercom cameras, gunshot alert, crash detection, the tunnel over a real
router, the OpenMANET gateway switch, and radio streams with a real feed.
