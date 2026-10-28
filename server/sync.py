"""Sync destinations for the web admin page (the "Sync" tab).

Copies new and changed files from this store to other YCC stores (for example the Pi), each on its own
schedule and bandwidth limit. One way only, and nothing is ever deleted at a destination. Destinations
are saved in <store>/.ycc_sync.json; what each one already has is remembered in
<store>/.ycc_sync/<id>.json so later runs only send what changed.
"""
import base64
import http.client
import json
import os
import threading
import time
from urllib.parse import quote, urlsplit

FOLDERS = ("Configs", "DRYAD", "Data", "Logs", "Maps cache", "Missions", "Updates")
CHUNK = 64 * 1024

_root = _ycc = _file = _state_dir = None
_lock = threading.Lock()
_running = {}  # id -> progress text
_stop = set()


def init(root):
    global _root, _ycc, _file, _state_dir
    _root = root
    _ycc = os.path.join(root, "YCC")
    _file = os.path.join(root, ".ycc_sync.json")
    _state_dir = os.path.join(root, ".ycc_sync")
    threading.Thread(target=_loop, name="sync", daemon=True).start()


def load():
    try:
        with open(_file, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, list) else []
    except (OSError, ValueError):
        return []


def save(dests):
    with _lock:
        tmp = _file + ".part"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dests, f, indent=2)
        os.chmod(tmp, 0o600)
        os.replace(tmp, _file)


def update(did, **kv):
    dests = load()
    for d in dests:
        if d.get("id") == did:
            d.update(kv)
    save(dests)


def erase():
    for p in (_file,):
        if p and os.path.isfile(p):
            os.remove(p)
    if _state_dir and os.path.isdir(_state_dir):
        for n in os.listdir(_state_dir):
            os.remove(os.path.join(_state_dir, n))


def running(did):
    return _running.get(did)


def stop(did):
    if did in _running:
        _stop.add(did)


# ---- schedule ----

def due(d, now=None):
    if not d.get("on") or d.get("id") in _running:
        return False
    now = now or time.time()
    h = time.localtime(now).tm_hour
    a, b = int(d.get("from_h", 0)), int(d.get("to_h", 24))
    if a != b and not (a <= h < b if a < b else (h >= a or h < b)):
        return False  # outside its hours
    return now - d.get("last", 0) >= int(d.get("every", 60)) * 60


def _loop():
    while True:
        time.sleep(30)
        try:
            for d in load():
                if due(d):
                    run(d)  # one destination at a time
        except Exception:
            pass


def start(did):
    for d in load():
        if d.get("id") == did and did not in _running:
            threading.Thread(target=run, args=(d,), daemon=True).start()
            return True
    return False


# ---- copying ----

class _Dest:
    def __init__(self, d):
        u = urlsplit(d["url"].rstrip("/"))
        self.https = u.scheme == "https"
        self.netloc = u.netloc
        self.base = u.path  # e.g. /YCC
        self.auth = "Basic " + base64.b64encode(("%s:%s" % (d.get("user", ""), d.get("pass", ""))).encode()).decode()
        self.kbps = int(d.get("kbps", 0) or 0)
        self.made = set()
        self.conn = None

    def _conn(self):
        if self.conn is None:
            cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
            self.conn = cls(self.netloc, timeout=60)
        return self.conn

    def close(self):
        if self.conn:
            self.conn.close()
            self.conn = None

    def request(self, method, rel, body=None, length=0):
        """(status, headers) of one request; body is an open file sent at the bandwidth limit."""
        path = self.base + "/" + quote(rel) if rel else self.base + "/"
        for attempt in (1, 2):
            try:
                c = self._conn()
                c.putrequest(method, path)
                c.putheader("Authorization", self.auth)
                c.putheader("Content-Length", str(length))
                c.endheaders()
                if body is not None:
                    self._send(c, body, length)
                r = c.getresponse()
                r.read()
                return r.status, r
            except (OSError, http.client.HTTPException):
                self.close()
                if attempt == 2 or body is not None:
                    raise
        return 0, None

    def _send(self, c, f, length):
        t0, sent = time.time(), 0
        while sent < length:
            chunk = f.read(CHUNK)
            if not chunk:
                break
            c.send(chunk)
            sent += len(chunk)
            if self.kbps > 0:  # kilobytes per second
                ahead = sent / (self.kbps * 1024.0) - (time.time() - t0)
                if ahead > 0:
                    time.sleep(ahead)

    def mkdirs(self, rel_dir):
        parts = [p for p in rel_dir.split("/") if p]
        for i in range(1, len(parts) + 1):
            sub = "/".join(parts[:i])
            if sub not in self.made:
                self.request("MKCOL", sub + "/")  # 405 = already there
                self.made.add(sub)

    def put(self, rel, path, size):
        for attempt in (1, 2):
            with open(path, "rb") as f:
                st, _ = self.request("PUT", rel, f, size)
            if st in (200, 201, 204):
                return True
            if st == 401:
                raise PermissionError("the destination refused the login")
            if attempt == 1 and st in (403, 404, 409):
                self.mkdirs(rel.rpartition("/")[0])
                continue
            raise OSError("destination answered %d for %s" % (st, rel))
        return False

    def has(self, rel, size):
        st, r = self.request("HEAD", rel)
        return st == 200 and r.getheader("Content-Length") == str(size)


def _files(folders):
    for name in folders:
        top = os.path.join(_ycc, name)
        for d, subdirs, names in os.walk(top):
            subdirs[:] = [s for s in subdirs if not s.startswith(".")]
            for n in names:
                if n.startswith(".") or n.endswith(".part"):
                    continue
                p = os.path.join(d, n)
                yield os.path.relpath(p, _ycc).replace(os.sep, "/"), p


def _state(did):
    try:
        with open(os.path.join(_state_dir, did + ".json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save_state(did, st):
    os.makedirs(_state_dir, exist_ok=True)
    p = os.path.join(_state_dir, did + ".json")
    with open(p + ".part", "w", encoding="utf-8") as f:
        json.dump(st, f)
    os.replace(p + ".part", p)


def test(d):
    """A short text saying whether the destination answers and accepts the login."""
    dest = _Dest(d)
    try:
        st, _ = dest.request("HEAD", "")
        if st == 401:
            return "The destination refused the login."
        if st in (200, 204, 301, 302, 403, 404, 405):
            st2, _ = dest.request("MKCOL", "Logs/")
            return "Reached it (login accepted)." if st2 != 401 else "The destination refused the login."
        return "The destination answered %d." % st
    except (OSError, http.client.HTTPException, ValueError) as e:
        return "Could not reach it: %s" % e
    finally:
        dest.close()


def run(d):
    did = d["id"]
    _running[did] = "starting"
    _stop.discard(did)
    dest = _Dest(d)
    st = _state(did)
    sent = skipped = failed = 0
    nbytes = 0
    t0 = time.time()
    result = None
    try:
        for i, (rel, p) in enumerate(_files(d.get("folders") or FOLDERS)):
            if did in _stop:
                result = "Stopped"
                break
            try:
                s = os.stat(p)
            except OSError:
                continue
            key = [s.st_size, int(s.st_mtime)]
            if st.get(rel) == key:
                continue
            _running[did] = "%d sent, %d already there; now %s" % (sent, skipped, rel)
            try:
                if rel not in st and dest.has(rel, s.st_size):
                    skipped += 1  # first run: it already has this file
                else:
                    dest.put(rel, p, s.st_size)
                    sent += 1
                    nbytes += s.st_size
                st[rel] = key
            except PermissionError as e:
                failed += 1
                result = "Stopped: %s" % e
                break
            except (OSError, http.client.HTTPException) as e:
                failed += 1
                if failed >= 20:
                    result = "Gave up after 20 failures (last: %s)" % e
                    break
            if (sent + skipped) % 200 == 0:
                _save_state(did, st)
    except Exception as e:  # the run must never kill the scheduler
        result = "Failed: %s" % e
    finally:
        dest.close()
        _save_state(did, st)
        secs = int(time.time() - t0)
        summary = "%s: %d files sent (%.1f MB), %d already there, %d failed, %d s." % (
            result or "Done", sent, nbytes / 1048576.0, skipped, failed, secs)
        update(did, last=time.time(), result=summary)
        _running.pop(did, None)
        _stop.discard(did)


# ---- web admin tab ----

def _form_dest(d, e, hidden, confirm, new):
    did = d.get("id", "")
    out = ["<form method=post class=b%s>%s<table>" % (confirm("Save this sync destination?"),
                                                     hidden(a="sync_save", tab="sync", id=did))]
    row = "<tr><td>%s</td><td class=w>%s</td></tr>"
    out.append(row % ("Name", "<input name=name value='%s' size=30 required>" % e(d.get("name", ""))))
    out.append(row % ("Address", "<input name=url value='%s' size=40 required placeholder='http://host:port/YCC'>"
                      % e(d.get("url", ""))))
    out.append(row % ("Login", "user <input name=user value='%s' size=12> password <input type=password name=pass "
                      "size=12 autocomplete=off placeholder='%s'>" % (e(d.get("user", "")),
                                                                     "unchanged" if d.get("pass") else "")))
    boxes = " ".join("<label><input type=checkbox name='f|%s'%s> %s</label>"
                     % (e(f), " checked" if f in (d.get("folders") or FOLDERS) else "", e(f)) for f in FOLDERS)
    out.append(row % ("Folders", boxes))
    out.append(row % ("Schedule", "every <input type=number name=every min=5 value=%d> minutes, between "
                      "<input type=number name=from_h min=0 max=23 value=%d> and <input type=number name=to_h min=0 "
                      "max=24 value=%d> o'clock <span class=muted>(0 and 24 = any time; the server's clock, which is UTC "
                      "unless the container has a TZ setting)</span>"
                      % (int(d.get("every", 60)), int(d.get("from_h", 0)), int(d.get("to_h", 24)))))
    out.append(row % ("Bandwidth", "at most <input type=number name=kbps min=0 value=%d style='width:90px'> KB/s "
                      "<span class=muted>(0 = no limit; 125 KB/s is about 1 Mbit/s)</span>" % int(d.get("kbps", 0) or 0)))
    out.append(row % ("On", "<label><input type=checkbox name=on%s> Run on the schedule</label>"
                      % (" checked" if d.get("on", True) else "")))
    out.append("</table><p><button>%s</button></p></form>" % ("Add destination" if new else "Save"))
    return "".join(out)


def tab(out, e, hidden, confirm, when, size):
    dests = load()
    out.append("<div class=card><h2>Sync destinations</h2><p>Copies new and changed files from this store to other "
               "YCC stores (for example the Pi), so phones that use them see the same tiles, updates, missions and "
               "sheets. One way only: nothing is ever deleted or changed at a destination except files sent from "
               "here. The first run checks each file and skips those already there.</p>"
               "<p class=muted>The page does not refresh itself; reload it to see progress.</p></div>")
    for d in dests:
        did = d["id"]
        prog = running(did)
        out.append("<div class=card><h2>%s <span class=muted>%s</span></h2>" % (e(d.get("name")), e(d.get("url"))))
        out.append("<p>%s. Last run: %s. %s</p>" % (
            "Running now: " + e(prog) if prog else ("On, every %d minutes" % int(d.get("every", 60)) if d.get("on") else "Off"),
            when(d.get("last", 0)), e(d.get("result", ""))))
        out.append("<p><form method=post>%s<button>%s</button></form> <form method=post>%s<button>Test login</button>"
                   "</form> <form method=post%s>%s<button class=d>Delete destination</button></form></p>"
                   % (hidden(a="sync_stop" if prog else "sync_run", tab="sync", id=did), "Stop" if prog else "Run now",
                      hidden(a="sync_test", tab="sync", id=did),
                      confirm("Delete this sync destination? Files already copied there stay."),
                      hidden(a="sync_delete", tab="sync", id=did)))
        out.append("<details><summary>Change</summary>%s</details></div>" % _form_dest(d, e, hidden, confirm, False))
    out.append("<div class=card><h2>Add a destination</h2>%s</div>"
               % _form_dest({"every": 60, "on": True}, e, hidden, confirm, True))


def act(form):
    a, did = form.get("a"), form.get("id", "")
    dests = load()
    d = next((x for x in dests if x.get("id") == did), None)
    if a == "sync_save":
        url = form.get("url", "").strip().rstrip("/")
        if not url.startswith(("http://", "https://")) or not urlsplit(url).netloc:
            return "Not saved: the address must look like http://host:port/YCC."
        try:
            every = max(5, int(form.get("every") or 60))
            from_h = min(23, max(0, int(form.get("from_h") or 0)))
            to_h = min(24, max(0, int(form.get("to_h") or 24)))
            kbps = max(0, int(form.get("kbps") or 0))
        except ValueError:
            return "Not saved: schedule and bandwidth must be whole numbers."
        if d is None:
            d = {"id": "d%d" % int(time.time() * 1000), "last": 0}
            dests.append(d)
        folders = [f for f in FOLDERS if ("f|" + f) in form]
        d.update(name=form.get("name", "").strip() or url, url=url, user=form.get("user", "").strip(),
                 folders=folders or list(FOLDERS), every=every, from_h=from_h, to_h=to_h, kbps=kbps, on="on" in form)
        if form.get("pass"):
            d["pass"] = form["pass"]
        if d.get("url_was") != url:  # a new address starts over: check every file again
            st = os.path.join(_state_dir, d["id"] + ".json")
            if os.path.isfile(st):
                os.remove(st)
            d["url_was"] = url
        save(dests)
        return "Sync destination %s saved." % d["name"]
    if d is None:
        return "Unknown sync destination."
    if a == "sync_run":
        return ("Started copying to %s; reload to see progress." % d["name"]) if start(did) else "Already running."
    if a == "sync_stop":
        stop(did)
        return "Stopping after the current file."
    if a == "sync_test":
        return "%s: %s" % (d["name"], test(d))
    if a == "sync_delete":
        stop(did)
        save([x for x in dests if x.get("id") != did])
        st = os.path.join(_state_dir, did + ".json")
        if os.path.isfile(st):
            os.remove(st)
        return "Deleted sync destination %s." % d["name"]
    return "Unknown action."
