# YCC file store (release 1; Docker, Project NOMAD custom app)

The LAN storage location for Your Community Communications: map tiles, app updates, missions, logs,
data files, DRYAD sheets, config backups and the group's network defaults. Plain HTTP with
WebDAV-style PUT and MKCOL, and Basic auth. Python 3 only, no add-ons. Port 47490 inside the
container. Full install steps (Project NOMAD, plain Docker, and the commander view on an Ubuntu
desktop): [docs/NOMAD-SETUP.md](docs/NOMAD-SETUP.md).

## Add to Project NOMAD (Supply Depot)

NOMAD's "Add Custom App" form takes an image already on the server; it does not upload files.

1. Copy this folder to the NOMAD server (e.g. ~/ycc-files) and build the image there:
   `sudo docker build -t ycc-files:1.1 .` (a pinned tag avoids NOMAD's moving-tag warning).
2. Add Custom App:
   - Docker Image: `ycc-files:1.1`
   - Port Mappings: host `8647` → container `47490` (custom apps use host ports 8600+)
   - Volume Mounts: `/opt/project-nomad/storage/ycc-files` : `/data` (inside NOMAD's managed storage)
   - Environment Variables: `YCC_PASSWORD` (and `YCC_USER` if not `ycc`; `TZ` for local schedule hours)
   - Launcher URL: `http://<nomad>:8647/webadmin`
3. Phones use `http://<nomad>:8647/YCC` as their LAN storage location, or scan the QR code on the
   admin page's Phone setup tab.

## Or plain Docker

Create `.env` next to `docker-compose.yml`:
```
YCC_USER=ycc
YCC_PASSWORD=choose-one
```
Then `docker build -t ycc-files:1.1 .` and `docker compose up -d`. Without Docker:
`YCC_ROOT=/path YCC_PASSWORD=choose-one python3 ycc_dav.py 47490`.

## The admin page (/webadmin, same login)

| Tab | What it does |
|---|---|
| Manage | Folder sizes; delete files older than N days; delete missions or idle missions; delete map tile sources |
| Files | Browse, download, upload, rename, delete, new folder |
| Defaults | The network defaults phones load (from a phone's backup or a file; edit settings and DRYAD sheets) |
| Phone setup | The setup QR code: store and login, tunnel, Public key, channel A, OpenMANET, home WiFi |
| Sync | Copy new and changed files to other stores (e.g. a Pi): folders, schedule, hours, KB/s limit; never deletes there |
| Bridge | The bridge service on the host (bridge/, install with `sudo sh bridge/install-bridge.sh`): links Waydroid/emulator devices to the WiFi and is the tunnel relay for remote people (port, DRYAD-row key, QR code each); on/off and status |
| Updates | Publish an APK (version code read from it) or stop offering it |
| Login | Change the user and password (kept in the data volume) |
| Docs | Licence (Apache-2.0), rebuilding with the AI prompt, install notes |
| Erase | Everything back to an empty store, for a clean snapshot (type ERASE) |

Destructive buttons ask first, and posts are only accepted from the page itself.

## What needs a login

| Path | Read | Write |
|---|---|---|
| `/YCC/Maps cache/...` | anyone (ATAK map sources cannot log in) | login |
| `/YCC/Updates/YCC.apk` | anyone (for browser downloads) | login |
| everything else, and /webadmin | login | login |

## Files

`ycc_dav.py` (the server), `admin.py` (the admin page), `setup.py` (phone setup QR), `qr.py` (QR
maker), `sync.py` (sync destinations), `bridge_admin.py` (Bridge tab) and `bridge/` (the bridge itself:
`ycc_bridge.py`, `tunnel.py`, `yccrypto.py`; it runs on the host, not in Docker). Saved in the data volume as hidden files: `.ycc_login`,
`.ycc_setup.json`, `.ycc_sync.json` and `.ycc_sync/` (never served).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `YCC_PASSWORD` | (required) | Login password (the Login tab can override it) |
| `YCC_USER` | `ycc` | Login user |
| `YCC_PORT` | `47490` | Port inside the container |
| `YCC_MAX_UPLOAD_MB` | `2048` | Largest single upload |
| `TZ` | UTC | Time zone for the Sync tab's hours |
