package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.communitycomms.app.net.UbusApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Mesh controller > More > "HT-HD01 v2 mods": per-node CPU / RF savings for Heltec HT-HD01 V2 nodes. Pick a node from
 * the drop-down (every HD01 in the list), its current state is read, and each mod is a switch. Everything goes through
 * the calls LuCI itself is allowed on these nodes: luci init actions, uci, /etc/rc.local start-up blocks, /sbin/wifi.
 */
public final class HdMods {
    private HdMods() {
    }

    static final String QUIET = "hd01-quiet", OMD_LOCK = "omd-lock";
    static final String OMD_LOCK_BODY = "pidof openmanetd >/dev/null || { [ -d /etc/openmanetd/openmanetd.db.lock ] && rmdir "
            + "/etc/openmanetd/openmanetd.db.lock 2>/dev/null && logger -t ycc-hd01 \"cleared a stale openmanetd database lock before it starts\"; }";

    /** Adds (or removes) the start-up step that clears a stale openmanetd lock. */
    static void omdGuard(UbusApi u, boolean on) throws Exception {
        String rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
        if (rc == null) rc = "#!/bin/sh\nexit 0\n";
        u.call("file", "write", new JSONObject().put("path", CustomizeOm.RCLOCAL).put("data", on ? withBlock(rc, OMD_LOCK, OMD_LOCK_BODY) : withoutBlock(rc, OMD_LOCK)));
    }

    /** What a status read found on one node. */
    static final class State {
        String board = "", error, info = "";
        int wifiMode = -1; // CustomizeOm.MODE_*
        boolean halowToo, quiet, quiet2, ntp, dns, mdns, halowUp, oldSvc, usb, omdGuard;
        String load = "";
    }

    public static void dialog(final Activity a, final List<CustomizeOm.Target> all, final RoLogin.Login root) {
        final float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        box.addView(text(a, "Power, CPU and RF savings for Heltec HT-HD01 V2 nodes, one node at a time. The state is read when you "
                + "pick a node; each switch asks before it changes anything."));
        final Spinner pick = new Spinner(a);
        box.addView(pick);
        LinearLayout r0 = new LinearLayout(a);
        final Button check = btn(a, r0, "Check current status");
        final Button rescan = btn(a, r0, "Find HD01 nodes");
        box.addView(r0);
        final TextView status = text(a, "Looking for HT-HD01 nodes in the list…");
        status.setTextSize(15);
        status.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        box.addView(status);

        // WiFi mode
        box.addView(head(a, "WiFi mode"));
        final RadioGroup modes = new RadioGroup(a);
        final RadioButton mN = radio(a, modes, 0x7d0001, "Normal: WiFi and Ethernet both on (bridged)");
        final RadioButton mP = radio(a, modes, 0x7d0002, "Ethernet preferred: WiFi off if the LAN cable is in at start-up (from the next restart)");
        final RadioButton mO = radio(a, modes, 0x7d0003, "Ethernet only: WiFi switched off in its settings, stays off");
        box.addView(modes);
        final CheckBox halowToo = new CheckBox(a);
        halowToo.setText("Ethernet preferred: HaLow off too (the node leaves the mesh while cabled; restart to undo)");
        box.addView(halowToo);
        LinearLayout r1 = new LinearLayout(a);
        final Button applyMode = btn(a, r1, "Apply WiFi mode");
        box.addView(r1);

        // switches
        box.addView(head(a, "Savings (switch on = saving on)"));
        final Switch sQuiet = sw(a, box, "LED and button monitor off 3 minutes after start-up",
                "Heltec's own scripts poll the button and set the LED every fraction of a second. On: 3 minutes after each start "
                        + "they are stopped and the LED goes dark. The button then does nothing until the next restart (no "
                        + "factory reset, no Ethernet/USB switch, no setup mode), and Heltec setup-page changes apply at the next restart.");
        final Switch sNtp = sw(a, box, "Time sync (NTP) off (not recommended)",
                "NOT RECOMMENDED: may cause unexpected problems. On: the NTP client is stopped and not started at boot. The node's "
                        + "clock then drifts and resets on every restart, which can upset anything time-based: logs, certificates "
                        + "(https), mesh security keys, scheduled jobs. Only worth it where the node never sees an internet time source.");
        final Switch sDns = sw(a, box, "DNS on this node off (DHCP stays)",
                "On: the node stops answering name lookups; phones on its WiFi are told to use the gateway for names instead. "
                        + "DHCP (handing out addresses) keeps working.");
        final Switch sMdns = sw(a, box, "mDNS (NAME.local names) off (not recommended)",
                "NOT RECOMMENDED: may cause unexpected problems. On: avahi is stopped and not started at boot. The node can no "
                        + "longer be reached as NAME.local, YCC's mDNS fallback for finding nodes stops for it, and other OpenMANET "
                        + "or app features that discover devices by name may stop finding it.");

        box.addView(head(a, "Reliability"));
        final Switch sLock = sw(a, box, "Clear a stale openmanetd lock at start-up (recommended)",
                "openmanetd keeps a lock on its database while it runs. If the node loses power or is restarted mid-write, the "
                        + "lock stays behind and openmanetd refuses to start again (\"database is locked\"): no OpenMANET web page, "
                        + "and YCC cannot read the node. On: a start-up step removes a left-over lock before openmanetd starts "
                        + "(only when it is not running).");

        box.addView(head(a, "HaLow (radio silence)"));
        final Switch sHalow = sw(a, box, "HaLow radio off",
                "Turns this node's HaLow mesh radio off now (runtime only: a restart brings it back), with the green / red bar on "
                        + "the home screen. Only for the node this phone is connected to: the phone keeps reaching it over WiFi or "
                        + "Ethernet and can turn HaLow back on. A node reached over the mesh could not be switched back on.");
        final TextView halowWhy = text(a, "");
        halowWhy.setTextColor(0xffffb74d);
        halowWhy.setVisibility(View.GONE);
        box.addView(halowWhy);

        box.addView(head(a, "Port"));
        final Switch sUsb = sw(a, box, "Use the USB port instead of Ethernet (disabled for now)",
                "Heltec's eth/usb setting (the button's short press). Disabled: it appears present, but USB Ethernet does not "
                        + "work with the OpenMANET firmware yet. If a node is found set to USB, this switch can only put it back to Ethernet.");
        sUsb.setEnabled(false);
        final TextView usbWhy = text(a, "? Why is this disabled (tap)");
        usbWhy.setTextColor(0xff90caf9);
        usbWhy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("USB port: disabled for now")
                        .setMessage("Though the eth/usb option appears present on the HT-HD01 V2, USB Ethernet support does not appear "
                                + "to work with the OpenMANET firmware yet.\n\nTested 2026-11-27 with a known data cable to a phone and to a PC: "
                                + "in USB mode neither side saw any USB device (the node's USB controller saw no activity at all), and the "
                                + "Ethernet port kept working anyway. The USB-C port is in practice power only.\n\n"
                                + "So the switch is off for now. If a node is found already set to USB, you can switch it back to Ethernet here.")
                        .setPositiveButton("OK", null).show();
            }
        });
        box.addView(usbWhy);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final Handler ui = new Handler(Looper.getMainLooper());
        final List<CustomizeOm.Target> hd = new ArrayList<CustomizeOm.Target>();
        final boolean[] quietSet = {false};
        final State[] cur = {null};

        final Runnable[] read = new Runnable[1];
        final Switch[] sws = {sQuiet, sNtp, sDns, sMdns, sUsb, sHalow, sLock};
        final View[] controls = {applyMode, sQuiet, sNtp, sDns, sMdns, sLock, mN, mP, mO, halowToo, check};
        read[0] = new Runnable() {
            @Override
            public void run() {
                final CustomizeOm.Target t = selected(pick, hd);
                if (t == null) return;
                enable(controls, false);
                status.setText("Reading " + t.name + " (" + t.host + ")…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final State s = readState(t, root);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                cur[0] = s;
                                enable(controls, true);
                                if (s.error != null) {
                                    status.setText(t.name + ": " + s.error);
                                    status.setTextColor(0xffffb74d);
                                    return;
                                }
                                status.setTextColor(0xffa5d6a7);
                                status.setText(t.name + ": " + s.info);
                                quietSet[0] = true;
                                sQuiet.setChecked(s.quiet);
                                sNtp.setChecked(!s.ntp);
                                sDns.setChecked(!s.dns);
                                sMdns.setChecked(!s.mdns);
                                sUsb.setChecked(s.usb);
                                sUsb.setEnabled(s.usb); // only to put a node back to Ethernet
                                sLock.setChecked(s.omdGuard);
                                sHalow.setChecked(!s.halowUp);
                                String[] dn = HalowToggle.directNode(a);
                                final boolean direct = dn != null && dn[0].equals(t.host);
                                sHalow.setEnabled(direct);
                                halowWhy.setVisibility(direct ? View.GONE : View.VISIBLE);
                                final String why = "This phone is not connected to " + t.name + " directly ("
                                        + (dn == null ? "it is not on any node's WiFi or Ethernet" : "it is on " + dn[0] + ", " + dn[1])
                                        + "). Turning its HaLow off would cut it off with no way to turn it back on from here.\n\n"
                                        + "Join " + t.name + "'s WiFi or cable to it, then pick it again.";
                                halowWhy.setText("⚠ Not connected to " + t.name + " (tap for why)");
                                halowWhy.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        new AlertDialog.Builder(a).setTitle("Why HaLow off is greyed out").setMessage(why)
                                                .setPositiveButton("OK", null).show();
                                    }
                                });
                                (s.wifiMode == CustomizeOm.MODE_ONLY ? mO : s.wifiMode == CustomizeOm.MODE_PREF ? mP : mN).setChecked(true);
                                halowToo.setChecked(s.halowToo);
                                quietSet[0] = false;
                            }
                        });
                    }
                }, "hd01-read").start();
            }
        };

        final Runnable scan = new Runnable() {
            @Override
            public void run() {
                status.setText("Looking for HT-HD01 nodes in the list…");
                enable(controls, false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<CustomizeOm.Target> found = new ArrayList<CustomizeOm.Target>();
                        final StringBuilder other = new StringBuilder();
                        for (CustomizeOm.Target t : all) {
                            String b = board(t, root);
                            if (b != null && b.startsWith("heltec,ht-hd01")) found.add(t);
                            else other.append(other.length() > 0 ? ", " : "").append(t.name).append(b == null ? " (no answer)" : b.length() == 0 ? "" : " (" + b + ")");
                        }
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                hd.clear();
                                hd.addAll(found);
                                List<String> names = new ArrayList<String>();
                                for (CustomizeOm.Target t : hd) names.add(t.name + " (" + t.host + ")");
                                if (names.isEmpty()) names.add("(no HT-HD01 nodes found)");
                                ArrayAdapter<String> ad = new ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, names);
                                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                pick.setAdapter(ad);
                                if (hd.isEmpty()) {
                                    status.setText("No HT-HD01 nodes answered" + (other.length() > 0 ? ". Others: " + other : "") + ". Scan in the Mesh controller first, and log in.");
                                    rescan.setEnabled(true);
                                } else {
                                    if (other.length() > 0) status.setText("Not HT-HD01 (left out): " + other);
                                    // picking the first node triggers the status read
                                }
                            }
                        });
                    }
                }, "hd01-scan").start();
            }
        };

        pick.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                read[0].run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                read[0].run();
            }
        });
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan.run();
            }
        });
        applyMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final CustomizeOm.Target t = selected(pick, hd);
                if (t == null) return;
                final int mode = mO.isChecked() ? CustomizeOm.MODE_ONLY : mP.isChecked() ? CustomizeOm.MODE_PREF : CustomizeOm.MODE_NORMAL;
                final boolean h = mode == CustomizeOm.MODE_PREF && halowToo.isChecked();
                String msg = mode == CustomizeOm.MODE_NORMAL ? "Normal on " + t.name + ": the start-up check is removed, Ethernet only is undone, any radio that is off comes back on."
                        : mode == CustomizeOm.MODE_PREF ? "Ethernet preferred on " + t.name + (h ? " (HaLow off too)" : "") + ": from its next restart, WiFi goes off "
                        + "if the LAN cable is in (a few looks in the first 2 minutes, then it stops). Unplugging later does not bring WiFi back; restart for that."
                        : "Ethernet only on " + t.name + ": WiFi is switched off in its settings now and stays off. Phones on its WiFi drop off. HaLow stays on.";
                confirm(a, "WiFi mode", msg, new Runnable() {
                    @Override
                    public void run() {
                        final String script = mode == CustomizeOm.MODE_PREF ? CustomizeOm.asset(a, "ycc-ap-auto") : null;
                        change(a, t, root, status, controls, read[0], "WiFi mode " + CustomizeOm.modeName(mode), new CustomizeOm.Job() {
                            @Override
                            public String run(CustomizeOm.Target tt, UbusApi u) throws Exception {
                                return CustomizeOm.applyMode(u, mode, script, h, true, 0);
                            }
                        });
                    }
                });
            }
        });

        // each switch: confirm, change, re-read (or snap back on Cancel)
        final String[] what = {"LED and button monitor off", "Time sync (NTP) off", "DNS off", "mDNS off", "USB port instead of Ethernet",
                "HaLow radio off", "Clear a stale openmanetd lock at start-up"};
        for (int i = 0; i < sws.length; i++) {
            final int k = i;
            sws[i].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(final CompoundButton b, final boolean on) {
                    if (quietSet[0]) return;
                    final CustomizeOm.Target t = selected(pick, hd);
                    if (t == null || cur[0] == null || cur[0].error != null) {
                        quietSet[0] = true;
                        b.setChecked(!on);
                        quietSet[0] = false;
                        return;
                    }
                    if (k == 4 && on) { // USB: disabled for now, only back to Ethernet
                        quietSet[0] = true;
                        b.setChecked(false);
                        quietSet[0] = false;
                        return;
                    }
                    if (k == 5) { // HaLow: only the node this phone reaches directly (re-checked now, not just when read)
                        String[] dn = HalowToggle.directNode(a);
                        if (dn == null || !dn[0].equals(t.host)) {
                            quietSet[0] = true;
                            b.setChecked(!on);
                            quietSet[0] = false;
                            new AlertDialog.Builder(a).setTitle("HaLow radio off").setMessage("Not allowed: this phone is not connected to "
                                    + t.name + " directly, so it could not turn HaLow back on.").setPositiveButton("OK", null).show();
                            return;
                        }
                    }
                    String msg = (on && (k == 1 || k == 3) ? "⚠ Not recommended: this can cause unexpected problems on the node "
                            + "and elsewhere on the mesh.\n\n" : "") + (on && k == 5 ? "⚠ " + t.name + " leaves the mesh: it stops relaying, "
                            + "nodes that reach the mesh only through it drop out, a gateway node cuts the mesh's internet, and phones on "
                            + "its WiFi reach only " + t.name + ".\n\n" : "")
                            + (on ? "Turn ON: " : "Turn OFF (back to normal): ") + what[k] + " on " + t.name + "?\n\n" + b.getTag();
                    new AlertDialog.Builder(a).setTitle(what[k]).setMessage(msg)
                            .setPositiveButton(on ? "Turn on" : "Turn off", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface di, int w) {
                                    change(a, t, root, status, controls, read[0], (on ? "" : "Undo: ") + what[k], new CustomizeOm.Job() {
                                        @Override
                                        public String run(CustomizeOm.Target tt, UbusApi u) throws Exception {
                                            if (k == 5) return halow(u, tt, !on);
                                            return apply(u, k, on);
                                        }
                                    });
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface di, int w) {
                                    quietSet[0] = true;
                                    b.setChecked(!on);
                                    quietSet[0] = false;
                                }
                            }).setCancelable(false).show();
                }
            });
        }

        new AlertDialog.Builder(a).setTitle("HT-HD01 v2 mods").setView(sv).setPositiveButton("Close", null).show();
        scan.run();
    }

    // ---- UI helpers ----

    private static TextView text(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        return t;
    }

    private static TextView head(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, (int) (14 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static Button btn(Activity a, LinearLayout row, String s) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(s);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private static RadioButton radio(Activity a, RadioGroup g, int id, String s) {
        RadioButton r = new RadioButton(a);
        r.setId(id);
        r.setText(s);
        g.addView(r);
        return r;
    }

    private static Switch sw(Activity a, LinearLayout box, String title, String help) {
        Switch s = new Switch(a);
        s.setText(title);
        s.setTag(help);
        s.setPadding(0, (int) (8 * a.getResources().getDisplayMetrics().density), 0, 0);
        box.addView(s);
        TextView h = text(a, help);
        h.setTextSize(12);
        h.setTextColor(0xffb0bec5);
        box.addView(h);
        return s;
    }

    private static void enable(View[] vs, boolean on) {
        for (View v : vs) v.setEnabled(on);
    }

    private static CustomizeOm.Target selected(Spinner s, List<CustomizeOm.Target> hd) {
        int i = s.getSelectedItemPosition();
        return i >= 0 && i < hd.size() ? hd.get(i) : null;
    }

    private static void confirm(Activity a, String title, String msg, final Runnable yes) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("Apply", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                yes.run();
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Runs one change on the node in the background, logs it, then re-reads the state. */
    private static void change(final Activity a, final CustomizeOm.Target t, final RoLogin.Login root, final TextView status, final View[] controls,
                               final Runnable reread, final String what, final CustomizeOm.Job job) {
        enable(controls, false);
        status.setText(what + " on " + t.name + "…");
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = withNode(t, root, job);
                NodeShare.log(what + " on " + t.name + (r == null || r.startsWith("-") ? "" : ": FAILED " + r));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        enable(controls, true);
                        if (r != null && !r.startsWith("-")) {
                            status.setText(what + " on " + t.name + " failed: " + r);
                            status.setTextColor(0xffffb74d);
                        }
                        reread.run();
                    }
                });
            }
        }, "hd01-change").start();
    }

    /** Logs in (20 s per call, up to 3 tries when the node is busy) and runs the job; null = OK, else why not. */
    static String withNode(CustomizeOm.Target t, RoLogin.Login root, CustomizeOm.Job job) {
        String ru = root.user() == null || root.user().length() == 0 ? "root" : root.user(), rp = root.pass();
        if (rp == null) return "log in first (Scan in the Mesh controller)";
        String r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            UbusApi u = new UbusApi(t.host, 20000);
            boolean again = false;
            try {
                u.login(ru, rp);
                return job.run(t, u);
            } catch (UbusApi.UbusException e) {
                r = e.loginFailed() && !"Access denied".equalsIgnoreCase(e.getMessage()) ? "login refused" : e.getMessage();
                again = e.noAnswer() || "Access denied".equalsIgnoreCase(e.getMessage());
            } catch (Exception e) {
                r = String.valueOf(e.getMessage());
            } finally {
                u.logout();
            }
            if (!again) break;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return r;
    }

    static String board(CustomizeOm.Target t, RoLogin.Login root) {
        final String[] b = {null};
        String r = withNode(t, root, new CustomizeOm.Job() {
            @Override
            public String run(CustomizeOm.Target tt, UbusApi u) throws Exception {
                b[0] = u.call("system", "board", null).optString("board_name", "");
                return null;
            }
        });
        return r == null ? b[0] : null;
    }

    // ---- reading the state ----

    static State readState(CustomizeOm.Target t, RoLogin.Login root) {
        final State s = new State();
        String r = withNode(t, root, new CustomizeOm.Job() {
            @Override
            public String run(CustomizeOm.Target tt, UbusApi u) throws Exception {
                s.board = CustomizeOm.board(u);
                JSONObject sys = u.call("system", "info", null);
                JSONArray ld = sys.optJSONArray("load");
                JSONObject mem = sys.optJSONObject("memory");
                long up = sys.optLong("uptime");
                if (ld != null && ld.length() > 0)
                    s.load = String.format(Locale.US, "load %.2f", ld.optLong(0) / 65536.0);
                if (mem != null)
                    s.load += String.format(Locale.US, ", %d MB free of %d", (mem.optLong("available", mem.optLong("free"))) >> 20, mem.optLong("total") >> 20);
                s.load += ", up " + (up / 3600) + " h " + (up % 3600 / 60) + " min";
                JSONObject wl = u.call("uci", "get", new JSONObject().put("config", "wireless")).optJSONObject("values");
                String radio = CustomizeOm.wifiRadio(wl);
                JSONObject sec = radio == null ? null : wl.optJSONObject(radio);
                String rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
                if (rc == null) rc = "";
                Boolean old = CustomizeOm.oldService(u);
                s.oldSvc = old != null && old;
                if (sec != null && "1".equals(sec.optString("ycc_ethonly"))) s.wifiMode = CustomizeOm.MODE_ONLY;
                else if (rc.contains(CustomizeOm.BLOCK_START) || s.oldSvc) s.wifiMode = CustomizeOm.MODE_PREF;
                else s.wifiMode = CustomizeOm.MODE_NORMAL;
                int a = rc.indexOf(CustomizeOm.BLOCK_START);
                s.halowToo = a >= 0 && rc.indexOf("HALOW=1", a) > 0 && rc.indexOf("HALOW=1", a) < rc.indexOf(CustomizeOm.BLOCK_END, a);
                s.quiet = rc.contains(start(QUIET));
                s.quiet2 = s.quiet && rc.contains("( sleep 120; /etc/init.d/checkkey stop"); // installed before the 3-minute change
                s.omdGuard = rc.contains(start(OMD_LOCK));
                JSONObject init = u.call("luci", "getInitList", null);
                s.ntp = enabled(init, "sysntpd");
                s.mdns = enabled(init, "avahi-daemon");
                JSONObject dn = dnsmasq(u);
                s.dns = dn == null || !"0".equals(dn.optString("port"));
                JSONObject sy = systemSection(u);
                s.usb = sy != null && "usb".equals(sy.optString("target"));
                try {
                    JSONObject wd = u.call("luci-rpc", "getWirelessDevices", null);
                    for (Iterator<String> it = wl.keys(); it.hasNext(); ) {
                        String k = it.next();
                        JSONObject x = wl.optJSONObject(k);
                        if (x != null && "morse".equals(x.optString("type")) && wd.optJSONObject(k) != null)
                            s.halowUp = wd.optJSONObject(k).optBoolean("up");
                    }
                } catch (UbusApi.UbusException ignored) {
                }
                s.info = s.load + "\nWiFi mode: " + CustomizeOm.modeName(s.wifiMode) + (s.oldSvc ? " (hand-installed service)" : "")
                        + (s.halowToo ? ", HaLow too" : "") + " · HaLow " + (s.halowUp ? "on" : "OFF")
                        + "\nLED/button monitor: " + (s.quiet ? (s.quiet2 ? "stops 2 min after start (old setting: switch it off and on to make it 3 min)" : "stops 3 min after start") : "always on")
                        + " · NTP " + (s.ntp ? "on" : "off") + " · DNS " + (s.dns ? "on" : "off") + " · mDNS " + (s.mdns ? "on" : "off")
                        + "\nPort in use: " + (s.usb ? "USB" : "Ethernet") + " · openmanetd lock guard " + (s.omdGuard ? "on" : "off");
                return null;
            }
        });
        if (r != null) s.error = r;
        else if (!s.board.startsWith("heltec,ht-hd01")) s.error = "not an HT-HD01 V2 (" + s.board + ")";
        return s;
    }

    static boolean enabled(JSONObject init, String name) {
        JSONObject o = init == null ? null : init.optJSONObject(name);
        return o != null && o.optBoolean("enabled", false);
    }

    /** The dnsmasq section of /etc/config/dhcp (values with ".name"), or null. */
    static JSONObject dnsmasq(UbusApi u) throws Exception {
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "dhcp")).optJSONObject("values");
        if (all == null) return null;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = all.optJSONObject(k);
            if (s != null && "dnsmasq".equals(s.optString(".type"))) {
                s.put(".name", k);
                return s;
            }
        }
        return null;
    }

    // ---- the changes ----

    static String start(String name) {
        return "# >>> YCC " + name;
    }

    static String end(String name) {
        return "# <<< YCC " + name;
    }

    static String withoutBlock(String rc, String name) {
        int a = rc.indexOf(start(name));
        if (a < 0) return rc;
        int b = rc.indexOf(end(name), a);
        int e = b < 0 ? rc.length() : rc.indexOf('\n', b) < 0 ? rc.length() : rc.indexOf('\n', b) + 1;
        return rc.substring(0, a) + rc.substring(e);
    }

    static String withBlock(String rc, String name, String body) {
        rc = withoutBlock(rc, name);
        String blk = start(name) + " (set by the YCC Mesh controller, HT-HD01 v2 mods)\n" + body + (body.endsWith("\n") ? "" : "\n") + end(name) + "\n";
        int ex = rc.lastIndexOf("\nexit 0");
        return ex >= 0 ? rc.substring(0, ex + 1) + blk + rc.substring(ex + 1) : rc + (rc.endsWith("\n") ? "" : "\n") + blk;
    }

    /** HaLow up / down on this node now (/sbin/wifi), and the home bar's state follows. */
    static String halow(UbusApi u, CustomizeOm.Target t, boolean up) throws Exception {
        String radio = HalowToggle.morseRadio(u);
        if (radio == null) return "this node has no HaLow radio";
        if (!up) HalowToggle.services(u, false, null); // OpenMANET's pollers stop with HaLow (a restart brings them back)
        try {
            HalowToggle.wifi(u, up ? "up" : "down", radio);
        } catch (UbusApi.UbusException e) {
            if (!e.noAnswer()) throw e;
        }
        boolean now = !up;
        for (int i = 0; i < 8; i++) {
            Thread.sleep(1500);
            try {
                now = HalowToggle.isUp(u, radio);
                if (now == up) break;
            } catch (UbusApi.UbusException e) {
                if (!e.noAnswer()) throw e;
            }
        }
        if (up) HalowToggle.services(u, true, null);
        HalowToggle.set(now ? "on" : "off", t.host, t.name);
        if (!HalowToggle.sp().contains("halow_bar")) HalowToggle.sp().edit().putBoolean("halow_bar", true).commit();
        return now == up ? null : "not confirmed yet: HaLow is still " + (now ? "on" : "off");
    }

    /** The system section of /etc/config/system (Heltec keeps the Ethernet / USB choice in its "target" option). */
    static JSONObject systemSection(UbusApi u) throws Exception {
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "system")).optJSONObject("values");
        if (all == null) return null;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = all.optJSONObject(k);
            if (s != null && "system".equals(s.optString(".type"))) {
                s.put(".name", k);
                return s;
            }
        }
        return null;
    }

    /** k: 0 LED/button monitor, 1 NTP, 2 DNS, 3 mDNS (on = saving on), 4 USB port instead of Ethernet. */
    static String apply(UbusApi u, int k, boolean on) throws Exception {
        if (k == 6) {
            omdGuard(u, on);
            return null;
        }
        if (k == 4) {
            // What Heltec's button does: system.@system[0].target = usb / eth; checkkey sets the switch pin (GPIO 551) from
            // it when it starts, so restarting checkkey applies it now and every boot applies it again.
            JSONObject sy = systemSection(u);
            if (sy == null) return "no system settings found";
            u.call("uci", "set", new JSONObject().put("config", "system").put("section", sy.optString(".name"))
                    .put("values", new JSONObject().put("target", on ? "usb" : "eth")));
            u.call("uci", "commit", new JSONObject().put("config", "system"));
            String rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
            boolean quiet = rc != null && rc.contains(start(QUIET));
            CustomizeOm.initAction(u, "checkkey", "restart");
            if (quiet) { // the LED/button saving keeps the button script off: start it just long enough to set the pin
                Thread.sleep(4000);
                CustomizeOm.initAction(u, "checkkey", "stop");
            }
            return null;
        }
        if (k == 0) {
            String rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
            if (rc == null) rc = "#!/bin/sh\nexit 0\n";
            String body = "( sleep 180; /etc/init.d/checkkey stop; /etc/init.d/checkstat stop; sleep 2; /ht-service/rgb n; "
                    + "logger -t ycc-hd01 \"LED and button monitor stopped, LED off (until the next restart)\" ) >/dev/null 2>&1 &";
            u.call("file", "write", new JSONObject().put("path", CustomizeOm.RCLOCAL).put("data", on ? withBlock(rc, QUIET, body) : withoutBlock(rc, QUIET)));
            // now too: stop (on) or start again (off) the two Heltec services; the LED goes dark at the next restart
            CustomizeOm.initAction(u, "checkkey", on ? "stop" : "start");
            CustomizeOm.initAction(u, "checkstat", on ? "stop" : "start");
            return null;
        }
        if (k == 1) {
            CustomizeOm.initAction(u, "sysntpd", on ? "stop" : "enable");
            CustomizeOm.initAction(u, "sysntpd", on ? "disable" : "start");
            return null;
        }
        if (k == 3) {
            CustomizeOm.initAction(u, "avahi-daemon", on ? "stop" : "enable");
            CustomizeOm.initAction(u, "avahi-daemon", on ? "disable" : "start");
            return null;
        }
        // DNS: port 0 turns dnsmasq's DNS off and keeps DHCP; tell DHCP clients to use the gateway for names instead
        JSONObject dn = dnsmasq(u);
        if (dn == null) return "no dnsmasq settings on this node";
        String sec = dn.optString(".name");
        JSONObject dh = u.call("uci", "get", new JSONObject().put("config", "dhcp")).optJSONObject("values");
        String lanSec = dh != null && dh.optJSONObject("ahwlan") != null ? "ahwlan" : "lan";
        JSONObject lan = dh == null ? null : dh.optJSONObject(lanSec);
        JSONArray opts = lan == null ? null : lan.optJSONArray("dhcp_option");
        List<String> keep = new ArrayList<String>();
        if (opts != null) for (int i = 0; i < opts.length(); i++) if (!opts.optString(i).startsWith("6,")) keep.add(opts.optString(i));
        if (on) {
            String gw = gateway(u);
            u.call("uci", "set", new JSONObject().put("config", "dhcp").put("section", sec).put("values", new JSONObject().put("port", "0")));
            if (gw != null && lan != null) {
                keep.add("6," + gw);
                u.call("uci", "set", new JSONObject().put("config", "dhcp").put("section", lanSec)
                        .put("values", new JSONObject().put("dhcp_option", new JSONArray(keep)).put("ycc_dns", gw)));
            }
        } else {
            u.call("uci", "delete", new JSONObject().put("config", "dhcp").put("section", sec).put("option", "port"));
            if (lan != null && lan.has("ycc_dns")) {
                if (keep.isEmpty())
                    u.call("uci", "delete", new JSONObject().put("config", "dhcp").put("section", lanSec).put("option", "dhcp_option"));
                else
                    u.call("uci", "set", new JSONObject().put("config", "dhcp").put("section", lanSec).put("values", new JSONObject().put("dhcp_option", new JSONArray(keep))));
                u.call("uci", "delete", new JSONObject().put("config", "dhcp").put("section", lanSec).put("option", "ycc_dns"));
            }
        }
        u.call("uci", "commit", new JSONObject().put("config", "dhcp"));
        CustomizeOm.initAction(u, "dnsmasq", "restart");
        return null;
    }

    /** The node's default gateway (from its interface status), or null. */
    static String gateway(UbusApi u) {
        try {
            JSONArray ifs = u.call("network.interface", "dump", null).optJSONArray("interface");
            for (int i = 0; ifs != null && i < ifs.length(); i++) {
                JSONArray routes = ifs.optJSONObject(i).optJSONArray("route");
                for (int j = 0; routes != null && j < routes.length(); j++) {
                    JSONObject rt = routes.optJSONObject(j);
                    if ("0.0.0.0".equals(rt.optString("target")) && rt.optInt("mask") == 0 && rt.optString("nexthop").length() > 0)
                        return rt.optString("nexthop");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** HTTP status of a plain GET (no login), or -1 = no answer. 403 / 401 count as an answer. */
    static int httpCode(String url) {
        java.net.HttpURLConnection h = null;
        try {
            h = org.communitycomms.app.net.LanNet.open(new java.net.URL(url));
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setInstanceFollowRedirects(false);
            return h.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    // ---- Mesh troubleshooting ----

    public static void troubleshoot(final Activity a, final List<CustomizeOm.Target> all, final RoLogin.Login root) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        box.addView(text(a, "Looks for known mesh faults across every node in the list.\n\n• openmanetd down: its web page / API "
                + "(port 8080) does not answer while the node's LuCI page does. Usually a database lock left behind by a restart or "
                + "power cut. Fix: add the start-up step that clears it, and restart the node.\n• DHCP clash: two nodes handing out "
                + "the same addresses (phones end up with the same address; calls and chat then fail one way). Fix: each clashing "
                + "node moves to a free block of the same size, after you see the plan."));
        LinearLayout r = new LinearLayout(a);
        final Button go = btn(a, r, "Check the mesh");
        box.addView(r);
        final TextView status = text(a, all.size() + " nodes in the list.");
        status.setPadding(0, (int) (8 * d), 0, 0);
        status.setTextIsSelectable(true);
        box.addView(status);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final Handler ui = new Handler(Looper.getMainLooper());
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go.setEnabled(false);
                status.setText("Checking openmanetd on each node…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<CustomizeOm.Target> down = new ArrayList<CustomizeOm.Target>();
                        final StringBuilder b = new StringBuilder();
                        for (CustomizeOm.Target t : all) {
                            int omd = httpCode("http://" + t.host + ":8080/"), web = httpCode("http://" + t.host + "/cgi-bin/luci/");
                            boolean isDown = omd < 0 && web > 0;
                            if (isDown) down.add(t);
                            b.append(omd > 0 ? "✓ " : isDown ? "✗ " : "– ").append(t.name).append(": openmanetd ")
                                    .append(omd > 0 ? "answers" : isDown ? "NOT answering (its web page does)" : "no answer (node unreachable?)").append("\n");
                        }
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                go.setEnabled(true);
                                status.setText(b.toString().trim());
                                if (down.isEmpty()) {
                                    status.append("\nopenmanetd OK. Now checking DHCP ranges…");
                                    CustomizeOm.planAndFix(a, all, status, root);
                                    return;
                                }
                                StringBuilder nm = new StringBuilder();
                                for (CustomizeOm.Target t : down) nm.append(nm.length() > 0 ? ", " : "").append(t.name);
                                new AlertDialog.Builder(a).setTitle("openmanetd not running")
                                        .setMessage("openmanetd is not answering on " + nm + ", although the node's LuCI page does. This "
                                                + "is usually a stale database lock left by a restart or power cut.\n\nFix: add the start-up "
                                                + "step that clears a stale lock, then restart " + nm + " (about 3 minutes; phones on its "
                                                + "WiFi drop off meanwhile).")
                                        .setPositiveButton("Fix and restart", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface di, int w) {
                                                CustomizeOm.run(a, down, status, root, "Fixing ", new CustomizeOm.Job() {
                                                    @Override
                                                    public String run(CustomizeOm.Target t, UbusApi u) throws Exception {
                                                        omdGuard(u, true);
                                                        try {
                                                            u.call("system", "reboot", null);
                                                        } catch (UbusApi.UbusException e) {
                                                            if (!e.noAnswer()) throw e;
                                                        }
                                                        return null;
                                                    }
                                                }, "openmanetd lock fix + restart: ");
                                            }
                                        })
                                        .setNeutralButton("Skip, check DHCP", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface di, int w) {
                                                CustomizeOm.planAndFix(a, all, status, root);
                                            }
                                        })
                                        .setNegativeButton(android.R.string.cancel, null).show();
                            }
                        });
                    }
                }, "mesh-check").start();
            }
        });
        new AlertDialog.Builder(a).setTitle("Mesh troubleshooting").setView(sv).setPositiveButton("Close", null).show();
    }
}
