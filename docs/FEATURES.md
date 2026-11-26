# Your Community Communications user guide (release 2, 2026-11-16)

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
A new phone gets a random callsign such as TANGO38 and a short wizard:
1. **Quick set from a QR code** first: scanning a phone setup QR code (or pasting one as text) sets
   everything at once, applied straight away with a summary. If the code had no callsign, it then asks for one.
2. **Your callsign** and units (greyed out when a setup QR code locked it).
3. **What is this device for?** (the device role, in alternating colours): **Phone radio**, **Commander**
   (base station), **Public intercom**, **TV / display**, **Camera and security monitor** or **Network
   gateway**. The role only ever turns things on (a commander gets Record all voice, dual watch and the team,
   battery and cast features; a TV opens in TV mode) and never sets Anchored.
4. Phone radio only: **Advanced or simple** (what each looks like), then **Advanced or Simple** with a
   **Profile** list (General, Friends and family, Security, Firefighting, Military, Emergency services,
   Scouts, Staff, 4WDing, Friends convoy, Caravan / camping) and an **I lead a team** tick. Simple with a
   Scouts, Staff, 4WD, convoy or camping profile picks that group type; any other profile is **General
   (simple)**.
5. **Voice** (burst, recommended, or live), **Power** (Normal with rest GPS, and an **Anchored** tick for a
   device on stable power), **Location** (share automatically when someone on a private channel asks, how far
   to obscure your position, sharing with Public and cloud groups, off) and **Maps** (ask before fetching
   online tiles, or always online; Data Tile Management keeps an area offline).
6. **Keys**: Advanced asks for the Public key and offers to **Create sheet** (a never-expiring DRYAD sheet:
   row A becomes channel A, row B channel B) or **Join Public**; Simple and the intercom ask for the group's
   **private key** ("Make one" picks a strong random key) and the Public key (the intercom also a Settings
   PIN). Then the Permissions screen and an **All set** summary (callsign, role, group type, profile, voice,
   Anchored, channel A, features) with **Setup QR for the next device**.

A camera monitor or gateway is then offered its setup (the Reolink wizard, or the Cloud Server / tunnel).
New installs open on the **map home** with the **General** quick buttons: I have arrived, On my way, Running
late, I need help now (a map point at you), Delayed, All OK, Call me, Let's meet here, Let's eat here, Car park, Share location until stop.
## Group types (first start, Settings > Me, or the top of the Channels page)
- **Advanced:** everything in this guide.
- **Simple modes: Scouts, Staff, 4WDing, Friends convoy, Caravan / camping.** One private key and the
  public key, no DRYAD, Pigpen or OpenMANET. The home screen shows **Private** (and Public, which can
  be hidden) and four quick buttons made for the activity (all of them are on the Shortcuts page).
  **Channels** opens the **Simple channels** page: the private and public keys, hidden until Show, with
  Make one, Show QR and Scan QR, the Cloud group (when that feature is on or a group is set) and an
  **eye** on each channel (shown on the home screen or hidden). Quick buttons either drop a map point (announced on everyone's phone), send a
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
At the top: **Simple channels | Advanced channels**, the same setting as the group type (Simple uses the
simple group type used last, or General, and keeps the profile). **Advanced channels** lists every channel: A, B, the extra **C–H** (switch at the bottom,
off by default), Public (with its key), the Cloud group and OpenMANET\* (when those features are on).
Each row: **eye** (shown on the home screen, room for 4, or hidden: a hidden channel keeps its key and
can still be listened to, but has no home button and no pinned PTT of its own; C–H keep their pins),
**key** (DRYAD), **note** (kept on this phone, moves with the channel), **⋮** (set the name, **Swap with
A / B**: name, note, listening and mute move together; **Mute**: listened to and saved, not played),
**Listen** and **Talk here / Leave**. At the bottom: DRYAD sheets, Pigpen, Enigma / CW, the Channel array
(when on), Cloud group… and Scan QR.
## Home screen styles (Settings > Screen)
The **map home is the default** for new installs; phones set up before keep the home they had (offered the
map home once). The map / buttons icon in the title bar switches between them.
- **Radio buttons**: channel buttons and **Channels**, status,
  **Contacts (n)** (red when nobody is online; "▶ NAME" while someone talks), **status button**, a
  small **camera** button, four quick map point buttons (pick which on the Shortcuts page), **Shortcuts**
  (shows "sharing location" while you share), Call, Silent, Chat, Map, Lock and a big PTT. Landscape puts
  the PTT on the right half.
- **Shortcuts** (the Shortcuts button, or the dots icon in the title bar on both homes): the current state
  at the top, each tap to change it: **Check for updates** (or **Update available: 0.1.N, tap to update**,
  with a red dot on the Shortcuts icon and button; the app also checks by itself every 6 hours), power mode
  and Mission mode (opens Power and batteries), **Anchored** and plugged in / battery %, **Black screen**
  (never, 30 s to 10 min, and the home screen button), **Voice** (live, or burst mode and its quality; burst is off and refused while ATAK compatibility or OpenMANET is on), status, location sharing, radio silent, Fake GPS and
  Obscure location. Then **Edit current layout (profile)** (change the profile, edit the buttons as an editable
  copy, or pick the four on the radio home) and, beside it, **Use a button** (the layout's buttons, to use one now).
- **Menu**: Chat, **More…**, Settings and Exit. **More** groups everything else by job (Talk and chat, Map
  and location, Team, Cameras and power, Codes, Network, Tools, Screen), showing only what is switched on.
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
The main Settings page is **Me**: callsign (locked when a setup QR code said so), team name, Run the setup
wizard again, Permissions, **Device role** (a list of what it will turn on, to confirm), **I lead a team**,
**Group type**, units and **Phone setup QR code**. The setup code can now carry the device role, group
type, team lead tick, a callsign (optionally locked), Anchored and the Advanced and Region features, beside
the LAN storage, channels, Public key and Cloud Server it always had; it is applied without asking, and a
LAN storage server is optional. Then the pages:
**Channels and listening** (names, QR codes, Public key, OpenMANET, key rotation, rejoin, dual watch,
catch-up, offer missed messages, Share settings), **Voice and PTT**, **Alerts** (calls, map points,
directions, status, gunshot, drop-out, crash), **Screen** (home screen, orientation, invert, map point
buttons, my icon, TV mode, black screen, red light), **Power and batteries** (power modes, Anchored,
Mission mode, sleep), **Location and privacy** (Fake GPS, Obscure location, automatic location sharing),
**Network** (WiFi, peers, ATAK, tunnel, Cloud Server), **Security** (PIN, wipe, radio silent, Clear all data and exit), **Data and
backups** (mission, LAN storage, sync, config backups, clearing data), **Advanced and Region features** and
**About and updates** (**Update from URL** / **Edit update URL**: by default the newest GitHub release,
https://github.com/yourcommunitycomms/ycc/releases/latest/download/YCC.apk, or the LAN storage's
Updates/YCC.apk; About; How Modern DRYAD works), plus Tools.

**Advanced and Region features** (once called Specialised menus): a checklist, all off by default and set
by a setup QR code. Advanced: Channel array, Report to leaders, Tasks and notes, Calendar and notes,
Battery power, Cast screen to TV, Relay admin, Cloud group, T.REX Arms Topo Map. Region: VicEmergency
alerts, Melbourne trains preset, Load station names, ARTC rail. **More…**: Project NOMAD, ATAK items,
OpenMANET, Internet audio. **Advanced setup** lists short guided setups (what it is, the steps, then the
screen that does it): Cloud Server and Relay admin, Join the Cloud Server, Cloud group, Internet tunnel,
LAN storage server, Cameras (Reolink), Batteries, ATAK and OpenMANET, Channel array. Ticking Relay admin,
Cloud group, Battery power or Channel array offers its setup.
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
  double-tap to wake; the power button or leaving the app also ends it) in the menu. Blackout gives no light at all. On an older device where double-tap does not wake it, tick **Legacy support, non zero brightness** in Settings > Power and sleep. While black, turning the phone does not turn the screen or bring it back: it keeps the way it faced until the double tap, then turns as usual.
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
- **Planning and sharing a route:** an offline route is shown first, with **Start**, **Route…** and **Stop**. Route… can **close the road at the +** (a tree down, a flood: roads within 25 m are avoided), **+ Go via here** (a small blue dot at the map's + that shapes the route, no questions), **+ Planned rest / meeting stop here** (an orange cup, with a name, planned time, length and note), **+ Add a waypoint here** (a purple flag with a name and note; new points go into the order where they add the least distance), **Edit this route** (see below; it replaces Undo), clear those, **share this route** (to the channel chat, one person privately, or another app as text), work out a new route now, and turn automatic new routes on or off. A shared route shows in Chat as one line; tapping it opens the same route (same start, via points and closed roads) ready to Start. **Don't reroute** is on by default (Settings > Audio, PTT and alerts): leaving the route only says how far off you are.
- **Home screen choice** (Settings > Home screen and menus): Radio, Map, **Full map** (the app opens straight into the full map) or **TV mode** (opens in TV mode). **Screen orientation:** Auto, Portrait or Landscape for every screen, for a phone that keeps turning the wrong way, and **Invert screen** (upside down, e.g. mounted on a plate carrier with the leads out the other side; on Auto, the way the screen is held when a screen opens).
- **Specialised menus** (Settings > Home screen and menus): Channel array, Report to leaders, Tasks and notes, Project NOMAD, VicEmergency alerts, the Melbourne trains preset, the Load station names button, the ARTC rail network the ATAK items in the map menu (import map file, export for ATAK, ATAK map source) , **Cast screen to TV**, **Calendar and notes**, **Relay admin**, **Battery power** and **T.REX Arms Topo Map** are hidden until ticked here (Trains on the map itself is always in the map menu, for your own home town's feed). All are off by default. A phone setup QR code carries the ticks of the phone that made it, and a team setup code turns on Tasks and notes (and Report to leaders with an approved code).
- **Calendar and notes** (a specialised menu): **My calendar** (on this phone), **Team** (entries shared on the channel; only whoever made one can change it), **Both** (team entries in amber, mine in green) and **My notes**. A month at a time with ◀ ▶. Entries have a title, date, time or all day, length, a note, and **Remind me** (at the time, 5, 10, 15 or 30 minutes, 1 or 2 hours, or a day before) with or without a **buzz**; team entries with a reminder remind everyone on the channel. Notes have a name, a date and text with pictures in it (**Insert picture / screenshot** puts it where the cursor is). Notes and my calendar never leave the phone.
  - **Protected notes and calendar entries**: the padlock at the top of Calendar and notes. The first tap asks you to create a password (twice), with the warning that there is no recovery: forget it and the protected entries are lost. Later taps ask for it to unlock. **3 wrong passwords in a row erase all protected entries** and the password (the count survives leaving the screen and restarting; the right password resets it); the unlock box shows the attempts left. While unlocked, protected entries show among the others with a small blue padlock, and a new note or entry has a **Protected (this device only)** tick (protected notes cannot hold pictures, and a protected entry can never be a team entry). Every time the screen is opened, or the phone leaves it (Home, screen off), it locks again: protected entries vanish and the tick is gone, though normal notes and entries can still be added. Tap the open padlock to lock at once. The menu (top right) has **Change protected password** (needs the current one) and **Erase all protected entries** (for a forgotten password; no password needed). Protected entries are encrypted (PBKDF2-HMAC-SHA1 with 10000 rounds and a random salt, then AES-256-CBC with HMAC-SHA256 per entry) in their own file on this phone; the password and key are never stored. They are never sent on air, shared, put in a setup QR code, Save my config, LAN or NOMAD sync, and the lost-phone wipe deletes them. A protected entry starts with no reminder; its tick reads **Add a Protected Meeting reminder**, and the reminder shows only "Protected Meeting reminder" and the time and is set again after a phone restart only once you unlock.
- **Cast screen to TV** (menu, when ticked): opens the phone's own Cast screen list (Google TV, Chromecast, smart TVs; some phones call it Smart View or Screen mirroring) so the app shows on the TV. While casting, the screen stays on and the black screen timer waits. Nothing is sent until casting starts; while casting, a continuous video stream uses the WiFi and battery, so stop it when done. **Stop casting after 1 hour without using the app** (in the Cast box, off by default) ends a forgotten cast; where a phone does not let an app end it, a notification asks you to. A Google TV can also run the app itself in TV mode.
- **ARTC rail network** (a tick under Layer): Australia's interstate and freight track (ARTC Rail Centreline, CC BY 4.0, Australian Rail Track Corporation) as orange dashed lines, from map zoom 6 in. Read in squares of about 70 km as the map moves and kept on the phone, so areas seen before show offline.
- **Trains (live)** (map menu, and a tick under Layer): live trains on the map from GTFS Realtime feeds, every 30 seconds (or 2 minutes in slow mode), within about 80 km of the map. Tap a train for its line, speed and its next stops with times and delays. **Melbourne** (Metro Trains and V/Line) is a tick and needs a free Transport Victoria open data key. **We recommend setting up your own home town:** most transport agencies publish GTFS Realtime feeds on their open data page; put its vehicle positions address (and trip updates address for timetables, plus a key header and key if it needs one) in the boxes. Station names come from the timetable's stops.txt (Load station names…); without it stops show by number.
- **Route… > Download detailed map tiles along this route:** zoom 14 to 18 (ticks, up to 17 by default) and the map types, for about 300 m either side of the route, from the phone, the LAN storage, then the internet (in the background with Pause / Stop, and given to the LAN storage).
- **Routes** (map menu): saved routes (★) and the last 10 planned, each with its via points and closed roads; open one to plan it again, save it for later, or delete it. **Route… > Save this route for later** names and keeps the current one. Every closed road you mark or receive in a shared route is remembered: when a later route goes along one, it asks "marked closed X ago: ignore it?" with Avoid, Ignore or Forget it. Routes… can also forget all remembered closed roads. A shared route carries its via points and closed roads (with when they were closed).
- **Route editor** (Route… > Edit this route, or Routes > a route > Edit): route notes (e.g. "Meet at the servo 10am"), the start (set to the map's + or my position), each via point, waypoint and rest stop in order (move up or down, change its name, note or kind, move it to the map's +, show it, delete), the destination (rename, move to the +) and closed roads (show, open again). **Add at the +** adds a point; **Done** works the route out again offline; **Cancel** puts everything back; **Show on the map** closes the editor (Route… > Edit opens it again). Notes, names and stop times are saved with the route and sent with **Share this route**: the chat message lists them, and the receiving phone shows the dots, flags and cups with their names, the notes in the route bar and editor. While following, waypoints and rest stops are announced about 500 m ahead (100 m walking) and on arrival, with their note, and the bar shows the next one. Older app versions that receive a route with stops still get the plain route (stops as via points).
- **Geofences:** a point drawn as a rectangle, circle or oval can be ticked **Geofence: alert me when a contact leaves it**. The phone that made it watches the positions contacts share: when one goes from inside to outside it sounds a warning, speaks it and shows a notification, and (if chosen) also sends a message to the channel ("Geofence: X has left Camp", with where).
- **Off-route warning:** following an offline route, more than 500 m off it sounds a warning (Audio, PTT and alerts: on by default), and can also tell the channel (off by default). Beyond 500 m it does not keep making new routes.
- **Personal points:** tick **Personal** when placing or editing a point and it stays on this phone only: never sent, synced, resent or shared on Public. Making a shared point personal removes it from everyone else's map.
- **Sync** (full map, refresh icon) offers Sync (all your active points and asks everyone for theirs) or Resync (clears others' points first).
- **Map points:** tap one to see, edit or remove it; you are asked whether to **announce** the change
  (default yes; if not, maps still update silently).
- **Quick map point profiles** (Settings > Quick map points, or "Change profile" in the Mark…/More…
  list): Friends, Security, Firefighting, Military,
  Custom (edit text, announcement, icon, duration, my position or map centre).
- **Tracking my path** (full map > Tools > Start tracking my path): pick how often a position is taken, **Low** (every 3 minutes, to save battery), **Walking** (10 s), **Running** (5 s), **Riding** (3 s) or **Driving** (2 s). Your path is drawn in red with a ring at the start point and, zoomed in to street level, small white arrows showing the way you went. A red bar over the map shows the moving time, distance and current lap, with **Pause** / **Resume** (each pause is a break: no line is drawn across it and breaks are left out of moving and lap times), **Stats** and **Stop**; the same controls are in Tools and in the notification, and it keeps recording with the screen off or off air. **Laps:** passing your start point again (within 25 m, after going more than 60 m away and at least 20 s after the last lap) counts a lap and gives a short buzz, but only when you are heading the same way round as when you set off (checked as you head off again, within 75°; the lap is timed from when you passed). An out-and-back passing the start the other way is not a lap; stopping at the start counts the lap if you arrived heading the lap direction. **Stats:** total time, moving time, breaks (count, total, longest), distance, average pace or speed, laps done, this lap, last, fastest, slowest and average lap, each lap's time, and steps. **Count steps** (a tick, off by default; phones with a step counter, Android 4.4+; Android 10+ asks for the Physical activity permission). Tracking will not start while **Fake GPS** is on: it asks to turn Fake GPS off first, and turning Fake GPS on while tracking holds the recording until it is off again. **My tracks** (Tools) lists saved tracks to show on the map, see stats for or delete. Tracks stay on this phone only, and **Clear all data** erases them.
- **Sharing my path live:** when starting (or Tools > Share my live path with…), choose who gets it, such as the team leader or commander. Only on a private channel (never Public) and while on air; other phones on the channel ignore a path not addressed to them. The **Path** button beside each person in Contacts (and the tablet commander view) shows their path on your map in its own colour with their name: it asks them for it, and they choose **Share** (what is recorded so far, then live), or, if they are not tracking, **Start tracking** or No (you are told). Tap **Path ✓** to hide it; Tools > Hide everyone's paths hides them all. Received paths are kept in memory only; Clear mission data clears them.

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

## Tools (Settings > Tools)
Everyday helpers that are not radio features, on the main Settings page (not the home screen).
- **Create QR from text**: type or paste any text and tap Make QR. The code fills a bright screen;
  **Save image** puts a PNG in Pictures/YCC (Android 6 to 9 ask for storage permission the first
  time), **Share image** hands it to any app.
- **Scan QR code**: reads any QR code with the camera (permission asked on Android 6+) and shows its
  text. Nothing opens by itself: **Copy**, **Open in browser** (only for http:// or https://),
  **Use as QR text**, **Put in browser field**.
- **Open in browser**: type, paste or take the QR text, then Open. Addresses that start with http://
  or https:// open in the phone's browser straight away; anything else shows a warning first
  (with **Add https:// and open** when there is no scheme at all).

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
  - **Anchored button** (title bar of the radio home, map home and full map): while plugged in it shows a grey crossed anchor (not anchored) or a gold anchor (anchored); tap to set or clear Anchored, with a question first. An anchored device on battery shows a red dot on the anchor (power lost). Plugging in never anchors a device by itself.
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

**Cloud group** (Channels page > Cloud > *Cloud group…*, or in Simple modes Channels > *Cloud group…*;
tested on emulators against a local test relay, not yet on a real relay or real phones): a community
channel like Public that reaches people anywhere through **its own Cloud Server** (a relay; the screen's **Cloud Server** section), and **Scan QR** on the Cloud group screen reads a group's QR code (shared from another phone's Show QR code) and offers to join it in one go;  separate from this
phone's own tunnel and internet relay. It has a name, a **group key** (the channel passcode, the same
for everyone, never rotated) and the relay's address and **relay key**. The relay key is a *cloud
group* user on an internet relay (Relay admin > **Add cloud group**): unlike a site or remote, many
phones share that one key, each with its own session, and they are not sent the relay's site list.
The group shows as **Cloud** beside the other channels (home screen and Channels page); while it is
on air or listened to, the phone keeps a connection to that relay carrying only the Cloud channel.
**Share link** (`ycc://cloud/…`, sent with any app) and **Show QR code** carry only this group and its
settings; opening the link or scanning the code asks *Join cloud group?* and replaces any earlier
cloud group. Anyone with the link can join, hear it and use its relay, so treat it as public.
*Paste a link* takes a copied link; *Remove group* forgets it. Old QR codes and links work as before.

**Show receive-only code** (on the Cloud group screen) makes a second link and QR code for the same group that lets people **listen and read but not send**, like Public's listen key: it carries a listen key instead of the group key, so their phone can check messages are genuine but cannot sign its own. Opening it asks *Listen to cloud group?* and says RECEIVE ONLY; the Cloud group screen then shows *Receive only*, and sending a chat message says the phone cannot send on this channel (nothing goes on air, and the others would ignore it anyway). Give the normal link to people who should talk, the receive-only one to people who should only listen. Tested on emulators only.

**Share my location with public and cloud groups** (on the Cloud group screen; **off by default**):
while off, no position goes to Public or a cloud group: not Send my location, not the timed or
distance updates, not check-ins or answers to location requests, not map points (quick points, "share
on Public"), and automatic alert messages (crash, gunshots*, geofence) leave their coordinates out on
those channels. Private channels are not affected, and everyone's messages still show. Ticking it shows
a big warning (everyone on those channels gets your position; once sent it cannot be taken back) and
needs a second yes.

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
this guide, and the **ready-made APKs** below it),  **Sync** (copy new files to other stores such as a Pi: chosen folders, every N minutes
between set hours, a KB/s limit, Test login, Run now / Stop; never deletes anything there),
**Bridge** (the server's bridge: joins the WiFi to a Waydroid or emulator tablet on the same computer, and is the **tunnel relay** for remote people, each with a forwarded port, a DRYAD-row key and their own QR code; on/off and live status), **Updates** (publish an APK; the version is read from it), **Login**, **Docs** (Apache-2.0 licence,
rebuilding with the AI prompt) and **Erase** (everything back to an empty store for a clean snapshot;
type ERASE). Destructive buttons ask first.

**Ready-made APKs (Phone setup page).** Stamped copies of the published app that already know this server, so a
new phone needs no QR scan. The stamp sits in the APK's signing block, which the signatures do not cover, so
the APK installs and updates like the plain one (Android 4.1 and newer).
- **Site APK** (Updates/YCC-site.apk, no login, with a QR code to download it): on first start the app asks "Set
  up from <site>?" and fetches the Phone setup code from here, without the store password and channel A unless
  the admin ticks that (then the phone asks for the password later). Role, group type, profile and features
  can be preset.
- **Personal APKs**: one per person, with their callsign (optionally locked) stamped in and a long random code
  (30 letters) in the file name and its own update address, e.g. Personal/ycc-<code>.apk, so it can't be
  guessed; the folder is never listed. On first start the phone asks "Set up as CALLSIGN?" and fetches the rest
  of that person's setup from Personal/<code>.setup. The app's Update from URL then uses that person's own
  address. Per person: **Edit**, **Copy** (the same settings for someone else: just change the callsign),
  **New code** (the old APK and address stop working), **Revoke** (the APK is deleted and the code refused) and
  **Re-setup** on or off (off: the setup is given only once).
- **Settings** for the site APK and each person: everything the setup wizard asks (device role, leads a team,
  group type, profile, voice, Anchored, share location when asked, obscure range, location on Public / cloud,
  online map tiles, channel A, the Advanced and Region features checklist, a cloud group, and Fake GPS on or off
  near given coordinates, which goes in the APK itself), each "(the phone
  chooses)" unless set, on top of the phone setup above it (store, Public key, tunnel, OpenMANET, home WiFi).
- **On the phone**: a stamped app fetches its setup straight away on first start and shows what it will set
  ("Set up as CALLSIGN?"); nothing is applied without that tap. If the fetch fails (not on the network, server
  down, revoked, already used), it says why, with **Retry** and **Continue without it**; Settings > Me > **Set up
  from this app's server** tries again later.
- **Keys in the APK**: the Public channel key (ticked by default) and, if ticked, a cloud group can go in the APK
  itself; private channel keys never do. Keys in the APK stay in the installed app after the app's own wipe.
- When a new app is published (Updates tab, or put there by a phone), every stamped APK is made again from it
  within a minute.
- After **Clear all data and exit** on a stamped app, Android is asked to uninstall it too (the person
  confirms), so no stamp is left. Setting up from a stamp is always asked, never silent.

## More map features
- **Online map tiles…** (map menu, or tap the Tiles line): online for 5 min, 1 hour, 1 day,
  permanently, or offline now. Android 4.x uses Google's road map in place of OSM/Topo/Esri (their
  servers need newer TLS).
- The + position as lat/lon and **MGRS** (lat/lon on one line, the whole grid reference on the next, never split); ▼/▲ folds the buttons and list away; **+ POI** adds a point In the point's box the position line ("at …" with the grid reference) can be tapped to **type a position**: GPS latitude / longitude (decimal or degrees minutes seconds) or a grid reference (full, square + figures, or 6 / 8 / 10 figures in the map's square); it then reads "GPS: …" or "mil: …".
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
- **Chat filter** (top right of Chat): show or hide messages, voice, pictures and files, map points, reports, status changes (hidden to start with; a missed call counts as Voice, so it still shows); and which channel The channel list includes **Cloud group**, and each message's top line ends with its source (A, B, PUBLIC, or CLOUD with a little cloud).
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
- **T.REX Arms Topo Map** (a specialised menu, off by default: Settings > Home screen and menus, or a setup QR code; then map menu > **Data import and layers**, or the Data screen's button). With the menu off, the Layer choice, the menu entry and the button are hidden and nothing is sent to or read from the cache server; imported files are kept for when it is turned on again. It is an offline
  topographic map (OpenStreetMap with hill shading, usually zoom 12 to 15) baked as an ATAK .mbtiles file
  on the T.REX Arms Maps site. **How to make the file** gives the steps and opens maps.trex-arms.com in the
  browser; **Import .mbtiles file** picks the file, copies it into the app's own folder (delete the download
  afterwards) and shows its size, tiles, zooms and bounds; **Remove** deletes this phone's copy. A file that
  is not SQLite or has no tiles table is refused and nothing is kept. Choose it under **Layer** on the map:
  **T.REX Arms Topo Map** is one of the map choices with Street map, Satellite and Topographic (picking
  another turns it off). It is drawn first wherever it covers (past its top zoom the deepest tile is
  enlarged; elsewhere the usual map shows), with its credit line (© OpenStreetMap contributors plus the
  file's own attribution).
  **Sharing through the cache server** (Settings > LAN storage location): an imported file is also sent, in
  the background, to the cache server's own **T.REX Arms Topo Map** folder (separate from the Maps cache and
  the ATAK imports): the whole file, plus each tile so other phones can view it. **Send to cache server**
  sends it again. Under **On the cache server** other phones see each file's name, size, zooms and bounds,
  and **Download** saves it on the phone for offline use (it carries on after a break only where the server
  supports that; otherwise it starts again). Until then, with T.REX chosen under Layer, a phone reads the
  tiles it needs from the cache server as it pans and keeps them; if the server does not answer, the usual
  map shows.
- **Commander view** extras: ▶ slides the side buttons away, leaving a small PTT and + POI on the map.
- **Commander view:** someone back after more than 2 minutes offline who missed messages brings up a 10 s
  pop-up, "X has been offline for Y min and missed N messages on B. Send?" (Settings can switch it off).
- **Missed live voice:** phones with Record all voice send their recordings to a phone that comes back
  (one announces, the others leave those out); they arrive unread in Chat.

## Mesh testing (Settings > Advanced and Region features > Mesh testing; all off by default)
Four tools for testing a mesh, on the **Mesh testing** page (Feature menus > Network, Settings, or the Shortcuts row).
The page starts with two buttons, **Stationary** and **Roaming**, neither chosen until you pick one (remembered;
a setup QR can set it with "mtRole"). Stationary shows Map mesh ("Set antenna fixed here": the base node's antenna point, by GPS or a long-press on the map, drawn as an antenna; walk distances are measured from it; polling the base node) and the Echo
Test; Roaming shows the Mapping walk (fixed station, my mesh node, Start walk; the rarely used settings under
"More options"). Play audio is under "Other tests" in both. A red line at the top warns when nobody else is heard on
the channel (check the channel name and the mesh WiFi), and, roaming, when the chosen stationary phone has not been
heard for 30 s, with a Channels button.
**Play audio to channel works on private channels only**: never on Public, the cloud group or OM/TAK (no tick,
no setup QR can allow it; switching to one while it plays stops it). Echo Test, Map mesh and Mapping walk are for
private channels too, but each can be allowed on public ones with "Allow on public channels", and then warns every
time (everyone on the channel hears it and it uses airtime for all). The Play audio page shows only the chosen
source's controls (stream address and recent addresses; playlist with up / down / remove and loop; or the phone
speaker output notes), with volume, auto-stop and monitor shared below.
While test audio, Echo Test or a walk runs, a red banner on the home screen says so; tap it for **Stop all**.
The ticks travel in the phone setup QR like the other Advanced features.
- **Play audio to channel**: an internet radio stream (plain HTTP/HTTPS MP3 or AAC, Icecast, Shoutcast,
  .m3u / .pls) or audio files picked on the phone (a playlist, optional loop) decoded on the phone and sent
  as live voice from it, as if PTT were held: normal encrypted voice on YCC channels, the standard Opus
  48 kHz 20 ms stream on the OM/TAK talk group. Volume / gain, auto-stop (10 min by default), now playing
  line (the stream's title when it sends one). Nothing plays on the sending phone unless "monitor" is ticked.
  PTT waits while it plays. Not supported: Ogg/Opus/FLAC streams and HLS (.m3u8).
  **Phone speaker output** (Android 10 or newer; greyed out on older phones): whatever another app plays (music,
  a radio app, a browser) goes over the channel. Start the other app, tap Start and allow Android's "start
  recording or casting" prompt (a notification shows while it runs). Media, game and unknown audio is captured;
  YCC's own sounds never are (no loops). The phone still plays it out loud unless its media volume is turned
  down. Some apps block sharing (protected music, some streaming apps): then silence is sent and the page says
  "No audio captured" after 8 s.
- **Echo Test**: the phone also shows as a contact called ECHO TEST (editable). Talk to it: it records the
  transmission (direct, or through a relay, the internet relay, a cloud group or a channel link: "Answer relayed and
  remote tests", on by default, untick for direct-only mesh tests; never another Echo Test or itself), then
  2 s after you let go posts "Echo Test: received from you", 3 s later a link report as a text-as-voice
  message (read aloud on the phones and shown in chat) that says how it arrived, e.g. "Received a test from ALPHA121, via internet relay. 312 packets,
  38 lost, 4 late, 2 duplicates, duration 6.2 seconds, average jitter 45 milliseconds, signal fair", then
  plays your test back exactly as it arrived (lost frames left as gaps). Longest recording 30 s, 5 s between
  echoes, others wait in a queue. Optionally answers chat messages saying "echo" or "@ECHO TEST" with the
  recent link figures, and shows itself on the map. YCC channels only (not OM/TAK).
- **Map mesh** (coverage map): on a phone ticked "This phone is fixed here" (its GPS or a long-pressed map
  point), records every position it hears from other phones straight off the network, never through a relay,
  tunnel or the internet, with time, callsign, channel (A/B/Public, never the channel name), hops, the
  sender's recent loss and jitter, and with the fixed node polled, signal and throughput. Dots: filled =
  direct (green good, amber fair, red poor; or by signal), blue ring = 2 hops, purple ring = 3+, square =
  hops unknown, the fixed station as a cross. Filters by callsign, time and hops; Export CSV / GPX (saved in
  Data / files and offered to share); Clear; up to 20,000 points kept (oldest dropped); also a layer on the
  full map. Works offline.
- **The fixed station's node**: the fixed phone can poll its own OpenMANET node (normally the Raspberry Pi
  base), read only, every 5 s or slower: its neighbours (name, signal, throughput) and hops to every node.
  The node login is held in memory unless "Remember" is ticked. The walkers can also read their own Heltec
  node (below).
- **Mapping walk**: the moving phone picks the fixed station from its contacts and probes it every second
  (1-60 s; walks set up before 2026-11-26 move to 1 s once). The fixed station answers each probe with what it measured. Big status: green **Direct link OK**,
  blue **Working via relay (N hops)**, amber **Weak / losing packets** (20% of probes lost, high loss at the
  fixed end, over 1.5 s round trip or high jitter), red **No link to fixed station** (3 probes unanswered),
  with loss, round trip, jitter, hops, the walker's node signal and throughput, and the time since the last
  answer. **My mesh node** is found from the phone's WiFi name (an OpenMANET node's access point is named after
  it, e.g. localnetHT01 for HT01), or picked from the list; it lets the fixed station tell direct from relayed.
  **Also read MY node** (on by default; asks for the nodes' login once per walk, Skip is fine): the walker reads
  its own node read-only every 10 s (5-60; every 30 s and "busy" when the node is slow or does not answer; one
  request at a time): its neighbours with signal and throughput, its hops and next hop toward the fixed node.
  So both sides of the link are shown (the fixed node's view of the walker's node and the walker's node's view of
  its next hop), stored with every trail point (CSV columns my_node_signal_dbm, my_node_throughput_kbps,
  my_node_hops, next_hop, worse_signal_dbm) and summarised at the end. When the fixed station cannot tell the
  hops, the walker's node's count is used. A coloured trail of the walk; the fixed station plots the probes on its Map mesh layer too.
  Optional gentle vibration on status changes (no sound). Walk CSV / GPX.
  **From the full map:** menu > Mapping walk starts a walk (pick the fixed station), shows a status strip above
  the map (colour, status, loss, round trip, hops, last answer; tap it to stop) and draws the trail live. Leaving
  the map or the Mesh testing page never stops the walk. Every probe is stored with its status colour (green
  direct, blue via relay with hops, amber weak, red no link); tap a trail or Map mesh dot for its figures.
  **Own GPS during a walk:** the walker asks the GPS for a fix every second while the walk runs, so every probe
  has its own position (before, probes used the app's shared last fix, which could be minutes old when location
  sharing was off or resting, and a whole stretch of the walk landed on one spot).
  **Live graph (last 30 s):** under the big status on the Mesh testing page and under the strip on the full map:
  one bar per probe, per second, right = now. Green answered, amber slow (over 500 ms, taller = slower), red lost
  (full height), grey waiting; above it "Last 30 s: 26 answered, 3 lost (10%)" and the round trip average / max.
  **Flags:** the ⚑ Flag button beside the graph (or tap the map strip > Flag this spot…) marks where you are
  (taken when the dialog opens) with an optional comment, e.g. "drops behind the shed". Flags show as pennants
  on the map (tap one for its comment and time), are listed in the summary, and go into the CSV (status "flag")
  and GPX (waypoints named "Flag: …").
  **Notes:** the summary has a notes box (saved with the walk as you type, shown again in Past walks, added to
  Share and the exports). Summary buttons: On map, Done, Share.
  **When the walk stops** a summary is shown and saved with the walk: start and end, duration, distance walked,
  probes sent and answered, count and share of time per colour, average and worst loss, average and max round
  trip and jitter, signal range, the longest no-link outage and where it began, the furthest direct point from
  the fixed station, and the hop breakdown. Share it as text, CSV or GPX (with the status colour per point), or
  view it on the map. Past walks are listed (Mesh testing page or the map menu) and can be reopened. "Post the
  walk summary to the channel chat" is off by default.

## OpenMANET Mesh (needs only OpenMANET on; Feature menus > Network, Shortcuts, Mesh controller > More, or the title bar icon)
What this phone's OpenMANET node sees, read-only: **my node** (found from the WiFi name, or picked, or a typed
address in its Settings), its **neighbours** (signal in dBm with bars, throughput, last seen), the **mesh layout**
from it (hops and next hop to every node), its **load and uptime**, and each **contact's link** (YCC's own loss
and jitter, plus the node's view of the contact's node when it is known from their walk probes). It reads the node
every 10 s while the page is open. A small **signal bars icon** in the title bar shows the best neighbour's signal
(grey when not read) and opens the page; it reads every 60 s (30-600), never in Ultra low, Away or Offline or with
the screen off; turn it off in the page's Settings. A slow node is read every 30 s and shown as busy; never more
than one request at a time. The login is the Mesh controller's (its user name; its password only if Remember was
ticked there or here), otherwise held in memory until YCC closes. Without a login the page shows YCC's own
figures and a **Log in for radio data** button.

**Sharing the node login with team phones (Mesh controller > More, off unless the Mesh controller is on).**
OpenMANET's openmanetd has no read-only accounts (it logs in the node's system users and has no roles), so for now
the admin can share the ADMIN login they are logged in with, so team phones can read radio data. Five warnings must
be ticked and SHARE typed. Then: send to everyone on the **private** channel or one callsign (never Public, the
cloud group or OM/TAK), or show a QR sealed with a share PIN of 6+ characters (tell it by voice; the QR hides after
60 s, screenshots blocked). Over the channel each phone that **Accepts** sends a fresh key of its own; the admin sees
its 6-digit key check number (both screens show it, compare by voice if you like) and taps Send; the login is
sealed to that phone's key only (the channel name alone is never enough). Receivers never see the password
("●●●●● from BRAVO7"); it is kept in YCC's private storage (no backups, not in the setup QR, exports or logs) and
used only to read (OpenMANET Mesh, Mapping walk); a phone with the Mesh controller on can use it there too, under a red
"Admin login from ..." banner. **Forget** (OpenMANET Mesh, or Mesh controller > More) clears it; the admin's **Revoke**
asks the phones that hear it to forget, but the real revoke is a new password on every node. A log keeps who
shared, accepted, sent, revoked or forgot, and when (never the password).

**Read-only node login "ycc-ro" (the better way; Mesh controller > More > Read-only login for team phones).** One user and ONE
password for the whole team, the same on every node, letters and digits only (8-32, one case: easy to read out over
another radio or send in Morse; a suggestion like MAPLERIVER42 can be edited). **Set on all nodes** logs in to each node
in the list with the admin login over OpenWrt's web API (/ubus) and adds an rpcd login allowed ONLY to read: the HaLow
neighbours and signal (mesh11s), wifi info, system info (load, uptime) and a small YCC script that prints the batman
neighbour and route tables and the node names. Each node shows ✓ or ✗ with the reason; press again to retry the failed
ones; the same button changes the password; **Remove from nodes** takes it off. Phones get it by typing it in (OpenMANET Mesh >
Read-only login, or the walk's login prompt: user ycc-ro already filled in), a plain QR (**Show QR** / **Read QR**), the
setup QR, or the server's Phone setup (site / personal APK, stage 1). OpenMANET Mesh and walk polling use it first and the
admin login only as a fallback. With it, hops are 1 or "2+" (batman knows only the next hop).
**Customize OpenMANET (Mesh controller > More > Customize OpenMANET).** Node-side tweaks pushed with the admin login over
/ubus, node by node with a tick or cross each (press again to retry). **WiFi mode** (pick a mode, then Apply to nodes… and tick the nodes; none are ticked to start with): **Normal** (WiFi and Ethernet both on, bridged, as OpenMANET ships: removes the service below and switches WiFi back on if Ethernet only had switched it off), **Ethernet preferred** (the service below: WiFi off when the node finds its cable), **Ethernet only** (the 2.4 GHz radio is switched off in the node's wireless settings and stays off after restarts and with the cable out; HaLow stays on; the old setting is kept in /etc/ycc-ethonly so Normal puts it back; skipped where radio0 is also a WiFi uplink). Check nodes shows each node's mode. **WiFi off while Ethernet is plugged in** (less RF
to be found, less power and noise): installs a small service (/etc/init.d/ycc-ap-auto) that turns the node's 2.4 GHz
access point off 10 s after its Ethernet port gets a link and back on when it is unplugged. Runtime only (a reboot always
starts with WiFi on), skipped while the 2.4 GHz radio is also a WiFi client uplink, failsafe: cable in but cut off from the
gateway (and mesh) for 2 minutes = radios back on. Heltec nodes only unless "Also on non-Heltec nodes" is ticked (on a Pi
gateway the Ethernet is usually the internet uplink). Optional "Also turn the HaLow mesh radio off" (warning: the node then
leaves the mesh while wired; only for nodes whose cable carries their traffic). No polling (these nodes are CPU limited): the service looks at the port a few times in the first 2 minutes after each start (installing counts as one), acts once and exits; what it decided stays until the node restarts (unplugging later does not bring WiFi back; restart the node). There is no failsafe any more. Only a cable on the mesh LAN counts (an Ethernet port bridged into br-ahwlan); a WAN / internet uplink (the default route, e.g. a Pi gateway's eth0) never does, so on a Pi gateway the service does nothing. **Turn off (remove)** stops it (radios back
on) and deletes it. **Each node its own DHCP range:** every node hands out addresses on the same mesh LAN, so overlapping
pools can give two phones the same address. **Check nodes** lists each node's board, WiFi-off state and DHCP range;
**Fix overlaps…** keeps the lowest-addressed node (the gateway) and moves each overlapping node to the first free block of
the same size from .100 up, shows the plan, and only changes after you press Change (dhcp start, commit, dnsmasq restart;
the old value is in the Node login sharing log). Timed burst mode (radios on only in short bursts) is planned, not built.
**HaLow on / off (radio silence; OpenMANET Mesh > HaLow on / off, or Customize OpenMANET).** Turns the HaLow radio of the
node this phone is connected to (its WiFi / Ethernet gateway or DHCP server; never a node reached over the mesh, which could
not be switched back) off or back on with the admin login (/ubus network.wireless down/up of the morse radio). Off asks
first: the node leaves the mesh, stops relaying, a gateway node cuts the mesh's internet, and phones on it reach only that
node. Runtime only: a node restart brings HaLow back. Once used, a bar under the home screen's title bar shows the state:
green "HaLow ON on HT02", red "HaLow OFF on HT02: mesh radio silent" (tap it to turn HaLow back on), grey "not checked".
The bar shows only on the mesh LAN and can be switched off in the dialog. If HaLow neighbours are seen again on that node
(it restarted) the bar turns green by itself. If the WiFi-off service runs with "HaLow off too", unplugging the cable
turns HaLow back on.
**Finding "my node":** first the WiFi lease (each OpenMANET node runs its own DHCP pool and is its own gateway, e.g. the
Pi 4 hands out 10.41.0.100-115 and HT01 10.41.0.116-131): the DHCP server when it differs from the gateway, else the
gateway; then the WiFi name; then a manual pick. The page says how it was found, e.g. "found by gateway 10.41.254.238".
**Fake GPS and Map mesh:** turning Map mesh / "fixed here" on or starting a walk with Fake GPS on asks: Turn off Fake GPS,
Keep it (testing only) or Cancel. Kept, every position is marked FAKE GPS (a cross on the map dot, a line in the walk
summary, a fake_gps CSV column); walkers also flag fake positions in their probes, so the fixed station marks them too.

## More comms features
- **Burst voice** (Audio page): record while PTT is held, sent as one file on release, plays by
  itself on arrival (quality from AMR 4.75k to AAC 48k). For weak links. A burst that arrives while the channel is busy
  waits (up to 1 min by default, set on the Audio page) and plays once it clears, with an optional call-waiting beep.
- **Share my camera** (phone or network camera, small pictures 1–5 per second, or 10–15 when Tiny; optional audio, off by default, heard only on the Video feed screen) and **Tactical
  **Telemetry overlay** (ticked to start): each picture carries this phone's position (with accuracy), the compass heading the camera points (with a small north arrow), tilt and roll, speed when the GPS knows it, and the time, drawn onto the picture before it is sent, so every viewer sees it. With a network camera the figures are this phone's, so keep the phone with the camera.
  Sharing keeps running after Home, another app or **Blackout** (it runs as its own service): a red-dot notification says the camera is live and has a **Stop** button. The Share my camera screen shows the pictures as they go out, and has **Torch** (the back camera's flash at full power) and **Blackout**; Video feed has **Blackout** in its top row and shows a dot on My camera while it is shared.
  videos** (a grid of chosen people's cameras, tap to enlarge).
  Video feed goes to **Blackout by itself** after 60 s with no touch and no camera trigger (the **timer icon** beside
  Blackout shows it on, or crossed out when off, and one tap switches it; menu > Auto blackout:
  on or off and the time). A triggered Reolink camera wakes it and shows that camera; it goes back to Blackout once
  the cameras have been quiet for the hold time.
- **Reolink cameras** (Video feed menu > Reolink cameras, or on the Intercom the **Camera** button or the
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
    shared phone cameras on Video feed and TV, and in a strip above the rooms on the Intercom. They work on
    every Android version. A tile says "offline" and why if the camera stops answering.
  - **Full screen** (tap a camera tile): pictures at about 2 a second, **Live video** (the RTSP stream; if this
    phone cannot play it, or it does not start in 15 s, it goes back to pictures), **Listen** (the camera's
    microphone through the stream; live video always starts muted), **Light on/off** (cameras with a floodlight)
    and **Close**. Live video did not play on the phones tried so far (see below), so expect pictures.
  - **Triggers**: about once a second the app asks each lens for motion and AI detection (person, vehicle,
    animal) while Video feed, TV, the Intercom or a camera is open. Triggered cameras with Bring to front
    fill the Video feed wall together (one fills it, two split it...; tap to go back to the grid) and take
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
- **Battery power** (a specialised menu, off by default, was "Bluetti power"; Settings > Home screen and menus, or a
  setup QR code; menu key still "bluetti"): watches **several batteries at once**, each through the one reader for
  its kind. **Read-only for every kind:** the app never switches outputs, changes settings or signs in; use the
  maker's own app for those. Kinds (* = untested, nobody here owns one yet):
  - **Bluetti** power stations over Bluetooth (offline; their WiFi only talks to the Bluetti cloud).
  - **Tesla Powerwall 2** through the Backup Gateway's local status pages on your home network (no sign-in, no
    cloud): battery %, as the Tesla app shows it (the 5% reserve left out), solar, home use, grid import / export,
    battery charging or giving, grid status (an outage shows as OFF GRID), battery size and firmware. Add it with the
    gateway's address from your router. Newer firmware may refuse pages from other networks: then join the phone to
    the Powerwall's own Wi-Fi (TEG-xxx) and use its fixed address there. If the gateway asks for a sign-in the app
    says so and stops (it never signs in, and never calls the control pages).
  - **Anker SOLIX*** C300, C300 DC, C800, C1000, C1000 Gen 2 over Bluetooth (pick the model when adding).
  - **EcoFlow*** River 3 and Delta 3 family over Bluetooth. Needs your EcoFlow **user ID** typed in once (the unit
    checks it; the app never signs in to EcoFlow) and the unit's serial (filled in from Bluetooth). Delta 2 and older
    use another format and are not read.

  Bluetooth kinds need Android 4.3 or newer with Bluetooth LE; Android 6 to 11 also need Location turned on to find
  units. **Add a battery** (menu) looks for nearby Bluetooth units for 9 s, labels each by kind, and you pick yours
  (close the maker's app first: each unit takes one connection at a time), or type a Powerwall's address. Each card
  shows **battery %**, **power in** (AC, and DC / solar), **power out** (AC and DC / USB), whether the outputs are on
  (shown only), **time to full or time left** at the present load (from the battery size the unit reports, or a
  setting), and when it was last read; tap a card for its settings (name, size, overload, address / model / user ID),
  **Probe (raw log)** and **Remove**. It reads every 10 s while open.
  - **Watch in the background** (Settings, on the phone left near the units): reads every 60 s (every 5 min in
    Ultra low or while the radio sleeps) and lets go of the Bluetooth link between reads. **Quiet by default:** nothing
    goes to the channel until the unit is down to **10%** (Settings: "Send status and alerts ... at or below %"; 100 =
    always). From then it **shares a short status** ("Power station EL30V2: 9%, in 0 W, out 45 W, about 35 min left")
    every 5 minutes when it has changed (the minutes are a setting) and at once on a 5% change. The **On the channel**
    part of the Battery power screen lists the latest status from each phone (no Bluetooth needed there), and the
    **commander view** (and Contacts) shows a small power station icon with the unit's % next to that phone's own
    battery %; More details shows the whole latest message.
  - **Alerts** to the channel, like the Reolink ones (text, optionally read aloud on the other phones): **low battery**
    (10%) and **critical** (5%), **charging started / stopped** (portable units only), **mains / grid lost and back**
    (for a Powerwall: the grid outage), **overload** (watts out; 300 W by default for a Bluetti, 5000 W for a
    Powerwall, off for the others) and **out of contact** for 10 minutes (and back), each per battery, all held back
    above the send level. All the numbers are settings; 0 turns one off.
  - **Tested** on a Galaxy S5 (Android 6) with a Bluetti Elite 30 V2 (it reports itself as EL30V2; its WiFi name says
    AC30): finding it, the encrypted link, and reading battery % and the outputs' state. **Not yet tested:** watt
    readings under a real load or charge, sharing / alerts on the channel, the Powerwall reader on a real gateway
    (written to the public field names; Probe logs the gateway's raw answers to check them), and the Anker SOLIX* and
    EcoFlow* readers on any real unit (their crypto and framing were checked on a PC only).
  - **Probe (raw log)** and **Show log** (menu) record what each unit says (hex, or the gateway's JSON), to help add
    other models.
  - Works with Bluetti, Tesla Powerwall, Anker SOLIX and EcoFlow products. These names are trademarks of their owners;
    this app is not affiliated with any of them.

## DRYAD extras
5-letter sheet IDs, optional random row order, huge AA–ZZ sheets (676 rows, not QR-shareable),
**Enigma** (plain and cipher text with Paste/Copy, Create/Read QR), **Challenge and response**, a
**How modern DRYAD works** page (bottom of Settings) and a Pigpen button on the DRYAD screen.

## Still to do
Automatic location sending after moving a set distance, a Windows app (use the commander APK in an
Android emulator, see NOMAD-SETUP.md), MeshCore integration, and testing with real hardware: a
FalconClaw, USB / network / intercom cameras, gunshot alert, crash detection, the tunnel over a real
router, the OpenMANET gateway switch, and radio streams with a real feed.
