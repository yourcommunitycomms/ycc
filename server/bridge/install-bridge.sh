#!/bin/sh
# Installs the YCC bridge as a startup service on this computer (the host, not inside Docker).
# Run from the server folder: sudo sh bridge/install-bridge.sh [store folder]
set -e
STORE="${1:-/opt/project-nomad/storage/ycc-files}"
DIR=/opt/ycc-bridge
mkdir -p "$DIR"
cp "$(dirname "$0")"/ycc_bridge.py "$(dirname "$0")"/tunnel.py "$(dirname "$0")"/yccrypto.py "$DIR"/
cat > /etc/systemd/system/ycc-bridge.service <<EOF
[Unit]
Description=YCC bridge (WiFi to Waydroid/emulators, tunnel relay for remote people)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 $DIR/ycc_bridge.py $STORE
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now ycc-bridge
systemctl --no-pager status ycc-bridge | head -5
echo "Installed. Switch it on or off, and add remote people, on the admin page's Bridge tab."