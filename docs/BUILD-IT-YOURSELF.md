# Build Your Community Communications yourself with Claude

Your Community Communications was coded entirely by Claude (Anthropic) in Claude Code. The owner wrote requests
in plain language, approved tool installs, and tested. Below are those requests, tidied and in order.

Two ways to use this:
- **Fastest:** paste `docs/RECREATE-PROMPT.md` (the whole app, with the exact wire protocol) into Claude Code in
  an empty folder. The result talks to phones already running this app.
- **Step by step:** give the requests below to Claude Code one at a time, on a Windows PC with an Android phone or
  the emulators. Let each step finish and check it before sending the next.

## Short version (one blurb)

> Build a native Android push-to-talk radio app called Your Community Communications (plain Java, minSdk 16, no
> AndroidX, sideloaded, fully offline, nothing uploaded). It runs over OpenMANET meshes or any WiFi that passes
> broadcast/multicast. Full duplex, many to many, PTT by on-screen button, latch or a learned hardware key. No
> accounts: the channel name is the encryption key. Add acknowledgements with "transmission failed" and retry
> options; channels A, B, Public and an experimental OpenMANET/ATAK Vx mode; a network selector (WiFi, VPN, USB
> Ethernet); resilient file, photo and screenshot sharing; GPS location sharing with an offline tile map, compass
> list and shared POIs with icons and expiry; Modern DRYAD sheets (A-Z plus two chosen special symbols, random
> batches, sharing by text, QR and over the air, age rules) and keyed pigpen with a tap-to-decode pad; a PIN lock,
> a secret wipe, red light mode and a blackout screen. Set up an old and a recent Android emulator first and test
> every feature on both before telling me it is done.

## Step by step: the first build (Release 1)

1. **Goal and ground rules**
   > I want an Android app for radio-like operation on an OpenMANET mesh or any broadcast WiFi network. Duplex,
   > many to many. PTT via an on-screen button or USB PTT. No accounts: the channel name is the passcode, so anyone
   > on that channel name decrypts the audio. Sideloaded, not in an app store. Name it Your Community
   > Communications. Voice is the base; it must work fully offline and nothing gets uploaded. Native Android, not
   > Flutter. Support Android phones as old as possible. First set up two Android emulators on this PC (one old,
   > one recent), then build the voice app.
2. **Approvals** (Claude will ask)
   > Yes, OK to accept the Android SDK licence. Windows Hypervisor Platform is on and the PC has rebooted. Make sure
   > the audio is duplex.
3. **Code tools**
   > Add DRYAD-style sheets that include Z: create and share them. Add a tool that makes a pigpen message from a
   > DRYAD row, and a screen where you tap pigpen symbols and pick the DRYAD row to decode. Generate page, share page
   > with bulk share, a drop-down for the sheet in use, age settings, and a friendly name shared with each sheet.
4. **Randomness**
   > Normal DRYAD creation must be truly random and then shared. Building from a phrase is emergency only, with a warning.
5. **Channels**
   > Add A/B channels and a public channel with a published default key. On a DRYAD row, add buttons to make that row
   > channel A or B. Pick sensible defaults and tell me what you chose.
6. **Offline sharing**
   > Share DRYAD sheets over channel A/B and by QR code (sheets and channel names). Give sheets random colours.
7. **Files**
   > Add file, camera picture and screenshot sharing with resiliency for lossy WiFi.
8. **Know you were heard**
   > Receivers acknowledge each transmission. If nobody acknowledges within 5 seconds, say "transmission failed" and
   > offer Retry now and Retry in… (5 min, 15 min, 1 hour, a set time, 1-99 tries).
9. **More symbols (Modern DRYAD)**
   > Add pigpen space and new line (the centres of the X figures), and two extra randomised DRYAD characters chosen
   > from a drop-down (default `.` = space, `@` = new line). Lay the decode pad out like the figures (# # X X).
10. **Networks and OpenMANET**
    > Add experimental OpenMANET / ATAK voice, a link to the node's web page, and a network selector: WiFi, VPN, USB Ethernet.
11. **Map and data**
    > A Data menu: send location, open map, map options. Offline tiles (ATAK imagery folders), everyone's last
    > position with a compass list, POIs with note, expiry and icons that anyone can change and resend, Sync POIs.
12. **Protection and night use**
    > A PIN for the DRYAD screens and settings, a secret wipe, Blackout (screen dark, app open, double-tap to wake)
    > and a red light mode with no white.
13. **Everything else in Release 1**
    > Chat and reports, contacts, the commander tablet view, modes (Advanced, Simple, Intercom), the first-start
    > wizard and phone setup QR, catch-up sync, tunnel comms, internet audio, Tactical videos and TV mode, the LAN
    > storage server with its /webadmin page. (RECREATE-PROMPT.md describes each one exactly.)

## Step by step: Release 2 additions (2026-10-28 to 2026-11-16)

14. **Power and sleep**
    > Add power modes (Normal, Power saver, Ultra low, Offline), Mission mode, rest GPS when still, Away with
    > automatic check-ins, a mains-powered anchor, burst sleep windows and secret radio-silence check-in windows.
15. **Maps, part 2**
    > Data Tile Management with prefetch (radius, current screen, World), a secondary map server, search, Layer,
    > scale bar, ruler, shapes and geofences, bulk add and export of points, live layers (emergency alerts, aircraft,
    > ships, trains, ARTC rail), and offline street directions planned first with closed roads.
16. **Internet relay**
    > A headless relay on a small cloud server linking MANETs and remote phones by channel tag, many users on one
    > port, gateway election, link-down alerts, and a Relay admin screen with signed admin commands.
17. **Leaders and teams**
    > Channel array, team setup QR codes and cards, Report to leaders, Tasks and notes, Private contacts by QR,
    > Calendar and notes.
18. **Cameras and batteries**
    > Reolink cameras on the LAN (offline), with triggers, floodlights and channel alerts; Battery power (Bluetti,
    > Tesla Powerwall 2, Anker SOLIX, EcoFlow), read-only, with channel alerts.
19. **Cloud group**
    > A community channel like Public with its own Cloud Server (a cloud group user on the relay), share link and
    > QR, Scan QR to join, a receive-only code, and location off for public groups unless ticked.
20. **Protected notes**
    > Under Calendar and notes, a lock icon. The first tap asks for a password to encrypt a new kind of secret note
    > or calendar entry kept only on this device. Every time the screen opens, ask again to see them; no new
    > protected ones while locked. 3 wrong passwords erase them. Protected reminders show no details.
21. **Routes, part 2**
    > + Go via here just drops a dot. Add + Planned rest / meeting stop and + Add a waypoint with their own icons,
    > a route editor (start, destination, points in order, stops, closed roads) in place of Undo, and route notes
    > shared with the route ("meet here 10am").
22. **Small things that matter**
    > Chat filter for Cloud and the source on each message; a cloud icon instead of "Cld"; blackout must not
    > rotate or lift when the phone turns; typed POI positions (GPS or 6-10 figure grid reference, labelled
    > "GPS:" / "mil:"); the whole grid reference on one line under the lat/long.

## Tips
- Ask Claude to prove each feature on both emulators (screenshots, logs) before moving on, then test on real phones.
- Say your constraints early (offline only, oldest Android, no app store, quiet tests); they shape everything.
- "Pick sensible defaults and tell me" keeps things moving; change any of them afterwards.
- Keep a running requirements file (docs/REQUIREMENTS.md) and one changelog line per change (docs/UPDATES.md).
- AI-written code can contain mistakes, and the encryption has not been independently reviewed.
