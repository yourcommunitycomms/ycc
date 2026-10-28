#!/usr/bin/env python3
"""YCC bridge: joins the group's WiFi to devices that cannot hear its broadcasts, and is the tunnel
relay for remote people. Runs on the computer that hosts the LAN storage (e.g. Project NOMAD), as a
startup service. Python 3, no add-ons. It never decrypts radio packets (it has no channel names).

- LAN: hears the phones' broadcasts (UDP 47474) and broadcasts what the others send.
- Clients: Waydroid or emulator devices whose Extra peers point here (UDP 47400).
- Remotes: phones away from the group, each on its own forwarded port with its own DRYAD-row key
  (tunnel frames, docs/PROTOCOL.md section 11). They get what is heard on the channels they listen to.

Settings come from <store>/.ycc_bridge.json, written by the admin page's Bridge tab (re-read when it
changes); the state goes to <store>/.ycc_bridge_status.json every few seconds for the page to show.

Run: python3 ycc_bridge.py <store folder, e.g. /opt/project-nomad/storage/ycc-files>
"""
import json
import os
import select
import socket
import struct
import subprocess
import sys
import time
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel  # noqa: E402

VERSION = "2.0"
STORE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "/opt/project-nomad/storage/ycc-files")
CONFIG = os.path.join(STORE, ".ycc_bridge.json")
STATUS = os.path.join(STORE, ".ycc_bridge_status.json")
DEFAULTS = {"on": True, "lan_broadcast": "auto", "client_port": 47400, "app_port": 47474, "public_host": "",
            "remotes": []}
CLIENT_KEEP = 60


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def own_ips():
    try:
        return set(subprocess.check_output(["hostname", "-I"], text=True).split())
    except (OSError, subprocess.CalledProcessError):
        return set()


def auto_broadcast():
    """The broadcast address of the interface with the default route."""
    try:
        dev = subprocess.check_output(["ip", "-4", "route", "show", "default"], text=True).split()
        dev = dev[dev.index("dev") + 1]
        for word in subprocess.check_output(["ip", "-4", "-o", "addr", "show", "dev", dev], text=True).split("\n"):
            parts = word.split()
            if "brd" in parts:
                return parts[parts.index("brd") + 1]
    except (OSError, ValueError, IndexError, subprocess.CalledProcessError):
        pass
    return "255.255.255.255"


def lan_addresses():
    """[{"dev", "ip", "prefix", "brd"}] of this computer's IPv4 networks (not loopback or Docker)."""
    out = []
    try:
        for line in subprocess.check_output(["ip", "-4", "-o", "addr", "show"], text=True).splitlines():
            p = line.split()
            dev, cidr = p[1], p[3]
            if dev == "lo" or dev.startswith(("docker", "br-", "veth")):
                continue
            ip, _, prefix = cidr.partition("/")
            out.append({"dev": dev, "ip": ip, "prefix": prefix, "brd": p[p.index("brd") + 1] if "brd" in p else ""})
    except (OSError, ValueError, IndexError, subprocess.CalledProcessError):
        pass
    return out


def packet_key(p):
    """(senderId, seq) of a radio packet, or None if it is not one."""
    return p[8:20] if len(p) >= 30 and p[:2] == b"UB" else None


def packet_tag(p):
    return struct.unpack(">I", p[4:8])[0] if len(p) >= 8 and p[:2] == b"UB" else 0


class Bridge:
    def __init__(self, cfg):
        self.cfg = cfg
        self.bcast = cfg["lan_broadcast"] if cfg.get("lan_broadcast") not in ("", "auto", None) else auto_broadcast()
        self.app_port, self.client_port = int(cfg["app_port"]), int(cfg["client_port"])
        self.mine = own_ips()
        self.clients = {}
        self.seen = OrderedDict()
        self.counts = {"lan_in": 0, "lan_out": 0, "client_in": 0, "remote_in": 0}
        self.problem = ""
        self.lan = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.lan.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.lan.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.lan.bind(("0.0.0.0", self.app_port))
        self.cli = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.cli.bind(("0.0.0.0", self.client_port))
        self.remotes = []
        for r in cfg.get("remotes", []):
            if r.get("on", True) and r.get("row") and r.get("port"):
                self.remotes.append(tunnel.Remote(r.get("label", ""), r["port"], r["row"]))

    def close(self):
        for s in (self.lan, self.cli):
            s.close()
        for r in self.remotes:
            r.close()

    def first_time(self, p):
        """False for a packet already passed on (loops, UDP+TCP copies)."""
        k = packet_key(p)
        if k is None:
            return False
        if k in self.seen:
            return False
        self.seen[k] = 1
        while len(self.seen) > 4000:
            self.seen.popitem(last=False)
        return True

    def to_remotes(self, p, skip=None):
        tag = packet_tag(p)
        for r in self.remotes:
            if r is not skip and r.listens_to(tag):
                r.send(tunnel.DATA, p)

    def to_clients(self, p, skip=None):
        for a in self.clients:
            if a != skip:
                self.cli.sendto(p, a)

    def to_lan(self, p):
        try:
            self.lan.sendto(p, (self.bcast, self.app_port))
            self.counts["lan_out"] += 1
        except OSError as e:
            self.problem = "LAN send: %s" % e

    def step(self, timeout):
        now = time.time()
        for a in [a for a, t in self.clients.items() if now - t > CLIENT_KEEP]:
            del self.clients[a]
        rd = [self.lan, self.cli]
        wr = []
        owner = {}
        for r in self.remotes:
            for s in (r.udp, r.server):
                if s:
                    rd.append(s)
                    owner[s] = r
            for c in r.conns:
                rd.append(c.sock)
                owner[c.sock] = (r, c)
                if c.wants_write():
                    wr.append(c.sock)
        ready, writable, _ = select.select(rd, wr, [], timeout)
        for s in writable:
            r, c = owner[s]
            try:
                c.flush()
            except OSError:
                r.drop_tcp(c)
        for s in ready:
            if s is self.lan:
                p, src = s.recvfrom(4096)
                if src[0] in self.mine or any(src[0] == a[0] for a in self.clients) or not self.first_time(p):
                    continue
                self.counts["lan_in"] += 1
                self.to_clients(p)
                self.to_remotes(p)
            elif s is self.cli:
                p, src = s.recvfrom(4096)
                if len(p) < 4:
                    continue
                self.clients[src] = now
                if not self.first_time(p):
                    continue
                self.counts["client_in"] += 1
                self.to_clients(p, skip=src)
                self.to_lan(p)
                self.to_remotes(p)
            else:
                o = owner.get(s)
                if isinstance(o, tuple):
                    r, c = o
                    try:
                        data = s.recv(65536)
                        if not data:
                            raise OSError("closed")
                        for f in list(c.frames(data)):
                            self.from_remote(r, r.handle(f, "TCP", conn=c))
                    except OSError:
                        r.drop_tcp(c)
                elif s is o.server:
                    try:
                        sock, addr = s.accept()
                        o.conns.append(tunnel.TcpConn(sock, addr))
                    except OSError:
                        pass
                else:
                    f, src = s.recvfrom(4096)
                    self.from_remote(o, o.handle(f, "UDP", addr=src))

    def from_remote(self, r, p):
        if p is None or not self.first_time(p):
            return
        self.counts["remote_in"] += 1
        self.to_lan(p)
        self.to_clients(p)
        self.to_remotes(p, skip=r)

    def status(self):
        return {"time": time.time(), "running": True, "version": VERSION, "broadcast": self.bcast,
                "app_port": self.app_port, "client_port": self.client_port, "problem": self.problem,
                "clients": ["%s:%d" % a for a in self.clients], "counts": self.counts, "lan": lan_addresses(),
                "remotes": [r.status() for r in self.remotes]}


def write_status(st):
    tmp = STATUS + ".part"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(st, f)
        os.replace(tmp, STATUS)
    except OSError:
        pass


def main():
    print("YCC bridge %s, settings in %s" % (VERSION, CONFIG), flush=True)
    bridge, stamp, last_status = None, None, 0
    while True:
        try:
            m = os.path.getmtime(CONFIG)
        except OSError:
            m = 0
        if m != stamp:  # settings changed on the admin page: start again with them
            stamp = m
            if bridge:
                bridge.close()
                bridge = None
            cfg = load_config()
            if cfg.get("on"):
                try:
                    bridge = Bridge(cfg)
                    print("on: broadcast %s, %d remote(s)" % (bridge.bcast, len(bridge.remotes)), flush=True)
                except OSError as e:
                    write_status({"time": time.time(), "running": False, "problem": "cannot start: %s" % e})
                    print("cannot start:", e, flush=True)
                    time.sleep(10)
                    stamp = None  # try again
                    continue
            else:
                print("off", flush=True)
        if bridge:
            bridge.step(1.0)
        else:
            time.sleep(1.0)
        if time.time() - last_status >= 3:
            last_status = time.time()
            write_status(bridge.status() if bridge else {"time": time.time(), "running": False, "version": VERSION,
                                                         "lan": lan_addresses(), "problem": "switched off on the admin page"})


if __name__ == "__main__":
    main()
