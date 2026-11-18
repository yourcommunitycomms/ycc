"""Stamped APKs for the web admin page (the "Phones" tab): a site APK and personal APKs.

A stamp is a small JSON text put into the APK's signing block (a key / value area the APK signatures do not
cover), so the APK keeps its signature and installs and updates like the plain one, on Android 4.1 (v1 JAR
signature) and newer (v2). The app reads it from its own installed APK on first start.

- Site APK (YCC/Updates/YCC-site.apk, no login needed, like YCC.apk): stamped with this server's address. On
  first start the app offers "Set up from <site>": it fetches YCC/Updates/YCC-site.setup, the Phone setup code
  (without the store login and channel A unless the admin ticks that).
- Personal APK (YCC/Personal/ycc-<token>.apk): stamped with this server, the person's callsign (and lock), a
  long random token and its own update address (the same file). Stage 1 is the stamp; stage 2 is the rest of
  that person's setup, fetched from YCC/Personal/<token>.setup (keys and channels never go in the APK). A
  revoked token gets nothing; with "allow re-setup" off, the setup is given only once.
- When the plain YCC/Updates/YCC.apk changes (published here, or put there by a phone), every stamped APK is
  made again from it, so a personal update address always serves the newest version.

People are kept in <store>/.ycc_people.json (hidden, never served). Personal APKs are never listed.
"""
import base64
import json
import os
import secrets
import struct
import threading
import time

STAMP_ID = 0x59434331  # "YCC1"
PADDING_ID = 0x42726577  # verity padding pair (kept aligned to 4096 if the APK had it)
MAGIC = b"APK Sig Block 42"
TOKEN_ABC = "abcdefghijkmnopqrstuvwxyz"

_root = None
_ycc = None
_file = None
_lock = threading.Lock()


def init(root):
    global _root, _ycc, _file
    _root = root
    _ycc = os.path.join(root, "YCC")
    _file = os.path.join(root, ".ycc_people.json")
    t = threading.Thread(target=_watch, name="ycc-restamp", daemon=True)
    t.start()


# ---- the APK signing block ----

def _eocd(data):
    start = max(0, len(data) - 22 - 65535)
    i = data.rfind(b"PK\x05\x06", start)
    if i < 0:
        raise ValueError("not an APK (no end of central directory)")
    return i


def stamp(apk, payload):
    """The APK with payload (bytes) in its signing block under STAMP_ID; anything stamped before is replaced."""
    e = _eocd(apk)
    cd_off = struct.unpack_from("<I", apk, e + 16)[0]
    if apk[cd_off - 16:cd_off] != MAGIC:
        raise ValueError("this APK has no v2 signing block (sign it with v2)")
    size = struct.unpack_from("<Q", apk, cd_off - 24)[0]
    start = cd_off - (size + 8)
    if start < 0 or struct.unpack_from("<Q", apk, start)[0] != size:
        raise ValueError("damaged signing block")
    pairs, had_padding = [], False
    p, end = start + 8, cd_off - 24
    while p < end:
        n = struct.unpack_from("<Q", apk, p)[0]
        pid = struct.unpack_from("<I", apk, p + 8)[0]
        val = apk[p + 12:p + 8 + n]
        if pid == PADDING_ID:
            had_padding = True
        elif pid != STAMP_ID:
            pairs.append((pid, val))
        p += 8 + n
    pairs.append((STAMP_ID, payload))
    body = b"".join(struct.pack("<QI", len(v) + 4, pid) + v for pid, v in pairs)
    if had_padding:  # keep the whole block a multiple of 4096 bytes, as the signer made it
        total = 8 + len(body) + 8 + 16
        pad = (-total) % 4096
        if pad and pad < 12:
            pad += 4096
        if pad:
            body += struct.pack("<QI", pad - 8, PADDING_ID) + b"\0" * (pad - 12)
    blk_size = len(body) + 8 + 16
    block = struct.pack("<Q", blk_size) + body + struct.pack("<Q", blk_size) + MAGIC
    eocd = bytearray(apk[e:])
    struct.pack_into("<I", eocd, 16, start + len(block))
    return apk[:start] + block + apk[cd_off:e] + bytes(eocd)


def read_stamp(apk):
    """The stamp in an APK (dict), or None."""
    try:
        e = _eocd(apk)
        cd_off = struct.unpack_from("<I", apk, e + 16)[0]
        if apk[cd_off - 16:cd_off] != MAGIC:
            return None
        size = struct.unpack_from("<Q", apk, cd_off - 24)[0]
        p, end = cd_off - (size + 8) + 8, cd_off - 24
        while p < end:
            n = struct.unpack_from("<Q", apk, p)[0]
            if struct.unpack_from("<I", apk, p + 8)[0] == STAMP_ID:
                return json.loads(apk[p + 12:p + 8 + n].decode("utf-8"))
            p += 8 + n
    except (ValueError, struct.error, UnicodeDecodeError):
        return None
    return None


# ---- people ----

def load():
    try:
        with open(_file, encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict):
            d.setdefault("people", {})
            d.setdefault("site", {})
            return d
    except (OSError, ValueError):
        pass
    return {"people": {}, "site": {}}


def save(d):
    tmp = _file + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, _file)


def new_token():
    return "".join(secrets.choice(TOKEN_ABC) for _ in range(30))


def valid_token(t):
    return isinstance(t, str) and len(t) == 30 and all(c in TOKEN_ABC for c in t)


def _baseline():
    return os.path.join(_ycc, "Updates", "YCC.apk")


def personal_path(token):
    return os.path.join(_ycc, "Personal", "ycc-%s.apk" % token)


def site_path():
    return os.path.join(_ycc, "Updates", "YCC-site.apk")


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def parse_cloud(text):
    """A cloud group code (YCCCLOUD1|{...} or a ycc://cloud/ link) as a dict, or None."""
    t = (text or "").strip()
    try:
        if t.startswith("YCCCLOUD1|"):
            o = json.loads(t[len("YCCCLOUD1|"):])
        elif "ycc://cloud/" in t:
            b = t.split("ycc://cloud/", 1)[1].split()[0]
            o = json.loads(base64.urlsafe_b64decode(b + "=" * (-len(b) % 4)).decode("utf-8"))
        else:
            return None
    except (ValueError, UnicodeDecodeError):
        return None
    return o if isinstance(o, dict) and o.get("k") else None


def _site_name():
    try:
        import setup
        return str(setup.load().get("name", "") or "").strip()
    except Exception:
        return ""


def _public_key():
    try:
        import setup
        return str(setup.load().get("pub", "") or "").strip()
    except Exception:
        return ""


def _embed(st, who):
    """The keys the admin chose to put in the APK itself: the Public key and a cloud group (a wiped phone keeps them)."""
    if who.get("embedPub", True):
        pub = _public_key()
        if pub:
            st["pub"] = pub
    if who.get("embedCloud") and who.get("cloud"):
        st["cloud"] = who["cloud"]
    return st


def _site_stamp(d, base_url):
    s = d.get("site", {})
    return _embed({"v": 1, "kind": "site", "server": base_url, "site": s.get("name") or "this site",
                   "setup": base_url + "/Updates/YCC-site.setup", "update": base_url + "/Updates/YCC-site.apk"}, s)


def _person_stamp(token, p, base_url):
    return _embed({"v": 1, "kind": "personal", "server": base_url, "site": p.get("site") or "this site",
                   "token": token, "call": p.get("call", ""), "nameLock": bool(p.get("nameLock")),
                   "setup": base_url + "/Personal/%s.setup" % token,
                   "update": base_url + "/Personal/ycc-%s.apk" % token}, p)


def build_all(d=None):
    """Makes the site APK and every personal APK again from the plain APK. Returns a short report."""
    with _lock:
        d = d or load()
        try:
            with open(_baseline(), "rb") as f:
                base = f.read()
        except OSError:
            return "No plain APK published yet (Updates tab), so nothing was stamped."
        base_url = d.get("url", "")
        if not base_url:
            return "Set the storage address first (Phone setup tab, or Save here)."
        made = 0
        try:
            if d.get("site", {}).get("on"):
                _write(site_path(), stamp(base, json.dumps(_site_stamp(d, base_url), separators=(",", ":")).encode()))
                made += 1
            elif os.path.isfile(site_path()):
                os.remove(site_path())
            for token, p in d["people"].items():
                _write(personal_path(token), stamp(base, json.dumps(_person_stamp(token, p, base_url), separators=(",", ":")).encode()))
                made += 1
        except ValueError as ex:
            return "Could not stamp: %s" % ex
        st = os.stat(_baseline())
        d["baseline"] = [st.st_size, int(st.st_mtime)]
        save(d)
        return "Stamped %d APK%s from the published app." % (made, "" if made == 1 else "s")


def _watch():
    """Every minute: if the plain APK changed (published, or put there by a phone), stamp everything again."""
    while True:
        time.sleep(60)
        try:
            d = load()
            if not d["people"] and not d.get("site", {}).get("on"):
                continue
            st = os.stat(_baseline())
            if d.get("baseline") != [st.st_size, int(st.st_mtime)]:
                build_all(d)
        except OSError:
            pass


# ---- what phones fetch (no login: the token is the key) ----

# ---- the settings a site or personal APK sets up (the same as the phone's setup wizard) ----

ROLES = (("", "(the phone chooses)"), ("phone", "Phone radio"), ("commander", "Commander"), ("intercom", "Public intercom"),
         ("tv", "TV / display"), ("camera", "Camera monitor"), ("gateway", "Network gateway"))
GROUPS = (("", "(the phone chooses)"), ("advanced", "Advanced"), ("general", "Simple: General"), ("scouts", "Simple: Scouts"),
          ("staff", "Simple: Staff"), ("4wd", "Simple: 4WDing"), ("convoy", "Simple: Friends convoy"), ("camping", "Simple: Caravan / camping"))
PROFILES = [("", "(the phone chooses)")] + [(x, x) for x in ("General", "Friends", "Security", "Firefighting", "Military",
                                                               "Emergency services", "Scouts", "Staff", "4WDing", "Friends convoy",
                                                               "Caravan / camping")]
YESNO = (("", "(the phone chooses)"), ("1", "Yes"), ("0", "No"))
FEATURES = (("array", "Channel array"), ("report", "Report to leaders"), ("tasks", "Tasks and notes"), ("calendar", "Calendar and notes"),
            ("bluetti", "Battery power"), ("cast", "Cast screen to TV"), ("relayadmin", "Relay admin"), ("cloud", "Cloud group"),
            ("trex", "T.REX Arms Topo Map"), ("vicemergency", "VicEmergency alerts"), ("trains", "Melbourne trains preset"),
            ("stopnames", "Load station names"), ("artc", "ARTC rail network"), ("nomad", "Project NOMAD"), ("atak", "ATAK map items"),
            ("openmanet", "OpenMANET"), ("streams", "Internet audio"))
# key, label, options (None = text), help
SETTINGS = (
    ("role", "Device role", ROLES, ""),
    ("lead", "Leads a team", YESNO, "Turns on Channel array, Report to leaders, Tasks and notes, Calendar and notes."),
    ("group", "Group type", GROUPS, "Advanced, or Simple with its group type."),
    ("prof", "Profile", PROFILES, "The quick buttons and map points."),
    ("burst", "Voice", (("", "(the phone chooses)"), ("1", "Burst (recommended)"), ("0", "Live")), ""),
    ("anchored", "Anchored", (("", "(not set)"), ("1", "Yes: on stable power")), "Only for a device on mains power."),
    ("autoLoc", "Share location when asked", (("", "(the phone chooses)"), ("1", "Automatically"), ("0", "Ask each time")), ""),
    ("obscure", "Obscure position", (("", "(the phone chooses)"), ("0", "Exact"), ("500", "Up to 500 m"), ("1000", "Up to 1 km"),
                                     ("2000", "Up to 2 km"), ("5000", "Up to 5 km"), ("10000", "Up to 10 km")), ""),
    ("shareLocPub", "Location on Public / cloud", YESNO, "Share my location with Public and cloud groups."),
    ("mapsOnline", "Online map tiles", (("", "(the phone chooses)"), ("0", "Ask first"), ("1", "Always when there is internet")), ""),
    ("chA", "Private channel (A)", None, "Optional: this channel instead of the one in the phone setup above. Fetched, never in the APK."),
)
NUMERIC = ("lead", "burst", "anchored", "autoLoc", "obscure", "shareLocPub", "mapsOnline")


def _settings_from(form, who):
    for k, _, opts, _ in SETTINGS:
        who[k] = form.get(k, "").strip()
    who["features"] = ",".join(k for k, _ in FEATURES if ("f_" + k) in form)


def _settings_to(o, who):
    """Puts the chosen settings into a setup code (dict); "" means the phone chooses."""
    for k, _, _, _ in SETTINGS:
        v = str(who.get(k, "") or "")
        if v:
            o[k] = int(v) if k in NUMERIC and v.lstrip("-").isdigit() else v
    if who.get("features"):
        o["features"] = who["features"]
        o["menus"] = who["features"]
    if who.get("cloud"):
        o["cloud"] = who["cloud"]


def _settings_rows(e, who):
    rows = []
    for k, label, opts, help_ in SETTINGS:
        v = str(who.get(k, "") or "")
        field = _select(k, opts, v) if opts else "<input name=%s value='%s' size=30 autocomplete=off>" % (k, e(v))
        rows.append("<tr><td>%s</td><td style='white-space:normal'>%s%s</td></tr>" % (e(label), field, ("<br><span class=muted>%s</span>" % e(help_)) if help_ else ""))
    on = set((who.get("features") or "").split(","))
    rows.append("<tr><td>Advanced and Region features</td><td style='white-space:normal'>%s</td></tr>" % " ".join(
        "<label style='display:inline-block;min-width:200px'><input type=checkbox name=f_%s%s> %s</label>" % (k, " checked" if k in on else "", e(lab))
        for k, lab in FEATURES))
    return "".join(rows)


# ---- what phones fetch (no login: the token is the key) ----

def site_setup(setup_mod, user, password):
    """Stage 2 for the site APK: the Phone setup code, without the store login and channel A unless allowed."""
    s = load().get("site", {})
    if not s.get("on"):
        return None
    code = setup_mod.payload(setup_mod.load(), user, password)
    o = json.loads(code[len(setup_mod.PREFIX):])
    _settings_to(o, s)
    if not s.get("login"):
        for k in ("pass", "chA", "defaults"):  # the defaults need the login
            o.pop(k, None)
    return setup_mod.PREFIX + json.dumps(o, separators=(",", ":"))


def person_setup(token, setup_mod, user, password):
    """Stage 2 for a personal APK: (status, text). 404 unknown or revoked, 410 already set up (re-setup off)."""
    if not valid_token(token):
        return 404, None
    with _lock:
        d = load()
        p = d["people"].get(token)
        if not p:
            return 404, None
        if p.get("fetched") and not p.get("resetup", True):
            return 410, None
        code = setup_mod.payload(setup_mod.load(), user, password)
        o = json.loads(code[len(setup_mod.PREFIX):])
        o["name"] = p.get("site") or o.get("name", "")
        _settings_to(o, p)
        if p.get("call"):
            o["call"] = p["call"]
            o["nameLock"] = bool(p.get("nameLock"))
        o["update"] = d.get("url", "") + "/Personal/ycc-%s.apk" % token
        p["fetched"] = int(time.time())
        p["fetches"] = int(p.get("fetches", 0)) + 1
        save(d)
        return 200, setup_mod.PREFIX + json.dumps(o, separators=(",", ":"))


def public_file(path):
    """True for the stamped APKs anyone may download: the site APK, and a personal APK of a current token."""
    if path == site_path():
        return load().get("site", {}).get("on", False)
    folder, name = os.path.split(path)
    if folder != os.path.join(_ycc, "Personal") or not (name.startswith("ycc-") and name.endswith(".apk")):
        return False
    token = name[4:-4]
    return valid_token(token) and token in load()["people"]


# ---- the web admin (below the Phone setup code) ----

def _select(name, options, value):
    return "<select name=%s>%s</select>" % (name, "".join(
        "<option value='%s'%s>%s</option>" % (k, " selected" if k == value else "", lab) for k, lab in options))


def tab(out, e, hidden, confirm, when, host, setup_url, qr, q=None):
    q = q or {}
    d = load()
    url = setup_url or d.get("url") or "http://%s/YCC" % host
    have_base = os.path.isfile(_baseline())
    out.append("<div class=card><h2>Ready-made APKs</h2><p>Instead of scanning the code above, a phone can install a "
               "stamped copy of the published app (Updates tab) that already knows this server: it installs and updates "
               "like the plain one, and on first start fetches its setup from here (the settings above plus the ones "
               "chosen below) and asks to apply it. Private channel keys are never put in the APK: the phone fetches them "
               "from here the first time (on this network). The APKs use the storage address above (%s) and are made "
               "again by themselves within a minute of a new app being published.</p>%s</div>"
               % (e(url), "" if have_base else "<p class=warn>Publish the app on the Updates tab first.</p>"))
    s = d.get("site", {})
    out.append("<div class=card><h2>Site APK</h2><p>One APK for anyone at this site. Download it without a login: "
               "<a href='/YCC/Updates/YCC-site.apk'>YCC-site.apk</a>%s.</p>" % ("" if s.get("on") else " (turned off)"))
    if s.get("on"):
        out.append("<p style='background:#fff;display:inline-block;padding:6px;border-radius:6px'>%s</p>"
                   "<p class=muted>Phones can scan this with their camera to download it.</p>" % _qr(qr, url + "/Updates/YCC-site.apk", 4))
    out.append("<form method=post%s>%s<table><tr><td>Offer it</td><td style='white-space:normal'><label><input type=checkbox name=on%s> Make the "
               "site APK (named after the Network name above)</label></td></tr>%s%s"
               "<tr><td>Login</td><td style='white-space:normal'><label><input type=checkbox name=login%s> Include the store login and channel A in "
               "its setup</label><br><span class=warn>Anyone on this network can download the site APK, so this gives "
               "them the store password and channel A. Off: phones get the address and ask for the password.</span></td></tr>"
               "</table><p><button>Save site APK</button></p></form></div>"
               % (confirm("Save the site APK settings?"), hidden(a="prov_site", tab="setup"), " checked" if s.get("on") else "",
                  _settings_rows(e, s), _key_rows(e, s), " checked" if s.get("login") else ""))
    # Personal APKs: a new one, or one being edited or copied (?edit=<token> / ?copy=<token>)
    edit, copy = q.get("edit", ""), q.get("copy", "")
    base = d["people"].get(edit) or d["people"].get(copy) or {"role": "phone", "nameLock": True, "resetup": True}
    editing = edit in d["people"]
    title = ("Edit %s" % base.get("call", "")) if editing else ("Copy of %s: give it a new callsign" % base.get("call", "")) if copy in d["people"] else "New personal APK"
    out.append("<div class=card><h2>Personal APKs</h2><p>One APK per person, with their callsign stamped in and a long "
               "random code in its file name and update address, so it can't be guessed. On first start the phone fetches "
               "that person's setup from here and asks \"Set up as CALLSIGN?\". Revoke a person to stop their code working "
               "(their APK is deleted).</p><h3 id=person>%s</h3><form method=post%s>%s<table>"
               "<tr><td>Callsign</td><td style='white-space:normal'><input name=call required size=16 value='%s'> <label><input type=checkbox "
               "name=nameLock%s> Lock it</label></td></tr>%s%s<tr><td>Re-setup</td><td style='white-space:normal'><label><input type=checkbox "
               "name=resetup%s> Allow setting up again (after a wipe or a new phone)</label></td></tr></table>"
               "<p><button class=big>%s</button>%s</p></form>"
               % (e(title), confirm("Save this personal APK?"),
                  hidden(a="prov_save", tab="setup", token=edit) if editing else hidden(a="prov_add", tab="setup"),
                  e(base.get("call", "")) if editing else "", " checked" if base.get("nameLock", True) else "",
                  _settings_rows(e, base), _key_rows(e, base), " checked" if base.get("resetup", True) else "",
                  "Save and stamp again" if editing else "Make personal APK",
                  " <a href='/webadmin?tab=setup#person'>cancel</a>" if (editing or copy) else ""))
    people = sorted(d["people"].items(), key=lambda kv: kv[1].get("call", ""))
    if people:
        out.append("<table class=list><tr><th>Callsign</th><th>Role</th><th>Made</th><th>Set up</th><th>APK</th><th></th></tr>")
        for token, p in people:
            link = "%s/Personal/ycc-%s.apk" % (url, token)
            out.append("<tr><td>%s%s</td><td style='white-space:normal'>%s</td><td style='white-space:normal'>%s</td><td style='white-space:normal'>%s</td><td style='white-space:normal'><a href='/YCC/Personal/ycc-%s.apk'>download</a>"
                       "<details><summary>QR</summary><div style='background:#fff;display:inline-block;padding:4px'>%s</div>"
                       "<br><span class=muted>%s</span></details></td><td style='white-space:normal'>"
                       "<a href='/webadmin?tab=setup&edit=%s#person'>Edit</a> <a href='/webadmin?tab=setup&copy=%s#person'>Copy</a> "
                       "<form method=post%s style=display:inline>%s<button>New code</button></form> "
                       "<form method=post%s style=display:inline>%s<button>%s</button></form> "
                       "<form method=post%s style=display:inline>%s<button class=d>Revoke</button></form></td></tr>"
                       % (e(p.get("call", "")), " (locked)" if p.get("nameLock") else "", e(dict(ROLES).get(p.get("role", ""), "")),
                          when(p.get("made", 0)), (when(p["fetched"]) + " (%dx)" % p.get("fetches", 1)) if p.get("fetched") else "not yet",
                          token, _qr(qr, link, 3), e(link), token, token,
                          confirm("Give %s a new code? Their old APK and update address stop working." % p.get("call", "")),
                          hidden(a="prov_regen", tab="setup", token=token),
                          confirm("Change whether %s can set up again?" % p.get("call", "")),
                          hidden(a="prov_resetup", tab="setup", token=token),
                          "Re-setup: on" if p.get("resetup", True) else "Re-setup: off",
                          confirm("Revoke %s? Their APK is deleted and their code stops working." % p.get("call", "")),
                          hidden(a="prov_revoke", tab="setup", token=token)))
        out.append("</table>")
    out.append("</div>")


def _key_rows(e, who):
    """Ticks for the keys that may go in the APK itself (Public on by default, a cloud group only if ticked)."""
    c = who.get("cloud") or {}
    code = ("YCCCLOUD1|" + json.dumps(c, separators=(",", ":"))) if c else ""
    return ("<tr><td>Cloud group</td><td style='white-space:normal'><input name=cloud value='%s' size=40 placeholder='YCCCLOUD1|... or ycc://cloud/... "
            "(Cloud group > Share)'><br><span class=muted>Optional: joined when the phone sets up (fetched).</span></td></tr>"
            "<tr><td>In the APK itself</td><td style='white-space:normal'><label><input type=checkbox name=embedPub%s> The Public channel key (from the "
            "phone setup above)</label><br><label><input type=checkbox name=embedCloud%s> The cloud group</label>"
            "<br><span class=warn>Keys in the APK stay in the installed app even after the app's own wipe (until it is "
            "uninstalled). Private channel keys never go in the APK: they come from this server the first time.</span>"
            "</td></tr>" % (e(code), " checked" if who.get("embedPub", True) else "", " checked" if who.get("embedCloud") else ""))


def _qr(qr, text, scale):
    try:
        return qr.svg(text, scale=scale)
    except ValueError:
        return ""


def _keys_from(form, who):
    who["embedPub"] = "embedPub" in form
    who["embedCloud"] = "embedCloud" in form
    text = form.get("cloud", "").strip()
    if text:
        c = parse_cloud(text)
        if c is None:
            return "Not saved: that cloud group code could not be read (paste the YCCCLOUD1|... text or the ycc://cloud/ link)."
        who["cloud"] = c
    elif "cloud" in who:
        del who["cloud"]
    if who["embedCloud"] and not who.get("cloud"):
        who["embedCloud"] = False
    return None


def _person_from(form, p):
    p["call"] = form.get("call", "").strip().upper()
    p["nameLock"] = "nameLock" in form
    p["resetup"] = "resetup" in form
    _settings_from(form, p)
    return _keys_from(form, p)


def act(form, setup_url):
    a = form.get("a", "")
    d = load()
    d["url"] = setup_url or d.get("url", "")
    if not d["url"]:
        return "Set the storage address in the phone setup above first."
    if a == "prov_site":
        s = {"on": "on" in form, "name": _site_name(), "login": "login" in form}
        _settings_from(form, s)
        bad = _keys_from(form, s)
        if bad:
            return bad
        d["site"] = s
        save(d)
        return "Site APK saved. " + build_all(d)
    if a == "prov_add":
        p = {"made": int(time.time()), "site": _site_name()}
        bad = _person_from(form, p)
        if bad:
            return bad
        if not p["call"]:
            return "Not made: enter a callsign."
        token = new_token()
        d["people"][token] = p
        save(d)
        return "Personal APK for %s: ycc-%s.apk. " % (p["call"], token) + build_all(d)
    token = form.get("token", "")
    p = d["people"].get(token)
    if not p:
        return "That person is not on the list any more."
    if a == "prov_save":
        bad = _person_from(form, p)
        if bad:
            return bad
        if not p["call"]:
            return "Not saved: enter a callsign."
        p["site"] = _site_name()
        save(d)
        return "Saved %s. " % p["call"] + build_all(d)
    if a == "prov_revoke":
        del d["people"][token]
        save(d)
        if os.path.isfile(personal_path(token)):
            os.remove(personal_path(token))
        return "Revoked %s: the APK is deleted and the code no longer works." % p.get("call", "")
    if a == "prov_regen":
        new = new_token()
        d["people"][new] = dict(p, made=int(time.time()), fetched=0, fetches=0)
        del d["people"][token]
        save(d)
        if os.path.isfile(personal_path(token)):
            os.remove(personal_path(token))
        return "New code for %s. " % p.get("call", "") + build_all(d)
    if a == "prov_resetup":
        p["resetup"] = not p.get("resetup", True)
        save(d)
        return "%s: setting up again is now %s." % (p.get("call", ""), "allowed" if p["resetup"] else "refused once set up")
    return "Unknown action."
