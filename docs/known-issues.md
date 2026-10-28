
### Bugs found while building this, and what to watch out for

Each of these happened in the original build. Check for them in yours.

**Radio and networking**
- **Two radios at once.** Key derivation takes seconds on old phones. If Join is tapped again (or Leave)
  during that time, the first join must cancel itself; otherwise a hidden second radio stays on air,
  the phone hears its own sends as someone else's (e.g. "X sent 7 DRYAD sheets" on the sender) and lists
  itself as a station. Fix: a join generation counter; a join that is no longer current stops itself.
- **A restarted app looks like a new person.** Sender ids are random per run, so a restart left the old
  entry listed too ("two of TANGO"). Replace an entry with the same callsign when a new id appears.
- **Emulators cannot see each other's broadcasts** (the Android 14 emulator sits behind its own router).
  Link test devices with a small UDP relay on the PC and "extra peers"; real phones on one WiFi do not
  need it. The emulator's fake microphone also runs faster than real time, so its recordings come out
  too long; real phones are fine.
  How the original was linked: on the PC run
  `java relay/UdpRelay.java 47400 0.0.0.0 0 <phone IP>:47474,<phone IP>:47474` (listen port, bind
  address, % of packets to drop for testing bad links, then fixed peers that always get a copy, here the real phones on the WiFi). In each
  emulator set Extra peers to `10.0.2.2:47400` (10.0.2.2 is the PC as seen from an emulator). On a
  real phone, Extra peers = the PC's WiFi IP and port 47400. The relay only forwards the encrypted
  datagrams, so it never needs the channel name.
- **Radio silent must block everything**, including acks, presence, catch-up sync, file repairs, calls,
  VOX and online map tiles, not just the PTT button.

**Files and storage**
- **Received files silently lost on real phones.** The finished file was moved from app storage to
  shared storage with rename(), which fails across file systems (most real phones, Android 11+). Copy
  when rename fails. Symptom: a photo reached one phone but not another.
- **"name (2).zip"** style duplicate names break checks on the file extension; match on the name part.

**Old Android (4.1 to 6)**
- **Classes that do not exist on old Android crash the app** if they are created in a field initializer
  (e.g. Bluetooth LE GATT callbacks on 4.1). Create them only after a version check.
- **Android 4.x only offers TLS 1.0** by default; enable TLS 1.2 for online map tiles. OpenStreetMap,
  OpenTopoMap and Esri still refuse 4.x; Google tiles work. Say so on the map screen.
- **Old built-in PBKDF2 mangles non-ASCII channel names** (Android before 4.4); implement PBKDF2 yourself.
  AES-GCM needs API 19, so use AES-CTR + HMAC instead.
- **Vector icons** need PNGs generated for old Android, and dashed strokes are not supported (draw dots).

**Maps and location**
- **OpenStreetMap blocks some user agents** (one containing "offline" got an "Access blocked" tile that
  was then cached). Use a plain user agent and a separate tile cache per tile source.
- **No position shown:** Location switched off in the phone's settings, no GPS fix indoors, and no
  network location without internet are all common. Show the reason on the map instead of nothing.
- **Phones without a compass** (e.g. ZTE Blade A462) cannot show which way they face; point the arrow
  along the GPS course while moving.
- A corner compass whose needle points north can look like it disagrees with the "me" arrow; on a
  north-up map show a pointer for the way you face instead.

**Audio and alerts**
- **Bluetooth headsets need SCO started**, or radio audio stays on the phone speaker.
- **A phone on vibrate mutes the ringer stream**, so the Call ring also plays a loud tone on the media
  stream (speaker / Bluetooth / headphones).
- Stereo dual watch must use the media stream; the call stream is mono on most phones.

**Screens**
- **Small screens (480x854):** button labels broke mid-word ("HOSTIL E"), report quick-pick codes hid
  behind the keyboard, drop-downs truncated to "…", landscape rows overflowed. Use mixed-case labels,
  tight padding and narrow fixed-width buttons; test at that size.
- Changing one button's background colour changed every button (shared drawable); call mutate() first.

**Security and sharing**
- **Sending settings or DRYAD sheets on Public gives your channel names to everyone on Public.** Warn
  before sending.
- The channel name is the whole secret; short names can be guessed offline from recorded traffic.

**Found on real phones (2026-10-23)**
- **Camera crash on Android 6+.** An app that lists the CAMERA permission but has not been granted it
  cannot even start the phone's camera app (SecurityException). Ask for CAMERA before taking a photo.
- **Listen-only channels must still acknowledge.** A dual-watch decoder that never sends made talkers on
  that channel see "transmission failed" although they were heard. Let it send ACKs (only ACKs) through
  the main socket, sealed with that channel's keys.
- **Buttons in a row jump up and down** when one label wraps: LinearLayout aligns baselines by default.
  Turn baselineAligned off and give the buttons match_parent height.
- **android:padding beats android:paddingBottom**; set the four sides separately when one must differ.
- **"All the way down" differs by phone:** the call stream's lowest step is 1 on some phones and 0 on
  others, and Android before 9 cannot say which. Silent/vibrate ringer modes count too.
- **A woken ringer must be put back.** Turning the volume up for a Call left phones loud and out of
  stealth; save the ringer mode and volumes and restore them after the ring.
- **Android 10+ blocks activities started from the background:** a full-screen alert needs a
  high-importance notification channel with a full-screen intent (and USE_FULL_SCREEN_INTENT).
- **Re-joining went quiet for seconds** while the new key was derived; derive first, then swap radios.

**Found later (2026-10-24)**
- **Speaker / headset chosen only at join:** a wired headset plugged in after joining left the audio on
  the loudspeaker. Listen for ACTION_HEADSET_PLUG while on air and switch then. Some PTT headsets read
  as plain headphones (no mic line), so their button never reaches the app.
- **A tap too short for one voice frame was reported as "transmission failed":** nothing was sent, so
  nobody could ack. Only expect acks when at least one frame went out.
- **Recordings 3x too long:** the same packet arrived by broadcast and through the relay / extra peers,
  and the recorder wrote every copy. Drop repeated (sender, seq) frames when recording.
- **Coming back to the front from the background** (intercom) is blocked on Android 10+ unless the app
  declares SYSTEM_ALERT_WINDOW and the user allows "Display over other apps".
- **Android 11+ hides other apps** from resolveActivity: declare a <queries> entry for geo: links and
  Google Maps. **Android 9+ blocks plain http** (streams, IP cameras) unless cleartext traffic is
  allowed.
- **Font glyphs:** ⚙, ➤ and ▶ are missing on Android 4.x; draw icons or use the system drawables.
- **Dialogs and buttons:** AlertDialog has only three buttons; put extra actions in its view.

**LAN storage, server and setup QR**
- **A shared button style with layout_weight 1 and width 0** made a lone full-width button in a
  vertical layout swallow the rest of the Settings page (everything below it blank). Give such a
  button its own width and weight 0.
- **The admin page's folder sizes** take tens of seconds on gigabytes of map tiles: count in one pass
  and cache the result for a few minutes.
- **The Docker container's clock is UTC** unless TZ is set, so schedules by hour follow UTC.
- **Network defaults must not overwrite the channel or Public key set by a setup QR code:** apply the
  defaults first, then the QR code's own fields.
- **Old Android TLS:** Android 4.x cannot reach OSM, OpenTopoMap or Esri tile servers; fall back to
  Google road tiles there.

**Not yet tested on real equipment:** scanning a phone setup QR code with a real camera, Bluetooth PTT buttons (e.g. FalconClaw "BLU-PTT"; the signal it
sends is unknown, so the app shows the last raw value in Settings), OpenMANET / ATAK Vx voice, USB,
network and intercom cameras, the possible-gunshots alert, crash detection, tunnel comms over a real
router and the internet, internet radio streams.
