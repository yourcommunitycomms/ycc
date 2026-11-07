#!/bin/sh
# Installs the YCC internet relay as a systemd service on Ubuntu/Debian (run with sudo from the
# folder holding ycc-relay.jar, ycc-relay.service and relay.conf).
# Needs Java 8+ (sudo apt install openjdk-17-jre-headless). Open the relay's port (47100 by default),
# UDP and TCP, in the server's firewall and the cloud provider's network rules.
# The relay makes its admin key on the first start; see relay.conf.example for showing it.
set -e
id ycc-relay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin ycc-relay
install -d -m 755 /opt/ycc-relay
install -m 644 ycc-relay.jar /opt/ycc-relay/ycc-relay.jar
install -d -m 750 -g ycc-relay /etc/ycc-relay
[ -f /etc/ycc-relay/relay.conf ] || install -m 640 -g ycc-relay relay.conf /etc/ycc-relay/relay.conf
install -m 644 ycc-relay.service /etc/systemd/system/ycc-relay.service
systemctl daemon-reload
systemctl enable ycc-relay
systemctl restart ycc-relay
sleep 3
systemctl --no-pager status ycc-relay | head -n 12
ss -lntu | grep -E ':471[01][0-9] ' || true
