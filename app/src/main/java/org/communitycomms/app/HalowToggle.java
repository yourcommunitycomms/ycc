package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.net.UbusApi;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * HaLow on / off for the node this phone is connected to (its WiFi or Ethernet gateway / DHCP server), for radio
 * silence: less RF to be found. Runtime only ("wifi down" of the morse radio over /ubus with the admin login), so a
 * reboot of the node always brings HaLow back. Only the node this phone reaches directly can be switched: a node
 * reached over the mesh could not be switched back on. The home screen shows a bar under the title bar (green on,
 * red off, grey not known) once the toggle has been used; tap it for this dialog.
 */
public final class HalowToggle {
    private HalowToggle() {
    }

    static SharedPreferences sp() {
        return MeshTest.sp();
    }

    /** "on", "off" or "" (not known). */
    public static String state() {
        String s = sp().getString("halow_state", "");
        // HaLow comes back by itself when the node restarts: neighbours with a HaLow signal seen again on that node = on
        if ("off".equals(s) && System.currentTimeMillis() - sp().getLong("halow_at", 0) > 90000
                && node().length() > 0 && node().equals(MeshNode.LOCAL.host()) && MeshNode.LOCAL.fresh(60000)
                && MeshNode.LOCAL.bestSignal() != Integer.MIN_VALUE) {
            set("on", node(), name());
            NodeShare.log("HaLow on " + name() + " is back on (neighbours seen again: the node restarted?)");
            s = "on";
        }
        return s;
    }

    static String node() {
        return sp().getString("halow_node", "");
    }

    static String name() {
        String n = sp().getString("halow_name", "");
        return n.length() > 0 ? n : node();
    }

    static void set(String state, String host, String name) {
        String prev = sp().getString("halow_state", "");
        long offSince = sp().getLong("halow_off_since", 0);
        if ("off".equals(state) && !"off".equals(prev)) sp().edit().putLong("halow_off_since", System.currentTimeMillis()).commit();
        if ("on".equals(state) && "off".equals(prev)) RadioService.halowBackOn(offSince); // catch up what was missed, both ways
        SharedPreferences.Editor e = sp().edit().putString("halow_state", state).putString("halow_node", host)
                .putString("halow_name", name == null ? "" : name).putLong("halow_at", System.currentTimeMillis());
        if (!"off".equals(state)) e.remove("halow_until").remove("halow_mode");
        else e.remove("halow_off_at");
        e.commit();
    }

    // How long HaLow stays off (chosen when turning it off)
    static final int UNTIL_ON = 0, UNTIL_REBOOT = 1, MIN30 = 2, HOUR1 = 3, AT = 4;
    static final String[] MODE_NAMES = {"Until I turn it on (default; stays off after a restart too)", "Until the node restarts",
            "On again in 30 minutes", "On again in 1 hour", "On again at (24 h time):"};
    /** The tag on the node's one-off crontab line that turns HaLow back on (the line deletes itself when it runs). */
    static final String CRON_TAG = "ycc-halow-on";
    /** Turning on: how long for. The timed ones are a one-off crontab line tagged OFF_TAG that turns HaLow off again. */
    static final int ON_UNTIL_OFF = 10, ON_5 = 11, ON_15 = 12;
    static final String[] ON_NAMES = {"Leave it on until I turn it off (default)", "Turn it off again in 5 minutes", "Turn it off again in 15 minutes"};
    static final String OFF_TAG = "ycc-halow-off";

    /** "off again at 14:30" while a timed turn-on is running, else "". */
    static String offAtText() {
        long at = sp().getLong("halow_off_at", 0);
        if (at <= 0) return "";
        return "off again at " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(at))
                + (at < System.currentTimeMillis() ? " (due)" : "");
    }
    static final String CRONTAB = "/etc/crontabs/root";

    /** "until 14:30", "until you turn it on", "until a restart" or "" for the bar and the dialog. */
    static String untilText() {
        int m = sp().getInt("halow_mode", -1);
        long until = sp().getLong("halow_until", 0);
        if (m == UNTIL_ON) return "stays off (also after a restart) until you turn it on";
        if (HalowWindows.openNow()) return "a radio window is open now: HaLow should be on (turning it on, or tap to turn it on)";
        if (m == UNTIL_REBOOT && HalowWindows.saved().length() > 0) {
            List<int[]> w = HalowWindows.savedList();
            if (w != null) return "until the next radio window at " + HalowWindows.hhmm(HalowWindows.nextStart(w));
        }
        if (m == UNTIL_REBOOT) return "until the node restarts";
        if (until > 0) return "on again at " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(until))
                + (until < System.currentTimeMillis() ? " (due: not seen back on yet)" : "");
        return "";
    }

    /** "14:30" / "1430" / "9:05" -> minutes after midnight, or -1. */
    static int parseHhmm(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([01]?\\d|2[0-3])[:.h]?([0-5]\\d)\\s*$").matcher(s == null ? "" : s);
        return m.matches() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
    }

    /** The node name the bar shows (for other screens). */
    public static String barName() {
        return name();
    }

    public static boolean barOn() {
        return sp().getBoolean("halow_bar", false);
    }

    /** The home screen bar: null = none. [text, colour]. */
    public static Object[] bar(android.content.Context c) {
        if (!barOn() || !MeshRadio.onMeshLan(c)) return null;
        String fixed = MeshTest.sp().getString("local_host", "").trim();
        String[] dh = fixed.length() > 0 ? new String[]{fixed} : MeshRadio.detectHost(c);
        if (dh != null && node().length() > 0 && !dh[0].equals(node())) { // this phone is now on another node: what we knew was for the old one
            return new Object[]{"● HaLow on " + nodeName(dh[0]) + ": not checked yet (last checked " + name() + "). Tap to check", 0xff607d8b};
        }
        String s = state(), n = name(), w = HalowWindows.saved();
        String wt = w.length() > 0 ? " · windows " + w : "";
        if ("off".equals(s)) {
            String u = untilText();
            return new Object[]{"● HaLow OFF on " + n + (u.length() > 0 ? ", " + u : "") + wt + ". Tap for HaLow on / off", 0xffc62828};
        }
        if ("on".equals(s)) return new Object[]{"● HaLow ON on " + n + (offAtText().length() > 0 ? ", " + offAtText() : "") + wt, 0xff2e7d32};
        return new Object[]{"● HaLow: not checked yet. Tap to check", 0xff607d8b};
    }

    /** The node this phone reaches directly: {host, how}, or null. */
    static String[] directNode(Activity a) {
        String fixed = MeshTest.sp().getString("local_host", "").trim();
        if (fixed.length() > 0) return new String[]{fixed, "the node address set on the OpenMANET Mesh page"};
        return MeshRadio.detectHost(a);
    }

    // ---- node calls (admin login) ----

    static final class Result {
        String radio, error, note;
        boolean up, retry;
        long untilMs; // timed off: when it comes back on (phone clock), 0 = not timed
    }

    /** Shown when another phone set the node's clock recently and this one disagrees (the user can still set it). */
    static final String CLOCK_HELD = "Not changed: ";
    /** The node's time came from internet time (NTP) and differs from this phone's: the user picks which to correct. */
    static final String CLOCK_NTP = "NTP: ";

    /**
     * True when the node's NTP client has declared its time valid since it started: OpenWrt's ntpd hotplug writes
     * /var/state/dnsmasqsec then (on tmpfs, so gone after a restart). Read with "file list", which LuCI's login may use.
     */
    static boolean nodeTimeFromNtp(UbusApi u) {
        try {
            org.json.JSONArray l = u.call("file", "list", new JSONObject().put("path", "/var/state")).optJSONArray("entries");
            if (l == null) return false;
            for (int i = 0; i < l.length(); i++) if ("dnsmasqsec".equals(l.optJSONObject(i).optString("name"))) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** "Update this phone or the node?" when the node has internet time and the phone differs. */
    static void askWhichClock(final Activity a, final String text, final Runnable setNode) {
        new AlertDialog.Builder(a).setTitle("Clocks differ").setMessage(text + "\n\nWhich one should be corrected?")
                .setPositiveButton("This phone", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS)); // apps cannot set the phone's clock
                        } catch (Exception e) {
                            android.widget.Toast.makeText(a, "Open Android Settings > Date & time", android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("The node", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (setNode != null) setNode.run();
                    }
                })
                .setNegativeButton("Neither", null).show();
    }

    /** True when this phone takes its time from the network (automatic date and time), so it is fit to set a node's clock. */
    static boolean phoneTimeAutomatic() {
        android.content.Context c = MeshTest.app;
        if (c == null) return false;
        try {
            return android.os.Build.VERSION.SDK_INT >= 17
                    ? android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.AUTO_TIME, 0) == 1
                    : android.provider.Settings.System.getInt(c.getContentResolver(), android.provider.Settings.System.AUTO_TIME, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    static String checkClock(UbusApi u, boolean fix) throws Exception {
        return checkClock(u, fix, false);
    }

    /**
     * The node's clock against this phone's (both local wall clock). fix: set the node's clock from this phone (LuCI's
     * own "Sync with browser" call, luci setLocaltime) when it is more than 5 minutes out. Several phones may use the
     * same node, so to stop them fighting over it: only a phone on automatic (network) time sets it, the node records
     * who set it last (uci system ycc_timesync = "epoch callsign"), and within 6 hours of another phone's setting this
     * phone only overrides it when the gap is over 15 minutes (or force, the user's "Set anyway"). Not when the gap is a
     * whole number of hours (the node is set to another time zone). Returns a line for the user, or null when fine.
     */
    static String checkClock(UbusApi u, boolean fix, boolean force) throws Exception {
        long nodeLocal = u.call("system", "info", null).optLong("localtime", 0);
        if (nodeLocal <= 0) return "could not read the node's clock";
        long nowMs = System.currentTimeMillis();
        long phoneLocal = (nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)) / 1000;
        long diff = nodeLocal - phoneLocal, ad = Math.abs(diff);
        if (ad <= 300 && !force) return null;
        long hourRest = ad % 3600;
        if (ad >= 3000 && (hourRest <= 120 || hourRest >= 3480)) {
            return "the node's clock is " + Math.round(diff / 3600.0) + " h from this phone's: it looks set to another time zone "
                    + "(System > System on its web page); times here are the node's";
        }
        String gap = ad >= 86400 ? (ad / 86400) + " days" : ad >= 3600 ? (ad / 3600) + " h " + (ad % 3600 / 60) + " min"
                : ad >= 60 ? (ad / 60) + " min" : ad + " s";
        String rel = gap + (diff > 0 ? " ahead of" : " behind") + " this phone's";
        if (!fix) return "the node's clock is " + rel;
        if (!force && nodeTimeFromNtp(u)) { // the node has internet time: the phone may be the wrong one, so ask
            return CLOCK_NTP + "the node's clock is " + rel + ", and the node got its time from internet time (NTP), so this "
                    + "phone may be the one that is wrong";
        }
        if (!phoneTimeAutomatic() && !force) {
            return CLOCK_HELD + "the node's clock is " + rel + ", but this phone's time is set by hand (not automatic), so it "
                    + "does not set the node's clock";
        }
        // who set it last
        String section = null, last = "";
        try {
            JSONObject all = u.call("uci", "get", new JSONObject().put("config", "system")).optJSONObject("values");
            if (all != null) {
                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject s = all.optJSONObject(k);
                    if (s != null && "system".equals(s.optString(".type"))) {
                        section = k;
                        last = s.optString("ycc_timesync", "");
                        break;
                    }
                }
            }
        } catch (UbusApi.UbusException ignored) {
        }
        String me = MeshTest.app == null ? "" : new Prefs(MeshTest.app).callsign();
        if (!force && last.length() > 0) {
            String[] p = last.split(" ", 2);
            long at = 0;
            try {
                at = Long.parseLong(p[0]);
            } catch (NumberFormatException ignored) {
            }
            String who = p.length > 1 ? p[1] : "";
            if (at > 0 && nowMs / 1000 - at < 6 * 3600 && !who.equals(me) && ad <= 900) {
                return CLOCK_HELD + "the node's clock is " + rel + ", but " + (who.length() > 0 ? who : "another phone") + " set it at "
                        + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(at * 1000)) + ". One of the two "
                        + "phones has the wrong time: check this phone's clock";
            }
        }
        u.call("luci", "setLocaltime", new JSONObject().put("localtime", nowMs / 1000));
        if (section != null) {
            try {
                u.call("uci", "set", new JSONObject().put("config", "system").put("section", section)
                        .put("values", new JSONObject().put("ycc_timesync", (nowMs / 1000) + " " + me)));
                u.call("uci", "commit", new JSONObject().put("config", "system"));
            } catch (UbusApi.UbusException ignored) {
            }
        }
        return "the node's clock was " + gap + (diff > 0 ? " ahead" : " behind") + ": set from this phone";
    }
    /** Takes out the one-off "HaLow on" line(s) this app put in the node's crontab; true when there were any. */
    static boolean clearCron(UbusApi u) throws Exception {
        String ct = CustomizeOm.read(u, CRONTAB);
        if (ct == null || !ct.contains(CRON_TAG) && !ct.contains(OFF_TAG)) return false;
        StringBuilder b = new StringBuilder();
        for (String line : ct.split("\n", -1)) if (!line.contains(CRON_TAG) && !line.contains(OFF_TAG)) b.append(line).append('\n');
        String out = b.toString().replaceAll("\n+$", "\n");
        u.call("file", "write", new JSONObject().put("path", CRONTAB).put("data", out));
        CustomizeOm.initAction(u, "cron", "reload");
        return true;
    }

    /**
     * Adds a one-off crontab line on the node that brings HaLow back up at the node's own local time (minute, hour, day
     * and month fixed, so it cannot repeat) and then deletes itself. Uses the node's clock (system info localtime).
     * Returns when it fires on the phone's clock (ms).
     */
    static long scheduleOn(UbusApi u, String radio, int mode, int atMin) throws Exception {
        long nodeLocal = 0;
        for (int i = 0; i < 3 && nodeLocal <= 0; i++) { // a busy node may drop one call: try again
            try {
                nodeLocal = u.call("system", "info", null).optLong("localtime", 0);
            } catch (UbusApi.UbusException e) {
                if (!e.noAnswer()) break;
                Thread.sleep(1000);
            }
        }
        if (nodeLocal <= 0) throw new Exception("could not read the node's clock, so no timer was set");
        long delayS;
        if (mode == MIN30) delayS = 30 * 60;
        else if (mode == HOUR1) delayS = 60 * 60;
        else if (mode == ON_5) delayS = 5 * 60;
        else if (mode == ON_15) delayS = 15 * 60;
        else {
            int nowMin = (int) ((nodeLocal / 60) % 1440);
            int d = (atMin - nowMin + 1440) % 1440;
            if (d == 0) d = 1440;
            delayS = d * 60L - nodeLocal % 60;
        }
        long target = nodeLocal + delayS;
        if (target % 60 != 0) target += 60 - target % 60; // cron works in whole minutes: round up
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(target * 1000L); // "localtime" is the node's local wall clock counted as if it were UTC
        boolean down = mode >= ON_UNTIL_OFF; // a timed turn-on: the line turns HaLow off again
        String tag = down ? OFF_TAG : CRON_TAG;
        String line = c.get(java.util.Calendar.MINUTE) + " " + c.get(java.util.Calendar.HOUR_OF_DAY) + " "
                + c.get(java.util.Calendar.DAY_OF_MONTH) + " " + (c.get(java.util.Calendar.MONTH) + 1) + " * /sbin/wifi " + (down ? "down " : "up ") + radio
                + servicesShell(!down) + "; sed -i '/" + tag + "/d' " + CRONTAB + " # " + tag + (down ? " (YCC: HaLow off again, one-off)" : " (YCC: HaLow back on, one-off)");
        String ct = CustomizeOm.read(u, CRONTAB);
        if (ct == null) ct = "";
        StringBuilder b = new StringBuilder();
        for (String l : ct.split("\n", -1)) if (!l.contains(CRON_TAG) && !l.contains(OFF_TAG) && l.length() > 0) b.append(l).append('\n');
        b.append(line).append('\n');
        u.call("file", "write", new JSONObject().put("path", CRONTAB).put("data", b.toString()));
        CustomizeOm.initAction(u, "cron", "enable");
        CustomizeOm.initAction(u, "cron", "restart");
        return System.currentTimeMillis() + (target - nodeLocal) * 1000L;
    }

    /** The persistent "until I turn it on": uci disabled=1 on the HaLow radio (marked, so turning on knows to undo it). */
    static void persistOff(UbusApi u, String radio, boolean off) throws Exception {
        if (off) {
            u.call("uci", "set", new JSONObject().put("config", "wireless").put("section", radio)
                    .put("values", new JSONObject().put("disabled", "1").put("ycc_halow_off", "1")));
        } else {
            JSONObject s = u.call("uci", "get", new JSONObject().put("config", "wireless").put("section", radio)).optJSONObject("values");
            if (s == null || !"1".equals(s.optString("ycc_halow_off")) && !"1".equals(s.optString("disabled"))) return;
            u.call("uci", "set", new JSONObject().put("config", "wireless").put("section", radio)
                    .put("values", new JSONObject().put("disabled", "0")));
            u.call("uci", "delete", new JSONObject().put("config", "wireless").put("section", radio).put("option", "ycc_halow_off"));
        }
        u.call("uci", "commit", new JSONObject().put("config", "wireless"));
        if (!off) {
            try {
                wifi(u, "reload", null); // the radio's config changed (disabled 1 -> 0): netifd re-reads it; unchanged radios stay as they are
            } catch (UbusApi.UbusException e) {
                if (!e.noAnswer()) throw e;
            }
        }
    }

    /**
     * OpenMANET's own services that keep polling the HaLow radio and batman while HaLow is off: openmanetd (about 80%
     * CPU on an HT-HD01 with no wlh0) and the HT-HD01 status script checkstat (an error every 2 s). HaLow off: stopped.
     * They stay enabled; with "until I turn it on" a start-up block in /etc/rc.local stops them again 3 minutes after a
     * restart while the HaLow radio is still disabled. HaLow on: the block is removed, and they are enabled (undoing any
     * older disable) and started (checkstat not when the "LED off" HD01 mod is installed: that mod stops it anyway).
     * Returns a note for the user, or null.
     */
    static final String[] OFF_SERVICES = {"openmanetd", "checkstat"};
    static final String RC_QUIET = "halow-quiet";

    static String services(UbusApi u, boolean on, String persistRadio) {
        StringBuilder note = new StringBuilder();
        String rc = null;
        try {
            rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
        } catch (Exception ignored) {
        }
        boolean quiet = rc != null && rc.contains("# >>> YCC hd01-quiet");
        for (String s : OFF_SERVICES) {
            if (!hasService(u, s)) continue;
            try {
                if (on) {
                    CustomizeOm.initAction(u, s, "enable");
                    if (!("checkstat".equals(s) && quiet)) CustomizeOm.initAction(u, s, "start");
                } else {
                    CustomizeOm.initAction(u, s, "stop");
                }
            } catch (Exception e) {
                note.append(note.length() > 0 ? "; " : "").append("could not ").append(on ? "start " : "stop ").append(s).append(" (").append(e.getMessage()).append(")");
            }
        }
        if (rc != null) {
            String want = on || persistRadio == null ? HdMods.withoutBlock(rc, RC_QUIET) : HdMods.withBlock(rc, RC_QUIET, quietBody(persistRadio));
            if (!want.equals(rc)) {
                try {
                    u.call("file", "write", new JSONObject().put("path", CustomizeOm.RCLOCAL).put("data", want));
                } catch (Exception e) {
                    note.append(note.length() > 0 ? "; " : "").append("could not update the start-up block (").append(e.getMessage()).append(")");
                }
            }
        }
        return note.length() > 0 ? note.toString() : null;
    }

    /** rc.local: 3 minutes after start-up, if HaLow is still disabled, stop OpenMANET's pollers again (they start normally). */
    static String quietBody(String radio) {
        StringBuilder stop = new StringBuilder();
        for (String s : OFF_SERVICES) stop.append("[ -x /etc/init.d/").append(s).append(" ] && /etc/init.d/").append(s).append(" stop; ");
        return "( sleep 180; [ \"$(uci -q get wireless." + radio + ".disabled)\" = \"1\" ] && { " + stop
                + "logger -t ycc-halow \"HaLow is off: openmanetd and checkstat stopped (3 min after start-up)\"; } ) >/dev/null 2>&1 &";
    }
    private static boolean hasService(UbusApi u, String name) {
        try {
            return u.call("luci", "getInitList", new JSONObject().put("name", name)).optJSONObject(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shell for the node's one-off crontab lines: the same service switch (stop only, a restart brings them back). */
    static String servicesShell(boolean on) {
        StringBuilder b = new StringBuilder();
        for (String s : OFF_SERVICES) {
            String start = "checkstat".equals(s) ? "{ grep -q 'YCC hd01-quiet' /etc/rc.local || /etc/init.d/checkstat start; }" : "/etc/init.d/" + s + " start";
            b.append("; [ -x /etc/init.d/").append(s).append(" ] && /etc/init.d/").append(s).append(on ? " enable && " + start : " stop");
        }
        return b.toString();
    }

    static String morseRadio(UbusApi u) throws Exception {
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "wireless")).optJSONObject("values");
        if (all == null) return null;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = all.optJSONObject(k);
            if (s != null && "wifi-device".equals(s.optString(".type")) && "morse".equals(s.optString("type"))) return k;
        }
        return null;
    }

    /**
     * Only calls LuCI itself uses (HT-HD01 firmware grants the web login nothing else): LuCI's wireless status, and
     * running /sbin/wifi (allowed for LuCI's network page; the ACL is on the program, so arguments are fine).
     */
    static boolean isUp(UbusApi u, String radio) throws Exception {
        JSONObject st = u.call("luci-rpc", "getWirelessDevices", null);
        JSONObject r = st.optJSONObject(radio);
        return r != null && r.optBoolean("up", false);
    }

    static void wifi(UbusApi u, String action, String radio) throws Exception {
        org.json.JSONArray p = new org.json.JSONArray().put(action);
        if (radio != null) p.put(radio);
        u.call("file", "exec", new JSONObject().put("command", "/sbin/wifi").put("params", p));
    }

    /**
     * turn: null = only read, true = on, false = off. A busy node (an HT-HD01 at high load) can time out or have its web
     * server answer "Access denied" when it cannot check the login in time, so those are tried again (3 tries, 20 s each).
     */
    static Result work(String host, String user, String pass, Boolean turn) {
        return work(host, user, pass, turn, UNTIL_REBOOT, -1);
    }

    /** What the node call is doing right now, shown under "Turning HaLow off…" so a slow node is not a silent wait. */
    static volatile String step = "";

    static Result work(String host, String user, String pass, Boolean turn, int mode, int atMin) {
        Result r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            tryText = attempt == 0 ? "" : "try " + (attempt + 1) + " of 3 (" + (r != null && r.error != null ? shortErr(r.error) : "no answer") + ") – ";
            step = tryText;
            r = workOnce(host, user, pass, turn, mode, atMin);
            if (r.error == null || !r.retry) return r;
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                return r;
            }
        }
        r.error += " (tried 3 times: the node may be very busy; check its load on the OpenMANET Mesh page)";
        return r;
    }

    private static String shortErr(String e) {
        return e.length() > 60 ? e.substring(0, 60) + "…" : e;
    }

    private static volatile String tryText = "";

    private static void step(String s) {
        step = tryText + s;
    }

    static Result workOnce(String host, String user, String pass, Boolean turn, int mode, int atMin) {
        Result r = new Result();
        UbusApi u = new UbusApi(host, 20000);
        String usr = user == null || user.length() == 0 ? "root" : user;
        try {
            try {
                step("logging in to " + host + " (" + org.communitycomms.app.net.LanNet.routeFor(host) + ")");
                u.login(usr, pass);
            } catch (UbusApi.UbusException e) {
                if (e.noAnswer()) {
                    r.retry = true;
                    r.error = "no answer from its web API (port 80 /ubus): " + e.getMessage();
                } else if ("Access denied".equalsIgnoreCase(e.getMessage())) {
                    r.retry = true;
                    r.error = "its web server said Access denied before checking the login (busy node?)";
                } else if (e.status == 6 || e.status == -401) {
                    r.error = "the node refused user " + usr + " and this password on its LuCI web API (/ubus). Check the same "
                            + "login works on its LuCI page, http://" + host + "/cgi-bin/luci";
                } else {
                    r.error = "web API login failed: " + e.getMessage();
                    r.retry = e.status == -2 || e.status == -3;
                }
                return r;
            }
            step("logged in; finding the HaLow radio");
            r.radio = morseRadio(u);
            if (r.radio != null && turn != null) step("logged in; setting " + (turn ? "on" : "off") + " options");
            if (r.radio == null) {
                r.error = "this node has no HaLow (morse) radio";
                return r;
            }
            if (turn != null) {
                if (turn) {
                    // undo whatever kept it off: a pending timer line, and the persistent "until I turn it on"
                    try {
                        clearCron(u);
                    } catch (Exception e) {
                        r.note = "could not tidy its crontab (" + e.getMessage() + ")";
                    }
                    persistOff(u, r.radio, false);
                    if (mode == ON_5 || mode == ON_15) {
                        try {
                            String c = checkClock(u, true);
                            if (c != null) r.note = (r.note != null ? r.note + "; " : "") + c;
                        } catch (UbusApi.UbusException e) {
                            r.note = "could not check the node's clock (" + e.getMessage() + ")";
                        }
                        r.untilMs = scheduleOn(u, r.radio, mode, -1); // before going up: if this fails, HaLow stays as it is
                    }
                } else if (mode == UNTIL_ON) {
                    clearCron(u); // a pending "off again" or "on again" timer
                    persistOff(u, r.radio, true);
                } else if (mode == UNTIL_REBOOT) {
                    clearCron(u);
                } else if (mode == MIN30 || mode == HOUR1 || mode == AT) {
                    try {
                        r.note = checkClock(u, true); // timers run on the node's clock
                    } catch (UbusApi.UbusException e) {
                        r.note = "could not check the node's clock (" + e.getMessage() + ")";
                    }
                    r.untilMs = scheduleOn(u, r.radio, mode, atMin); // before going down: if this fails, HaLow stays on
                }
                if (!turn) { // OpenMANET's pollers first: with no HaLow they only load the node
                    step("stopping openmanetd / checkstat");
                    String n = services(u, false, mode == UNTIL_ON ? r.radio : null);
                    if (n != null) r.note = (r.note != null ? r.note + "; " : "") + n;
                }
                step("sending radio " + (turn ? "up" : "down"));
                try {
                    wifi(u, turn ? "up" : "down", r.radio);
                } catch (UbusApi.UbusException e) {
                    if (!e.noAnswer()) throw e; // the radio change can drop this very call
                }
                for (int i = 0; i < 8; i++) { // the radio takes a few seconds
                    step("sent; waiting for the radio to go " + (turn ? "up" : "down") + " (check " + (i + 1) + " of 8)");
                    Thread.sleep(1500);
                    try {
                        r.up = isUp(u, r.radio);
                        if (r.up == turn) break;
                    } catch (UbusApi.UbusException e) {
                        if (!e.noAnswer()) throw e;
                    }
                }
                if (turn) { // the radio is up (or on its way): OpenMANET's services back on, and on at start-up again
                    step("starting openmanetd / checkstat");
                    String n = services(u, true, null);
                    if (n != null) r.note = (r.note != null ? r.note + "; " : "") + n;
                }
            } else {
                r.up = isUp(u, r.radio);
            }
        } catch (UbusApi.UbusException e) {
            r.error = e.denied() ? "the login worked but this call was denied: " + e.getMessage() : e.getMessage();
            r.retry = e.noAnswer() && (turn == null || turn); // re-read, or send "on" again (safe to repeat); a lost "off" is not repeated
        } catch (Exception e) {
            r.error = String.valueOf(e.getMessage());
        } finally {
            u.logout();
        }
        return r;
    }

    // ---- the dialog (OpenMANET Mesh page, home screen bar) ----

    public static void dialog(final Activity a) {
        dialog(a, false);
    }

    /** turnOnNow: from the red bar: send "HaLow on" at once (no reading first). */
    public static void dialog(final Activity a, final boolean turnOnNow) {
        if (MeshRadio.adminPass() == null) {
            MeshRadio.askLogin(a, false, new Runnable() {
                @Override
                public void run() {
                    if (MeshRadio.adminPass() != null) dialog(a, turnOnNow);
                }
            });
            return;
        }
        final String[] dn = directNode(a);
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView t = new TextView(a);
        t.setText("Turns the HaLow mesh radio of the node this phone is connected to off (radio silence: less RF to be found) "
                + "or back on. The phone stays connected to the node over its WiFi or Ethernet. When turning it off you choose "
                + "how long for.");
        box.addView(t);
        final TextView st = new TextView(a);
        st.setTextSize(18);
        st.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        box.addView(st);
        LinearLayout row = new LinearLayout(a);
        final Button off = new Button(a), on = new Button(a);
        off.setAllCaps(false);
        on.setAllCaps(false);
        off.setText("Turn HaLow OFF…");
        on.setText("Turn HaLow ON");
        row.addView(off, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(on, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        final Button win = new Button(a);
        win.setAllCaps(false);
        win.setText("Radio windows (advanced)…" + (HalowWindows.saved().length() > 0 ? "  " + HalowWindows.saved() : ""));
        box.addView(win);
        final CheckBox show = new CheckBox(a);
        show.setText("Show the HaLow bar on the home screen (green on, red off)");
        show.setChecked(barOn());
        show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                sp().edit().putBoolean("halow_bar", c).commit();
            }
        });
        box.addView(show);
        if (dn == null) {
            off.setEnabled(false);
            on.setEnabled(false);
            win.setEnabled(false);
            st.setText("Not connected to a mesh node directly (no OpenMANET gateway / DHCP server on this phone's WiFi or Ethernet).");
        } else {
            final String host = dn[0];
            final String nm = nodeName(host);
            final Handler ui = new Handler(Looper.getMainLooper());
            final View.OnClickListener act = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (v == on) {
                        askOn(a, nm, new OffChoice() {
                            @Override
                            public void go(int mode, int atMin) {
                                turn(ui, st, off, on, host, nm, true, mode, -1);
                            }
                        });
                        return;
                    }
                    askOff(a, nm, new OffChoice() {
                        @Override
                        public void go(int mode, int atMin) {
                            turn(ui, st, off, on, host, nm, false, mode, atMin);
                        }
                    });
                }
            };
            off.setOnClickListener(act);
            on.setOnClickListener(act);
            win.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HalowWindows.dialog(a, host, nm, new Runnable() {
                        @Override
                        public void run() {
                            win.setText("Radio windows (advanced)…" + (HalowWindows.saved().length() > 0 ? "  " + HalowWindows.saved() : ""));
                            st.setText("Reading " + nm + "…");
                            st.setTextColor(0xffb0bec5);
                            call(ui, st, off, on, host, nm, null, UNTIL_REBOOT, -1, gen.incrementAndGet());
                        }
                    });
                }
            });
            // Both buttons work straight away (no waiting for the read); a tap simply sends.
            off.setEnabled(true);
            on.setEnabled(true);
            if (turnOnNow) {
                turn(ui, st, off, on, host, nm, true, ON_UNTIL_OFF, -1); // red bar: straight on, no questions
            } else {
                String last = state();
                st.setText(("off".equals(last) ? "Last known: OFF" + (untilText().length() > 0 ? ", " + untilText() : "") + ". "
                        : "on".equals(last) ? "Last known: ON. " : "") + "Reading " + nm + " (" + host + ", " + dn[1] + ")…");
                st.setTextColor(0xffb0bec5);
                call(ui, st, off, on, host, nm, null, UNTIL_REBOOT, -1, gen.incrementAndGet());
            }
        }
        new AlertDialog.Builder(a).setTitle("HaLow on my node").setView(box).setPositiveButton("Close", null).show();
        if (!sp().contains("halow_bar")) sp().edit().putBoolean("halow_bar", true).commit(); // used once: the bar shows from now on
        show.setChecked(barOn());
    }

    interface OffChoice {
        void go(int mode, int atMin);
    }

    /** Turning on: leave it on (default), or off again in 5 or 15 minutes (a one-off timer on the node). */
    static void askOn(final Activity a, final String nm, final OffChoice then) {
        final int[] modes = {ON_UNTIL_OFF, ON_5, ON_15};
        final int[] pick = {0};
        new AlertDialog.Builder(a).setTitle("HaLow on at " + nm)
                .setSingleChoiceItems(ON_NAMES, 0, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        pick[0] = which;
                    }
                })
                .setPositiveButton("Turn HaLow on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        then.go(modes[pick[0]], -1);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Turning off: what it means, and how long for (until I turn it on, until a restart, 30 min, 1 h, at a time). */
    static void askOff(final Activity a, final String nm, final OffChoice then) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView w = new TextView(a);
        w.setText("• " + nm + " leaves the mesh: it stops relaying, and nodes that reach the mesh only through it drop out.\n"
                + "• If " + nm + " is the mesh's internet gateway, the whole mesh loses the internet.\n"
                + "• This phone (and others on " + nm + "'s WiFi) can then only reach " + nm + " itself.\n"
                + "• OpenMANET's own services (openmanetd, and checkstat on HT-HD01) are stopped too: with no HaLow they only load "
                + "the node. Its OpenMANET page and the mesh node list are unavailable meanwhile; LuCI, WiFi and SSH keep working. "
                + "With \"Until I turn it on\" they start as usual after a restart and are stopped again 3 minutes later. Turning HaLow on here starts them again.\n\nTurn it back on:");
        box.addView(w);
        final android.widget.RadioGroup g = new android.widget.RadioGroup(a);
        final android.widget.RadioButton[] rb = new android.widget.RadioButton[MODE_NAMES.length];
        for (int i = 0; i < MODE_NAMES.length; i++) {
            rb[i] = new android.widget.RadioButton(a);
            rb[i].setId(0x4a10 + i);
            rb[i].setText(MODE_NAMES[i]);
            g.addView(rb[i]);
        }
        rb[UNTIL_ON].setChecked(true);
        box.addView(g);
        final android.widget.EditText at = new android.widget.EditText(a);
        at.setSingleLine(true);
        at.setHint("HH:MM, e.g. 14:30");
        at.setInputType(android.text.InputType.TYPE_CLASS_DATETIME | android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        at.setEnabled(false);
        box.addView(at);
        TextView foot = new TextView(a);
        foot.setTextSize(12);
        foot.setText("Timed choices use the node's own clock and also end if the node restarts. \"Until I turn it on\" also "
                + "keeps HaLow off after a restart: turn it on here (this phone on the node's WiFi or cable).");
        box.addView(foot);
        g.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup gr, int id) {
                at.setEnabled(id == rb[AT].getId());
                if (at.isEnabled()) at.requestFocus();
            }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("HaLow off at " + nm + "?").setView(sv)
                .setPositiveButton("Turn HaLow off", null).setNegativeButton(android.R.string.cancel, null).create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int mode = UNTIL_ON;
                        for (int i = 0; i < rb.length; i++) if (rb[i].isChecked()) mode = i;
                        int atMin = -1;
                        if (mode == AT) {
                            atMin = parseHhmm(at.getText().toString());
                            if (atMin < 0) {
                                at.setError("24 h time, e.g. 14:30");
                                return;
                            }
                        }
                        dlg.dismiss();
                        then.go(mode, atMin);
                    }
                });
            }
        });
        dlg.show();
    }

    static String nodeName(String host) {
        String self = MeshNode.LOCAL.selfName();
        if (host.equals(MeshNode.LOCAL.host()) && self != null && self.length() > 0) return self;
        for (String n : MeshNode.LOCAL.names()) if (host.equals(MeshNode.LOCAL.ipOf(n))) return n; // from the mesh's node list
        String det = MeshRadio.detectedNodeAt(host); // only a name found at this very address (not the node this phone was on before)
        if (det.length() == 0 && host.equals(node()) && sp().getString("halow_name", "").length() > 0) det = name(); // named here before
        return det.length() > 0 ? det : host;
    }

    /** Newest request wins: a read that answers after a tap does not overwrite the tap's result. */
    private static final java.util.concurrent.atomic.AtomicInteger gen = new java.util.concurrent.atomic.AtomicInteger();

    private static void turn(Handler ui, TextView st, Button off, Button on, String host, String nm, boolean turnOn, int mode, int atMin) {
        final String base = (turnOn ? "Turning HaLow on at " : "Turning HaLow off at ") + nm + "…";
        st.setText(base);
        st.setTextColor(0xffb0bec5);
        off.setEnabled(false);
        on.setEnabled(false);
        step = "";
        final int my = gen.incrementAndGet();
        progress(ui, st, off, base, my, System.currentTimeMillis());
        call(ui, st, off, on, host, nm, turnOn, mode, atMin, my);
    }

    /** Once a second while a turn is running: what it is doing and for how long (a slow / gone node is not a silent wait). */
    private static void progress(final Handler ui, final TextView st, final Button off, final String base, final int my, final long t0) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (my != gen.get() || off.isEnabled()) return; // finished (the result is shown) or replaced by a newer tap
                String s = step;
                st.setText(base + "\n" + (s.length() > 0 ? s + " · " : "") + (System.currentTimeMillis() - t0) / 1000 + " s");
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static void call(final Handler ui, final TextView st, final Button off, final Button on, final String host, final String nm,
                             final Boolean turn, final int mode, final int atMin, final int my) {
        final String user = MeshRadio.user(), pass = MeshRadio.adminPass();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Result r = work(host, user, pass, turn, mode, atMin);
                if (my != gen.get()) return; // a newer tap took over
                if (r.error == null) {
                    set(r.up ? "on" : "off", host, nm);
                    if (turn != null && turn) { // also when not confirmed yet (the re-check follows)
                        if (r.untilMs > 0) sp().edit().putLong("halow_off_at", r.untilMs).commit();
                        else sp().edit().remove("halow_off_at").commit();
                    }
                    if (!r.up && turn != null && !turn) {
                        SharedPreferences.Editor e = sp().edit().putInt("halow_mode", mode);
                        if (r.untilMs > 0) e.putLong("halow_until", r.untilMs);
                        else e.remove("halow_until");
                        e.commit();
                    }
                    if (turn != null) NodeShare.log("HaLow turned " + (turn ? "on" : "off") + " at " + nm
                            + (!turn ? " (" + untilText() + ")" : offAtText().length() > 0 ? " (" + offAtText() + ")" : "") + (r.up == turn ? "" : " (not confirmed yet: radio is " + (r.up ? "up" : "down") + ")"));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (my != gen.get()) return;
                        off.setEnabled(true);
                        on.setEnabled(true);
                        if (r.error != null) {
                            st.setText(nm + ": " + r.error);
                            st.setTextColor(0xffffb74d);
                            return;
                        }
                        show(st, off, on, nm, r.up);
                        if (r.note != null) st.append("\n(" + r.note.replace(CLOCK_NTP, "") + ")");
                        if (r.note != null && r.note.contains(CLOCK_NTP) && st.getContext() instanceof Activity) {
                            String txt = r.note.substring(r.note.indexOf(CLOCK_NTP) + CLOCK_NTP.length());
                            askWhichClock((Activity) st.getContext(), nm + ": " + txt + ". (The timer was set by the node's clock.)", new Runnable() {
                                @Override
                                public void run() { // set the node from this phone, then set the timer again by the corrected clock
                                    st.setText("Setting " + nm + "'s clock from this phone…");
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            UbusApi u = new UbusApi(host, 20000);
                                            try {
                                                u.login(user == null || user.length() == 0 ? "root" : user, pass);
                                                checkClock(u, true, true);
                                            } catch (Exception ignored) {
                                            } finally {
                                                u.logout();
                                            }
                                            ui.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    call(ui, st, off, on, host, nm, turn, mode, atMin, gen.incrementAndGet());
                                                }
                                            });
                                        }
                                    }, "node-clock").start();
                                }
                            });
                        }
                        if (turn != null && r.up != turn) {
                            st.append("\nNot confirmed yet: checking again every 5 s…");
                            recheck(ui, st, off, on, host, nm, turn, my, 0);
                        }
                    }
                });
            }
        }, "halow-toggle").start();
    }

    private static void show(TextView st, Button off, Button on, String nm, boolean up) {
        String u = untilText();
        String o = offAtText();
        st.setText(up ? "● HaLow is ON at " + nm + (o.length() > 0 ? "\n" + o : "") : "● HaLow is OFF at " + nm + " (radio silent)" + (u.length() > 0 ? "\n" + u : ""));
        st.setTextColor(up ? 0xff66bb6a : 0xffef5350);
    }

    /** After a switch that did not show yet (HaLow can take a while to come up): read again every 5 s for up to 2 minutes. */
    private static void recheck(final Handler ui, final TextView st, final Button off, final Button on, final String host, final String nm,
                                final boolean want, final int my, final int n) {
        final String user = MeshRadio.user(), pass = MeshRadio.adminPass();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (my != gen.get()) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Result r = workOnce(host, user, pass, null, UNTIL_REBOOT, -1);
                        if (my != gen.get()) return;
                        if (r.error == null) {
                            set(r.up ? "on" : "off", host, nm);
                            if (r.up == want) NodeShare.log("HaLow at " + nm + " now confirmed " + (want ? "on" : "off"));
                        }
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (my != gen.get()) return;
                                if (r.error == null) show(st, off, on, nm, r.up);
                                if (r.error == null && r.up == want) return; // confirmed
                                if (n + 1 >= 24) {
                                    st.append("\nStill not confirmed after 2 minutes. Tap the button again, or check the node.");
                                    return;
                                }
                                st.append("\nNot confirmed yet (" + ((n + 1) * 5) + " s): checking again…"
                                        + (r.error != null ? " (" + r.error + ")" : ""));
                                recheck(ui, st, off, on, host, nm, want, my, n + 1);
                            }
                        });
                    }
                }, "halow-recheck").start();
            }
        }, 5000);
    }
}
