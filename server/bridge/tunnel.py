"""Tunnel relay ends for the bridge: one per remote person (their own port and DRYAD-row key).

Same frames and rules as Tunnel.java and docs/PROTOCOL.md section 11:
  'U' 'T' type(1) 0(1) session(8) seq(4) | AES-256-CTR body (IV session seq 'T' 0 0 0) | HMAC (10)
  type 1 DATA (a radio packet), 2 HELLO (time(8) nonce(8) count(1) tags(4 each)), 3 HELLO_ACK (nonce(8)).
UDP and TCP on the same port; on TCP each frame has a 2-byte big-endian length in front.
"""
import os
import socket
import struct
import time
from collections import OrderedDict

from yccrypto import Keys, MAC_LEN

DATA, HELLO, HELLO_ACK, SITES, BEACON, BYE = 1, 2, 3, 4, 5, 6
HDR = 16
HDR_ID = 20  # with the 4-byte key ID (flags 1): internet relay frames
TCP_QUEUE = 80  # frames kept waiting on a slow TCP link (about 1.5 s of voice), oldest dropped


def _ago(s):
    return "%d s" % s if s < 120 else "%d min" % (s // 60) if s < 7200 else "%.1f h" % (s / 3600)


def tunnel_keys(row):
    return Keys("tunnel:" + row.strip())


class Window:
    """Replay window: each seq of the peer's current session once (last 64)."""

    def __init__(self):
        self.session, self.top, self.bits, self.ready = None, -1, 0, False

    def reset(self, s):
        self.session, self.top, self.bits, self.ready = s, -1, 0, True

    def accept(self, s, seq):
        if not self.ready or s != self.session or seq < 0:
            return False
        if seq > self.top:
            shift = seq - self.top
            self.bits = 1 if shift >= 64 else ((self.bits << shift) | 1) & ((1 << 64) - 1)
            self.top = seq
            return True
        back = self.top - seq
        if back >= 64 or self.bits & (1 << back):
            return False
        self.bits |= 1 << back
        return True


class TcpConn:
    def __init__(self, sock, addr):
        self.sock, self.addr = sock, addr
        self.inbuf = b""
        self.out = []  # whole length-prefixed frames
        self.partial = b""
        sock.setblocking(False)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def frames(self, data):
        self.inbuf += data
        while len(self.inbuf) >= 2:
            n = struct.unpack(">H", self.inbuf[:2])[0]
            if n > 4096:
                raise OSError("frame too big")
            if len(self.inbuf) < 2 + n:
                break
            yield self.inbuf[2:2 + n]
            self.inbuf = self.inbuf[2 + n:]

    def queue(self, frame):
        self.out.append(struct.pack(">H", len(frame)) + frame)
        while len(self.out) > TCP_QUEUE:
            self.out.pop(0)

    def flush(self):
        while self.partial or self.out:
            if not self.partial:
                self.partial = self.out.pop(0)
            try:
                n = self.sock.send(self.partial)
            except BlockingIOError:
                return
            self.partial = self.partial[n:]

    def wants_write(self):
        return bool(self.partial or self.out)


class Remote:
    """One remote person: their port, label and key, and how to reach them now."""

    def __init__(self, label, port, row):
        self.label, self.port = label, int(port)
        self.keys = tunnel_keys(row)
        self.session = struct.unpack(">Q", os.urandom(8))[0]
        self.seq = 0
        self.win = Window()
        self.listen = ()
        self.last_hello_time = 0
        self.last_rx = 0.0
        self.via = ""
        self.udp_peer = None
        self.tcp = None  # the TcpConn replies go to
        self.conns = []
        self.problem = ""
        self.frames_in = self.frames_out = 0
        self.udp = self.server = None
        try:
            self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp.bind(("0.0.0.0", self.port))
        except OSError as e:
            self.udp = None
            self.problem = "UDP port %d busy (%s)" % (self.port, e.strerror)
        try:
            self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server.bind(("0.0.0.0", self.port))
            self.server.listen(4)
            self.server.setblocking(False)
        except OSError as e:
            self.server = None
            self.problem = (self.problem + "; " if self.problem else "") + "TCP port %d busy (%s)" % (self.port, e.strerror)

    # ---- frames ----

    def seal(self, ftype, body):
        head = b"UT" + bytes((ftype, 0)) + struct.pack(">QI", self.session, self.seq & 0xFFFFFFFF)
        iv = head[4:16] + b"T\0\0\0"
        self.seq += 1
        f = head + self.keys.ctr(iv, body)
        return f + self.keys.mac(f)

    def open(self, b):
        if len(b) < HDR + MAC_LEN or b[:2] != b"UT" or not self.keys.verify(b[:-MAC_LEN], b[-MAC_LEN:]):
            return None
        session, seq = struct.unpack(">QI", b[4:16])
        if seq & 0x80000000:
            seq -= 1 << 32
        return b[2], session, seq, self.keys.ctr(b[4:16] + b"T\0\0\0", b[HDR:-MAC_LEN])

    def send(self, ftype, body):
        f = self.seal(ftype, body)
        self.frames_out += 1
        if self.tcp is not None:
            self.tcp.queue(f)
            try:
                self.tcp.flush()
            except OSError:
                self.drop_tcp(self.tcp)
        elif self.udp is not None and self.udp_peer is not None:
            try:
                self.udp.sendto(f, self.udp_peer)
            except OSError:
                pass

    def drop_tcp(self, c):
        if c in self.conns:
            self.conns.remove(c)
        try:
            c.sock.close()
        except OSError:
            pass
        if self.tcp is c:
            self.tcp = None

    def listens_to(self, tag):
        return time.time() - self.last_rx < 30 and tag in self.listen

    def handle(self, b, via, addr=None, conn=None):
        """A frame from the remote: returns a radio packet to pass on, or None."""
        f = self.open(b)
        if f is None:
            return None
        ftype, session, seq, body = f
        if ftype == HELLO:
            if len(body) < 17:
                return None
            t = struct.unpack(">Q", body[:8])[0]
            if session != self.win.session or not self.win.ready:
                if t <= self.last_hello_time:
                    return None  # an old recorded HELLO cannot reopen an old session
                self.win.reset(session)
            if not self.win.accept(session, seq):
                return None
            self.last_hello_time = max(self.last_hello_time, t)
            n = min(body[16], (len(body) - 17) // 4)
            self.listen = tuple(struct.unpack(">%dI" % n, body[17:17 + 4 * n]))
            self.arrived(via, addr, conn)
            self.send(HELLO_ACK, body[8:16])
            return None
        if ftype != DATA or not self.win.accept(session, seq):
            return None
        self.arrived(via, addr, conn)
        self.frames_in += 1
        return body

    def arrived(self, via, addr, conn):
        """Replies go back the way the remote last reached us (its NAT address may change)."""
        self.last_rx = time.time()
        self.via = via
        if via == "TCP":
            self.tcp = conn
        else:
            self.udp_peer = addr
            self.tcp = None

    def close(self):
        for s in [self.udp, self.server] + [c.sock for c in self.conns]:
            try:
                if s:
                    s.close()
            except OSError:
                pass
        self.conns = []

    def peer(self):
        a = self.tcp.addr if self.tcp is not None else self.udp_peer
        return "%s:%d" % a if a else "?"

    def status(self):
        ago = time.time() - self.last_rx
        if self.problem:
            state = self.problem
        elif not self.last_rx:
            state = "waiting"
        elif ago < 20:
            state = "connected over %s from %s, %d channel(s)" % (self.via, self.peer(), len(self.listen))
        else:
            state = "last heard %s ago from %s" % (_ago(ago), self.peer())
        return {"label": self.label, "port": self.port, "state": state, "connected": bool(self.last_rx) and ago < 20,
                "via": self.via, "channels": len(self.listen), "last": self.last_rx,
                "frames_in": self.frames_in, "frames_out": self.frames_out}


def open_frame(keys, b):
    """(type, session, seq, body) of a frame with or without a key ID, or None."""
    if len(b) < HDR + MAC_LEN or b[:2] != b"UT" or b[3] not in (0, 1):
        return None
    h = HDR_ID if b[3] == 1 else HDR
    if len(b) < h + MAC_LEN or not keys.verify(b[:-MAC_LEN], b[-MAC_LEN:]):
        return None
    session, seq = struct.unpack(">QI", b[4:16])
    if seq & 0x80000000:
        seq -= 1 << 32
    return b[2], session, seq, keys.ctr(b[4:16] + b"T\0\0\0", b[h:-MAC_LEN])


class CloudClient:
    """This server as a site's gateway to the internet relay (ycc-relay.jar on a VPS).

    docs/PROTOCOL.md section 11.1: frames carry the key ID (flags 1); HELLO every 5 s lists the channels
    shared; DATA both ways; SITES lists the relay's sites; BYE = removed by the relay's admin. UDP first,
    TCP to the same port after 12 s with no answer, back to UDP when it answers again. BEACON frames on the
    mesh elect one gateway per site, so this server and a gateway phone with the same key never both send.
    """
    DOWN_S = 30

    def __init__(self, host_port, key, name):
        host, _, port = host_port.strip().rpartition(":")
        self.host, self.port = host.strip("[] "), int(port)
        self.keys = tunnel_keys(key)
        self.name = (name or "LAN store")[:40]
        self.session = struct.unpack(">Q", os.urandom(8))[0]
        self.nonce = struct.unpack(">Q", os.urandom(8))[0]
        self.seq = 0
        self.win = Window()
        self.addr = None
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.setblocking(False)
        self.tcp = None
        self.use_tcp = False
        self.started = time.time()
        self.candidate_since = int(time.time() * 1000)
        self.standby = True  # first listen 7 s for another gateway on the mesh
        self.other, self.other_beacon = "", 0.0
        self.beacon_windows = {}
        self.last_rx = 0.0
        self.last_hello = self.last_beacon = self.last_udp_try = 0.0
        self.link_up, self.down_since = False, 0.0
        self.revoked = ""
        self.sites = []
        self.state = "starting"
        self.frames_in = self.frames_out = 0
        self.from_relay, self.sent_up = OrderedDict(), OrderedDict()

    # ---- frames ----

    def seal(self, ftype, body, with_id=True):
        head = b"UT" + bytes((ftype, 1 if with_id else 0)) + struct.pack(">QI", self.session, self.seq & 0xFFFFFFFF)
        if with_id:
            head += struct.pack(">I", self.keys.tag)
        iv = head[4:16] + b"T\0\0\0"
        self.seq += 1
        f = head + self.keys.ctr(iv, body)
        return f + self.keys.mac(f)

    def send(self, ftype, body):
        if self.revoked or self.addr is None:
            return
        f = self.seal(ftype, body)
        self.frames_out += 1
        if self.use_tcp and self.tcp is not None:
            self.tcp.queue(f)
            try:
                self.tcp.flush()
            except OSError:
                self.close_tcp()
        else:
            try:
                self.udp.sendto(f, self.addr)
            except OSError:
                pass

    def hello_body(self, tags):
        tags = list(tags)[:60]
        return struct.pack(">QQB", int(time.time() * 1000), self.nonce, len(tags)) + b"".join(struct.pack(">I", t) for t in tags)

    # ---- the loop's pieces ----

    def sockets(self):
        s = [self.udp]
        if self.tcp is not None:
            s.append(self.tcp.sock)
        return s

    def tick(self, tags, lan_send):
        """Every pass of the bridge loop: election, beacon, HELLO, UDP/TCP switching, link state."""
        now = time.time()
        if self.addr is None:
            try:
                self.addr = (socket.gethostbyname(self.host), self.port)
            except OSError as e:
                self.state = "cannot find %s (%s)" % (self.host, e)
                return
        if self.revoked:
            return
        if now - self.last_beacon >= 5:
            self.last_beacon = now
            if self.standby and (now - self.started > 7) and now - self.other_beacon > 20:
                self.standby = False
            if not self.standby:
                nm = self.name.encode("utf-8")[:40]
                body = struct.pack(">QQQBB", int(now * 1000), self.candidate_since, self.nonce, 1 if self.link_up else 0,
                                   len(nm)) + nm
                lan_send(self.seal(BEACON, body, with_id=False))
        if self.standby:
            self.state = ("standby: %s is the gateway" % self.other) if self.other else "checking for another gateway"
            return
        if now - self.last_hello >= 5:
            self.last_hello = now
            self.send(HELLO, self.hello_body(tags))
        if self.use_tcp and now - self.last_udp_try >= 30:
            self.last_udp_try = now
            try:
                self.udp.sendto(self.seal(HELLO, self.hello_body(tags)), self.addr)
            except OSError:
                pass
        if not self.use_tcp and now - max(self.last_rx, self.started) > 12:
            self.state = "no answer over UDP, trying TCP"
            self.connect_tcp()
            self.started = time.time()
        elif now - self.last_rx > 20:
            self.state = "connecting to %s:%d" % (self.host, self.port)
        up = self.last_rx > 0 and now - self.last_rx < self.DOWN_S
        if up != self.link_up:
            self.link_up = up
            self.down_since = 0.0 if up else self.last_rx

    def connect_tcp(self):
        try:
            s = socket.create_connection(self.addr, timeout=8)
            self.tcp = TcpConn(s, self.addr)
            self.use_tcp = True
            self.send(HELLO, self.hello_body(()))
        except OSError as e:
            self.state = "TCP failed too: %s" % e
            self.use_tcp = False

    def close_tcp(self):
        if self.tcp is not None:
            try:
                self.tcp.sock.close()
            except OSError:
                pass
        self.tcp, self.use_tcp = None, False

    def readable(self, s):
        """Frames from the relay: returns the radio packets to put on the mesh."""
        out = []
        if s is self.udp:
            try:
                b, _ = s.recvfrom(4096)
                out += self.handle(b, "UDP")
            except OSError:
                pass
        elif self.tcp is not None and s is self.tcp.sock:
            try:
                data = s.recv(65536)
                if not data:
                    raise OSError("closed")
                for fr in list(self.tcp.frames(data)):
                    out += self.handle(fr, "TCP")
            except OSError:
                self.close_tcp()
        return out

    def handle(self, b, via):
        if self.standby:
            return []
        f = open_frame(self.keys, b)
        if f is None:
            return []
        ftype, session, seq, body = f
        if ftype == HELLO_ACK:
            if len(body) < 8 or struct.unpack(">Q", body[:8])[0] != self.nonce:
                return []
            if session != self.win.session or not self.win.ready:
                self.win.reset(session)
            if not self.win.accept(session, seq):
                return []
            self.last_rx = time.time()
            if via == "UDP" and self.use_tcp:
                self.close_tcp()
            self.state = "connected over %s" % via
            return []
        if not self.win.accept(session, seq):
            return []
        if ftype == SITES:
            self.last_rx = time.time()
            self.sites = self.parse_sites(body)
            return []
        if ftype == BYE:
            self.revoked = body.decode("utf-8", "replace").strip() or "removed"
            self.state = "removed from the relay by its admin (%s): a new key is needed" % self.revoked
            self.close_tcp()
            return []
        if ftype != DATA:
            return []
        self.last_rx = time.time()
        k = body[8:20]
        if k in self.sent_up:  # heard here and sent up: already on the mesh
            return []
        self._remember(self.from_relay, k)
        self.frames_in += 1
        return [body]

    @staticmethod
    def parse_sites(b):
        sites, o = [], 1
        for _ in range(b[0] if b else 0):
            if o + 7 > len(b):
                break
            kind, state = b[o], b[o + 1]
            ago = struct.unpack(">I", b[o + 2:o + 6])[0]
            nl = b[o + 6]
            name = b[o + 7:o + 7 + nl].decode("utf-8", "replace")
            o += 7 + nl
            sites.append({"name": name, "kind": "site" if kind == 1 else "remote",
                          "state": ("never seen", "up", "down")[state] if state < 3 else "?", "ago": ago})
        return sites

    @staticmethod
    def _remember(d, k):
        d[k] = 1
        while len(d) > 4000:
            d.popitem(last=False)

    def from_lan(self, p):
        """A radio packet heard on the mesh (or from a linked device) goes up, once."""
        if self.standby or self.revoked or len(p) < 20 or p[:2] != b"UB":
            return
        k = p[8:20]
        if k in self.from_relay or k in self.sent_up:
            return
        self._remember(self.sent_up, k)
        self.send(DATA, p)

    def on_beacon(self, b):
        """A tunnel frame heard on the mesh: another gateway's beacon for this site."""
        f = open_frame(self.keys, b)
        if f is None or f[0] != BEACON or len(f[3]) < 26:
            return
        ftype, session, seq, body = f
        t, since, other_nonce = struct.unpack(">QQQ", body[:24])
        if other_nonce == self.nonce or abs(time.time() * 1000 - t) > 15 * 60000:
            return
        w = self.beacon_windows.get(session)
        if w is None:
            w = Window()
            w.reset(session)
            self.beacon_windows[session] = w
            if len(self.beacon_windows) > 16:
                self.beacon_windows.pop(next(iter(self.beacon_windows)))
        if not w.accept(session, seq):
            return
        n = min(body[25], len(body) - 26)
        who = body[26:26 + n].decode("utf-8", "replace")
        if since < self.candidate_since or (since == self.candidate_since and other_nonce < self.nonce):
            self.other, self.other_beacon = who, time.time()
            if not self.standby:
                self.standby = True
                self.close_tcp()
                self.link_up = False

    def close(self):
        self.close_tcp()
        try:
            self.udp.close()
        except OSError:
            pass

    def status(self):
        return {"server": "%s:%d" % (self.host, self.port), "state": self.state, "link_up": self.link_up,
                "standby": self.standby, "other_gateway": self.other, "revoked": self.revoked,
                "down_since": self.down_since, "last": self.last_rx, "sites": self.sites,
                "frames_in": self.frames_in, "frames_out": self.frames_out}