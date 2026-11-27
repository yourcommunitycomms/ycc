
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

**Reolink cameras (2026-11-10)**
- **Do not force "Connection: close" on camera requests.** With it, about one snapshot in three came back cut
  short ("unexpected end of stream") on the Android 14 emulator; Android's own connection handling is fine.
- **A light changing sets off motion.** A floodlight switching off looks like motion and would turn the
  linked lights straight back on, forever. Real cameras hold that alarm for a while, so 10 s was not enough
  (lights came back every minute or so): ignore linked cameras for 30 s after the lights go off, and then until
  their alarm has dropped once.
- **Lights left on.** If the app is killed while it has floodlights on, nothing turns them off. Remember on
  disk which lights the app switched on, and turn those off the next time it starts watching. Only switch on
  lights that were off, and leave lights that were already on as they were.
- **Camera pictures are 4 to 8 megapixels.** Decode snapshots at a reduced size (inSampleSize, RGB_565) or old
  phones run out of memory.
- **Lens numbers differ:** the HTTP API counts lenses from 0 (channel=0,1), RTSP from 1 with two digits
  (h264Preview_01_sub; newer firmware also /Preview_01_sub). Newer firmware may turn HTTP off and keep only
  HTTPS with its own certificate.
- **Real cameras differ from the documentation** (found on real RLC-510A/810A and Duo models):
  older firmware reports AI as flat "people_state" / "vehicle_state", not "people":{"alarm_state"};
  GetWhiteLed answers even on cameras with no light (use GetAbility abilityChn[ch] supportFLswitch);
  Snap needs both width and height to shrink the picture (a Duo Floodlight picks its own size: 640x360 gives 1536x576,
  but 1280x720 gives its full 4608x1728, 5 MB; Android 4.1 cannot draw a bitmap over 2048 px wide and shows
  nothing, and an OutOfMemoryError must not end the picture thread);
  a wrong password gives -7 "login failed" on Login and -27 "invalid user" with user/password on the request.
  New cameras can come with HTTP/HTTPS/RTSP off (only port 9000 answers): tell the user how to turn them on.
  A 350 ms connect timeout in the LAN sweep missed Wi-Fi cameras; 900 ms finds them.
- **Old Android (4.x) and kept-alive sockets:** a camera closes idle connections and the next request fails at
  once, which pushed cameras onto HTTPS. Use a fresh connection there (Connection: close below Android 4.4) and
  retry once. Sign out (Logout) after one-off checks: cameras allow only a few sessions.
- **VideoView error retry:** calling setVideoURI from inside onError after "media server died" throws
  IllegalStateException and closes the app. Never retry after error 100; post any retry.
- **Android 4.1 RTSP and Reolink:** the built-in player gets 401 even with the right sign-in (ffprobe on a PC
  plays the same address). Android 14 signs in, then its media service crashes on the stream. Fall back to
  pictures.
- **Port 9000 only cameras (E1):** no HTTP, RTSP or ONVIF at all. The camera's own LAN protocol: little-endian
  header, login nonce over a 20-byte legacy header, XOR-scrambled login with MD5 hashes cut to 31 upper-case
  hex characters, then AES-128-CFB (key = first 16 of MD5(nonce-password), IV "0123456789abcdef", a fresh
  stream for each part), Snap = cmd 109 (the picture follows in more messages with the same number, ending
  with an empty one), events = cmd 31 subscribe then cmd 33 pushes, keepalive cmd 93, answer heartbeat cmd 234.
  Never copy AGPL code for this; keep the MIT notice of the description used (NOTICE).
**Found in Release 2 (2026-11-15)**
- **Turning the phone lifts the black screen.** A rotation rebuilds the activity and the cover is gone. Lock the
  orientation while black (SCREEN_ORIENTATION_LOCKED on 4.3+, the current fixed orientation on 4.1/4.2) and
  restore it at the double tap; if a configuration change rebuilds it anyway, black it out again on resume.
- **Signed channels have a different tag.** A changed Public key or a cloud group takes its tag from the listen
  secret (ChannelCrypto("L:" + base32(S))), so filters that worked the tag out from the key itself found nothing.
- **Do not count hidden lists.** The status line counted Channel array rows (listened to by default) even with the
  Channel array menu off, so a phone said "and 25 teams" with nothing to see. Only listen to what the user can see.
- **PowerShell alias trap when scripting tests:** `R` is Invoke-History, and `-eq` is case-insensitive.
- **Testing on the Android 4.1 emulator:** with the backlight at exactly 0 (Blackout) adb shell calls hang until the
  emulator is restarted; test blackout there with the legacy (lowest backlight) setting. `adb install -r` while the
  app is open can log "Unable to instantiate application" once as Android restarts it mid-install; it is harmless.
- **Typed grid references:** pick the 2000 km northing cycle from the band (or the map's +), place a short
  reference in the middle of its square, and test that every reference the map shows parses back to itself.
**openmanetd stale lock:** openmanetd 1.4.0 leaves /etc/openmanetd/openmanetd.db.lock behind when it is killed mid-write (restart, power cut) and then refuses to start ("database is locked"). Found on HT02 2026-11-27; YCC adds a start-up step that clears it (HT-HD01 v2 mods / Mesh troubleshooting).
**HT-HD01 web API limits:** the HD01 firmware lets the web login make only LuCI's own calls, so the read-only login for team phones cannot be installed on HD01 nodes from the app (it needs to write an rpcd permission file); it works on the Pi. HaLow on / off and Customize OpenMANET were reworked to fit (2026-11-26).
**Not yet tested on real equipment:** Customize OpenMANET pushed from the app to real nodes (the WiFi-off service itself was tested by hand on HT02, the DHCP move by hand on HT02; HaLow-off and non-Heltec boards untested; the HaLow on / off toggle untested on a real node; the walk's own 1 s GPS and graph tested on emulators only), scanning a phone setup QR code with a real camera, Bluetooth PTT buttons (e.g. FalconClaw "BLU-PTT"; the signal it
sends is unknown, so the app shows the last raw value in Settings), OpenMANET / ATAK Vx voice, USB,
network and intercom cameras, the possible-gunshots alert, crash detection, tunnel comms over a real
router and the internet, internet radio streams, Reolink live video, sound and multi-camera alerts (tested against tools/fake-reolink only); cloud groups and the internet relay on a real server; Tesla Powerwall 2, Anker SOLIX and EcoFlow readers on real units; a real baked T.REX Arms map file; scanning QR codes with a real phone camera (Tools, cloud group Scan QR); spoken route stop announcements and protected meeting reminders going off on a real phone. Mesh testing (2026-11-24): Play audio, Echo Test, Map mesh and Mapping walk on real phones and a real mesh, real radio stream audio quality, and ListMeshNeighbors on a real node (field names read from the openmanetd 1.4.0 binary, emulators tested against a stand-in node). OpenMANET Mesh and the walker reading its own node (2026-11-25): finding "My mesh node" from the WiFi name assumes the node's access point is named after it (e.g. localnetHT01); tested only with a stand-in SSID and two stand-in nodes, so a real Heltec and a real phone's WiFi name (which needs location permission on Android 8.1+) are untested; nextHopMac in the mesh layout is a guess at the field name. Node login sharing (2026-11-25): offer / accept / sealed login / revoke tested between two API 34 emulators; the PIN-sealed QR scan and Android 4.x P-256 key making are untested; the shared password is kept in YCC's private app storage (not encrypted at rest with the Android Keystore yet). Read-only login (2026-11-25): installing it (rpcd login, ACL file, script, rpcd reload over /ubus) and reading with it are tested only against a stand-in /ubus on emulators, NOT on the real Pi or Heltec (no node writes until the owner says go); the rpcd ACL format and the ycc-ro-radio script are untested on real OpenWrt; the server Phone setup fields are in server/provision.py but not deployed. Finding my node by gateway is confirmed on the real mesh (S5 on the Pi 4, Nexus 7 on HT01, named from each node's web page title without a login); the real openmanetd ListMeshNeighbors answer has now been seen and is read (S5 with the admin login). Fixed 2026-11-25: on Android 5.1+ with mobile data on, a WiFi without internet was not used for the channel broadcasts (one-way: heard everyone, nobody heard it); multicast-only mode (netMode multicast) still sends on the default network. 2026-11-25: re-join on a WiFi address change is tested only as far as the WiFi-off/on case on emulators (same address, no re-join); a real change of network is untested until the next field test. USB Ethernet / two LANs (2026-11-26): only emulators (WiFi + their cellular eth0) tested; a real USB Ethernet adapter, Ethernet gateway detection and "Bridge my LANs" between two real LANs are untested.
