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
  - **Pin** (the pin icon on a listening channel): that channel gets its own one-touch PTT on the radio
    screen (buttons home only), splitting the big PTT (up to 3 pins; side by side in portrait, stacked in
    landscape). Holding it talks on that channel only. Colours: green the channel on air, grey a
    listened channel, blue whichever channel the last voice was heard on, red while talking. Normal PTT
    only (not burst, offline or VOX). **Text as voice** splits the same way: one button per pinned
    channel, read aloud on the phones on that channel.
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
  double-tap to wake; the power button or leaving the app also ends it) in the menu. Blackout gives no light at all. On an older device where double-tap does not wake it, tick **Legacy support, non zero brightness** in Settings > Power and sleep.
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
  orienting arrow ("turn until the red needle sits in it"), heading and "turn left / right N°", the area's
  magnetic declination at the top (from the last known position until the compass has a fix), a
  **Declination offset arrow** tick (an amber arrow on the bezel marking magnetic north when showing true
  bearings, or true north when showing magnetic),
  **Set to my heading**, **Bearing to X** (the + you moved on the map, with distance) and True /
  Magnetic north with the local declination. **Map points** shows the points and people around the
  edge (icon and distance where each lies) and as a list; tap one for a red arrow pointing at it.
- **Mark…** on a map home screen asks **At the X** (default) or **At me**.
- **Move a point:** Edit › **Move…**, drag the map under the +, **Save here**, then Send. A note or icon already changed in Edit is kept through the move.
- **Copy** beside the + position under the full map copies it as "lat, lon", ready to paste into Google Maps. The map's **Share** button asks: copy my position to the clipboard, or share it with the team on the channel.
- **Navigate…** from a point: **Online** (Google Maps or any other map app) or **Offline on this map**, driving or walking. Offline routing uses the street routing data (OpenStreetMap roads, in areas of about 28 km): if the trip needs areas not on the phone, it offers to download them (LAN storage first, then the internet; the LAN storage keeps a copy). The route is drawn in blue with a bar showing the next turn, distance and time left, spoken prompts (Voice on / off, nothing in Stealth), a new route when you leave it, and Stop.
- **Planning and sharing a route:** an offline route is shown first, with **Start**, **Route…** and **Stop**. Route… can **close the road at the +** (a tree down, a flood: roads within 25 m are avoided), **go via the +** (a set way for a convoy), undo or clear those, **share this route** (to the channel chat, one person privately, or another app as text), work out a new route now, and turn automatic new routes on or off. A shared route shows in Chat as one line; tapping it opens the same route (same start, via points and closed roads) ready to Start. **Don't reroute** is on by default (Settings > Audio, PTT and alerts): leaving the route only says how far off you are.
- **Home screen choice** (Settings > Home screen and menus): Radio, Map, **Full map** (the app opens straight into the full map) or **TV mode** (opens in TV mode). **Screen orientation:** Auto, Portrait or Landscape for every screen, for a phone that keeps turning the wrong way, and **Invert screen** (upside down, e.g. mounted on a plate carrier with the leads out the other side; on Auto, the way the screen is held when a screen opens).
- **Specialised menus** (Settings > Home screen and menus): Channel array, Report to leaders, Tasks and notes, Project NOMAD, VicEmergency alerts, the Melbourne trains preset, the Load station names button, the ARTC rail network the ATAK items in the map menu (import map file, export for ATAK, ATAK map source) , **Cast screen to TV**, **Calendar and notes**, **Relay admin** and **Bluetti power** are hidden until ticked here (Trains on the map itself is always in the map menu, for your own home town's feed). All are off by default. A phone setup QR code carries the ticks of the phone that made it, and a team setup code turns on Tasks and notes (and Report to leaders with an approved code).
- **Calendar and notes** (a specialised menu): **My calendar** (on this phone), **Team** (entries shared on the channel; only whoever made one can change it), **Both** (team entries in amber, mine in green) and **My notes**. A month at a time with ◀ ▶. Entries have a title, date, time or all day, length, a note, and **Remind me** (at the time, 5, 10, 15 or 30 minutes, 1 or 2 hours, or a day before) with or without a **buzz**; team entries with a reminder remind everyone on the channel. Notes have a name, a date and text with pictures in it (**Insert picture / screenshot** puts it where the cursor is). Notes and my calendar never leave the phone.
- **Cast screen to TV** (menu, when ticked): opens the phone's own Cast screen list (Google TV, Chromecast, smart TVs; some phones call it Smart View or Screen mirroring) so the app shows on the TV. While casting, the screen stays on and the black screen timer waits. Nothing is sent until casting starts; while casting, a continuous video stream uses the WiFi and battery, so stop it when done. **Stop casting after 1 hour without using the app** (in the Cast box, off by default) ends a forgotten cast; where a phone does not let an app end it, a notification asks you to. A Google TV can also run the app itself in TV mode.
- **ARTC rail network** (a tick under Layer): Australia's interstate and freight track (ARTC Rail Centreline, CC BY 4.0, Australian Rail Track Corporation) as orange dashed lines, from map zoom 6 in. Read in squares of about 70 km as the map moves and kept on the phone, so areas seen before show offline.
- **Trains (live)** (map menu, and a tick under Layer): live trains on the map from GTFS Realtime feeds, every 30 seconds (or 2 minutes in slow mode), within about 80 km of the map. Tap a train for its line, speed and its next stops with times and delays. **Melbourne** (Metro Trains and V/Line) is a tick and needs a free Transport Victoria open data key. **We recommend setting up your own home town:** most transport agencies publish GTFS Realtime feeds on their open data page; put its vehicle positions address (and trip updates address for timetables, plus a key header and key if it needs one) in the boxes. Station names come from the timetable's stops.txt (Load station names…); without it stops show by number.
- **Route… > Download detailed map tiles along this route:** zoom 14 to 18 (ticks, up to 17 by default) and the map types, for about 300 m either side of the route, from the phone, the LAN storage, then the internet (in the background with Pause / Stop, and given to the LAN storage).
- **Routes** (map menu): saved routes (★) and the last 10 planned, each with its via points and closed roads; open one to plan it again, save it for later, or delete it. **Route… > Save this route for later** names and keeps the current one. Every closed road you mark or receive in a shared route is remembered: when a later route goes along one, it asks "marked closed X ago: ignore it?" with Avoid, Ignore or Forget it. Routes… can also forget all remembered closed roads. A shared route carries its via points and closed roads (with when they were closed).
- **Geofences:** a point drawn as a rectangle, circle or oval can be ticked **Geofence: alert me when a contact leaves it**. The phone that made it watches the positions contacts share: when one goes from inside to outside it sounds a warning, speaks it and shows a notification, and (if chosen) also sends a message to the channel ("Geofence: X has left Camp", with where).
- **Off-route warning:** following an offline route, more than 500 m off it sounds a warning (Audio, PTT and alerts: on by default), and can also tell the channel (off by default). Beyond 500 m it does not keep making new routes.
- **Personal points:** tick **Personal** when placing or editing a point and it stays on this phone only: never sent, synced, resent or shared on Public. Making a shared point personal removes it from everyone else's map.
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
- **Power and sleep** (Settings): Mission mode, the max sleep time, the power mode, levels that ask to change it, the black screen settings and red light.
  **Mission mode (life or death)** at the top asks how long (1, 2, 4, 8, 12 or 24 hours; tap again to extend or end). This phone runs flat out: live voice even if burst is ticked, fast check-ins, GPS at full speed. Its presence carries the mission and its time left, so every phone on the channel that hears it stays out of deep sleep (Ultra low acts as Power saver) and shows "Mission mode on by NAME until HH:MM" on the channel line; tap it to **acknowledge and hide** the banner (the mission carries on; a new or extended mission shows it again). If the mission phone is not heard for 10 minutes the others go back to their own setting. It turns itself off at the end with a notice.
  **Rest GPS when still** (on by default): with the screen off and no movement for 3 minutes (significant motion sensor, or a short accelerometer sample each minute), the GPS turns off and shared positions keep going out as the same place, confirmed now ("still here"). Moving, or the screen coming on, brings the GPS back. Network location stops once the GPS has a fix within 30 m.
  **Wake-ups:** a phone asleep in Ultra low checks in straight away when it is moved or picked up (phones with a significant motion sensor), plugged in or unplugged, or a hand passes the proximity sensor (where it is a wake-up sensor). A PTT button learned on the Audio page as a media key (headset button, play/pause on a remote) works with the screen off; Bluetooth LE buttons already did.
  **Quieter when quiet (Normal):** check-ins go every 5 seconds while the channel is busy (voice in the last 2 minutes) or the screen is on, then every 30 seconds, then every minute after 10 quiet minutes; each check-in says how long until the next, so nobody is counted as lost. Wi-Fi keeps low latency only while busy and is allowed its power saving otherwise (the first words after a quiet spell may arrive a fraction of a second later).
  **Deep sleep** (experimental, off by default): with the screen off and nothing said for 2 minutes, the phone lets go of its CPU lock and sleeps between packets, checking in every minute. Saves the most battery; some phones miss the start of a call while asleep, so test it on yours first.
  **Away** (the status button): with the screen off the phone sleeps like Ultra low and checks in by itself. Others see "Away, auto check-in in about 6 min": that is the phone checking in automatically, not the person coming back. Voice and messages missed while asleep come back with the catch-up at each check-in. Mission mode on the channel keeps an Away phone awake.
  **I'm on mains power (anchor):** set by hand (a power bank is not mains). The phone stays fully awake and keeps a copy of everything said, so sleeping phones catch up from it. With an anchor heard in the last 10 minutes, sleepers use the full max sleep time; without one, sleeps are kept to 10 minutes at most. If an anchor is unplugged for 10 minutes it tells the channel "Anchor NAME lost mains power" (handy if a generator or base power fails) and asks its owner whether it is still an anchor; plugging back in says so. Contacts show "(anchor)".
  **Burst sleep windows** (on by default): when everyone heard on the channel is in burst mode and the screen is off, the phones wake together for 5 seconds in every 30 (lined up to the clock) and sleep in between; bursts recorded meanwhile go out at the next window. Live voice, the screen or a mission brings full listening back.
  **Radio silence check-in windows** (off by default; every phone on the channel must use the same setting): about every 15 minutes to 4 hours, open for 3, 5 or 10 minutes. The windows are worked out from the channel key and the clock, so the phones agree but a listener cannot predict them (an hourly window lands anywhere from 50 to 70 minutes). Outside a window the app sends nothing at all: no check-ins, acks or positions; texts wait in Chat and reach the others through the catch-up at the next window, and PTT records a message that goes out then. The channel line says "RADIO SILENCE, next check-in window about HH:MM". At the overdue mark (4 minutes in, or sooner for short windows) anyone heard in the last window but not this one is named overdue by one phone, and everyone stays awake 5 more minutes; missing two windows alerts with their last position. Mission mode breaks the silence. Wi-Fi itself still sends small housekeeping frames and mesh nodes keep broadcasting: true silence needs flight mode.
  **Max sleep time** (1 to 60 minutes, 5 to start) is how long Ultra low and Away sleep between check-ins. Others are told, so they do not count the phone as lost. On Android 6 to 11 a still phone may stretch sleeps under about 9 minutes.
  **Normal**: GPS every 5 seconds on the map and 30 seconds in the background to save battery; **Full power GPS while plugged in** (off by default) uses 2 and 5 seconds on charge. **Power saver**: check-ins and GPS every 30 seconds, no map feeds or LAN sync with the screen off, and the screen sleeps normally; live voice still works.
  **Ultra low**: with the screen off the radio, sound and GPS rest and wake every max sleep time (5 minutes to start) to check in and catch up; a sound plays if messages came in; live voice is missed. Android can stretch this when the phone is idle unless the app is exempt from battery optimisation.
  **Offline**: for no network: the channel says Offline instead of On air, and PTT says **Record offline message**: it records while held (hardware PTT too) and sends by itself when someone is heard, oldest first, surviving a restart. No remote prompts, sync or tunnel; with nobody around and the screen off, GPS and map feeds stop and check-ins slow to a minute.
  After PTT, Power saver and Ultra low work as normal for 3 minutes so a reply is heard. Battery levels (20 % and 10 % to start, 0 = never) ask once each, not on charge or in Offline.
  Others do not count a phone in a power mode as lost between its check-ins. The nobody heard and lost contact warnings wait longer in the saving modes (3 minutes in Power saver, 10 in Ultra low). Black screen: Screen on during radio calls (on by default, never in Stealth) brings it back on for a call or voice, then it goes black again; with it off or in Stealth, the call screen does not open over a black screen (the call still buzzes and shows a notification). Red light can be on a schedule; turning it on or off by hand lasts until the next set time.
- **Exit** (menu): off air, service stopped, app closed. With others on the channel it first offers **Going offline as planned** or **Back in 1 / 4 / 8 / 12 / 24 h** (or Exit without notice): one chat line such as "SAM is offline as planned, back about 18:30". The others show Sam that way in Contacts, give no lost contact warning, and say "SAM is overdue" (with a buzz) if Sam is not heard 15 minutes after the time given. Being heard again clears it. If it was on air, opening it again asks **Reconnect last channels?**, listing them and their remote state.

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
**Internet relay (VPS)** (Settings > Tunnel comms > *Internet relay*; tested on emulators against a
cloud server, not yet with real phones): an always-on relay on a small internet server links several
MANETs and people away from them, with no port forwarding at home. Each site and each remote person
has its own key on the relay, and they all share one port (the server runs `ycc-relay.jar` as a
service; see relay/server). In the app choose **Relay mode** Direct (the tunnel above only), Internet relay, or
Both (sent both ways, repeats dropped), the relay's `address:port` and the key, and whether this
phone is **on the MANET** (it becomes the site's gateway), **off the MANET** (it connects straight to
the relay) or **Auto-detect** (on the MANET when its WiFi address is in the MANET range, OpenMANET's
10.41.0.0/16 by default; re-checked every 15 s). Only channels with their **globe** icon on (Channels
page, off by default) are shared to the internet by a gateway. Only one gateway per MANET is active:
others with the same settings show *Relay gateway standby* and take over if it goes quiet.
If the link drops for 30 s the gateway shows a silent notification (*Internet link to relay down*,
with an optional tone), a red **RELAY LINK DOWN** on the channel line, and puts a line in the chat of
the channel on air if it is shared; optionally it says "Link down" / "Link restored". Remote phones
and other sites see *Site X offline / back online* in Chat. *Internet relay sites* lists every site
and remote: green online, amber late, red offline, grey never seen, with the time since last heard.
The relay never has channel names; everything stays end-to-end encrypted. The address, key, mode
and MANET range can travel in the Phone setup QR.
**Relay admin** (a specialised menu, off by default; tested on emulators, not yet on real phones):
the relay makes a long random **admin key** the first time it starts; its owner shows it once as an
admin QR code. Scan it (or paste the key) on the admin's phone, where it stays (never in backups,
network defaults or setup codes). The screen lists the relay's sites and remotes, online or offline
and when last seen. **Add site** / **Add remote** asks for a name; the relay makes that user's key
and the phone shows its **setup QR code** (relay address, key, relay mode, and "on the MANET" for a
site's gateway or "off the MANET" for a remote), so a new phone is set up with one scan. Tap a user
to show its setup QR again, **rename** it, or **remove** it: its key stops working at once, it is
cut off within seconds and its phone says it was removed. *More…* shows the admin QR for a second
admin phone, **changes the admin key** (old admin codes stop working; sites and remotes are not
affected) or forgets it on this phone. Admin commands are encrypted and signed with the admin key
and cannot be replayed; the relay logs each one, without keys.

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
- **Home network** (same dialog): on this WiFi (SSID) everything but logs syncs at least hourly. With a tunnel set up, the app also asks once on each change of network: on the home network with the tunnel on for every channel, whether to disable remote; on another WiFi with it off, whether to enable it.
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
  "NAME (Fake)". Off by default with no centre: **+ Fake being here** (full map) sets the centre and the spot to the +.
- **CW (Morse) on the DRYAD Enigma page**: Generate CW turns the cipher text into a Morse tone file using a message
  format (CW settings: %R% random letter, %SheetID%, %StartRow%, %CipherText%, %Call%, prosigns such as <AR>; speed 5–40 wpm,
  tone, repeats, a VOX lead-in tone (a 1900 Hz tone the decoder ignores, to key the radio up), clear air before and after). The lock enters **CW mode** (stealth: PTT, VOX and voice off) for a radio
  plugged into the phone; then **Send CW** plays a made file (newest first) and **Receive CW** listens, records and decodes
  each message (done after the set seconds of clear air), filling in the sheet ID, start row and cipher text. **CW decode**
  decodes a saved recording without a radio; ⋯ > CW files plays, decodes, joins, splits, downloads (save or share) and uploads them. CW settings can also put
  received CW in Chat ("CW Decoded: …" or "CW received: …") and, in CW mode, send private texts out by CW automatically
  (encoded on the last sheet used, from its next row). CW mode stays on across restarts and crashes until the lock is tapped.
- **Clean up received bursts** (Audio page, off by default): less background noise, quiet bursts turned up and loud ones
  evened out before playing (the original recording stays in Chat).
- **Street routing data** can be ticked in a Data Tile Management prefetch (not for the whole world) to get offline directions for the area; it comes from the phone, the LAN storage, then the internet, and fills the LAN storage like tiles. Its count and size show at the top.
- **Data Tile Management** (map menu): what is stored on this phone and in the LAN maps cache, and Prefetch the **Current screen** (the corners of the map now, any zoom level that is not too many tiles) or 1, 5, 10, 20, 50, 100 or 200 km around
  the + at the zoom levels you tick (12 to 17, with the tile count beside each; 8 to 11 are always included; over 20 km, 12 to 14 start ticked and 16 and 17 are not offered; **World** gets the whole world at zoom 8 and 9 only, always super slow, about 8 days), for the map types you tick (street, satellite, topographic). Tiles already here are skipped, the LAN cache is used before the internet, and whatever the
  cache is missing is added to it. Needs a fast connection; internet tiles are limited to 5 a second, so large areas take hours. **Super slow** (ticked by default over 20 km) fetches one internet tile every 2 seconds and rests after a refused tile, so a huge area takes days but is far less likely to get blocked. It runs in the background with
  a notification (Pause / Resume, Stop); Data Tile Management shows it live while open, or carries on after the app was closed.
- **Secondary map server** (Data Tile Management): a second LAN storage for map tiles only. Phones read from it when the primary
  lacks a tile, and give new tiles to it if the primary is off. **Sync the two servers** copies map tiles between them through this
  phone with no internet (either way or both), only what is missing, never deleting; handy for filling a new server in the field.
  Needs folder listings on both servers (the YCC server has them).
- **Named LAN tile sets**: a tile set copied into the LAN storage's Maps cache folder under its own name (e.g. "ATAK Home",
  z/x/y.jpg) is used by every phone on a satellite map source (it would not match a street map), after its own saved tiles and before going online. The map's tiles line
  counts tiles read from and given to the LAN maps cache.
- **Eye** (full map, top left): shows or hides Search, Layer, Tools and the arrows pad together. The Tools key in the bottom buttons shows or hides the pad, and brings them all back if the eye has hidden them. Speed shows top right after moving more than 5 m in the last minute.
- **Map layer** (full map, top left, under Search): street map, satellite or topographic in one tap, and ticks to turn ships, aircraft and emergency alerts on or off (if not set up yet, the tick opens their settings); each keeps its own saved tiles.
- **Scale and ruler** (full map, top left): a scale bar in your units, and **Tools > Ruler**: tap two points for the distance and bearing
  (and the bearing back); a third tap starts again, Tools > Ruler off clears it.
- **Map search** (full map, top left): suburbs, towns and streets. Offline lists are offered the first time in a country
  (Australia and the USA by their outline; others looked up online): suburbs and towns from GeoNames (also shared through the
  LAN storage) and streets from OpenStreetMap around the map centre (5–40 km). With internet, OpenStreetMap's search adds the
  rest. A result moves the + there (Add POI at + to mark it). Search lists… adds streets for another area.
- **Tunnel per channel** (Channels page, with a relay server set up): "Use the tunnel for every channel" (also in Settings >
  Tunnel comms), or leave it off and tap the arch icon on a channel so only that channel goes through the relay. The
  Channels page also shows your callsign (tap to change).
- **Mobile data prompt**: with a relay server set up (Settings > Tunnel comms) and no WiFi, the app offers to talk through
  the relay over mobile data; when WiFi is back it offers to switch back.
- **Bulk add / export points** (full map menu): one point per line, `Name, Icon, Lat, Lon, Notes` (the position may be one
  field; a Name,Icon header and # lines are skipped). Type or paste it, scan it from QR codes, or open a .csv / .txt file.
  Unknown icons become a grey dot with the icon name in brackets. Then choose: send to everyone on the channel or just this
  phone, a layer name, expiry, and skip repeats. Export (all or one layer) copies the text, shares or saves a .csv, or shows
  QR codes in parts. Map > Points can show just one layer.
- **Full map buttons** are icons in two rows of five (Me, All, POI, Sync / Resync, Points filter, Pad, Fake GPS,
  Share my location, Ask to share, Tiles); long press for what each does. Landscape puts them in a column on the right.
- **Ask to share** (full map, private channels only, not Public): everyone on the channel is asked to share their location for 30
  minutes (Share / Not now), or shares by itself with Mission and data > Automatically accept location sharing (off by default).
- **Channel array** (menu, for leaders): a table of teams with name, private key, Public key, remote server, tunnel key, local cache, profile, map area and approved code. Fill AA-ZZ makes up to 676 teams from random keys or a DRYAD sheet; Import CSV asks what each column is and whether the first row is headings; Export CSV warns that it holds every key. Each team has Talk, Sound on / off, Setup QR, Edit, and under Edit > More: print card, new key, block reports, delete. Set all Public to the read-only key puts the listen key in setup codes. Print makes one page a team (Android 4.4 and later) with an app download QR, setup QR, instructions and map area. Leaders listen to every team at once with sound off by default, and voice is recorded for playback.
- **Team setup QR**: the wizard or Scan QR lists each set (team channel, Public, remote relay, local cache, profile, map area, approved code) with a tick, so any can be left out. It talks on the team channel and listens to Public, and starts the map area download.
- **Team name** (Settings, under the callsign): shown before the callsign on air, like AA TANGO.
- **Report to leaders** (phones with an approved code): I'm OK, I need help, SOS, send location, ask to talk, and a short message. Only leaders can read them: other teams and read-only phones cannot. A valid code is needed, so an edited app cannot make reports without one. SOS repeats every 30 seconds until a leader's phone confirms it, and rings on leaders' phones. The screen shows Delivered to leaders or not. A leader must be listening to the signed Public channel. Reports appear under Reports in Chat, and positions on the map.
- **Tasks and notes** (menu): for the whole channel, a team or a callsign, with an optional time limit and photo. Everyone can read them; only Advanced mode can add, and only the person who added one can change it.
- **Private contacts** (bottom of Contacts): Share my private comms asks their callsign and shows a QR with a new private key and optional remote server. If the callsign does not match on scanning, it can be ignored or kept as an unknown contact. Talk uses channel H. Private contacts are listened to all the time, and Delete removes them.- **Text as voice** (beside PUSH TO TALK): type a message and every phone on the channel reads it aloud, like map point announcements (not in Stealth). It also shows in Chat.
- **Missed calls**: a call not acknowledged after 30 seconds shows as an unread Missed call line in Chat (under Voice) with a notification. It reminds every 5 minutes with a beep, buzz, notification and the call screen until Acknowledge or Stop ringing is tapped, for up to an hour. In Stealth it only buzzes and notifies. Audio, PTT and alerts has the on / off tick (on by default).
- **Chat filter** (top right of Chat): show or hide messages, voice, pictures and files, map points, reports, status changes (hidden to start with; a missed call counts as Voice, so it still shows); and which channel
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
  **Telemetry overlay** (ticked to start): each picture carries this phone's position (with accuracy), the compass heading the camera points (with a small north arrow), tilt and roll, speed when the GPS knows it, and the time, drawn onto the picture before it is sent, so every viewer sees it. With a network camera the figures are this phone's, so keep the phone with the camera.
  Sharing keeps running after Home, another app or **Blackout** (it runs as its own service): a red-dot notification says the camera is live and has a **Stop** button. The Share my camera screen shows the pictures as they go out, and has **Torch** (the back camera's flash at full power) and **Blackout**; Tactical videos has **Blackout** in its top row and shows a dot on My camera while it is shared.
  videos** (a grid of chosen people's cameras, tap to enlarge).
  Tactical videos goes to **Blackout by itself** after 60 s with no touch and no camera trigger (the **timer icon** beside
  Blackout shows it on, or crossed out when off, and one tap switches it; menu > Auto blackout:
  on or off and the time). A triggered Reolink camera wakes it and shows that camera; it goes back to Blackout once
  the cameras have been quiet for the hold time.
- **Reolink cameras** (Tactical videos menu > Reolink cameras, or on the Intercom the **Camera** button or the
  menu dots > Reolink cameras, which ask for the PIN if one is set). One list, shared by both screens, kept only
  on this phone: user names and passwords are never in the setup QR, shared settings, LAN sync or logs.
  - **Find and add cameras** (a wizard, no internet needed): scans this network (an ONVIF discovery call, then
    every address for Reolink's port 9000 and the video ports 554 / 80; up to half a minute) or takes an address
    typed by hand (add :port for another web port) → tick the cameras → user name (admin by default) and
    password → test (signs in, reads the model, name and number of lenses, shows a picture; tries HTTP, then
    HTTPS for newer firmware) → for dual-lens cameras and recorders, tick the lenses (each becomes its own tile)
    → stream for the full-screen view (**Sub**, recommended; **Main**, best but often H.265, which older phones
    cannot play; **Pictures only**) → options per lens (name, **Bring to front when triggered**, **Linked
    floodlight**) → save.
    Newer Reolink cameras come with HTTP, HTTPS and RTSP switched off; the scan then shows them with only port 9000
    and says so. Turn on HTTP (or HTTPS) and RTSP in the Reolink app (camera Settings > Network > Advanced > Port
    Settings) and scan again.
  - **Tiles**: small still pictures (asked for at 640x360, about 30 KB) from each lens every 2 s, shown with the
    shared phone cameras on Tactical videos and TV, and in a strip above the rooms on the Intercom. They work on
    every Android version. A tile says "offline" and why if the camera stops answering.
  - **Full screen** (tap a camera tile): pictures at about 2 a second, **Live video** (the RTSP stream; if this
    phone cannot play it, or it does not start in 15 s, it goes back to pictures), **Listen** (the camera's
    microphone through the stream; live video always starts muted), **Light on/off** (cameras with a floodlight)
    and **Close**. Live video did not play on the phones tried so far (see below), so expect pictures.
  - **Triggers**: about once a second the app asks each lens for motion and AI detection (person, vehicle,
    animal) while Tactical videos, TV, the Intercom or a camera is open. Triggered cameras with Bring to front
    fill the Tactical videos wall together (one fills it, two split it...; tap to go back to the grid) and take
    the TV's video corner. On the Intercom they open on a full-screen camera page (also out of Blackout, but
    not while the intercom is talking); tap one there for its own full screen. Each stays up for the **hold
    time** after its own movement ends (60 s by default). When all have gone quiet the camera page closes, and an
    intercom that was in Blackout goes back to Blackout.
  - **Motion alerts to the channel** (per camera, Change options, for the phone or tablet that watches the
    cameras, e.g. one left on mains power with **Keep watching in the background** on): **Send motion alert to
    channel** sends a chat message such as "Motion: Garage (person)" on the channel that phone is on, so it also
    crosses the internet relay; **Announce it** makes the other phones read it aloud (text as voice); **Attach one
    picture** adds a single small JPEG (640 wide, about 30-50 KB). At most one alert per camera per hold time.
    **Tell the channel if this camera goes offline** sends "Camera offline: <name>" after 2 minutes without an
    answer and "Camera back online: <name>" when it answers again (one message per camera, not per lens; read
    aloud too if Announce it is ticked).
    Never live video over the radio or relay.
  - **Linked floodlights**: when any linked camera is triggered, every linked camera with a floodlight turns it
    on (only those that were off), and they go off again a set time after the last trigger (60 s by default).
    Triggers on linked cameras are ignored for 30 s after the lights go off, and after that until the camera's
    alarm has dropped once, so the light changing does not set them off again (real cameras hold the alarm).
    If the app stops while it has lights on, they are turned off the next time it starts watching.
  - **Settings** (on the camera list): sign-in offered for new cameras, floodlight time, hold time, whether
    plain motion and AI detection count, and **Keep watching in the background** (the linked floodlights and
    channel alerts keep working while the radio runs, with no screen open).
  - Tap a saved camera to view, change options, change its sign-in, test (sign-in, picture, triggers,
    floodlight) or remove it. Old firmware without token sign-in is handled (the sign-in goes with each request).
  - **Port 9000 cameras** (cameras with no HTTP or RTSP at all, such as the E1): the app signs in on Reolink's own
    port 9000 (our own implementation of the camera's LAN protocol, LAN only, never Reolink's cloud) for picture
    tiles, motion / person / vehicle / animal triggers and offline alerts. Find and add cameras tries this when HTTP
    does not answer, or use **Add a port 9000 camera** (name, address, sign-in). Full screen shows pictures only: no
    live video, Listen or light. Tested with an E1.
  - **Not included: talking to the camera.** Reolink only offers two-way audio over its own closed protocol
    (port 9000), not the web API, RTSP or ONVIF, so there is no Talk button. The E1 reports talk support (ADPCM,
    16 kHz), but the messages that start talk and carry the audio are not publicly documented.
  - Works with Reolink cameras. Reolink is a trademark of its owner; this app is not affiliated with Reolink.
  - **Tested with real cameras** (RLC-510A, RLC-810A, Reolink Duo PoE, Reolink Duo Floodlight PoE) on an
    Android 4.1 tablet: scan, sign-in, test pictures, the Duo PoE's two lenses (the Duo Floodlight PoE is one wide
    180° stitched picture, so one long tile; it has no separate lens pictures), tiles, floodlight detection and switching, and motion / AI state answers. ONVIF discovery
    found nothing (these cameras did not answer it; the port check finds them anyway). **Live video:** Android
    4.1's player is refused by the camera's RTSP sign-in, and Android 14's (emulator) signs in but its media
    service crashes on the stream, so both show pictures; it may play on other phones. Listen was never tried
    aloud. Multiple triggers, the Intercom camera page and channel alerts were tested against the pretend camera
    (tools/fake-reolink/FakeReolink.java).
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
- **Bluetti power** (a specialised menu, off by default; Settings > Home screen and menus, or a setup QR code):
  works with Bluetti power stations over **Bluetooth**, offline, never the Bluetti cloud (the units' WiFi only talks
  to that cloud). Needs Android 4.3 or newer with Bluetooth LE; Android 6 to 11 also need Location turned on to find
  the unit. **Find my unit** (menu) looks for nearby units for 8 s and you pick yours (close the Bluetti app first:
  the unit takes one connection at a time). The screen shows **battery %**, **power in** (AC, and DC / solar),
  **power out** (AC and DC), **time to full or time left** at the present load (from the battery size, 300 Wh for
  an AC30, editable in Settings), when it was last read, and **AC / DC output on and off** buttons (with a
  confirm). It reads every 10 s while open. Newer units that ask for an encrypted link get it automatically.
  - **Watch in the background** (Settings, on the phone left near the unit): reads every 60 s (every 5 min in
    Ultra low or while the radio sleeps) and lets go of the Bluetooth link between reads. **Quiet by default:** nothing
    goes to the channel until the unit is down to **10%** (Settings: "Send status and alerts ... at or below %"; 100 =
    always). From then it **shares a short status** ("Power station EL30V2: 9%, in 0 W, out 45 W, about 35 min left")
    every 5 minutes when it has changed (the minutes are a setting) and at once on a 5% change. The **On the channel**
    part of the Bluetti power screen lists the latest status from each phone (no Bluetooth needed there), and the
    **commander view** (and Contacts) shows a small power station icon with the unit's % next to that phone's own
    battery %; More details shows the whole latest message.
  - **Alerts** to the channel, like the Reolink ones (text, optionally read aloud on the other phones): **low battery**
    (10%) and **critical** (5%), **charging started / stopped**, **mains input lost / back**, **overload** (watts out,
    300 W by default) and **out of contact** for 10 minutes (and back), all held back above the send level. All the
    numbers are settings; 0 turns one off.
  - **Tested** on a Galaxy S5 (Android 6) with a Bluetti Elite 30 V2 (it reports itself as EL30V2; its WiFi name says
    AC30): finding it, the encrypted link, and reading battery % and the outputs' state. **Not yet tested on the
    unit:** the AC/DC switches, watt readings under a real load or charge, and sharing / alerts on the channel.
  - **Probe (raw log)** and **Show log** (menu) record what the unit says, in hex, to help add other models.
  - Works with Bluetti power stations. Bluetti is a trademark of its owner; this app is not affiliated with Bluetti.

## DRYAD extras
5-letter sheet IDs, optional random row order, huge AA–ZZ sheets (676 rows, not QR-shareable),
**Enigma** (plain and cipher text with Paste/Copy, Create/Read QR), **Challenge and response**, a
**How modern DRYAD works** page (bottom of Settings) and a Pigpen button on the DRYAD screen.

## Still to do
Automatic location sending after moving a set distance, a Windows app (use the commander APK in an
Android emulator, see NOMAD-SETUP.md), MeshCore integration, and testing with real hardware: a
FalconClaw, USB / network / intercom cameras, gunshot alert, crash detection, the tunnel over a real
router, the OpenMANET gateway switch, and radio streams with a real feed.
