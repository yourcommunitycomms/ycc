# Server and desktop setup (release 1)

Two optional extras for a group using Your Community Communications:

1. The **LAN storage server**: a small file server in Docker, here on **Project NOMAD** (an offline
   Ubuntu server with a "Supply Depot" for custom apps), though any Linux box, PC or Raspberry Pi
   with Docker or Python 3.7+ works.
2. The **commander view on an Ubuntu desktop**: the same Android app running in the Android
   emulator, since there is no native desktop version.

Replace `<nomad>` with the server's name or address on your network and choose your own
passwords. Nothing here needs the internet once the files are copied across.

## 1. LAN storage server on Project NOMAD

### What it is
`ycc_dav.py` (Python, no add-ons) serves `/data` over HTTP with WebDAV-style PUT and MKCOL, behind one
login. Phones keep map tiles, app updates, missions, logs, data files, DRYAD sheets and config
backups in `/data/YCC/`. `/webadmin` is the management page. Map tiles (`/YCC/Maps cache/...`) and
the APK (`/YCC/Updates/YCC.apk`) can be read without a login, because ATAK map sources and phone
browsers cannot log in. Everything else, and every upload, needs the login.

### Build the image on the NOMAD box
NOMAD's Supply Depot runs images that already exist on the server; it does not upload or build them.
1. Copy the `server` folder (Dockerfile, `*.py`, README, LICENSE, NOTICE, docs) to the NOMAD box, for
   example with `scp -r server <user>@<nomad>:ycc-files`.
2. On the box (SSH, or a terminal on its desktop):
   ```
   cd ~/ycc-files
   sudo docker build -t ycc-files:1.1 .
   sudo mkdir -p /opt/project-nomad/storage/ycc-files
   ```

### Add it in Supply Depot > Add Custom App
- **Docker image:** `ycc-files:1.1` (a version tag avoids the "latest" warning).
- **Port mapping:** host `8647` to container `47490` (NOMAD wants custom apps on host ports 8600+;
  pick any free one).
- **Volume:** host `/opt/project-nomad/storage/ycc-files` to container `/data` (inside NOMAD's managed
  storage, so no warning, and the files survive app updates).
- **Environment variables:** `YCC_PASSWORD` = your password (required), and `YCC_USER` if not `ycc`.
  Optional: `YCC_MAX_UPLOAD_MB` (default 2048), and `TZ` (for example `AEST-10AEDT,M10.1.0,M4.1.0/3`)
  so the Sync tab's hours follow local time instead of UTC.
- **Launcher URL:** `http://<nomad>:8647/webadmin`.

Install and start it, then open the launcher. The browser asks for the login.

### First steps on the admin page
1. **Login:** change the password if the environment one was temporary. Phones need the new one.
2. **Phone setup:** check the storage address (`http://<nomad>:8647/YCC`), add the tunnel server,
   Public key, OpenMANET group and node, home WiFi and (only if everyone with the code may have it)
   channel A. Save, then print or show the QR code.
3. **Defaults:** set one phone up as the group wants, turn on *LAN sync: config* and **Sync now**
   there, then **Use as defaults** on its backup. Tip: put `Extra peers` for a desktop commander
   (below) in the defaults so every phone gets it.
4. **Updates:** publish a new APK (the version is read from it). Phones with auto updates on fetch it.
5. **Sync:** add other stores (for example a Raspberry Pi running the same server) with the folders
   to copy, how often, between which hours, and a KB/s limit. Test login, then Run now. Sync only
   adds and replaces files there; it never deletes anything.

### The bridge and tunnel relay (Bridge tab)
The **bridge** (`server/bridge/`) runs on the NOMAD box itself, not in Docker, because it has to hear
the WiFi. It does two jobs, and never decrypts anything:
- It joins the WiFi to devices on this computer that cannot hear its broadcasts (a Waydroid tablet
  or an emulator, section 3), with no changes on the phones.
- It is the **tunnel relay** for remote people: phones away from the group connect over the
  internet to a port forwarded on your router. Each person has their own port and their own DRYAD
  row as the key. No phone has to stay at home as the relay.

Install it once, as a startup service (in a terminal on the box, from the server folder):
```
sudo sh bridge/install-bridge.sh /opt/project-nomad/storage/ycc-files
```
Then everything is on the admin page's **Bridge** tab, which talks to it through the shared data
folder: **Run the bridge** on or off (remembered across restarts), the WiFi broadcast address
(auto), the ports, and its live state. Its **External gateway** section lists this computer's LAN
addresses, says which ports to forward to which address, and holds your **internet address** (the
router's public address or a DDNS name; **Find my WAN address** looks it up), which remote people's
QR codes need. Live state (linked devices, packet counts, each remote person's connection). **Add a person:** name,
port (9991, 9992, …) and a key picked from the DRYAD sheets the store holds (or a pasted row). Forward
that port, UDP and TCP, on the router to the NOMAD box. Their **QR code** holds the tunnel address
and key; they scan it in Settings > Scan QR (after the normal phone setup code on a new phone) and
turn on Settings > Tunnel comms > **Use the relay now** when away.

### Moving or copying files in
Files dropped into `/opt/project-nomad/storage/ycc-files/YCC/` appear straight away (for example a
`Maps cache` folder copied from an older store). A tile set put in `YCC/Maps cache/<any name>/z/x/y.jpg` (e.g. one
made for ATAK) of satellite pictures is read by every phone using a satellite map, before it goes online.

### A second map server in the field
Any second copy of this server can hold map tiles only: on a phone, Map menu > Data Tile Management > Secondary server, then
Sync the two servers copies the Maps cache across through the phone with no internet. Mission data is not copied.

### Updating the server
Copy the new files to `~/ycc-files`, rebuild with the same tag (`sudo docker build -t ycc-files:1.1 .`)
and restart the app in NOMAD (or reinstall it with the same settings). The data volume is untouched.

### A clean copy to share
The **Erase** tab empties the store and puts the login back to the container's `YCC_PASSWORD`
(optionally keeping map tiles). The image itself never holds anyone's files, so after that the image
(`sudo docker save ycc-files:1.1 | gzip > ycc-files.tar.gz`) or the server folder can be handed on.

### Without NOMAD
- Docker Compose: `docker-compose.yml` in the server folder (`YCC_PASSWORD` in a `.env` file next to it),
  then `docker compose up -d`.
- No Docker: `YCC_ROOT=/path/to/files YCC_PASSWORD=... python3 ycc_dav.py 47490`.

## 2. Commander view on an Ubuntu desktop (Android emulator)

There is no desktop build of the app, and no easy way to run an Android app on Linux without an
emulator, so the commander view runs as a **tablet in the Android emulator**. In landscape it shows
the same commander view as a real tablet: big map, contacts and channels beside it, broadcast to
teams, and PTT with the desktop microphone.

### Needs
A 64-bit Ubuntu desktop (22.04 or 24.04) with hardware virtualisation (Intel VT-x or AMD-V turned on in
the BIOS), 8 GB RAM or more, about 10 GB of disk, and a microphone and speakers or a headset.

### Install the emulator
Either install **Android Studio** (from developer.android.com; Device Manager does the next steps
with buttons), or use the command-line tools:
```
sudo apt install openjdk-17-jdk unzip qemu-kvm cpu-checker
kvm-ok                                  # should say "KVM acceleration can be used"
sudo adduser $USER kvm                  # then log out and back in
mkdir -p ~/android/cmdline-tools && cd ~/android/cmdline-tools
unzip ~/Downloads/commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
export ANDROID_SDK_ROOT=~/android PATH=$PATH:~/android/cmdline-tools/latest/bin:~/android/platform-tools:~/android/emulator
sdkmanager "platform-tools" "emulator" "platforms;android-34" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n YCC_Commander -k "system-images;android-34;google_apis;x86_64" -d pixel_tablet
```
(Add the `export` line to `~/.bashrc` to keep it.)

### Start it and install the app
```
emulator -avd YCC_Commander -gpu host &
adb wait-for-device
adb install YCC.apk
```
`YCC.apk` comes from `http://<nomad>:8647/YCC/Updates/YCC.apk`. Turn the tablet sideways with the
rotate button on the emulator's side bar for the commander view. For the microphone: the emulator's
**⋯ (Extended controls) > Microphone > "Virtual microphone uses host audio input"** (turn it on
again after each start). Location: Extended controls > Location sets where the commander is, or use
the app's Fake GPS.

### Link it to the group
The emulator sits behind its own network address translation, so it cannot hear or send the
group's WiFi broadcasts. A tiny relay on the desktop joins them:
1. Copy `relay/UdpRelay.java` from the source and run it (it never decrypts anything):
   ```
   java UdpRelay.java 47400 0.0.0.0
   ```
   If phones cannot reach it, list them as fixed clients so the desktop firewall lets their replies
   in: `java UdpRelay.java 47400 0.0.0.0 0 <phone1>:47474,<phone2>:47474`. With `ufw`, also
   `sudo ufw allow 47400/udp`.
2. In the emulator's app: Settings > Network > Advanced > **Extra peers** `10.0.2.2:47400` (the
   desktop, seen from inside the emulator).
3. On each phone: **Extra peers** `<desktop address>:47400`. Easiest: put it in the network defaults
   on the admin page, so every phone set up by QR gets it.

Then set the commander's channels as on any phone (or scan the phone setup QR code shown on another
screen: the emulator's camera can be the desktop webcam, `emulator -avd YCC_Commander -camera-back webcam0`).

### Everyday use
Start the emulator from Android Studio's Device Manager or `emulator -avd YCC_Commander`, and the relay
with `java UdpRelay.java 47400 0.0.0.0`. The app's settings, maps and chats stay in the emulator
between runs. A desktop shortcut can start both.

## 3. Commander tablet in Waydroid (Ubuntu desktop on Wayland, tested on NOMAD)

Waydroid runs Android in a container instead of an emulator: lighter, but one Android at a time, and
it needs a Wayland desktop session (Ubuntu's default GNOME is fine). Tested on Ubuntu 26.04 with no
graphics card (software rendering).
1. Install from Waydroid's own repository (replace `resolute` with your Ubuntu codename):
   ```
   sudo curl -fsSL https://repo.waydro.id/waydroid.gpg -o /usr/share/keyrings/waydroid.gpg
   echo 'deb [signed-by=/usr/share/keyrings/waydroid.gpg] https://repo.waydro.id/ resolute main' | sudo tee /etc/apt/sources.list.d/waydroid.list
   sudo apt update && sudo apt install waydroid
   sudo waydroid init -s VANILLA            # downloads about 1 GB of Android 13 images
   ```
2. No graphics card (a server's onboard VGA): add `ro.hardware.gralloc=default`,
   `ro.hardware.egl=swiftshader` and `ro.sf.lcd_density=160` under `[properties]` in
   `/var/lib/waydroid/waydroid.cfg`, then `sudo waydroid upgrade --offline`.
3. In the desktop session: `waydroid session start`; make a landscape tablet-sized window with
   `waydroid prop set persist.waydroid.width 1600` and `waydroid prop set persist.waydroid.height 1000`,
   restart the session, then `waydroid show-full-ui`.
4. Install the app: `waydroid app install YCC.apk` (or copy it into
   `~/.local/share/waydroid/data/local/tmp/` and run `sudo waydroid shell -- pm install /data/local/tmp/YCC.apk`).
   At that size, in landscape, the app shows the commander view.
5. Set it up as usual: Settings > LAN storage location `http://192.168.240.1:8647/YCC` (the host as
   Waydroid sees it), and **Extra peers** `192.168.240.1:47400`.
6. Link it to the WiFi with the **bridge** (section 1, Bridge tab). It hears the phones' broadcasts
   and passes them to Waydroid (and any emulator), and broadcasts what Waydroid sends, so the real
   phones need no changes. Without the service: `sudo python3 server/bridge/ycc_bridge.py <store folder>`.
7. Desktop use: turn Android's screen timeout off
   (`sudo waydroid shell -- settings put system screen_off_timeout 2147483647`). After a reboot: log
   in, then `waydroid session start` and `waydroid show-full-ui` (the bridge service starts by itself).
