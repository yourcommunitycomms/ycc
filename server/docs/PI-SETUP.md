# YCC local cache server on a Raspberry Pi

The file server (LAN storage and the `/webadmin` page) and the bridge (relay), installed straight onto
Raspberry Pi OS with systemd, over SSH. Same code as the NOMAD server, no Docker, nothing beyond the
Python 3 that comes with the OS. Commands are for **Raspberry Pi OS Lite (64-bit, Bookworm)**.

- **File server** (`ycc_dav.py`, port 8647): maps, updates, missions, logs, DRYAD sheets, config backups;
  the admin page is `/webadmin` on the same port and login.
- **Bridge** (`bridge/ycc_bridge.py`): a host service, set from the admin page's **Bridge** tab. It hears
  the phones' WiFi broadcasts and is the tunnel relay for remote people (**Direct**: a forwarded port
  each), or this site's gateway to your internet relay (**Via cloud relay**: no port forwarding), or both.
  A virtual Android device (Waydroid, emulator) is only something it can link in; the relay does not need
  one. It never decrypts anything.

## What you need
Pi 3B+, 4 or 5 (a Zero 2 W for a small group); a 32 GB+ card or a USB SSD; the official power supply (a
UPS HAT keeps it up); Ethernet or WiFi on the **same network as the phones**; a PC with Raspberry Pi
Imager and SSH; internet once, for packages and the code. For the cloud relay: the relay's `host:port` and
a **site** key from the app's Relay admin screen.

## 1. Flash and log in
In Raspberry Pi Imager pick the model, **Raspberry Pi OS Lite (64-bit)** and the card. In Edit settings:
hostname `ycc-pi`, a user and password (this guide uses `pi`), WiFi if not on Ethernet, the time zone,
and **Enable SSH** (password). Write it, boot the Pi, wait two minutes, then:
```
ssh pi@ycc-pi.local
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y git python3 python3-cryptography
```
(`python3-cryptography` is optional: faster encryption for the bridge.) Give the Pi a fixed address (a
DHCP reservation on the router).

## 2. Get the code
```
cd ~
git clone https://github.com/yourcommunitycomms/ycc.git
ls ~/ycc/server
```
No internet on the Pi: `scp -r server pi@ycc-pi.local:~/ycc-server` from a PC and use that folder below.

## 3. File server and admin page
```
sudo mkdir -p /opt/ycc-files /srv/ycc-files
sudo cp -r ~/ycc/server/* /opt/ycc-files/
sudo useradd --system --home /srv/ycc-files --shell /usr/sbin/nologin ycc
sudo chown -R ycc:ycc /srv/ycc-files

sudo tee /etc/ycc-files.env > /dev/null <<'EOF'
YCC_ROOT=/srv/ycc-files
YCC_PORT=8647
YCC_USER=ycc
YCC_PASSWORD=choose-a-strong-password
TZ=Australia/Melbourne
EOF
sudo chmod 600 /etc/ycc-files.env

sudo tee /etc/systemd/system/ycc-files.service > /dev/null <<'EOF'
[Unit]
Description=YCC file store and admin page
After=network-online.target
Wants=network-online.target

[Service]
User=ycc
EnvironmentFile=/etc/ycc-files.env
WorkingDirectory=/opt/ycc-files
ExecStart=/usr/bin/python3 -u /opt/ycc-files/ycc_dav.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now ycc-files
systemctl status ycc-files --no-pager
curl -s -o /dev/null -w '%{http_code}\n' -u ycc:choose-a-strong-password http://localhost:8647/webadmin
```
`200` = working. With `ufw` on: `sudo ufw allow 8647/tcp`, `sudo ufw allow 47474/udp`,
`sudo ufw allow 47400/udp` (file server, phones' radio port, linked devices).

## 4. Bridge
```
cd /opt/ycc-files
sudo sh bridge/install-bridge.sh /srv/ycc-files
journalctl -u ycc-bridge -n 20 --no-pager
```
The log shows `YCC bridge 2.1` and `relay mode direct`. Everything else is on the Bridge tab.

## 5. First login and phones
Open `http://ycc-pi.local:8647/webadmin`, log in as `ycc`. **Login**: change the password if needed.
**Phone setup**: check the storage address `http://<pi>:8647/YCC`, fill in your group's settings, show the
QR code; phones scan it (Settings > Scan QR). **Updates**: publish the APK. **Sync** (optional): copy to or
from another store such as NOMAD (never deletes).

## 6. Relay mode (Bridge tab)
| Mode | What it does | Set up |
|---|---|---|
| Direct | Remote people connect through router port forwards, one port and key each | Internet address, Remote people, router forwards |
| Via cloud relay | The Pi joins your internet relay (VPS) as this site's gateway | Relay address and site key |
| Both | Both at once | Both |

**Via cloud relay:** on the admin's phone, Relay admin > add a **site**; copy its key or its setup code text
(`YCCSETUP1|{...}`). On the Bridge tab choose Via cloud relay and fill **Relay address** (`host:port`),
**Site key** (or paste the setup code: it fills both), **Gateway name**, and **Channels shared** (empty =
every channel heard on the WiFi, or channel tags such as `230fba64`). Save: within about 10 s the tab shows
Cloud relay up, the channels going up and the relay's sites. Only one gateway per site sends: if a gateway
phone on the WiFi has the same site key, the one that started first carries the traffic and the other
waits (taking over 20 s after the first goes quiet).

## Updating
```
cd ~/ycc && git pull
sudo cp -r ~/ycc/server/* /opt/ycc-files/
sudo systemctl restart ycc-files
sudo sh /opt/ycc-files/bridge/install-bridge.sh /srv/ycc-files
```

## Checking and troubleshooting
- Services: `systemctl status ycc-files ycc-bridge --no-pager`; logs `journalctl -u ycc-files -f`, `journalctl -u ycc-bridge -f`;
  bridge state `cat /srv/ycc-files/.ycc_bridge_status.json`; addresses `hostname -I`; disk `df -h /srv`.
- Bridge tab says it never ran: run step 4 again with `/srv/ycc-files`.
- No packets from the WiFi: the Pi is not on the phones' network, or set the broadcast address (e.g. `192.168.1.255`).
- Cloud relay down: check the address and port, the VPS firewall (UDP and TCP), and the key. "standby" = a
  gateway phone with the same key is active (expected). "removed" = the relay's admin removed the site: add it again.
