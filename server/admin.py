"""Web admin page for the YCC file store: /webadmin (same login as the store; /admin redirects).

Tabs: Manage (folder sizes, tidy old files, missions, map tile sources), Files (browse, upload,
rename, delete), Defaults (the network defaults phones load), Updates (publish an APK), Login,
Docs (licence, rebuilding with the AI prompt) and Erase (everything back to an empty store).
Plain HTML forms, no scripts beyond confirm() prompts.
"""
import base64
import email.parser
import email.policy
import html
import json
import os
import shutil
import struct
import threading
import time
import zipfile
from urllib.parse import parse_qs, quote, urlsplit

import bridge_admin
import setup
import sync

VERSION = "1.1"
APP_DIR = os.path.dirname(os.path.abspath(__file__))
DOCS = {"LICENSE": "LICENSE", "NOTICE": "NOTICE", "README.md": "README.md",
        "RECREATE-PROMPT.md": os.path.join("docs", "RECREATE-PROMPT.md"),
        "NOMAD-SETUP.md": os.path.join("docs", "NOMAD-SETUP.md"),
        "ALL-FEATURES.txt": os.path.join("docs", "ALL-FEATURES.txt")}
TABS = (("manage", "Manage"), ("files", "Files"), ("defaults", "Defaults"), ("setup", "Phone setup"),
        ("sync", "Sync"), ("bridge", "Bridge"), ("updates", "Updates"), ("login", "Login"), ("docs", "Docs"), ("erase", "Erase"))

ROOT = None  # set by init()
YCC = None
LOGIN_FILE = None
DEFAULTS = None
_auth = None
_env_auth = None
_lock = threading.Lock()
_stats = {}  # path -> (time, (bytes, files, newest))
STATS_TTL = 600
LIST_MAX = 500  # entries shown per folder in Files (map tile folders hold thousands)

# Mirrors ConfigShare.java: settings that belong to one phone, and secrets. Phones ignore them in
# network defaults anyway; they are dropped here so the defaults file shows only what applies.
PREF_FILES = ("settings", "map", "quickProfiles", "dryad")
LOCAL = set((
    "callsign pinHash pinSalt pinFails wipeOnBadPin lastChannelHash pmPub pmPriv setupDone uiLocked btButton "
    "btButtonName pttKey netInterface radioSilent rxGainPct micGainPct onlineUntil status wasOnAir "
    "permissionsShown titleHidden commanderChannels commanderSideHidden contactsCompact broadcast noteA noteB "
    "noteC noteD noteE noteF noteG noteH notePublic noteOpenMANET audioOut micType ringSpeaker tunnelServer "
    "tunnelKey tunnelRemotes tunnelRelayOn myIcon intercomRooms intercomCamera streams fakeGpsFor fakeGpsLat "
    "fakeGpsLon lanAutoUpdate lanSyncEvery lanSyncLogs lanSyncData lanSyncDryad lanSyncConfig lanSyncLast "
    "lanSyncResult lanUpdateFile lanUpdateCode swapLR tunnelClientOn relayPromptAt homeNetOn homeNetSsid atakOn "
    "atakUid lanUrl lanUser lanPass lanShare").split())
TYPES = (("s", "text"), ("i", "number"), ("l", "long number"), ("b", "on/off"), ("f", "decimal"))


def init(root, user, password):
    global ROOT, YCC, LOGIN_FILE, DEFAULTS, _auth, _env_auth
    ROOT = root
    YCC = os.path.join(root, "YCC")
    LOGIN_FILE = os.path.join(root, ".ycc_login")
    DEFAULTS = os.path.join(YCC, "Configs", "_defaults.ucconfig")
    _env_auth = _encode(user, password)
    _auth = _env_auth
    setup.init(root)
    sync.init(root)
    bridge_admin.init(root)
    if os.path.isfile(LOGIN_FILE):  # a login changed on this page wins over the environment
        with open(LOGIN_FILE) as f:
            user, _, password = f.read().strip().partition(":")
        _auth = _encode(user, password)


def _encode(user, password):
    return "Basic " + base64.b64encode((user + ":" + password).encode()).decode()


def auth():
    return _auth


def current_user():
    return base64.b64decode(_auth[6:]).decode().partition(":")[0]


def current_password():
    return base64.b64decode(_auth[6:]).decode().partition(":")[2]


# ---- folder sizes ----

def stats(path, fresh=False):
    """(bytes, files, newest mtime) of everything under path, cached for a few minutes."""
    now = time.time()
    with _lock:
        hit = _stats.get(path)
    if hit and not fresh and now - hit[0] < STATS_TTL:
        return hit[1]
    size = files = 0
    newest = 0.0
    stack = [path]
    while stack:
        try:
            it = os.scandir(stack.pop())
        except OSError:
            continue
        with it:
            for e in it:
                try:
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.is_file(follow_symlinks=False):
                        st = e.stat()
                        size += st.st_size
                        files += 1
                        newest = max(newest, st.st_mtime)
                except OSError:
                    pass
    r = (size, files, newest)
    with _lock:
        _stats[path] = (now, r)
    return r


def forget_stats():
    with _lock:
        _stats.clear()


def delete_older(path, days):
    """Deletes files under path not changed for `days` days, then empty folders. Returns count."""
    cut = time.time() - days * 86400
    n = 0
    for d, subdirs, names in os.walk(path, topdown=False):
        for name in names:
            p = os.path.join(d, name)
            try:
                if os.path.getmtime(p) < cut:
                    os.remove(p)
                    n += 1
            except OSError:
                pass
        if d != path:
            try:
                os.rmdir(d)  # only if now empty
            except OSError:
                pass
    forget_stats()
    return n


def _plain(name):
    return bool(name) and not name.startswith(".") and "/" not in name and "\\" not in name


def _child(parent, name):
    """parent/name if name is a plain existing folder name there, else None."""
    if not _plain(name):
        return None
    p = os.path.join(parent, name)
    return p if os.path.isdir(p) else None


def _subdirs(path):
    try:
        return sorted(e.name for e in os.scandir(path) if e.is_dir() and not e.name.startswith("."))
    except OSError:
        return []


def _inside(rel):
    """Absolute path for a path relative to the store, if it is YCC or inside it (no hidden parts)."""
    rel = (rel or "YCC").replace("\\", "/").strip("/")
    if any(s.startswith(".") for s in rel.split("/") if s):
        return None
    p = os.path.normpath(os.path.join(ROOT, rel))
    return p if p == YCC or p.startswith(YCC + os.sep) else None


def _rel(p):
    return os.path.relpath(p, ROOT).replace(os.sep, "/")


# ---- APK version (binary AndroidManifest.xml) ----

def apk_version(data):
    """(versionCode, versionName) read from an APK's manifest, or (None, None)."""
    try:
        import io
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            x = z.read("AndroidManifest.xml")
        strings = []
        pos = struct.unpack_from("<H", x, 2)[0]
        while pos + 8 <= len(x):
            ctype, hsize, csize = struct.unpack_from("<HHI", x, pos)
            if ctype == 0x0001:  # string pool
                count, _, flags, start = struct.unpack_from("<IIII", x, pos + 8)
                utf8 = flags & 0x100
                for i in range(count):
                    o = pos + start + struct.unpack_from("<I", x, pos + hsize + 4 * i)[0]
                    if utf8:
                        o += 2 if x[o] & 0x80 else 1
                        n = x[o]
                        if n & 0x80:
                            n = ((n & 0x7F) << 8) | x[o + 1]
                            o += 1
                        strings.append(x[o + 1:o + 1 + n].decode("utf-8", "replace"))
                    else:
                        n = struct.unpack_from("<H", x, o)[0]
                        if n & 0x8000:
                            n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", x, o + 2)[0]
                            o += 2
                        strings.append(x[o + 2:o + 2 + 2 * n].decode("utf-16-le", "replace"))
            elif ctype == 0x0102:  # start element
                name = struct.unpack_from("<I", x, pos + 20)[0]
                if strings[name] == "manifest":
                    astart, asize, acount = struct.unpack_from("<HHH", x, pos + 24)
                    code = vname = None
                    for i in range(acount):
                        a = pos + 16 + astart + i * asize
                        an, raw, dtype, d = struct.unpack_from("<4xIIxxxBI", x, a)
                        if strings[an] == "versionCode":
                            code = d
                        elif strings[an] == "versionName":
                            vname = strings[raw] if raw != 0xFFFFFFFF else str(d)
                    return code, vname
            pos += csize
    except Exception:
        pass
    return None, None


# ---- network defaults ----

def load_defaults():
    try:
        with open(DEFAULTS, encoding="utf-8") as f:
            cfg = json.load(f)
        return cfg if cfg.get("format") == "UCCONFIG1" else None
    except (OSError, ValueError, AttributeError):
        return None


def _clean(cfg):
    """A UCCONFIG1 object fit to be the network defaults, or raises ValueError."""
    if not isinstance(cfg, dict) or cfg.get("format") != "UCCONFIG1" or not isinstance(cfg.get("prefs", {}), dict):
        raise ValueError("not a settings file (UCCONFIG1)")
    prefs = {}
    for name in PREF_FILES:
        one = cfg.get("prefs", {}).get(name) or {}
        prefs[name] = {k: v for k, v in sorted(one.items())
                       if k not in LOCAL and isinstance(v, dict) and v.get("t") in dict(TYPES)}
    sheets = cfg.get("sheets", "")
    return {"format": "UCCONFIG1", "from": str(cfg.get("from", "") or "web admin"), "prefs": prefs,
            "sheets": sheets if isinstance(sheets, str) else ""}


def save_defaults(cfg):
    os.makedirs(os.path.dirname(DEFAULTS), exist_ok=True)
    tmp = DEFAULTS + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.replace(tmp, DEFAULTS)


def _typed(t, raw):
    raw = raw.strip() if t != "s" else raw
    if t == "s":
        return raw
    if t in ("i", "l"):
        return int(raw)
    if t == "b":
        return raw.lower() in ("true", "1", "on", "yes")
    return float(raw)


def _sheet_count(text):
    return sum(1 for line in (text or "").splitlines() if line.startswith("COMMUNITY-COMMS-DRYAD"))


# ---- page ----

def _size(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.0f %s" if unit == "B" else "%.1f %s") % (n, unit)
        n /= 1024.0


def _when(t):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(t)) if t else "-"


def _e(s):
    return html.escape(str(s), quote=True)


def _url(p):
    return quote("/" + _rel(p))


CSS = """
:root{color-scheme:light dark;--bg:#f6f6f4;--fg:#1d1d1b;--muted:#6b6b66;--card:#fff;--line:#deded8;--accent:#2f6f4f;--danger:#a33}
@media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ececea;--muted:#9a9a94;--card:#1f1f1d;--line:#34342f;--accent:#6fbf8f;--danger:#e77}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}
main{max-width:1000px;margin:0 auto;padding:16px}h1{font-size:22px;margin:4px 0 2px}h2{font-size:17px;margin:0 0 10px}h3{font-size:15px;margin:14px 0 6px}
.sub{color:var(--muted);margin:0 0 12px}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px;margin:0 0 14px;overflow-x:auto}
nav{display:flex;flex-wrap:wrap;gap:4px;margin:0 0 14px}nav a{padding:6px 12px;border-radius:6px;border:1px solid var(--line);text-decoration:none;color:var(--fg);background:var(--card)}
nav a.on{background:var(--accent);border-color:var(--accent);color:#fff}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:middle;white-space:nowrap}
th{color:var(--muted);font-weight:600;font-size:13px}td.n{text-align:right;font-variant-numeric:tabular-nums}td.w{white-space:normal;word-break:break-all}
form{display:inline-flex;gap:6px;align-items:center;margin:2px 0;flex-wrap:wrap}form.b{display:block}
input,select,textarea{font:inherit;padding:5px 7px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
textarea{width:100%;font:13px/1.4 ui-monospace,monospace}input[type=number]{width:70px}
button{font:inherit;padding:5px 10px;border-radius:6px;border:1px solid var(--accent);background:var(--accent);color:#fff;cursor:pointer}
button.d{background:transparent;color:var(--danger);border-color:var(--danger)}button.big{padding:8px 16px}
.msg{border-left:4px solid var(--accent);padding:8px 12px;background:var(--card);margin:0 0 14px}
.warn{border-left:4px solid var(--danger);padding:8px 12px;margin:8px 0}
.muted{color:var(--muted)}a{color:var(--accent)}code{font:13px ui-monospace,monospace}p{margin:6px 0 10px}li{margin:3px 0}
"""


def _confirm(text):
    return ' onsubmit="return confirm(%s)"' % _e(json.dumps(text))


def _hidden(**kv):
    return "".join("<input type=hidden name=%s value='%s'>" % (k, _e(v)) for k, v in kv.items())


def page(query="", message="", host=""):
    q = {k: v[0] for k, v in parse_qs(query).items()}
    q["_host"] = host or "this-server"
    tab = q.get("tab", "manage")
    if tab not in dict(TABS):
        tab = "manage"
    if q.get("fresh") == "1":
        forget_stats()
    du = shutil.disk_usage(ROOT)
    out = ["<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
           "<title>YCC Web Admin</title><style>", CSS, "</style></head><body><main>",
           "<h1>Your Community Comms: file store</h1>",
           "<p class=sub>Signed in as %s. Disk: %s free of %s. Server %s.</p>"
           % (_e(current_user()), _size(du.free), _size(du.total), VERSION), "<nav>"]
    for key, label in TABS:
        out.append("<a href='/webadmin?tab=%s'%s>%s</a>" % (key, " class=on" if key == tab else "", label))
    out.append("</nav>")
    if message:
        out.append("<div class=msg>%s</div>" % _e(message))
    globals()["_tab_" + tab](out, q)
    out.append("</main></body></html>")
    return "".join(out).encode()


def _tab_manage(out, q):
    out.append("<p class=muted>Folder sizes are refreshed every 10 minutes "
               "(<a href='/webadmin?tab=manage&fresh=1'>refresh now</a>; the map tiles take a while to count).</p>")
    out.append("<div class=card><h2>Folders</h2><table><tr><th>Folder</th><th>Files</th><th>Size</th><th>Last change</th><th>Tidy</th></tr>")
    for name in _subdirs(YCC):
        b, f, t = stats(os.path.join(YCC, name))
        tidy = ""
        if name in ("Logs", "Data", "Configs", "DRYAD"):
            tidy = ("<form method=post%s>%sDelete files older than <input type=number name=days min=1 value=30> days "
                    "<button class=d>Delete</button></form>"
                    % (_confirm("Delete old files in " + name + "?"), _hidden(a="older", folder=name, tab="manage")))
        out.append("<tr><td><a href='/webadmin?tab=files&dir=%s'>%s</a></td><td class=n>%d</td><td class=n>%s</td><td>%s</td><td>%s</td></tr>"
                   % (_e(quote("YCC/" + name)), _e(name), f, _size(b), _when(t), tidy))
    out.append("</table></div>")

    missions = os.path.join(YCC, "Missions")
    out.append("<div class=card><h2>Missions</h2>")
    names = _subdirs(missions)
    if not names:
        out.append("<p class=muted>No missions yet.</p>")
    else:
        out.append("<table><tr><th>Mission</th><th>Files</th><th>Size</th><th>Last change</th><th>Delete</th></tr>")
        for m in names:
            b, f, t = stats(os.path.join(missions, m))
            out.append(
                "<tr><td><a href='/webadmin?tab=files&dir=%s'>%s</a></td><td class=n>%d</td><td class=n>%s</td><td>%s</td><td>"
                "<form method=post%s>%sFiles older than <input type=number name=days min=1 value=30> days "
                "<button class=d>Delete</button></form> "
                "<form method=post%s>%s<button class=d>Delete whole mission</button></form></td></tr>"
                % (_e(quote("YCC/Missions/" + m)), _e(m), f, _size(b), _when(t),
                   _confirm("Delete old files in mission " + m + "?"), _hidden(a="mission_older", mission=m, tab="manage"),
                   _confirm("Delete mission " + m + " and everything in it? This cannot be undone."),
                   _hidden(a="mission_delete", mission=m, tab="manage")))
        out.append("</table>")
        out.append("<p><form method=post%s>%sMissions with nothing new for <input type=number name=days min=1 value=90> days "
                   "<button class=d>Delete those missions</button></form></p>"
                   % (_confirm("Delete every mission with nothing new in that many days?"), _hidden(a="missions_older", tab="manage")))
    out.append("</div>")

    cache = os.path.join(YCC, "Maps cache")
    out.append("<div class=card><h2>Map tiles</h2><p class=muted>Readable without a login, for ATAK map sources: "
               "<code>http://&lt;this server&gt;/YCC/Maps%20cache/&lt;source&gt;/{z}/{x}/{y}.jpg</code></p>"
               "<table><tr><th>Source</th><th>Tiles</th><th>Size</th><th>Last change</th><th></th></tr>")
    for s in _subdirs(cache):
        b, f, t = stats(os.path.join(cache, s))
        out.append("<tr><td>%s</td><td class=n>%d</td><td class=n>%s</td><td>%s</td><td>"
                   "<form method=post%s>%s<button class=d>Delete</button></form></td></tr>"
                   % (_e(s), f, _size(b), _when(t), _confirm("Delete all tiles of " + s + "?"),
                      _hidden(a="tiles_delete", source=s, tab="manage")))
    out.append("</table></div>")


def _tab_files(out, q):
    d = _inside(q.get("dir"))
    if not d or not os.path.isdir(d):
        d = YCC
    rel = _rel(d)
    crumbs, acc = [], ""
    for part in rel.split("/"):
        acc = (acc + "/" + part).strip("/")
        crumbs.append("<a href='/webadmin?tab=files&dir=%s'>%s</a>" % (_e(quote(acc)), _e(part)))
    out.append("<div class=card><h2>%s</h2>" % " / ".join(crumbs))
    try:
        entries = sorted((e for e in os.scandir(d) if not e.name.startswith(".")),
                         key=lambda e: (not e.is_dir(), e.name.lower()))
    except OSError:
        entries = []
    if not entries:
        out.append("<p class=muted>Empty folder.</p>")
    else:
        out.append("<table><tr><th>Name</th><th>Size</th><th>Changed</th><th></th></tr>")
        for e in entries[:LIST_MAX]:
            try:
                st = e.stat()
            except OSError:
                continue
            isdir = e.is_dir()
            link = ("<a href='/webadmin?tab=files&dir=%s'>%s/</a>" % (_e(quote(rel + "/" + e.name)), _e(e.name)) if isdir
                    else "<a href='%s'>%s</a>" % (_e(_url(e.path)), _e(e.name)))
            what = "folder " + e.name + " and everything in it" if isdir else e.name
            out.append("<tr><td class=w>%s</td><td class=n>%s</td><td>%s</td><td>"
                       "<form method=post>%s<input name=new value='%s' size=14><button>Rename</button></form> "
                       "<form method=post%s>%s<button class=d>Delete</button></form></td></tr>"
                       % (link, "" if isdir else _size(st.st_size), _when(st.st_mtime),
                          _hidden(a="rename", tab="files", dir=rel, name=e.name), _e(e.name),
                          _confirm("Delete " + what + "? This cannot be undone."),
                          _hidden(a="delete", tab="files", dir=rel, name=e.name)))
        out.append("</table>")
        if len(entries) > LIST_MAX:
            out.append("<p class=muted>Showing the first %d of %d entries.</p>" % (LIST_MAX, len(entries)))
    out.append("</div><div class=card><h2>Add here</h2>"
               "<p><form method=post enctype=multipart/form-data>%s<input type=file name=up multiple required> "
               "<button>Upload</button></form> <span class=muted>(up to 300 MB at a time; same names are replaced)</span></p>"
               "<p><form method=post>%sNew folder <input name=new required> <button>Create</button></form></p></div>"
               % (_hidden(a="upload", tab="files", dir=rel), _hidden(a="mkdir", tab="files", dir=rel)))


def _tab_defaults(out, q):
    cfg = load_defaults()
    out.append("<div class=card><h2>Network defaults</h2><p>Phones whose LAN storage location points here get these when "
               "someone taps <b>Settings &gt; Config backups &gt; Load defaults</b> and picks <b>Network defaults</b>. "
               "They replace that phone's settings and DRYAD sheets; the callsign, PIN, LAN storage login and each "
               "phone's own choices (PTT key, audio, sync options and so on) are never changed.</p>")
    if cfg is None:
        out.append("<p class=muted>No network defaults set yet, so phones only offer the app's own defaults. "
                   "Start from a phone's backup below, or upload a settings file.</p>")
    else:
        n = sum(len(v) for v in cfg["prefs"].values()) if isinstance(cfg.get("prefs"), dict) else 0
        out.append("<p>Set%s, %d settings and %d DRYAD sheet(s), saved %s. <a href='%s'>Download</a></p>"
                   "<form method=post%s>%s<button class=d>Remove the network defaults</button></form>"
                   % (" from " + _e(cfg.get("from")) if cfg.get("from") else "", n, _sheet_count(cfg.get("sheets")),
                      _when(os.path.getmtime(DEFAULTS)), _e(_url(DEFAULTS)),
                      _confirm("Remove the network defaults? Phones go back to offering only the app's defaults."),
                      _hidden(a="def_clear", tab="defaults")))
    out.append("</div>")

    out.append("<div class=card><h2>Start from a phone</h2><p class=muted>Phones with <i>LAN sync: config</i> on back up "
               "here as Configs/&lt;callsign&gt;.ucconfig. Set one phone up the way you want, sync it, then use its "
               "backup. Its own settings and secrets are left out.</p>")
    backups = []
    try:
        backups = sorted(e.name for e in os.scandir(os.path.dirname(DEFAULTS))
                         if e.name.endswith(".ucconfig") and not e.name.startswith(("_", ".")))
    except OSError:
        pass
    if backups:
        out.append("<table><tr><th>Backup</th><th>Changed</th><th></th></tr>")
        for b in backups:
            p = os.path.join(os.path.dirname(DEFAULTS), b)
            out.append("<tr><td><a href='%s'>%s</a></td><td>%s</td><td><form method=post%s>%s<button>Use as defaults</button>"
                       "</form></td></tr>" % (_e(_url(p)), _e(b), _when(os.path.getmtime(p)),
                                              _confirm("Replace the network defaults with " + b + "?"),
                                              _hidden(a="def_from", tab="defaults", name=b)))
        out.append("</table>")
    else:
        out.append("<p class=muted>No phone backups yet.</p>")
    out.append("<h3>Or upload a settings file</h3><form method=post enctype=multipart/form-data%s>%s"
               "<input type=file name=cfg accept='.ucconfig,.json' required> <button>Use as defaults</button></form>"
               "<p class=muted>A settings.ucconfig shared from a phone, or a downloaded defaults file.</p></div>"
               % (_confirm("Replace the network defaults with this file?"), _hidden(a="def_upload", tab="defaults")))

    if cfg is None:
        return
    out.append("<div class=card><h2>Edit</h2><form method=post class=b>%s" % _hidden(a="def_edit", tab="defaults"))
    for name in PREF_FILES:
        one = cfg["prefs"].get(name) or {}
        out.append("<h3>%s <span class=muted>(%d)</span></h3>" % (_e(name), len(one)))
        if not one:
            continue
        out.append("<table><tr><th>Setting</th><th>Type</th><th>Value</th><th>Remove</th></tr>")
        for k, v in one.items():
            field = "v|%s|%s" % (name, k)
            if v.get("t") == "b":
                val = "<select name='%s'><option%s>true</option><option%s>false</option></select>" % (
                    _e(field), " selected" if v.get("v") else "", "" if v.get("v") else " selected")
            else:
                val = "<input name='%s' value='%s' size=40>" % (_e(field), _e(v.get("v")))
            out.append("<tr><td>%s</td><td class=muted>%s</td><td>%s</td><td><input type=checkbox name='%s'></td></tr>"
                       % (_e(k), dict(TYPES).get(v.get("t"), "?"), val, _e("del|%s|%s" % (name, k))))
        out.append("</table>")
    out.append("<h3>Add a setting</h3><p><select name=nfile>%s</select> name <input name=nkey size=18> "
               "type <select name=ntype>%s</select> value <input name=nval size=24></p>"
               % ("".join("<option>%s</option>" % n for n in PREF_FILES),
                  "".join("<option value=%s>%s</option>" % t for t in TYPES)))
    out.append("<h3>DRYAD sheets <span class=muted>(%d)</span></h3><textarea name=sheets rows=10>%s</textarea>"
               "<p class=muted>The text of every sheet, as exported from the app's DRYAD screen. Empty means none.</p>"
               "<p><button class=big>Save defaults</button></p></form></div>"
               % (_sheet_count(cfg.get("sheets")), _e(cfg.get("sheets", ""))))


def _tab_setup(out, q):
    setup.tab(out, _e, _hidden, _confirm, q["_host"], current_user(), current_password())


def _tab_sync(out, q):
    sync.tab(out, _e, _hidden, _confirm, _when, _size)


def _tab_bridge(out, q):
    bridge_admin.tab(out, q, _e, _hidden, _confirm, _when, "http://%s/YCC" % q["_host"])


def _tab_updates(out, q):
    vt = os.path.join(YCC, "Updates", "version.txt")
    try:
        with open(vt) as f:
            lines = f.read().split()
        ver = "version code %s (%s)" % (lines[0], lines[1] if len(lines) > 1 else "?")
    except (OSError, IndexError):
        ver = "none published"
    apk = os.path.join(YCC, "Updates", "YCC.apk")
    out.append("<div class=card><h2>App updates</h2><p>Published: %s%s. Phones with auto updates on fetch it from here; "
               "anyone can download <a href='/YCC/Updates/YCC.apk'>YCC.apk</a> without a login.</p>"
               % (_e(ver), (", %s, %s" % (_size(os.path.getsize(apk)), _when(os.path.getmtime(apk)))) if os.path.isfile(apk) else ""))
    out.append("<form method=post enctype=multipart/form-data%s>%s"
               "APK <input type=file name=apk accept='.apk' required> Version code <input type=number name=code min=1 "
               "style='width:110px' placeholder='from APK'> <button>Publish</button></form>"
               "<p class=muted>Leave the version code empty to read it from the APK. It must be higher than the phones' "
               "(the app shows it as 0.1.&lt;code&gt;).</p>"
               % (_confirm("Publish this APK to every phone with auto updates on?"), _hidden(a="publish", tab="updates")))
    if os.path.isfile(vt):
        out.append("<p><form method=post%s>%s<button class=d>Stop offering the update</button></form> "
                   "<span class=muted>(removes version.txt; the APK stays downloadable)</span></p>"
                   % (_confirm("Stop offering this update to phones?"), _hidden(a="unpublish", tab="updates")))
    out.append("</div>")


def _tab_login(out, q):
    out.append("<div class=card><h2>Login</h2><p class=muted>Used by the phones (Settings &gt; LAN storage location) and this page. "
               "After changing it, enter the new password on every phone. Map tiles and the APK never need it.</p>"
               "<form method=post%s>%sUser <input name=user value='%s' required> "
               "New password <input type=password name=password required minlength=3> "
               "Again <input type=password name=password2 required minlength=3> <button>Change</button></form></div>"
               % (_confirm("Change the login? Phones keep the old one until you update them."),
                  _hidden(a="login", tab="login"), _e(current_user())))


def _tab_docs(out, q):
    out.append("""<div class=card><h2>About</h2>
<p>This is the LAN storage location for <b>Your Community Communications</b>, an offline push-to-talk radio, chat and
DRYAD app for phones on a MANET (such as OpenMANET) or any shared WiFi network. Phones keep map tiles, app updates,
missions, logs, data files, DRYAD sheets and config backups here. It is a small Python program (no add-ons) speaking
plain HTTP with WebDAV-style PUT and MKCOL, behind one login.</p>
<p>Files: <a href='/webadmin/doc/README.md'>README.md</a> (install and settings),
<a href='/webadmin/doc/RECREATE-PROMPT.md'>RECREATE-PROMPT.md</a> (the prompt that rebuilds the app),
<a href='/webadmin/doc/NOMAD-SETUP.md'>NOMAD-SETUP.md</a> (this server, and the commander view on an Ubuntu desktop),
<a href='/webadmin/doc/ALL-FEATURES.txt'>ALL-FEATURES.txt</a> (every feature of the app),
<a href='/webadmin/doc/LICENSE'>LICENSE</a>, <a href='/webadmin/doc/NOTICE'>NOTICE</a>.</p></div>

<div class=card><h2>Licence: Apache License 2.0</h2>
<p>The app, this server and their documents are released under the
<a href='/webadmin/doc/LICENSE'>Apache License, Version 2.0</a>. In short:</p>
<ul><li>You may use, copy, change and share them, for any purpose including commercial use.</li>
<li>Keep the licence and the NOTICE file with copies, and say which files you changed.</li>
<li>The licence includes a patent grant from contributors, and ends for anyone who sues over patents in the work.</li>
<li>It comes with no warranty: use it at your own risk, and test before relying on it.</li></ul>
<p class=muted>This summary is not the licence; the LICENSE file is what counts.</p></div>

<div class=card><h2>Rebuild it with an AI prompt</h2>
<p>The whole app was written by an AI coding assistant (Claude Code) from a plain-language prompt, and anyone can build
their own copy the same way, with their own name, colours or changes:</p>
<ol><li>Download <a href='/webadmin/doc/RECREATE-PROMPT.md'>RECREATE-PROMPT.md</a>. It describes every feature, plus the
exact wire format, encryption and test vectors, so a rebuilt app still talks to this one.</li>
<li>On a PC, open an AI coding assistant such as Claude Code in an empty folder and paste the prompt in.
It asks before installing tools (JDK, Android SDK, emulators) or accepting licences.</li>
<li>Ask for changes in plain words (for example "rename the app to ..." or "add a button that ..."). Keep the protocol
section unchanged if your phones must still talk to other builds; the same channel name always gives the same keys.</li>
<li>Install the APK it builds on each phone (sideloaded, no app store), or publish it on the Updates tab.</li></ol>
<p>This server can be rebuilt the same way: give the assistant <a href='/webadmin/doc/README.md'>README.md</a> and ask for
"a small Python file server that does exactly this, with a /webadmin page", or just copy the server folder.</p></div>

<div class=card><h2>Install on Project NOMAD</h2>
<ol><li>Copy the server folder to the NOMAD box and build the image there: <code>sudo docker build -t ycc-files:1.1 .</code></li>
<li>Supply Depot &gt; Add Custom App: image <code>ycc-files:1.1</code>; port host <code>8647</code> to container
<code>47490</code>; volume <code>/opt/project-nomad/storage/ycc-files</code> to <code>/data</code>;
environment <code>YCC_PASSWORD</code> (and <code>YCC_USER</code> if not <code>ycc</code>).</li>
<li>Set the app's launcher URL to <code>http://&lt;nomad&gt;:8647/webadmin</code>.</li>
<li>On each phone: Settings &gt; LAN storage location <code>http://&lt;nomad&gt;:8647/YCC</code> with the same login.</li></ol></div>

<div class=card><h2>What needs the login</h2><table><tr><th>Path</th><th>Read</th><th>Write</th></tr>
<tr><td><code>/YCC/Maps cache/...</code></td><td>anyone (ATAK map sources cannot log in)</td><td>login</td></tr>
<tr><td><code>/YCC/Updates/YCC.apk</code></td><td>anyone (browser downloads)</td><td>login</td></tr>
<tr><td>everything else, and /webadmin</td><td>login</td><td>login</td></tr></table></div>

<div class=card><h2>Making a clean copy to share</h2>
<p>Files and the login changed on this page live in the <code>/data</code> volume, not in the image, so the image itself
never holds anyone's files. To hand over a clean store as well, use the <a href='/webadmin?tab=erase'>Erase</a> tab, which
empties the volume and puts the login back to the container's <code>YCC_PASSWORD</code>. Then share the image
(<code>docker save ycc-files:1.1</code>) or the server folder.</p></div>""")


def _tab_erase(out, q):
    b, f, t = stats(YCC)
    out.append("<div class=card><h2>Erase all custom settings and files</h2>"
               "<p>Puts the store back to how a fresh install starts, for example before taking a snapshot to share. "
               "It deletes everything here: missions, logs, data files, DRYAD sheets, config backups, the network "
               "defaults, map tiles, published app updates, and the login set on this page (it goes back to the "
               "container's YCC_PASSWORD).</p><p>Now stored: %d files, %s.</p>"
               "<div class=warn>This cannot be undone. Phones keep their own copies of what they synced, but nothing "
               "here can be brought back.</div>"
               "<form method=post class=b%s>%s<p><label><input type=checkbox name=keep_tiles> Keep the map tiles</label></p>"
               "<p>Type <b>ERASE</b> to confirm: <input name=confirm autocomplete=off required> "
               "<button class='d big'>Erase everything</button></p></form></div>"
               % (f, _size(b), _confirm("Really erase every file and custom setting on this store?"),
                  _hidden(a="erase", tab="erase")))


# ---- actions ----

def _days(v):
    try:
        d = int(v)
        return d if d >= 1 else None
    except (TypeError, ValueError):
        return None


def _remove(p):
    if os.path.isdir(p) and not os.path.islink(p):
        shutil.rmtree(p, ignore_errors=True)
    elif os.path.lexists(p):
        os.remove(p)


def act(form, files):
    """Runs one form action, returns the message to show."""
    global _auth
    a = form.get("a", "")
    missions = os.path.join(YCC, "Missions")
    if a == "older":
        folder = form.get("folder")
        p = _child(YCC, folder) if folder in ("Logs", "Data", "Configs", "DRYAD") else None
        d = _days(form.get("days"))
        if not p or not d:
            return "Nothing deleted: check the folder and days."
        return "Deleted %d files older than %d days from %s." % (delete_older(p, d), d, folder)
    if a == "mission_older":
        p = _child(missions, form.get("mission"))
        d = _days(form.get("days"))
        if not p or not d:
            return "Nothing deleted: unknown mission or days."
        return "Deleted %d files older than %d days from mission %s." % (delete_older(p, d), d, form.get("mission"))
    if a == "mission_delete":
        p = _child(missions, form.get("mission"))
        if not p:
            return "Unknown mission."
        shutil.rmtree(p, ignore_errors=True)
        forget_stats()
        return "Deleted mission %s." % form.get("mission")
    if a == "missions_older":
        d = _days(form.get("days"))
        if not d:
            return "Nothing deleted: check the days."
        cut = time.time() - d * 86400
        gone = []
        for m in _subdirs(missions):
            p = os.path.join(missions, m)
            newest = stats(p, fresh=True)[2]
            if newest < cut:
                shutil.rmtree(p, ignore_errors=True)
                gone.append(m)
        forget_stats()
        return ("Deleted missions: " + ", ".join(gone)) if gone else "No mission was that old."
    if a == "tiles_delete":
        p = _child(os.path.join(YCC, "Maps cache"), form.get("source"))
        if not p:
            return "Unknown tile source."
        shutil.rmtree(p, ignore_errors=True)
        forget_stats()
        return "Deleted tile source %s." % form.get("source")

    # Files tab
    if a in ("delete", "rename", "upload", "mkdir"):
        d = _inside(form.get("dir"))
        if not d or not os.path.isdir(d):
            return "Unknown folder."
        forget_stats()
        if a == "delete":
            name = form.get("name", "")
            if not _plain(name) or not os.path.lexists(os.path.join(d, name)):
                return "Nothing deleted: unknown name."
            _remove(os.path.join(d, name))
            return "Deleted %s." % name
        if a == "rename":
            name, new = form.get("name", ""), form.get("new", "").strip()
            if not _plain(name) or not _plain(new) or not os.path.lexists(os.path.join(d, name)):
                return "Not renamed: names cannot be empty, start with '.', or contain slashes."
            if os.path.lexists(os.path.join(d, new)):
                return "Not renamed: %s already exists." % new
            os.rename(os.path.join(d, name), os.path.join(d, new))
            return "Renamed %s to %s." % (name, new)
        if a == "mkdir":
            new = form.get("new", "").strip()
            if not _plain(new):
                return "Folder not created: names cannot be empty, start with '.', or contain slashes."
            os.makedirs(os.path.join(d, new), exist_ok=True)
            return "Created folder %s." % new
        done = []
        for fname, data in files.get("up", []):
            fname = os.path.basename((fname or "").replace("\\", "/"))
            if not _plain(fname) or fname.endswith(".part"):
                continue
            tmp = os.path.join(d, fname + ".part")
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, os.path.join(d, fname))
            done.append(fname)
        return ("Uploaded " + ", ".join(done) + ".") if done else "Nothing uploaded."

    # Defaults tab
    if a == "def_clear":
        if os.path.isfile(DEFAULTS):
            os.remove(DEFAULTS)
        return "Network defaults removed."
    if a in ("def_from", "def_upload"):
        try:
            if a == "def_from":
                name = form.get("name", "")
                p = os.path.join(os.path.dirname(DEFAULTS), name)
                if not _plain(name) or not name.endswith(".ucconfig") or not os.path.isfile(p):
                    return "Unknown backup."
                with open(p, encoding="utf-8") as f:
                    raw = f.read()
            else:
                up = files.get("cfg") or []
                if not up:
                    return "Choose a settings file."
                raw = up[0][1].decode("utf-8")
            cfg = _clean(json.loads(raw))
        except (ValueError, UnicodeDecodeError) as e:
            return "Not used: %s." % e
        save_defaults(cfg)
        return "Network defaults set from %s: %d settings, %d DRYAD sheet(s)." % (
            cfg["from"], sum(len(v) for v in cfg["prefs"].values()), _sheet_count(cfg["sheets"]))
    if a == "def_edit":
        cfg = load_defaults()
        if cfg is None:
            return "No network defaults to edit."
        cfg = _clean(cfg)
        bad = []
        for name in PREF_FILES:
            one = cfg["prefs"][name]
            for k in list(one):
                if ("del|%s|%s" % (name, k)) in form:
                    del one[k]
                elif ("v|%s|%s" % (name, k)) in form:
                    try:
                        one[k]["v"] = _typed(one[k]["t"], form["v|%s|%s" % (name, k)])
                    except ValueError:
                        bad.append(k)
        nkey = form.get("nkey", "").strip()
        if nkey:
            nfile, ntype = form.get("nfile"), form.get("ntype")
            if nfile not in PREF_FILES or ntype not in dict(TYPES) or nkey in LOCAL:
                bad.append(nkey)
            else:
                try:
                    cfg["prefs"][nfile][nkey] = {"t": ntype, "v": _typed(ntype, form.get("nval", ""))}
                except ValueError:
                    bad.append(nkey)
        cfg["sheets"] = form.get("sheets", "").replace("\r\n", "\n")
        cfg["from"] = cfg.get("from") or "web admin"
        save_defaults(cfg)
        return "Defaults saved." + (" Not changed (bad value or a per-phone setting): " + ", ".join(bad) if bad else "")

    # Updates tab
    if a == "publish":
        up = files.get("apk") or []
        data = up[0][1] if up else b""
        code = form.get("code", "").strip()
        if not data or data[:2] != b"PK":
            return "Not published: choose an .apk file."
        found, vname = apk_version(data)
        if not code:
            if found is None:
                return "Not published: could not read the version code from the APK; enter it."
            code = str(found)
        if not code.isdigit():
            return "Not published: the version code must be a number."
        up = os.path.join(YCC, "Updates")
        os.makedirs(up, exist_ok=True)
        tmp = os.path.join(up, "YCC.apk.part")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(up, "YCC.apk"))
        with open(os.path.join(up, "version.txt.part"), "w") as f:
            f.write(code + "\nYCC.apk\n")
        os.replace(os.path.join(up, "version.txt.part"), os.path.join(up, "version.txt"))
        warn = ""
        if found is not None and str(found) != code:
            warn = " Warning: the APK itself says %d, so phones may keep updating." % found
        return "Published version code %s%s (%s).%s" % (code, " (%s)" % vname if vname else "", _size(len(data)), warn)
    if a == "unpublish":
        vt = os.path.join(YCC, "Updates", "version.txt")
        if os.path.isfile(vt):
            os.remove(vt)
        return "Update no longer offered."

    if a == "setup_save":
        return setup.act(form)
    if a.startswith("sync_"):
        return sync.act(form)
    if a.startswith("bridge_"):
        return bridge_admin.act(form)

    if a == "login":
        user = form.get("user", "").strip()
        pw = form.get("password", "")
        if not user or ":" in user or len(pw) < 3 or pw != form.get("password2"):
            return "Login not changed: the passwords must match, and the user cannot contain ':'."
        tmp = LOGIN_FILE + ".part"
        with open(tmp, "w") as f:
            f.write(user + ":" + pw)
        os.chmod(tmp, 0o600)
        os.replace(tmp, LOGIN_FILE)
        _auth = _encode(user, pw)
        return "Login changed. Your browser will ask for it again; update each phone's LAN storage password."

    if a == "erase":
        if form.get("confirm", "").strip() != "ERASE":
            return "Nothing erased: type ERASE to confirm."
        keep = os.path.join(YCC, "Maps cache") if "keep_tiles" in form else None
        for e in os.listdir(ROOT):
            p = os.path.join(ROOT, e)
            if p == YCC and keep:
                for sub in os.listdir(YCC):
                    if os.path.join(YCC, sub) != keep:
                        _remove(os.path.join(YCC, sub))
            else:
                _remove(p)
        for sub in ("Maps cache", "Updates"):
            os.makedirs(os.path.join(YCC, sub), exist_ok=True)
        _auth = _env_auth
        setup.erase()
        sync.erase()
        bridge_admin.erase()
        forget_stats()
        return ("Erased. The store is empty%s, and the login is back to the container's YCC_USER / YCC_PASSWORD."
                % (" apart from the map tiles" if keep else ""))
    return "Unknown action."


def doc(name):
    """(bytes, content type) of a bundled document, or None."""
    rel = DOCS.get(name)
    if not rel:
        return None
    try:
        with open(os.path.join(APP_DIR, rel), "rb") as f:
            return f.read(), "text/markdown; charset=utf-8" if name.endswith(".md") else "text/plain; charset=utf-8"
    except OSError:
        return None


def parse_post(handler, body):
    """(form fields, {field: [(file name, bytes)]})."""
    ctype = handler.headers.get("Content-Type", "")
    if ctype.startswith("multipart/form-data"):
        msg = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
            b"Content-Type: " + ctype.encode() + b"\r\n\r\n" + body)
        form, files = {}, {}
        for part in msg.iter_parts():
            name = part.get_param("name", header="content-disposition")
            if part.get_filename() is not None:
                data = part.get_payload(decode=True) or b""
                if part.get_filename() or data:
                    files.setdefault(name, []).append((part.get_filename(), data))
            else:
                form[name] = part.get_content().strip()
        return form, files
    q = parse_qs(body.decode("utf-8", "replace"), keep_blank_values=True)
    return {k: v[0] for k, v in q.items()}, {}


def same_origin(handler):
    """Blocks other web pages from posting to this one (the browser would add the login)."""
    src = handler.headers.get("Origin") or handler.headers.get("Referer")
    if not src:
        return False
    return urlsplit(src).netloc == handler.headers.get("Host", "")
