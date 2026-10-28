"""Phone setup QR code for the web admin page (the "Phone setup" tab).

A phone scans it (Settings > Scan QR) and gets this store's address and login, the remote tunnel
server, the Public channel key and optionally one private channel. It checks the login works, then
loads the network defaults and DRYAD sheets from here. Saved in <store>/.ycc_setup.json (hidden, never
served), because the QR code holds the login.

QR text: "YCCSETUP1|" + JSON {"name", "url", "user", "pass", "tunnel", "tkey", "pub", "chA", "omg", "omh",
"home", "defaults"} (empty fields left out; "omg" = OpenMANET group (turns OpenMANET on), "omh" = its node,
"home" = home WiFi SSID (turns home network sync on); "defaults": 1 = load the network defaults first).
Phones make the same code (Settings > Phone setup QR code).
"""
import json
import os

import qr

PREFIX = "YCCSETUP1|"
FIELDS = (  # name, label, input type, help
    ("name", "Network name", "text", "Shown on the phone when it scans, e.g. the group's name."),
    ("url", "Storage address", "text", "How phones reach this store, ending in /YCC."),
    ("user", "User", "text", "The store login (Login tab)."),
    ("pass", "Password", "password", "Leave empty to use the current login's password."),
    ("tunnel", "Remote tunnel server", "text", "host:port of the tunnel relay, for phones away from the MANET. Optional."),
    ("tkey", "Tunnel key", "text", "The tunnel's shared key. Optional."),
    ("pub", "Public channel key", "text", "The group's Public channel key. Empty keeps each phone's own."),
    ("chA", "Private channel (A)", "text", "A channel name set as channel A. Optional: anyone with the QR code can use it."),
    ("omg", "OpenMANET group", "text", "Turns on OpenMANET support with this group name. Empty leaves OpenMANET as it is."),
    ("omh", "OpenMANET node", "text", "The OpenMANET node's address, e.g. 10.41.0.1. Optional."),
    ("home", "Home WiFi name", "text", "Turns on the home network sync for this WiFi (SSID). Optional."),
)

_file = None


def init(root):
    global _file
    _file = os.path.join(root, ".ycc_setup.json")


def load():
    try:
        with open(_file, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def save(d):
    tmp = _file + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, _file)


def erase():
    if _file and os.path.isfile(_file):
        os.remove(_file)


def payload(d, user, password):
    out = {}
    for k, _, _, _ in FIELDS:
        v = str(d.get(k, "") or "").strip()
        if k == "user" and not v:
            v = user
        if k == "pass" and not v and str(d.get("user", "") or user) == user:
            v = password
        if v:
            out[k] = v
    if d.get("defaults", True):
        out["defaults"] = 1
    return PREFIX + json.dumps(out, separators=(",", ":"))


def tab(out, e, hidden, confirm, host, user, password):
    d = load()
    if not d.get("url"):
        d = dict(d, url="http://%s/YCC" % host)
    out.append("<div class=card><h2>Phone setup QR code</h2><p>New phones scan this (Settings &gt; Scan QR) to "
               "join (the setup wizard asks for it after the callsign): it sets this store and its login, the tunnel "
               "server, the Public channel, optionally one private channel, OpenMANET and the home WiFi, checks the login works, then loads the network defaults and DRYAD sheets from the "
               "Defaults tab.</p><div class=warn>The QR code holds the store's password (and the private channel "
               "name, if set). Only show it to people who should have them.</div>")
    try:
        out.append("<p style='background:#fff;display:inline-block;padding:6px;border-radius:6px'>%s</p>"
                   % qr.svg(payload(d, user, password), scale=5))
    except ValueError as ex:
        out.append("<p class=warn>%s</p>" % e(ex))
    out.append("<p class=muted>Print this page, or show it on a screen, for phones to scan.</p></div>")
    out.append("<div class=card><h2>What it holds</h2><form method=post class=b%s>%s<table>"
               % (confirm("Save these phone setup details?"), hidden(a="setup_save", tab="setup")))
    for k, label, typ, help_ in FIELDS:
        out.append("<tr><td>%s</td><td class=w><input type=%s name=%s value='%s' size=40 autocomplete=off>"
                   "<br><span class=muted>%s</span></td></tr>"
                   % (e(label), typ, k, e(d.get(k, "")) if k != "pass" else "", e(help_)))
    out.append("<tr><td>Defaults</td><td><label><input type=checkbox name=defaults%s> Load the network defaults and "
               "DRYAD sheets after setting up</label></td></tr></table>"
               "<p class=muted>The password is never shown again here. To keep the saved one, leave it empty "
               "(it then follows the current login).</p><p><button class=big>Save</button></p></form></div>"
               % (" checked" if d.get("defaults", True) else ""))


def act(form):
    d = load()
    for k, _, _, _ in FIELDS:
        v = form.get(k, "").strip()
        if k == "pass" and not v:
            continue  # keep the saved one (or follow the login)
        d[k] = v
    d["defaults"] = "defaults" in form
    if d.get("url") and not d["url"].startswith(("http://", "https://")):
        return "Not saved: the storage address must start with http:// or https://."
    save(d)
    return "Phone setup saved; the QR code is updated."
