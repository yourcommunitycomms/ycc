"""The admin page's Bridge tab: settings and live state of the YCC bridge (bridge/ycc_bridge.py).

The bridge runs on the host computer (it must hear the WiFi), not in this container. The two talk
through the shared store folder: this tab writes <store>/.ycc_bridge.json, the bridge writes
<store>/.ycc_bridge_status.json. Remote people (tunnel comms) each get a port and a DRYAD row as key,
picked from the sheets this store holds, and a phone setup QR code with both.
"""
import glob
import json
import os
import time

import qr
import setup

DEFAULTS = {"on": True, "lan_broadcast": "auto", "client_port": 47400, "app_port": 47474, "public_host": "",
            "remotes": []}
_root = None


def init(root):
    global _root
    _root = root


def _cfg_path():
    return os.path.join(_root, ".ycc_bridge.json")


def load():
    cfg = dict(DEFAULTS)
    try:
        with open(_cfg_path(), encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def save(cfg):
    tmp = _cfg_path() + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, _cfg_path())


def status():
    try:
        with open(os.path.join(_root, ".ycc_bridge_status.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def erase():
    for n in (".ycc_bridge.json", ".ycc_bridge_status.json"):
        p = os.path.join(_root, n)
        if os.path.isfile(p):
            os.remove(p)


# ---- DRYAD rows held on this store ----

def _parse_sheets(text, found):
    name, rows = None, {}

    def done():
        if name and len(rows) == 26:
            found.setdefault(name, rows)

    for line in (text or "").splitlines():
        if line.startswith("COMMUNITY-COMMS-DRYAD"):
            done()
            name, rows = "Sheet %d" % (len(found) + 1), {}
        elif line.upper().startswith("NAME:"):
            name = line[5:].strip() or name
        elif len(line) > 3 and line[1] == ":" and "A" <= line[0] <= "Z":
            rows[line[0]] = line[2:].replace(" ", "")
    done()


def sheets():
    """{sheet name: {row letter: row text}} from the store's DRYAD file, defaults and phone backups."""
    found = {}
    ycc = os.path.join(_root, "YCC")
    try:
        with open(os.path.join(ycc, "DRYAD", "sheets.txt"), encoding="utf-8") as f:
            _parse_sheets(f.read(), found)
    except OSError:
        pass
    for p in sorted(glob.glob(os.path.join(ycc, "Configs", "*.ucconfig"))):
        try:
            with open(p, encoding="utf-8") as f:
                _parse_sheets(json.load(f).get("sheets", ""), found)
        except (OSError, ValueError, AttributeError):
            pass
    return found


# ---- page ----

def _alive(st):
    return st is not None and st.get("running") and time.time() - st.get("time", 0) < 15


def tab(out, q, e, hidden, confirm, when, store_url):
    cfg, st = load(), status()
    out.append("<div class=card><h2>Bridge</h2><p>The bridge joins the group's WiFi to devices that cannot hear its "
               "broadcasts (a commander tablet in Waydroid or an emulator on this computer), and is the <b>tunnel relay</b> "
               "for remote people: phones away from the group reach it over the internet through a port forwarded on "
               "your router. It never decrypts anything.</p>")
    if st is None:
        out.append("<div class=warn>The bridge service has never run on this computer. Install it once on the host "
                   "(not in Docker): <code>sudo sh bridge/install-bridge.sh</code> in the server folder, see NOMAD-SETUP.md.</div>")
    elif not _alive(st):
        out.append("<div class=warn>The bridge is not running%s (last report %s).</div>"
                   % (": " + e(st.get("problem")) if st.get("problem") else "", when(st.get("time", 0))))
    else:
        c = st.get("counts", {})
        out.append("<p><b>Running</b> (version %s), broadcasting on %s:%s. Devices linked here: %s. Packets: %d from the "
                   "WiFi, %d sent to it, %d from linked devices, %d from remote people.%s</p>"
                   % (e(st.get("version")), e(st.get("broadcast")), e(st.get("app_port")),
                      e(", ".join(st.get("clients") or []) or "none"), c.get("lan_in", 0), c.get("lan_out", 0),
                      c.get("client_in", 0), c.get("remote_in", 0),
                      " <span class=warn>%s</span>" % e(st["problem"]) if st.get("problem") else ""))
    out.append("<form method=post class=b%s>%s<p><label><input type=checkbox name=on%s> Run the bridge (it also starts "
               "by itself when the computer starts)</label></p><table>"
               "<tr><td>WiFi broadcast address</td><td><input name=lan_broadcast value='%s' size=18> <span class=muted>"
               "auto = the main network's, or e.g. 192.168.1.255</span></td></tr>"
               "<tr><td>Port for linked devices</td><td><input type=number name=client_port value=%d style='width:90px'> "
               "<span class=muted>their Extra peers: this computer:%d (Waydroid: 192.168.240.1:%d, emulator: 10.0.2.2:%d)"
               "</span></td></tr>"
               "<tr><td>App port</td><td><input type=number name=app_port value=%d style='width:90px'> <span class=muted>"
               "the phones' network port (47474 unless changed in every phone)</span></td></tr></table>"
               "<p><button>Save</button></p></form></div>"
               % (confirm("Save the bridge settings? It restarts with them."), hidden(a="bridge_save", tab="bridge"),
                  " checked" if cfg.get("on") else "", e(cfg.get("lan_broadcast", "auto")), int(cfg["client_port"]),
                  int(cfg["client_port"]), int(cfg["client_port"]), int(cfg["client_port"]), int(cfg["app_port"])))
    _gateway_card(out, q, e, hidden, confirm, cfg, st)

    live = {r["port"]: r for r in (st or {}).get("remotes", [])} if _alive(st) else {}
    out.append("<div class=card><h2>Remote people (tunnel)</h2><p>Each person gets their own port and a DRYAD row as "
               "their key. On your router, forward that port (UDP and TCP) to this computer. They set it up by scanning "
               "their QR code, then turn the tunnel on (Settings &gt; Tunnel comms) when away.</p><p class=muted><b>Address they "
               "use:</b> empty = the internet address above. Set one for a person who reaches this server another way, e.g. "
               "over a VPN (this server's VPN address) or through a different router.</p>")
    rem = cfg.get("remotes", [])
    if q.get("qr"):
        _qr_card(out, e, cfg, q.get("qr"), store_url)
    if q.get("key"):
        _key_card(out, e, cfg, q.get("key"))
    if rem:
        out.append("<table><tr><th>Person</th><th>Port</th><th>Address they use</th><th>Key</th><th>Now</th><th></th></tr>")
        for r in rem:
            lv = live.get(int(r["port"]))
            now = lv["state"] if lv else ("off" if not r.get("on", True) else "-")
            out.append("<tr><td>%s</td><td class=n>%d</td><td><form method=post>%s<input name=host value='%s' size=18 "
                       "placeholder='%s'> <button>Set</button></form></td><td>%s</td><td class=w>%s</td><td>"
                       "<a href='/webadmin?tab=bridge&qr=%d#qr'>QR code</a> <a href='/webadmin?tab=bridge&key=%d#key'>Show key</a> "
                       "<form method=post>%s<button>%s</button></form> "
                       "<form method=post%s>%s<button class=d>Remove</button></form></td></tr>"
                       % (e(r.get("label")), int(r["port"]), hidden(a="bridge_rhost", tab="bridge", port=r["port"]),
                          e(r.get("host", "")), e(cfg.get("public_host") or "internet address"),
                          e(r.get("key_name", "pasted row")), e(now), int(r["port"]), int(r["port"]),
                          hidden(a="bridge_toggle", tab="bridge", port=r["port"]), "Turn off" if r.get("on", True) else "Turn on",
                          confirm("Remove %s? Their phone can no longer use the tunnel." % r.get("label")),
                          hidden(a="bridge_remove", tab="bridge", port=r["port"])))
        out.append("</table>")
    else:
        out.append("<p class=muted>Nobody yet.</p>")
    used = {int(r["port"]) for r in rem}
    nxt = next(p for p in range(9991, 65535) if p not in used)
    opts = []
    for sname, rows in sorted(sheets().items()):
        for letter in sorted(rows):
            opts.append("<option value='%s|%s'>%s, row %s</option>" % (e(sname), letter, e(sname), letter))
    out.append("<h3>Add a person</h3><form method=post class=b>%s<p>Name <input name=label required size=16> "
               "Port <input type=number name=port value=%d min=1025 max=65535 style='width:90px'> "
               "Address they use <input name=host size=18 placeholder='(the internet address)'></p>"
               "<p>Key: <select name=sheetrow><option value=''>(paste a row instead)</option>%s</select> or row "
               "<input name=row size=32 placeholder='28 letters and symbols'></p>"
               "<p class=muted>Use a row nobody else uses. Sheets come from this store's DRYAD file, defaults and phone backups.</p>"
               "<p><button>Add</button></p></form></div>"
               % (hidden(a="bridge_add", tab="bridge"), nxt, "".join(opts)))


def _gateway_card(out, q, e, hidden, confirm, cfg, st):
    """External gateway: the internet address remote people use, this computer's LAN addresses, forwarding."""
    host = cfg.get("public_host") or ""
    lan = (st or {}).get("lan") or []
    out.append("<div class=card id=gateway><h2>External gateway (for remote people)</h2>")
    if not host:
        out.append("<div class=warn><b>Needed for the tunnel:</b> remote people connect to your router's internet (WAN) "
                   "address, which goes in their QR code. Until it is set, their QR codes cannot be shown.</div>")
    out.append("<h3>This computer on the LAN</h3>")
    if lan:
        out.append("<table><tr><th>Network</th><th>Address</th><th>Broadcast</th></tr>%s</table>"
                   % "".join("<tr><td>%s</td><td>%s/%s</td><td>%s</td></tr>" % (e(n["dev"]), e(n["ip"]), e(n["prefix"]),
                                                                                e(n.get("brd", ""))) for n in lan))
        forward_to = next((n["ip"] for n in lan if not n["ip"].startswith("192.168.240.")), lan[0]["ip"])
    else:
        out.append("<p class=muted>Shown once the bridge service runs (it reports the host's networks).</p>")
        forward_to = "this computer's LAN address"
    ports = ", ".join(str(r["port"]) for r in cfg.get("remotes", [])) or "each person's port"
    out.append("<p>On your router, forward <b>%s</b> (UDP and TCP) to <b>%s</b>.</p>" % (e(ports), e(forward_to)))
    wan = cfg.get("wan_found", "")
    out.append("<h3>Internet address</h3><form method=post class=b%s>%s<p><input name=public_host value='%s' size=30 "
               "placeholder='e.g. 203.0.113.7 or mygroup.duckdns.org'> <button>Save</button></p></form>"
               "<p><form method=post>%s<button>Find my WAN address</button></form> <span class=muted>(asks api.ipify.org "
               "over the internet; a DDNS name is better if your provider changes the address)</span></p>"
               % (confirm("Use this internet address in the remote people's QR codes?"), hidden(a="bridge_host", tab="bridge"),
                  e(wan or host), hidden(a="bridge_wan", tab="bridge")))
    if wan and wan != host:
        out.append("<p class=muted>Found %s: press Save above to use it.</p>" % e(wan))
    out.append("</div>")


def wan_address():
    """This network's public address as the internet sees it, or raises OSError."""
    import urllib.request
    with urllib.request.urlopen("https://api.ipify.org", timeout=10) as r:
        ip = r.read(64).decode("ascii", "replace").strip()
    if not ip or any(c not in "0123456789abcdef.:" for c in ip.lower()):
        raise OSError("unexpected answer")
    return ip


def _key_card(out, e, cfg, port):
    r = next((x for x in cfg.get("remotes", []) if str(x["port"]) == str(port)), None)
    if r is None:
        return
    out.append("<div class=card id=key><h3>Key for %s</h3><p>%s. Their phone needs exactly this row "
               "(Settings &gt; Tunnel comms &gt; key), or scan their QR code.</p>"
               "<p><input id=rowtext value='%s' size=34 readonly style='font-family:ui-monospace,monospace'> "
               "<button type=button onclick=\"var t=document.getElementById('rowtext');t.select();"
               "try{navigator.clipboard.writeText(t.value)}catch(x){document.execCommand('copy')}\">Copy</button></p>"
               "<p class=muted>The row is a secret, like a password. Share it only with them.</p></div>"
               % (e(r.get("label")), e(r.get("key_name", "Pasted row")), e(r["row"])))


def _qr_card(out, e, cfg, port, store_url):
    r = next((x for x in cfg.get("remotes", []) if str(x["port"]) == str(port)), None)
    if r is None:
        return
    host = r.get("host") or cfg.get("public_host") or ""
    d = {"name": "Remote: " + r.get("label", ""), "tunnel": "%s:%d" % (host, int(r["port"])) if host else "",
         "tkey": r["row"]}
    text = setup.PREFIX + json.dumps({k: v for k, v in d.items() if v}, separators=(",", ":"))
    out.append("<div class=card id=qr><h3>QR code for %s</h3>" % e(r.get("label")))
    if not host:
        out.append("<div class=warn>Set the <b>internet address</b> in External gateway above first (your router's public "
                   "address or a DDNS name). Without it the code cannot say where to connect, so no QR code is shown. "
                   "<a href='#gateway'>Go there</a>.</div></div>")
        return
    out.append("<p style='background:#fff;display:inline-block;padding:6px;border-radius:6px'>%s</p>"
               "<p class=muted>Holds only their tunnel address and key. A new phone first scans the normal Phone "
               "setup code, then this one (Settings &gt; Scan QR). Show it only to them.</p></div>" % qr.svg(text, scale=5))


def act(form, sheet_rows=None):
    cfg = load()
    a = form.get("a")
    if a == "bridge_save":
        try:
            cp, ap = int(form.get("client_port") or 47400), int(form.get("app_port") or 47474)
        except ValueError:
            return "Not saved: ports must be numbers."
        cfg.update(on="on" in form, lan_broadcast=form.get("lan_broadcast", "auto").strip() or "auto", client_port=cp,
                   app_port=ap)
        save(cfg)
        return "Bridge settings saved; it restarts with them within a few seconds."
    if a == "bridge_add":
        label = form.get("label", "").strip()
        try:
            port = int(form.get("port"))
        except (TypeError, ValueError):
            return "Not added: the port must be a number."
        row, key_name = form.get("row", "").strip(), "pasted row"
        if form.get("sheetrow"):
            sname, _, letter = form["sheetrow"].rpartition("|")
            row = sheets().get(sname, {}).get(letter, "")
            key_name = "%s, row %s" % (sname, letter)
        if not label or not row or not 1024 < port < 65536:
            return "Not added: needs a name, a port above 1024 and a key."
        if any(int(r["port"]) == port for r in cfg["remotes"]) or port in (cfg["client_port"], cfg["app_port"]):
            return "Not added: port %d is already used." % port
        if any(r["row"] == row for r in cfg["remotes"]):
            return "Not added: that row is already someone's key."
        cfg["remotes"].append({"label": label, "port": port, "row": row, "key_name": key_name, "on": True,
                               "host": form.get("host", "").strip()})
        save(cfg)
        return "Added %s on port %d. Forward that port (UDP and TCP) to this computer, then show them their QR code." % (label, port)
    if a == "bridge_rhost":
        hit = [r for r in cfg["remotes"] if str(r["port"]) == str(form.get("port"))]
        if not hit:
            return "Unknown person."
        hit[0]["host"] = form.get("host", "").strip()
        save(cfg)
        return "%s now uses %s." % (hit[0]["label"], hit[0]["host"] or "the internet address")
    if a == "bridge_host":
        cfg["public_host"] = form.get("public_host", "").strip()
        save(cfg)
        return ("Internet address set to %s; QR codes now hold it." % cfg["public_host"]) if cfg["public_host"] else "Internet address cleared."
    if a == "bridge_wan":
        try:
            cfg["wan_found"] = wan_address()
        except (OSError, ValueError) as ex:
            return "Could not find the WAN address (no internet from this server?): %s" % ex
        save(cfg)
        return "Your WAN address is %s. Check it in the box, then press Save." % cfg["wan_found"]
    if a in ("bridge_remove", "bridge_toggle"):
        port = str(form.get("port"))
        hit = [r for r in cfg["remotes"] if str(r["port"]) == port]
        if not hit:
            return "Unknown person."
        if a == "bridge_remove":
            cfg["remotes"] = [r for r in cfg["remotes"] if str(r["port"]) != port]
            msg = "Removed %s." % hit[0]["label"]
        else:
            hit[0]["on"] = not hit[0].get("on", True)
            msg = "%s turned %s." % (hit[0]["label"], "on" if hit[0]["on"] else "off")
        save(cfg)
        return msg
    return "Unknown action."
