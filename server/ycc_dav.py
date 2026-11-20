#!/usr/bin/env python3
"""Small WebDAV-style file store for Your Community Communications (LAN storage location).

GET, HEAD, PUT, MKCOL (and OPTIONS) under YCC_ROOT (default /data), served as /YCC/...
Basic auth with YCC_USER / YCC_PASSWORD. Map tiles (/YCC/Maps cache/) and the app download
(/YCC/Updates/YCC.apk) can be read without a login, because ATAK map sources and a phone's
browser cannot log in. Everything else, and every upload, needs the login.
/webadmin is a management page (admin.py) behind the same login; / and /admin redirect to it.
"""
import hmac
import html
import os
import secrets
import sys
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import parse_qs, unquote, urlencode

import admin
import provision

ROOT = os.path.realpath(os.environ.get("YCC_ROOT", "/data"))
PORT = int(os.environ.get("YCC_PORT", sys.argv[1] if len(sys.argv) > 1 else "47490"))
USER = os.environ.get("YCC_USER", "ycc")
PASSWORD = os.environ.get("YCC_PASSWORD", "")
if not PASSWORD:
    sys.exit("Set YCC_PASSWORD")
MAX_UPLOAD = int(os.environ.get("YCC_MAX_UPLOAD_MB", "2048")) * 1024 * 1024
MAX_FORM = 300 * 1024 * 1024  # admin page posts (an APK upload)
STORE_FOLDERS = ("Maps cache", "Updates", "Logs", "Data", "DRYAD", "Configs", "Missions", "T.REX Arms Topo Map", "Personal")
for sub in ("Maps cache", "Updates"):
    os.makedirs(os.path.join(ROOT, "YCC", sub), exist_ok=True)
admin.init(ROOT, USER, PASSWORD)

# Login form sessions (for browsers with no Basic auth prompt). In memory: a restart logs everyone out.
COOKIE = "ycc_session"
SESSION_S = 12 * 3600
SESSIONS = {}  # token -> (the Basic auth value it was made with, expiry); a changed password ends them
SESS_LOCK = threading.Lock()


def cookie_token(h):
    for part in (h.headers.get("Cookie") or "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == COOKIE and v:
            return v
    return None


def session_ok(h):
    t = cookie_token(h)
    if not t:
        return False
    with SESS_LOCK:
        s = SESSIONS.get(t)
        if s is None or s[1] < time.time() or s[0] != admin.auth():
            SESSIONS.pop(t, None)
            return False
    return True


def apk_version():
    """The version code in Updates/version.txt (first line), or "" if there is none."""
    try:
        with open(os.path.join(ROOT, "YCC", "Updates", "version.txt"), encoding="utf-8") as f:
            v = f.readline().strip()
        return v if v.isdigit() else ""
    except OSError:
        return ""


def login_page(msg=""):
    m = '<p class="err">%s</p>' % html.escape(msg) if msg else ""
    return ("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>YCC server login</title><style>
body{font-family:sans-serif;background:#0f1f16;color:#cfe3d4;margin:0;padding:24px}
form{max-width:380px;margin:40px auto;background:#1f3d2b;padding:24px;border-radius:10px}
h1{color:#f0c75e;font-size:22px;margin-top:0}label{display:block;margin:14px 0 4px}
input{width:100%%;box-sizing:border-box;padding:12px;font-size:18px;border-radius:6px;border:2px solid #5a7a66}
input:focus,button:focus{outline:3px solid #f0c75e}
button{margin-top:20px;width:100%%;padding:14px;font-size:18px;background:#f0c75e;color:#0f1f16;border:0;border-radius:6px}
.err{color:#ff8a80}</style></head><body>
<form method="post" action="/webadmin/login"><h1>Your Community Communications server</h1>%s
<label for="u">User name</label><input id="u" name="user" autocomplete="username" autofocus>
<label for="p">Password</label><input id="p" name="password" type="password" autocomplete="current-password">
<button type="submit">Log in</button></form></body></html>""" % m).encode("utf-8")


class H(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def log_message(self, *a):
        pass  # quiet

    def end_headers(self):
        # The app download: never cached, and saved under a name with its version, so a TV or phone that kept an
        # older YCC.apk in its downloads cannot install that one by mistake.
        if self.command in ("GET", "HEAD") and unquote(self.path.split("?")[0]).endswith("/Updates/YCC.apk"):
            v = apk_version()
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Disposition", 'attachment; filename="YCC-0.1.%s.apk"' % v if v
                             else 'attachment; filename="YCC.apk"')
        super().end_headers()

    def done(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def ok_auth(self):
        if self.headers.get("Authorization") == admin.auth():
            return True
        if self.path.startswith("/webadmin") and session_ok(self):
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="YCC"')
        if self.path.startswith("/webadmin"):
            # Browsers that cannot show a login prompt (TV and other WebView browsers) show this page instead of
            # an error: the same user name and password, kept as a session cookie.
            body = login_page()
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return False
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def login_post(self, body):
        """The login form (POST /webadmin/login): same user and password as Basic auth; sets a session cookie."""
        src = self.headers.get("Origin") or self.headers.get("Referer")
        if src and not admin.same_origin(self):
            return self.done(403)
        f = parse_qs(body.decode("utf-8", "replace"))
        user, pw = f.get("user", [""])[0], f.get("password", [""])[0]
        if hmac.compare_digest(user.encode(), admin.current_user().encode()) and \
                hmac.compare_digest(pw.encode(), admin.current_password().encode()):
            token = secrets.token_urlsafe(32)
            with SESS_LOCK:
                now = time.time()
                for t in [t for t, s in SESSIONS.items() if s[1] < now]:
                    del SESSIONS[t]
                SESSIONS[token] = (admin.auth(), now + SESSION_S)
            self.send_response(303)
            self.send_header("Set-Cookie", "%s=%s; Path=/webadmin; HttpOnly; SameSite=Strict; Max-Age=%d"
                             % (COOKIE, token, SESSION_S))
            self.send_header("Location", "/webadmin")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        time.sleep(1)  # slows guessing
        body = login_page("Wrong user name or password.")
        self.send_page(body, code=401)

    def logout(self):
        token = cookie_token(self)
        if token:
            with SESS_LOCK:
                SESSIONS.pop(token, None)
        self.send_response(303)
        self.send_header("Set-Cookie", "%s=; Path=/webadmin; HttpOnly; SameSite=Strict; Max-Age=0" % COOKIE)
        self.send_header("Location", "/webadmin/login")
        self.send_header("Content-Length", "0")
        self.end_headers()

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
        login = self.path.split("?")[0].rstrip("/") == "/webadmin/login"
        if not self.is_admin() and not login:
            return self.done(405)
        n = int(self.headers.get("Content-Length", 0))
        if n > MAX_FORM or (login and n > 4096):
            return self.done(413)
        body = self.rfile.read(n)
        if login:
            return self.login_post(body)
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
                                  or p == os.path.join(ROOT, "YCC", "Updates", "YCC.apk")
                                  or p == os.path.join(ROOT, "YCC", "Updates", "version.txt")  # the app's update check
                                  or provision.public_file(p))

    def personal_dir(self, p):
        """The personal APKs folder is never listed (each file name holds that person's code)."""
        pd = os.path.join(ROOT, "YCC", "Personal")
        return p is not None and (p == pd or (os.path.isdir(p) and p.startswith(pd + os.sep)))

    def setup_text(self, path):
        """What a stamped APK fetches on first start (no login: the token in the address is the key)."""
        if path in ("/YCC/Updates/YCC-site.setup", "/Updates/YCC-site.setup"):
            t = provision.site_setup(admin.setup, admin.current_user(), admin.current_password())
            code = 200 if t else 404
        else:
            name = path.rsplit("/", 1)[-1]
            code, t = provision.person_setup(name[:-len(".setup")], admin.setup, admin.current_user(), admin.current_password())
        if t is None:
            return self.done(code)
        self.send_page(t.encode("utf-8"), ctype="text/plain; charset=utf-8")

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1")
        self.send_header("Allow", "OPTIONS, GET, HEAD, PUT, MKCOL, POST")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path.rstrip("/") == "/webadmin/login":
            if session_ok(self) or self.headers.get("Authorization") == admin.auth():
                return self.redirect("/webadmin")
            return self.send_page(login_page())
        if path.rstrip("/") == "/webadmin/logout":
            return self.logout()
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
        if path.rstrip("/").lower() == "/apk":  # short address for the app download (no login)
            v = apk_version()
            return self.redirect("/YCC/Updates/YCC.apk" + ("?v=" + v if v else ""))
        if path in ("/", "/admin", "/admin/"):
            return self.redirect("/webadmin")
        if path.endswith(".setup") and ("/Personal/" in path or path.endswith("/Updates/YCC-site.setup")):
            return self.setup_text(unquote(path))
        if self.local() is None or self.personal_dir(self.local()):
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
