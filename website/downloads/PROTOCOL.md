# Your Community Communications wire protocol and encryption (v1)

Everything another app (a Windows client, or a rebuild from scratch) needs to talk to Your Community Communications
phones. The Android source is the reference: `net/Packet.java`, `crypto/ChannelCrypto.java`,
`RadioEngine.java`, `msg/MessageCenter.java`, `msg/PrivateKeys.java`, `net/FileTransfer.java`,
`geo/Poi.java`, `dryad/DryadSheet.java`.

All multi-byte numbers are **big-endian**. Doubles and floats are IEEE 754 (Java `ByteBuffer`
order). Text is UTF-8. "len(1) text" means one length byte followed by that many bytes.

---

## 1. How a channel name becomes the encryption keys (plain language)

There are no accounts and no key exchange. **The channel name is the password.** Every phone
that types the same channel name works out the same secret keys on its own, so they can all read
each other, and a phone with a different name (or none) sees only noise.

1. **Stretch the name into key material.** The name (spaces at the ends removed, as UTF-8 bytes)
   goes through PBKDF2 with HMAC-SHA256, the fixed salt `communitycomms/v1` and 20,000 rounds. The
   20,000 rounds make each guess slow, so someone trying to guess a channel name by brute force
   pays 20,000 hash operations per guess. Out come 64 bytes.
2. **Split it into two keys.** The first 32 bytes are the **encryption key** (AES-256). The last 32
   bytes are the **authentication key** (HMAC-SHA256).
3. **Make a public channel tag.** HMAC-SHA256(authentication key, the text `channel-tag`); the first
   4 bytes are the tag. It goes in the clear on every packet so a phone can instantly drop packets
   from other channels without trying to decrypt them. It does not reveal the name or the keys.
4. **Every packet:** the payload (callsign, voice, message…) is encrypted with AES-256 in counter
   mode, using a starting counter built from the sender's random id and the packet's sequence
   number, so no two packets ever reuse a counter. Then a 10-byte authentication code (truncated
   HMAC-SHA256 over the header and the encrypted payload) is added. A receiver recomputes it and
   silently drops anything that does not match, so packets cannot be forged or altered without the
   name.

What this means in practice:
- **The name is the whole secret.** A short or guessable name (a word, "teamA") can be brute
  forced offline from a captured packet. Use long random names: a DRYAD row (28 random symbols,
  about 98 bits) is excellent, and that is what "Row → A/B" does.
- **Public** uses a published name, by default `publicchannelkey` (editable in Settings and in the
  first-start wizard, so a group can run its own), so anyone with the app can read it.
- **Private messages** add a second layer for one person (section 6): each phone has its own
  RSA-2048 key pair, and the message is locked with the recipient's public key on top of the
  channel encryption.
- **Emergency DRYAD from a phrase** uses the same stretching (salt `communitycomms/dryad/v1`) to turn
  a phrase into a deterministic sheet, so everyone who types the phrase gets the same sheet.
- Not provided: forward secrecy (anyone who later learns the name can decrypt recordings of old
  traffic), hiding who is talking to whom (sender ids, timing and sizes are visible), replay
  protection beyond de-duplication. The design has not been independently reviewed.

### Exact derivation

```
okm        = PBKDF2-HMAC-SHA256(password = UTF8(trim(name)), salt = "communitycomms/v1",
                                iterations = 20000, length = 64)
encKey     = okm[0..32)        (AES-256)
macKey     = okm[32..64)       (HMAC-SHA256)
channelTag = first 4 bytes of HMAC-SHA256(macKey, "channel-tag")   as a big-endian int
```
(An empty name would use a single 0x00 byte as the HMAC key; the app never allows empty names.)
The Android code does PBKDF2 by hand because old Android's built-in PBKDF2 mangled non-ASCII names;
any standard PBKDF2-HMAC-SHA256 gives the same result.

### Test vectors

```
name "publicchannelkey" (the default Public channel)
  encKey     59c8612e1eb6c613ee8113af0d18f81803e8189f3f9804cbc33ca9eace12c962
  macKey     68122fc3607f09414b456a9e29fd645ed458408ec827c7712094aef4da783680
  channelTag f9929a84
  PRESENCE packet, sender id 0102030405060708, seq 1, callsign "ALPHA":
  55420103f9929a840102030405060708000000012d3d7d76c1577acddbfc72bd822f291f
```

---

## 2. Transport

- **UDP port 47474** (configurable; every phone on a channel must use the same port).
- **Broadcast mode (default):** send to the subnet broadcast address of the chosen network
  interface(s) (or 255.255.255.255). **Multicast mode:** send to **239.47.47.47:47474**, TTL 8.
  Receivers bind 0.0.0.0:47474 with SO_REUSEADDR/broadcast on, and join 239.47.47.47.
- **Extra peers:** optional unicast `host:port` destinations (e.g. a relay across subnets). The
  relay (`relay/UdpRelay.java`) forwards every datagram to every other client heard in the last
  minute; it never decrypts.
- One packet per UDP datagram; payloads are kept at or under ~1200 bytes so datagrams stay under
  a typical 1500-byte MTU.
- Own packets are recognised by the sender id and ignored.

## 3. Packet format

```
 offset size
  0      2   'U' 'B'                      magic
  2      1   version = 1
  3      1   type                         see table
  4      4   channelTag                   cleartext
  8      8   senderId                     random per app run (new each time the app starts a channel)
 16      4   seq                          per sender, +1 every packet (starts random)
 20      n   ciphertext                   AES-256-CTR(encKey, IV, payload)
 20+n   10   mac                          first 10 bytes of HMAC-SHA256(macKey, bytes 0 .. 20+n)
 IV (16 bytes) = senderId(8) || seq(4) || 00 00 00 00     (standard CTR: the 128-bit block counter
                                                           increments big-endian from this value)
```
Receivers drop: wrong magic/version, wrong channelTag, bad mac, own senderId.

**Every payload starts with the callsign:** `nameLen(1) name(UTF-8, at most 32 bytes)`. The bodies
below are what follows it.

| type | name | body after the callsign |
|---|---|---|
| 1 | VOICE | txId(4) + one IMA-ADPCM frame (section 4) |
| 2 | VOICE_END | txId(4). Sent 3 times when the talker releases PTT |
| 3 | PRESENCE | status(1: 0 available, 1 away, 2 stealth) battery(1: 0-100 %, 255 unknown); every 5 s while on a channel and at once when the status changes. Old phones sent nothing here: treat a missing status as unknown |
| 4 | ACK | txId(4) targetSenderId(8) framesHeard(2) |
| 10 | CHAT | messages, reports, catch-up sync, private messages (section 6) |
| 11 | LOCATION | lat(8 double) lon(8 double) accuracyM(4 float) fixAgeMs(8) labelLen(1) label. Sent twice |
| 12 | MARK | map point (POI) body (section 7). Sent twice |
| 13 | DRYAD | transferId(4) chunkIndex(2) chunkCount(2) up to 1000 bytes of sheet text (section 8) |
| 14 | FILE | file transfer message (section 9) |
| 15 | CALL | callId(4), then optionally targetLen(1) + target callsign (UTF-8, at most 32 bytes): only that callsign rings (case-insensitive). Without a target, everyone rings. Sent 3 times 250 ms apart; receivers ring once per (sender, callId) |
| 16 | VOICE_TO | voice for one callsign only (intercom: talk to one room): txId(4) targetLen(1) target callsign (UTF-8, at most 32 bytes) then the ADPCM frame. Only the phone with that callsign (case-insensitive) plays it; it treats it exactly like VOICE. The transmission ends with a normal VOICE_END, so only the target acks. Phones without type 16 ignore it |
| 17 | VIDEO | live camera picture (small JPEG, 1–5 a second): streamId(4) frameNo(4) chunkIndex(1) chunkCount(1) then up to 1100 bytes of the JPEG. Shown only when every chunk of a frame arrived; no repair |
| 18 | ADSB | shared aircraft: count(1), then per aircraft 25 bytes: ICAO hex(3) lat(4, 1e-5 deg) lon(4) altitude(2, ft/25; -32768 on the ground, 32767 unknown) track(2, deg or -1) speed(2, kt or -1) flight(8, ASCII, space padded). At most 40 per packet. Shown for 10 min |
| 19 | AIS | shared ships: count(1), then per ship 29 bytes: MMSI(4) lat(4, 1e-5 deg) lon(4) speed(2, 0.1 kt or -1) course(2, 0.1 deg or -1) ITU ship type(1) name(12, ASCII, space padded). At most 35 per packet. Shown for 15 min |

**LOCATION extensions:** after the label, newer phones append an **icon** byte (the map icon index
others see them as, 255 = a plain dot) and a **flags** byte (bit 0 = a fake GPS position: show
"NAME (Fake)"). Older phones stop reading after the label, so both are optional.

Unknown types must be ignored (newer phones may add types).

**Stations:** a phone lists everyone it has heard (any packet) by senderId with their callsign.
Drop a station after 60 s of silence unless it has sent a position (keep those 24 h for the map).
A station whose app restarts gets a new senderId; replace the old entry with the same callsign.

## 4. Voice

- 8000 Hz, mono, 16-bit. **One VOICE packet per 40 ms frame (320 samples).** Full duplex: keep
  playing others while transmitting; mix all talkers (one jitter buffer per sender, start a talker
  after 3 frames, cap at 12, drop out-of-order/duplicate seq).
- `txId` is random per transmission (per PTT press).
- **IMA ADPCM frame** (163 bytes for 320 samples): predictor(2, signed) stepIndex(1) then 160 bytes,
  two samples per byte, **first sample in the low nibble**. Standard IMA step and index tables
  (89 steps, 7 … 32767; index deltas -1,-1,-1,-1,2,4,6,8). Each frame carries its own state, so a
  lost packet never corrupts the next.
- **Acknowledgements:** a receiver sends one ACK per (sender, txId) when VOICE_END arrives, or if
  that sender's voice stops for 1 s without an END. framesHeard = VOICE frames received. The
  talker waits 5 s after its END; if no ACK names it, it reports "transmission failed".
- VOX: same packets; the app keeps a 3 s pre-roll and 5 s hang.
- A PTT press too short to send any frame sends VOICE_END but expects no ACK (no "failed" report).
- Optional start tone (sender only, nothing on the wire changes): the first 4 frames of a PTT
  transmission are a beep (2 frames 880 Hz, 2 frames 1320 Hz) instead of the microphone.
- Recorders must drop duplicate (sender, seq) frames: the same packet can arrive by broadcast and
  through a relay or extra peer.

## 5. Channels in the app

- Slots **A** and **B**: user names (often a DRYAD row). **Public**: the public key, default
  `publicchannelkey`.
- Extra slots **C**–**H**: more user-named channels, mainly for listening.
- **Dual watch** listens to every channel ticked Listen (A–H, Public; none ticked = the other of
  A/B) with one receive-only decoder each, fed by the main socket (packets are routed by channel tag).
  It sends ACKs for voice it heard (sealed with that channel's keys, through the main socket), so
  their talkers see "Heard by", and otherwise only what the user sends there on purpose: a team
  message (CHAT text), a call (CALL) or a **broadcast** (the same VOICE frames and VOICE_END as the
  channel on air, under that channel's keys, own sender id and sequence). Never presence or sync.
- **Channel QR:** `UBC1|<A|B|P>|<channel name>`.
- **Phone setup QR:** `YCCSETUP1|` + compact JSON with any of `name`, `url` (LAN storage base, e.g.
  `http://server:8647/YCC`), `user`, `pass`, `tunnel` (host:port), `tkey`, `pub` (Public key),
  `chA` (channel A name), `omg` (OpenMANET group; turns OpenMANET on), `omh` (OpenMANET node),
  `home` (home WiFi SSID; turns home sync on) and `defaults` (1 = load the LAN storage's network
  defaults `Configs/_defaults.ucconfig` first). Empty fields are left out. Made by the server's admin
  page or by a phone; never sent over the radio.
- **LAN storage layout** (HTTP/WebDAV under the base URL): `Maps cache/<source>/z/x/y.jpg` (no login),
  `Updates/YCC.apk` (no login) and `Updates/version.txt` ("versionCode" line, then the APK name),
  `Logs/`, `Data/` and `Missions/<name>/`, `DRYAD/sheets.txt`, `Configs/<callsign>.ucconfig` and
  `Configs/_defaults.ucconfig` (settings bundle format, section 9).
- **OpenMANET\* (experimental, separate):** unencrypted Opus 48 kHz mono, 20 ms, 32 kbit/s in RTP
  (payload type 111) on a talk group's multicast address:port; half duplex. Not part of this protocol.

## 6. CHAT bodies (type 10)

`kind(1)` then:

```
 1 TEXT     id(8) ageMs(8) creatorLen(1) creator textLen(2) text(UTF-8, at most 1000 bytes)
 2 REPORT   id(8) ageMs(8) creatorLen(1) creator dataLen(2) zlib-deflate(report text)
 3 DIGEST   flags(1, bit0 = "answer with what I lack"; bits 1-7 = answer from this far back in
            12-minute steps, 0 = your own window) count(1), then per entry:
            type(1) id(8) version(4) ageSec(4) creatorLen(1) creator labelLen(1) label(<=40 bytes)
 4 REQUEST  count(1), then per entry: type(1) id(8)
 5 ACK      same layout as TEXT: an automatic "message received" reply; never itself acknowledged
 6 PRIVATE  id(8) ageMs(8) creatorLen(1) creator toFingerprint(8) sealedLen(2) sealed
 7 KEY      pubLen(2) RSA public key (X.509 / SubjectPublicKeyInfo DER)
 8 VOICE    flags(1, bit0 ask, bits 1-7 window in 12-minute steps) count(1), then per recording:
            id(8) ageSec(4) seconds(2) creatorLen(1) creator   (voice catch-up, see below)
```
- **Voice catch-up (kind 8):** every recording of one transmission has the same id on every phone:
  `id = talkerSenderId * 0x9E3779B97F4A7C15 + txId` (64-bit, wrapping). A phone coming back to the front
  sends VOICE with ask set, listing the recordings it holds from the window. A phone with Record all
  voice that holds others waits a random 0.3-2.8 s, leaves out any another phone announced in the last
  60 s, announces its own (VOICE without ask), then sends each as a FILE named
  `VOICEREC-<id as 16 hex digits>.wav` (one at a time). Receivers show announced recordings as
  "fetching" and fill them in under the same id when the file arrives (unread); still missing after
  30 s + 4 s per recording, they ask once more. At most 40 recordings or 10 MB per answer. Phones
  without kind 8 ignore it.
- **Ages, not clock times:** ageMs = now − creation time on the sender. Receivers set
  time = their now − age, so wrong phone clocks still agree.
- TEXT and REPORT are sent **twice**; receivers de-duplicate by id. Messages are at most
  1150 bytes of body.
- **Report text** (`UBR1`): lines `UBR1`, code, title, then one `label<TAB>value` per field;
  backslash, tab and newline escaped as `\\`, `\t`, `\n`. Labels travel with the report, so any
  template (including custom ones) displays without the receiver knowing it.
- **Catch-up sync:** every 5 min, on join, and when the app comes back to the front after more than 30 s
  away (back to the newest message it has from anyone, up to a day; bits 1-7 say how far), a phone sends a
  DIGEST (with bit0 set) of what it
  holds from the last 30 min: types 1 text, 2 report, 3 POI, 4 file. Anyone holding something the
  digest lacks answers with its own digest (no bit0) after a random 0.3–2.8 s; a phone that lacks
  a text/report/POI sends a REQUEST after a random 0.2–1.7 s and any holder resends it (suppress if
  someone else already resent it). Files are never resent this way; a missed file is shown as
  "missed". A POI with a higher version replaces an older one. File digest id =
  `senderId * 1000003 + transferId` (64-bit wrap-around).
- **Private messages:** each phone announces KEY on join and every 60 s. Fingerprint = first 8 bytes
  of SHA-256(public key DER), as a big-endian long. `sealed` =
  ```
  rsaLen(2) RSA-2048-OAEP(SHA-1, MGF1-SHA-1)(secret 32 bytes) iv(16)
            AES-256-CTR(SHA-256("enc"||secret), iv, text)  HMAC-SHA256(SHA-256("mac"||secret), iv||ciphertext)[first 16]
  ```
  Only the phone whose fingerprint matches opens it. Never in digests, never resent, never acked.

## 7. Map points (type 12)

```
 op(1)  1 = upsert, 2 = sync request (no further fields: everyone replies with all their points)
 upsert: id(8) version(4) lat(8 double) lon(8 double) msLeft(8, -1 = never expires) icon(1)
         creatorLen(1) creator  editorLen(1) editor  noteLen(2) note(UTF-8, <=600 bytes)
```
- Highest version wins. **Remove** = send a higher version that has already expired (msLeft 0).
- Note tags (hidden from people): `[photo:<hex file key>]` links a picture sent on the channel;
  `[quiet]` = update maps without speaking an announcement.
- Icons: 0 person blue, 1 person red, 2 person yellow, 3 car, 4 truck, 5 bus, 6 ambulance,
  7 helicopter, 8 plane, 9 drone, 10 red square, 11 blue square, 12 photo, 13 toilets,
  14 meeting point (flag), 15 parked car (P), 16 fire front, 17 spot fire, 18 fire truck,
  19 tanker truck, 20 water point, 21 hostile (red diamond), 22 friendly (blue rectangle),
  23 unknown, 24 observation post, 25 danger/hazard, 26 first aid/casualty, 27 landing zone,
  28 checkpoint, 29 food/camp, 30 road closed. Treat unknown numbers as 0.

## 8. DRYAD sheets (type 13)

The sheet text is split into chunks of up to 1000 bytes; every chunk is sent, then the whole set
twice more (3 rounds, 15 ms apart). Receivers reassemble by (senderId, transferId) and offer to
import. The joined text is one or more sheets in this form:

```
COMMUNITY-COMMS-DRYAD v1
NAME: Team DRYAD #1
VALID-FROM: 2026-10-21 00:00Z          (optional, UTC)
VALID-TO: 2026-10-22 00:00Z            (optional, UTC)
COLOUR: Orange                         (optional)
USED: yes                              (optional)
SPECIAL: .@  = SPACE NEWLINE           (Modern DRYAD: the sheet's space and end-of-line symbols)
   0    1   2   3   4  5   6  7   8   9
A: VZ.Q TEG DFW XLK UN CBJ YP @HM OSR IA
... rows B to Z ...
```
Each row is a random permutation of 28 symbols (A–Z plus the two specials) in columns of
4,3,3,3,2,3,2,3,3,2 (classic 26-letter sheets: 4,3,3,2,2,3,2,3,2,2). Generation: Fisher–Yates
with a secure random source. Emergency "from a phrase": PBKDF2-HMAC-SHA256(phrase,
"communitycomms/dryad/v1", 20000, 32) as key; random bytes = SHA-256(key || counter(8, big-endian))
blocks; below(n) uses rejection sampling (take a byte b, accept if b < 256 − 256 mod n, return b mod n);
alphabet order "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + space + eol; rows A to Z in order from one shared
random stream, each a Fisher–Yates shuffle from the last position down (j = below(i + 1)).
**QR form:** `UBD1|name|fromMin36|toMin36|colour|used(0/1)|<26×28 symbols row by row>|<space><eol>`
(times = minutes since 1970 in base 36, empty = open-ended).
**Row as a channel name:** the row's 28 symbols as a string.
**Pigpen keyed by a row:** slot i of the 28 pigpen figures holds the row's i-th symbol (sheet
specials mapped to '.' space and '@' new line). Slots: 0–8 grid no dot, 9–17 grid with dot,
18–21 X arms no dot (top, right, bottom, left), 22–25 X arms with dot, 26 plain X, 27 X with four dots.

## 9. Files (type 14)

```
 OFFER  1 id(4) size(8) chunkSize(2) count(4) sha256(32) mimeLen(1) mime nameLen(1) name
 CHUNK  2 id(4) index(4) data (chunkSize = 1150 bytes, last one shorter)
 NACK   3 id(4) rangeCount(1) { start(4) length(2) } …     (at most 100 ranges)
 DONE   4 id(4)
```
Sender: OFFER, then every chunk once at about 140 chunks/s, then DONE (OFFER repeated with DONE
for late joiners). Receivers write chunks to disk; after 1.2 s of quiet they NACK the missing
ranges and the sender re-sends exactly those (one re-send serves every receiver). Give up after
120 s without progress. The finished file must match the SHA-256. Max 50 MB.
**Map tile packs** are ordinary files named `SET.uctiles.zip`: entries `z/x/y.jpg|png` plus
`uctiles.txt` (`set=`, `lat=`, `lon=`, `radiusKm=`, `zMin=`, `zMax=`); receivers unpack into their
offline tile folder for that set.

### Settings bundle (shared setup)

A phone can hand its whole setup to the channel as an ordinary file named `settings.ucconfig`
(mime `application/x-ucconfig`), UTF-8 JSON:
```
{"format":"UCCONFIG1", "from":"<callsign>",
 "prefs":{"settings":{key:{"t":"s|i|l|b|f","v":value}}, "map":{…}, "quickProfiles":{…}, "dryad":{…}},
 "sheets":"<every DRYAD sheet in the text form of section 8, one after another>"}
```
Never included (and never overwritten on the receiver): callsign, PIN hash/salt/fail count,
lost-device wipe, private-message keys, PTT key, Bluetooth button, network interface, radio
silent, audio levels, online-tile window, setup-done flag, lock state. The receiver is asked
"Clear all settings and accept the new ones?"; accepting clears its shared settings, applies the
bundle and replaces all its DRYAD sheets. Sending it on Public gives the channel names to everyone
on Public (the sender is warned). A new phone's first-start wizard can join Public and wait for one.

## 10. Compatibility rules

- Keep the version byte at 1 and these layouts as they are; add new things as **new packet types or
  new CHAT kinds**, and ignore anything unknown.
- Truncate callsigns to 32 bytes, creators to 32, labels to 40, notes to 600, texts to 1000.
- Use ages, not absolute times, on the wire.
- On a channel you only watch (dual watch) send nothing but voice ACKs and what the user sends there on
  purpose (team message, call, broadcast), and send nothing at all in
  radio-silent mode.
- Extra bytes may be appended to an existing layout (PRESENCE, CALL) when old phones simply ignore
  them; anything else needs a new type or CHAT kind.

## 11. Tunnel comms (a phone reaching the group over the internet)

Optional. A **relay** (the LAN storage server's bridge, or a phone, best the commander tablet) stays on the group's network; the router
forwards one port per remote person (9991, 9992, …, UDP and TCP) to it. Each **remote** phone has
the relay's internet address, its port and a key made from one DRYAD row. The remote sends every
radio packet it would broadcast to the relay instead, and the relay sends back whatever is heard
locally on the remote's channels. Radio packets are carried unchanged, so they stay end-to-end
encrypted with the channel keys; the relay never needs channel names.

- **Key:** `ChannelCrypto("tunnel:" + DRYAD row)` (the same PBKDF2 derivation as a channel name).
- **Frame:** `'U' 'T'` type(1) 0(1) session(8) seq(4) | AES-256-CTR body (IV = session(8) seq(4)
  `'T'` 0 0 0) | HMAC-SHA256 over everything before it, truncated to 10 bytes.
  - type 1 **DATA**: body = one radio packet exactly as sent on the network.
  - type 2 **HELLO** (remote to relay, every 5 s): time ms(8) nonce(8) count(1) then count channel
    tags (4 each): every channel the remote talks or listens on.
  - type 3 **HELLO_ACK** (relay to remote): the nonce(8) from the HELLO.
- **Replay protection:** each end has a random session id per start and counts seq up from 0; the
  receiver accepts each seq of the current session once (64-frame window). The relay accepts a new
  remote session only from a HELLO whose time is later than any HELLO before; the remote accepts a
  new relay session only in a HELLO_ACK that echoes its own nonce.
- **Transport:** UDP first. If the remote hears nothing for 12 s it connects TCP to the same port
  and sends the same frames, each after a 2-byte big-endian length; while on TCP it still tries a
  UDP HELLO every 30 s and goes back to UDP as soon as a HELLO_ACK arrives over UDP. Replies go
  back the way the last valid frame came (the remote's NAT address may change). On TCP the sender
  keeps at most about 80 frames queued (1.5 s of voice), dropping the oldest, so voice cannot lag
  further and further behind.
- **Relay forwarding:** a DATA frame from a remote is broadcast on the local network (and handed to
  the relay phone itself) and sent to every other remote listening to its channel tag. Packets heard
  locally, and the relay phone's own, go to every remote whose last HELLO listed their channel tag
  (and that was heard in the last 30 s). The relay remembers (senderId, seq) of what it forwarded so
  a packet that comes back (broadcast loop-back, UDP and TCP copies) is not forwarded or played twice.
