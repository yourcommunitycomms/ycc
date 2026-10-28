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

from yccrypto import Keys, MAC_LEN

DATA, HELLO, HELLO_ACK = 1, 2, 3
HDR = 16
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
