package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.UbusApi;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Mesh controller > More > "Customize OpenMANET": node-side tweaks pushed node by node with the admin (root) login over
 * OpenWrt's /ubus, the same way as the read-only login.
 * <ul>
 * <li>RF footprint: the "ycc-ap-auto" service (assets/ycc-ap-auto) turns a node's 2.4 GHz WiFi off while its Ethernet
 * port has a link (optionally its HaLow radio too), back on when unplugged. Runtime only, failsafe, Heltec nodes by default.</li>
 * <li>DHCP ranges: every node hands out addresses on the same 10.41.0.0/16, so each node needs its own block. Reads each
 * node's dhcp.ahwlan start/limit and moves any node whose block overlaps another's to a free one.</li>
 * </ul>
 * A timed burst mode (radios only on for short bursts) is a possible later addition, not built.
 */
public final class CustomizeOm {
    static final String SVC = "/etc/init.d/ycc-ap-auto";
    static final String CONF = "/etc/ycc-ap-auto.conf";
    static final String LOOP = "/usr/bin/ycc-ap-auto-loop";
    static final String BOOT_LINK = "/etc/rc.d/S99ycc-ap-auto";
    static final String KEEP = "/etc/sysupgrade.conf";

    private CustomizeOm() {
    }

    /** One node. result: null = not done, "" = OK, "-…" = skipped (reason after the dash), else the error. */
    public static final class Target {
        public final String name, host;
        String result, info;
        // DHCP (read by check / plan)
        String dhcpSection;
        long base = -1, start = -1, limit = -1, ip = -1, newStart = -1;

        public Target(String name, String host) {
            this.name = name;
            this.host = host;
        }
    }

    interface Job {
        /** null = OK, "-reason" = skipped, else the error. */
        String run(Target t, UbusApi u) throws Exception;
    }

    public static void dialog(final Activity a, final List<Target> targets, final RoLogin.Login root) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);

        box.addView(head(a, "WiFi mode (pick the nodes)"));
        box.addView(text(a, "For nodes wired to a laptop, a router or another node: less RF to be found (and less power and noise). "
                + "The 2.4 GHz WiFi access point only; HaLow stays on unless ticked below."));
        final android.widget.RadioGroup modes = new android.widget.RadioGroup(a);
        final android.widget.RadioButton mNormal = new android.widget.RadioButton(a), mPref = new android.widget.RadioButton(a),
                mOnly = new android.widget.RadioButton(a);
        mNormal.setText("Normal: WiFi and Ethernet both on (bridged), as OpenMANET ships");
        mPref.setText("Ethernet preferred: WiFi goes off when the node finds the Ethernet cable");
        mOnly.setText("Ethernet only: WiFi switched off in the node's settings; stays off, even after a restart or with the cable out");
        mNormal.setId(0x7c0001); // fixed ids: View.generateViewId needs API 17
        mPref.setId(0x7c0002);
        mOnly.setId(0x7c0003);
        modes.addView(mNormal);
        modes.addView(mPref);
        modes.addView(mOnly);
        mPref.setChecked(true);
        box.addView(modes);
        final CheckBox halow = new CheckBox(a);
        halow.setText("Ethernet preferred: also turn the HaLow mesh radio off (see the warning)");
        box.addView(halow);
        final TextView boot = text(a, "Ethernet preferred looks at the port only a few times in the first 2 minutes after the node "
                + "starts (no polling: these nodes are CPU limited), acts once and stops. Plug the cable in before power-on; "
                + "restart the node to change it.");
        box.addView(boot);
        final CheckBox others = new CheckBox(a);
        others.setText("Also on non-Heltec nodes, e.g. a Pi gateway (not recommended)");
        box.addView(others);
        modes.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup g, int id) { // the HaLow and start-up options are for Ethernet preferred only
                int v = mPref.isChecked() ? View.VISIBLE : View.GONE;
                halow.setVisibility(v);
                boot.setVisibility(v);
                others.setVisibility(mNormal.isChecked() ? View.GONE : View.VISIBLE);
            }
        });
        halow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!on) return;
                new AlertDialog.Builder(a).setTitle("HaLow off too?")
                        .setMessage("With this, a node with its cable plugged in leaves the HaLow mesh completely: it no longer "
                                + "relays for other nodes, and nodes that only reach the mesh through it drop out.\n\n"
                                + "Only use it where the Ethernet cable itself carries the node's traffic (for example two nodes "
                                + "or sites joined by cable). There is no failsafe (nothing keeps running): if the node is cut off, "
                                + "restart it and both radios come back on.")
                        .setPositiveButton("I understand", null)
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                halow.setChecked(false);
                            }
                        }).show();
            }
        });
        others.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on) Toast.makeText(a, "Careful: on a gateway the Ethernet is usually the internet uplink, so its WiFi "
                        + "would be off all the time.", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout r1 = new LinearLayout(a);
        Button onB = btn(a, r1, "Apply to nodes…");
        box.addView(r1);

        box.addView(head(a, "Each node its own DHCP range"));
        box.addView(text(a, "Every node hands out addresses on the same mesh LAN, so two nodes with the same range can give two "
                + "phones the same address (calls and chat then fail one way). This reads each node's range and moves any node "
                + "that overlaps another to a free block of the same size. You see the plan before anything changes."));
        LinearLayout r2 = new LinearLayout(a);
        Button checkB = btn(a, r2, "Check nodes");
        Button fixB = btn(a, r2, "Fix overlaps…");
        box.addView(r2);

        box.addView(head(a, "HaLow on / off (radio silence)"));
        box.addView(text(a, "Turn the HaLow radio of the node this phone is connected to off or back on, with a green / red bar "
                + "on the home screen. Also on the OpenMANET Mesh page. Timed burst mode (radios on only for short bursts) is "
                + "planned, not built yet."));
        LinearLayout r3 = new LinearLayout(a);
        btn(a, r3, "HaLow on / off for my node…").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HalowToggle.dialog(a);
            }
        });
        box.addView(r3);

        final TextView status = new TextView(a);
        status.setPadding(0, (int) (8 * d), 0, 0);
        status.setTextIsSelectable(true);
        box.addView(status);
        status.setText(targets.isEmpty() ? "No nodes in the list yet: Scan first." : targets.size() + " nodes in the list. Press Check nodes to see each one.");
        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        onB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (targets.isEmpty()) {
                    status.setText("No nodes in the list yet: Scan first.");
                    return;
                }
                final int mode = mNormal.isChecked() ? MODE_NORMAL : mOnly.isChecked() ? MODE_ONLY : MODE_PREF;
                final boolean h = mode == MODE_PREF && halow.isChecked(), any = others.isChecked();
                final int watchMin = 0; // no longer used: the script looks only at start-up
                // pick the nodes (none ticked to start with: a mode is usually for one wired node, not the whole mesh)
                String[] names = new String[targets.size()];
                final boolean[] picked = new boolean[targets.size()];
                for (int i = 0; i < names.length; i++) names[i] = targets.get(i).name + " (" + targets.get(i).host + ")";
                new AlertDialog.Builder(a).setTitle(modeName(mode) + ": which nodes?")
                        .setMultiChoiceItems(names, picked, new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int which, boolean c) {
                                picked[which] = c;
                            }
                        })
                        .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                final List<Target> sel = new ArrayList<Target>();
                                StringBuilder nm = new StringBuilder();
                                for (int i = 0; i < picked.length; i++)
                                    if (picked[i]) {
                                        sel.add(targets.get(i));
                                        nm.append(nm.length() > 0 ? ", " : "").append(targets.get(i).name);
                                    }
                                if (sel.isEmpty()) {
                                    status.setText("No nodes ticked: nothing changed.");
                                    return;
                                }
                                confirmMode(a, sel, nm.toString(), mode, h, any, watchMin, status, root);
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        checkB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(a, targets, status, root, "Reading ", new Job() {
                    @Override
                    public String run(Target t, UbusApi u) throws Exception {
                        return check(t, u);
                    }
                }, null);
            }
        });
        fixB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                planAndFix(a, targets, status, root);
            }
        });
        new AlertDialog.Builder(a).setTitle("Customize OpenMANET").setView(sv).setPositiveButton("Close", null).show();
    }

    private static TextView head(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, (int) (12 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static TextView text(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        return t;
    }

    private static Button btn(Activity a, LinearLayout row, String s) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(s);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    static String asset(Activity a, String name) {
        try {
            InputStream in = a.getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8").replace("\r\n", "\n");
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the node work (each with a logged-in root UbusApi) ----

    static String board(UbusApi u) {
        try {
            return u.call("system", "board", null).optString("board_name", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String read(UbusApi u, String path) throws Exception {
        try {
            return u.call("file", "read", new JSONObject().put("path", path)).optString("data", "");
        } catch (UbusApi.UbusException e) {
            if (e.status == 4) return null; // not there
            throw e;
        }
    }

    static boolean exists(UbusApi u, String path) throws Exception {
        try {
            u.call("file", "stat", new JSONObject().put("path", path));
            return true;
        } catch (UbusApi.UbusException e) {
            if (e.status == 4) return false;
            throw e;
        }
    }

    static void rc(UbusApi u, String name, String action) throws Exception {
        try {
            u.call("rc", "init", new JSONObject().put("name", name).put("action", action));
        } catch (UbusApi.UbusException e) {
            if (!e.noAnswer()) throw e; // a WiFi change can drop this very call
        }
    }

    static String install(UbusApi u, String script, boolean halow, boolean anyBoard, int watchMin) throws Exception {
        String b = board(u);
        if (!anyBoard && !b.startsWith("heltec,")) return "-skipped: " + (b.length() == 0 ? "unknown board" : b) + " (not a Heltec)";
        u.call("file", "write", new JSONObject().put("path", SVC).put("data", script).put("mode", 493));
        u.call("file", "write", new JSONObject().put("path", CONF).put("data", "# YCC Customize OpenMANET\nHALOW=" + (halow ? 1 : 0)
                + "\n").put("mode", 420));
        String keep = read(u, KEEP);
        if (keep == null) keep = "";
        StringBuilder add = new StringBuilder();
        if (!hasLine(keep, SVC)) add.append(SVC).append("\n");
        if (!hasLine(keep, CONF)) add.append(CONF).append("\n");
        if (add.length() > 0) {
            String data = (keep.length() > 0 && !keep.endsWith("\n") ? keep + "\n" : keep) + add;
            u.call("file", "write", new JSONObject().put("path", KEEP).put("data", data).put("mode", 420));
        }
        rc(u, "ycc-ap-auto", "enable");
        rc(u, "ycc-ap-auto", "restart");
        return null;
    }

    static String remove(UbusApi u) throws Exception {
        if (!exists(u, SVC)) return "-not installed";
        rc(u, "ycc-ap-auto", "stop");
        rc(u, "ycc-ap-auto", "disable");
        for (String p : new String[]{SVC, CONF, LOOP, "/usr/bin/ycc-ap-auto-once"}) {
            try {
                u.call("file", "remove", new JSONObject().put("path", p));
            } catch (UbusApi.UbusException ignored) {
            }
        }
        String keep = read(u, KEEP);
        if (keep != null && (hasLine(keep, SVC) || hasLine(keep, CONF))) {
            StringBuilder b = new StringBuilder();
            for (String line : keep.split("\n", -1)) {
                if (line.trim().equals(SVC) || line.trim().equals(CONF)) continue;
                b.append(line).append("\n");
            }
            String data = b.toString();
            while (data.endsWith("\n\n")) data = data.substring(0, data.length() - 1);
            u.call("file", "write", new JSONObject().put("path", KEEP).put("data", data).put("mode", 420));
        }
        return null;
    }

    // ---- WiFi modes ----

    static final int MODE_NORMAL = 0, MODE_PREF = 1, MODE_ONLY = 2;
    /** Written when Ethernet only switches the WiFi radio off in uci: "RADIO=radio0" and "WAS=<its old disabled value>". */
    static final String ETHONLY = "/etc/ycc-ethonly";

    static String modeName(int m) {
        return m == MODE_NORMAL ? "Normal" : m == MODE_ONLY ? "Ethernet only" : "Ethernet preferred";
    }

    static void confirmMode(final Activity a, final List<Target> sel, String names, final int mode, final boolean h, final boolean any,
                            final int watchMin, final TextView status, final RoLogin.Login root) {
        String msg;
        if (mode == MODE_NORMAL) {
            msg = "Put " + names + " back to normal: the WiFi-off service is removed and, if Ethernet only switched the WiFi "
                    + "off, it is switched back on. Any radio the service turned off comes back on.";
        } else if (mode == MODE_PREF) {
            msg = names + ": WiFi goes off when the node finds its Ethernet cable" + (h ? ", HaLow off too" : " (HaLow stays on)")
                    + ". It looks at the port a few times in the first 2 minutes after each start (installing counts as one), "
                    + "acts once and stops: no polling. A node with its cable already in turns its WiFi off within about 30 s: "
                    + "phones on its WiFi drop off.\n\nWhat it decided stays until the node restarts: unplugging the cable later "
                    + "does NOT bring WiFi back" + (h ? " (with HaLow off too, the node is unreachable until it restarts)" : "") + "."
                    + (any ? "" : "\n\nNon-Heltec nodes are skipped.");
        } else {
            msg = names + ": the WiFi access point is switched OFF in the node's settings. It stays off after a restart and "
                    + "with the cable out; only Normal or Ethernet preferred (from here, over the cable or the mesh) turns it back on."
                    + "\n\n• Phones on that node's WiFi drop off at once.\n• HaLow stays on, so the node is still reachable over the "
                    + "mesh and its cable." + (any ? "" : "\n• Non-Heltec nodes are skipped.");
        }
        new AlertDialog.Builder(a).setTitle(modeName(mode)).setMessage(msg)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        final String script = mode == MODE_PREF ? asset(a, "ycc-ap-auto") : null;
                        if (mode == MODE_PREF && script == null) {
                            status.setText("The service script is missing from this app build.");
                            return;
                        }
                        run(a, sel, status, root, modeName(mode) + " on ", new Job() {
                            @Override
                            public String run(Target t, UbusApi u) throws Exception {
                                return applyMode(u, mode, script, h, any, watchMin);
                            }
                        }, "WiFi mode " + modeName(mode) + " on ");
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    static String applyMode(UbusApi u, int mode, String script, boolean halow, boolean anyBoard, int watchMin) throws Exception {
        if (mode == MODE_NORMAL) {
            String r = remove(u);
            boolean back = restoreWifi(u);
            return back || r == null ? null : "-already normal";
        }
        String b = board(u);
        if (!anyBoard && !b.startsWith("heltec,")) return "-skipped: " + (b.length() == 0 ? "unknown board" : b) + " (not a Heltec)";
        if (mode == MODE_PREF) {
            restoreWifi(u);
            return install(u, script, halow, true, watchMin);
        }
        // Ethernet only
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "wireless")).optJSONObject("values");
        JSONObject sta = all == null ? null : all.optJSONObject("sta_radio0");
        if (sta != null && !"1".equals(sta.optString("disabled", "0")))
            return "-skipped: its 2.4 GHz radio is also its WiFi uplink (sta_radio0)";
        String radio = wifiRadio(all);
        if (radio == null) return "-skipped: no 2.4 GHz WiFi radio";
        remove(u); // no service needed
        if (!exists(u, ETHONLY)) {
            String was = all.optJSONObject(radio).optString("disabled", "");
            u.call("file", "write", new JSONObject().put("path", ETHONLY).put("data", "# YCC Ethernet only\nRADIO=" + radio + "\nWAS=" + was + "\n").put("mode", 420));
        }
        u.call("uci", "set", new JSONObject().put("config", "wireless").put("section", radio).put("values", new JSONObject().put("disabled", "1")));
        u.call("uci", "commit", new JSONObject().put("config", "wireless"));
        reloadWifi(u, radio, false);
        return null;
    }

    static String wifiRadio(JSONObject all) {
        if (all == null) return null;
        for (String r : new String[]{"radio0", "radio1", "radio2"}) {
            JSONObject s = all.optJSONObject(r);
            if (s != null && "mac80211".equals(s.optString("type"))) return r;
        }
        return null;
    }

    /** Undoes Ethernet only (puts the radio's disabled setting back as it was). False when it was not on. */
    static boolean restoreWifi(UbusApi u) throws Exception {
        String m = read(u, ETHONLY);
        if (m == null) return false;
        java.util.regex.Matcher r = java.util.regex.Pattern.compile("RADIO=(\\w+)").matcher(m), w = java.util.regex.Pattern.compile("WAS=(\\S*)").matcher(m);
        String radio = r.find() ? r.group(1) : "radio0", was = w.find() ? w.group(1) : "";
        if (was.length() == 0 || "0".equals(was))
            u.call("uci", "delete", new JSONObject().put("config", "wireless").put("section", radio).put("option", "disabled"));
        else
            u.call("uci", "set", new JSONObject().put("config", "wireless").put("section", radio).put("values", new JSONObject().put("disabled", was)));
        u.call("uci", "commit", new JSONObject().put("config", "wireless"));
        try {
            u.call("file", "remove", new JSONObject().put("path", ETHONLY));
        } catch (UbusApi.UbusException ignored) {
        }
        reloadWifi(u, radio, true);
        return true;
    }

    /** Applies the committed wireless setting now (netifd reload), and makes sure the radio goes down / up. */
    static void reloadWifi(UbusApi u, String radio, boolean up) throws Exception {
        try {
            u.call("network", "reload", null);
        } catch (UbusApi.UbusException e) {
            if (!e.noAnswer()) throw e;
        }
        try {
            u.call("network.wireless", up ? "up" : "down", new JSONObject().put("device", radio));
        } catch (UbusApi.UbusException e) {
            if (!e.noAnswer() && e.status != 4 && e.status != 3) throw e;
        }
    }

    static boolean hasLine(String text, String line) {
        for (String l : text.split("\n")) if (l.trim().equals(line)) return true;
        return false;
    }

    /** Reads the board, the WiFi-off service state and the DHCP range into t.info. */
    static String check(Target t, UbusApi u) throws Exception {
        StringBuilder s = new StringBuilder();
        String b = board(u);
        s.append(b.length() == 0 ? "board ?" : b);
        if (exists(u, ETHONLY)) {
            s.append("; WiFi mode: Ethernet only (WiFi switched off)");
        } else if (exists(u, SVC)) {
            String conf = read(u, CONF);
            boolean h = conf != null && conf.contains("HALOW=1");
            String svc = read(u, SVC);
            String watch = svc != null && svc.contains("ycc-ap-auto-once") ? ", start-up check" : ", old always-watching version";
            s.append("; WiFi mode: Ethernet preferred").append(exists(u, BOOT_LINK) ? "" : " (installed, not enabled)")
                    .append(h ? ", HaLow too" : "").append(watch);
        } else {
            JSONObject all = u.call("uci", "get", new JSONObject().put("config", "wireless")).optJSONObject("values");
            String r = wifiRadio(all);
            boolean off = r != null && "1".equals(all.optJSONObject(r).optString("disabled"));
            s.append("; WiFi mode: Normal").append(off ? " (but WiFi is switched off in its settings)" : "");
        }
        readDhcp(t, u);
        s.append("; DHCP: ").append(t.start < 0 ? "none" : range(t, t.start));
        t.info = s.toString();
        return null;
    }

    static void readDhcp(Target t, UbusApi u) throws Exception {
        t.dhcpSection = null;
        t.start = t.limit = t.base = t.ip = -1;
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "dhcp")).optJSONObject("values");
        if (all == null) return;
        JSONObject s = all.optJSONObject("ahwlan");
        String name = "ahwlan";
        if (s == null) {
            s = all.optJSONObject("lan");
            name = "lan";
        }
        if (s == null || "1".equals(s.optString("ignore"))) return;
        String iface = s.optString("interface", name);
        JSONObject net = u.call("uci", "get", new JSONObject().put("config", "network").put("section", iface)).optJSONObject("values");
        if (net == null) return;
        String ipS = net.optString("ipaddr", "");
        if (ipS.startsWith("[")) ipS = ipS.replaceAll("[\\[\\]\" ]", "").split(",")[0]; // a list
        long mask;
        if (ipS.contains("/")) {
            int bits = Integer.parseInt(ipS.substring(ipS.indexOf('/') + 1));
            ipS = ipS.substring(0, ipS.indexOf('/'));
            mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        } else {
            mask = ip(net.optString("netmask", "255.255.255.0"));
        }
        long ip = ip(ipS);
        if (ip < 0 || mask < 0) return;
        t.dhcpSection = name;
        t.ip = ip;
        t.base = ip & mask;
        t.start = parse(s.optString("start", "100"), 100);
        t.limit = parse(s.optString("limit", "150"), 150);
    }

    static long parse(String s, long dflt) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    static long ip(String s) {
        try {
            String[] p = s.trim().split("\\.");
            if (p.length != 4) return -1;
            long v = 0;
            for (String x : p) {
                int n = Integer.parseInt(x);
                if (n < 0 || n > 255) return -1;
                v = (v << 8) | n;
            }
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    static String ipS(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    static String range(Target t, long start) {
        return ipS(t.base + start) + "–" + ipS(t.base + start + t.limit - 1).replaceFirst("^.*\\.", ".");
    }

    // ---- DHCP plan ----

    static boolean overlaps(long aFrom, long aTo, long bFrom, long bTo) {
        return aFrom <= bTo && bFrom <= aTo;
    }

    /** Works out t.newStart for every node that overlaps an earlier one (earlier nodes keep theirs). Returns the plan text, null if none needed. */
    static String plan(List<Target> ts) {
        List<long[]> taken = new ArrayList<long[]>();
        List<Long> nodeIps = new ArrayList<Long>();
        for (Target t : ts) if (t.ip >= 0) nodeIps.add(t.ip);
        List<Target> moving = new ArrayList<Target>();
        List<String> withs = new ArrayList<String>();
        List<Target> order = new ArrayList<Target>(ts); // lowest address first, so the gateway (.1) keeps its range
        java.util.Collections.sort(order, new java.util.Comparator<Target>() {
            @Override
            public int compare(Target x, Target y) {
                return x.ip < y.ip ? -1 : x.ip > y.ip ? 1 : 0;
            }
        });
        for (Target t : order) {
            t.newStart = -1;
            if (t.start < 0) continue;
            long from = t.base + t.start, to = from + t.limit - 1;
            boolean clash = false;
            String with = "";
            for (int i = 0; i < taken.size(); i++) {
                long[] r = taken.get(i);
                if (overlaps(from, to, r[0], r[1])) {
                    clash = true;
                    with = ts.get((int) r[2]).name;
                    break;
                }
            }
            if (!clash) {
                taken.add(new long[]{from, to, ts.indexOf(t)});
            } else {
                moving.add(t);
                withs.add(with);
            }
        }
        if (moving.isEmpty()) return null;
        StringBuilder lines = new StringBuilder();
        for (int m = 0; m < moving.size(); m++) {
            Target t = moving.get(m);
            long size = Math.max(1, t.limit), found = -1;
            lines.append(t.name).append(" overlaps ").append(withs.get(m)).append("\n");
            // blocks of the same size from .100 up, inside one /24 (never .0-.99 or past .250), first free one wins
            for (long k = 0; k < 256 && found < 0; k++) {
                for (long o = k * 256 + 100; o + size - 1 <= k * 256 + 250 && found < 0; o += size) {
                    long from = t.base + o, to = from + size - 1;
                    boolean free = true;
                    for (long[] r : taken) if (overlaps(from, to, r[0], r[1])) free = false;
                    for (Long n : nodeIps) if (n >= from && n <= to) free = false;
                    if (free) found = o;
                }
            }
            if (found < 0) {
                lines.append("✗ ").append(t.name).append(": no free block found, left as it is\n");
                continue;
            }
            t.newStart = found;
            taken.add(new long[]{t.base + found, t.base + found + t.limit - 1, ts.indexOf(t)});
            lines.append("   ").append(range(t, t.start)).append("  →  ").append(range(t, found)).append("\n");
        }
        return lines.toString();
    }

    static void planAndFix(final Activity a, final List<Target> targets, final TextView status, final RoLogin.Login root) {
        run(a, targets, status, root, "Reading ", new Job() {
            @Override
            public String run(Target t, UbusApi u) throws Exception {
                return check(t, u);
            }
        }, null, new Runnable() {
            @Override
            public void run() {
                final List<Target> ok = new ArrayList<Target>();
                for (Target t : targets) if ("".equals(t.result) && t.start >= 0) ok.add(t);
                int unread = 0;
                for (Target t : targets) if (t.result != null && t.result.length() > 0 && !t.result.startsWith("-")) unread++;
                final String p = plan(ok);
                if (p == null) {
                    show(status, status.getText() + "\nNo overlapping DHCP ranges" + (unread > 0 ? " among the " + ok.size() + " nodes read (" + unread + " could not be read)." : "."));
                    return;
                }
                final List<Target> moving = new ArrayList<Target>();
                for (Target t : ok) if (t.newStart >= 0) moving.add(t);
                if (moving.isEmpty()) {
                    show(status, status.getText() + "\n" + p);
                    return;
                }
                new AlertDialog.Builder(a).setTitle("Move DHCP ranges")
                        .setMessage(p + "\nOnly the start of each range changes (same size). Each node's DHCP server restarts; "
                                + "phones keep their address until they renew it (or turn WiFi off and on)."
                                + (unread > 0 ? "\n\n" + unread + " node(s) could not be read and are not counted." : ""))
                        .setPositiveButton("Change", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                CustomizeOm.run(a, moving, status, root, "Changing ", new Job() {
                                    @Override
                                    public String run(Target t, UbusApi u) throws Exception {
                                        long old = t.start;
                                        u.call("uci", "set", new JSONObject().put("config", "dhcp").put("section", t.dhcpSection)
                                                .put("values", new JSONObject().put("start", String.valueOf(t.newStart))));
                                        u.call("uci", "commit", new JSONObject().put("config", "dhcp"));
                                        rc(u, "dnsmasq", "restart");
                                        NodeShare.log("DHCP range on " + t.name + ": start " + old + " -> " + t.newStart + " (undo: set start back to " + old + ")");
                                        t.start = t.newStart;
                                        return null;
                                    }
                                }, null);
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
    }

    // ---- node by node ----

    static void run(Activity a, List<Target> targets, TextView status, RoLogin.Login root, String verb, Job job, String logPrefix) {
        run(a, targets, status, root, verb, job, logPrefix, null);
    }

    static void run(final Activity a, final List<Target> targets, final TextView status, final RoLogin.Login root, final String verb,
                    final Job job, final String logPrefix, final Runnable after) {
        if (targets.isEmpty()) {
            status.setText("No nodes in the list yet: Scan first.");
            return;
        }
        if (root.pass() == null) {
            status.setText("Log in (Scan) first: the admin login is needed.");
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        final String ru = root.user() == null || root.user().length() == 0 ? "root" : root.user(), rp = root.pass();
        for (Target t : targets) {
            t.result = null;
            t.info = null;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final Target t : targets) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show(status, lines(targets, verb + t.name + "…"));
                        }
                    });
                    String r = null;
                    for (int attempt = 0; attempt < 2; attempt++) { // every job is safe to repeat; a radio link can drop one call
                        UbusApi u = new UbusApi(t.host, 20000);
                        boolean again = false;
                        try {
                            u.login(ru, rp);
                            r = job.run(t, u);
                        } catch (UbusApi.UbusException e) {
                            r = e.loginFailed() ? "login refused" : e.getMessage();
                            again = e.noAnswer();
                        } catch (Exception e) {
                            r = String.valueOf(e.getMessage());
                        } finally {
                            u.logout();
                        }
                        if (!again) break;
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    t.result = r == null ? "" : r;
                    if (logPrefix != null && (r == null || !r.startsWith("-")))
                        NodeShare.log(logPrefix + t.name + (r == null ? "" : ": FAILED " + r));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        int bad = 0;
                        for (Target t : targets) if (t.result != null && t.result.length() > 0 && !t.result.startsWith("-")) bad++;
                        show(status, lines(targets, bad == 0 ? "Done." : bad + " failed: check the node is on and has the same "
                                + "admin password, then press the button again."));
                        if (after != null && !a.isFinishing()) after.run();
                    }
                });
            }
        }, "customize-om").start();
    }

    /** Sets the status text and scrolls the dialog down to it. */
    static void show(final TextView status, CharSequence text) {
        status.setText(text);
        View p = (View) status.getParent();
        final View sv = p == null ? null : (View) p.getParent();
        if (sv instanceof ScrollView) sv.post(new Runnable() {
            @Override
            public void run() {
                ((ScrollView) sv).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    static String lines(List<Target> targets, String tail) {
        StringBuilder b = new StringBuilder();
        for (Target t : targets) {
            String r = t.result;
            b.append(r == null ? "○ " : r.length() == 0 ? "✓ " : r.startsWith("-") ? "– " : "✗ ").append(t.name).append(" (").append(t.host).append(")");
            if (r != null && r.length() > 0) b.append(": ").append(r.startsWith("-") ? r.substring(1) : r);
            else if (t.info != null) b.append(": ").append(t.info);
            b.append("\n");
        }
        return b.append(tail).toString().trim();
    }
}
