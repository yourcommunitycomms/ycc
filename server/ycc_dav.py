#!/usr/bin/env python3
"""Small WebDAV-style file store for Your Community Communications (LAN storage location).

GET, HEAD, PUT, MKCOL (and OPTIONS) under YCC_ROOT (default /data), served as /YCC/...
Basic auth with YCC_USER / YCC_PASSWORD. Map tiles (/YCC/Maps cache/) and the app download
(/YCC/Updates/YCC.apk) can be read without a login, because ATAK map sources and a phone's
browser cannot log in. Everything else, and every upload, needs the login.
/webadmin is a management page (admin.py) behind the same login; / and /admin redirect to it.
"""
import os
import sys
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import unquote, urlencode

import admin

ROOT = os.path.realpath(os.environ.get("YCC_ROOT", "/data"))
PORT = int(os.environ.get("YCC_PORT", sys.argv[1] if len(sys.argv) > 1 else "47490"))
USER = os.environ.get("YCC_USER", "ycc")
PASSWORD = os.environ.get("YCC_PASSWORD", "")
if not PASSWORD:
    sys.exit("Set YCC_PASSWORD")
MAX_UPLOAD = int(os.environ.get("YCC_MAX_UPLOAD_MB", "2048")) * 1024 * 1024
MAX_FORM = 300 * 1024 * 1024  # admin page posts (an APK upload)
STORE_FOLDERS = ("Maps cache", "Updates", "Logs", "Data", "DRYAD", "Configs", "Missions")
for sub in ("Maps cache", "Updates"):
    os.makedirs(os.path.join(ROOT, "YCC", sub), exist_ok=True)
admin.init(ROOT, USER, PASSWORD)


class H(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def log_message(self, *a):
        pass  # quiet

    def done(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def ok_auth(self):
        if self.headers.get("Authorization") == admin.auth():
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="YCC"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def local(self):
        rel = unquote(self.path.split("?")[0]).lstrip("/")
        if any(s.startswith(".") and s not in (".", "..") for s in rel.split("/")):
            return None  # hidden files (the saved login) are never served or written
        if rel.split("/")[0] in STORE_FOLDERS:
            rel = "YCC/" + rel  # a phone set to http://server:port without /YCC still finds its folders
            self.path = "/YCC" + self.path  # and the file serving below uses the same place
        p = os.path.normpath(os.path.join(ROOT, rel))
        return p if p == ROOT or p.startswith(ROOT + os.sep) else None

    def is_admin(self):
        return self.path.split("?")[0].rstrip("/") == "/webadmin"

    def redirect(self, to):
        self.send_response(302)
        self.send_header("Location", to)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def send_page(self, body, code=200, ctype="text/html; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if not self.is_admin():
            return self.done(405)
        n = int(self.headers.get("Content-Length", 0))
        if n > MAX_FORM:
            return self.done(413)
        body = self.rfile.read(n)
        if not self.ok_auth():
            return
        if not admin.same_origin(self):
            return self.done(403)
        form, files = admin.parse_post(self, body)
        try:
            msg = admin.act(form, files)
        except OSError as e:
            msg = "Failed: %s" % e
        back = urlencode({k: form[k] for k in ("tab", "dir") if form.get(k)})
        self.send_page(admin.page(back, msg, self.headers.get("Host", "")))

    def public(self):
        p = self.local()
        return p is not None and (p.startswith(os.path.join(ROOT, "YCC", "Maps cache") + os.sep)
                                  or p == os.path.join(ROOT, "YCC", "Updates", "YCC.apk"))

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1")
        self.send_header("Allow", "OPTIONS, GET, HEAD, PUT, MKCOL, POST")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if self.is_admin():
            if self.ok_auth():
                self.send_page(admin.page(query, host=self.headers.get("Host", "")))
            return
        if path.startswith("/webadmin/doc/"):
            if self.ok_auth():
                d = admin.doc(unquote(path[len("/webadmin/doc/"):]))
                if d is None:
                    return self.done(404)
                self.send_page(d[0], ctype=d[1])
            return
        if path in ("/", "/admin", "/admin/"):
            return self.redirect("/webadmin")
        if self.local() is None:
            return self.done(404)
        if self.public() or self.ok_auth():
            super().do_GET()

    def do_HEAD(self):
        if self.public() or self.ok_auth():
            super().do_HEAD()

    def do_PUT(self):
        if not self.ok_auth():
            return
        p = self.local()
        n = int(self.headers.get("Content-Length", 0))
        if p is None or p == ROOT or n > MAX_UPLOAD:
            return self.done(403)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        tmp = p + ".part"
        with open(tmp, "wb") as f:
            left = n
            while left > 0:  # streamed, so large videos never sit in memory
                chunk = self.rfile.read(min(left, 1 << 20))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left > 0:
            os.remove(tmp)
            return self.done(400)
        os.replace(tmp, p)
        self.done(201)

    def do_MKCOL(self):
        if not self.ok_auth():
            return
        p = self.local()
        if p is None:
            return self.done(403)
        p = p.rstrip("/")
        if os.path.isdir(p):
            return self.done(405)
        try:
            os.makedirs(p)
            self.done(201)
        except OSError:
            self.done(409)


class Server(ThreadingHTTPServer):
    request_queue_size = 64
    daemon_threads = True


print("YCC file store on port %d, files in %s" % (PORT, ROOT), flush=True)
Server(("0.0.0.0", PORT), H).serve_forever()
