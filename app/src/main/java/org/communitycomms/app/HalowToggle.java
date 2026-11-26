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
        sp().edit().putString("halow_state", state).putString("halow_node", host).putString("halow_name", name == null ? "" : name)
                .putLong("halow_at", System.currentTimeMillis()).commit();
    }

    public static boolean barOn() {
        return sp().getBoolean("halow_bar", false);
    }

    /** The home screen bar: null = none. [text, colour]. */
    public static Object[] bar(android.content.Context c) {
        if (!barOn() || !MeshRadio.onMeshLan(c)) return null;
        String s = state(), n = name();
        if ("off".equals(s)) return new Object[]{"● HaLow OFF on " + n + ": mesh radio silent. Tap to turn it on", 0xffc62828};
        if ("on".equals(s)) return new Object[]{"● HaLow ON on " + n, 0xff2e7d32};
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
        String radio, error;
        boolean up, retry;
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

    static boolean isUp(UbusApi u, String radio) throws Exception {
        JSONObject st = u.call("network.wireless", "status", new JSONObject().put("device", radio));
        JSONObject r = st.optJSONObject(radio);
        return r != null && r.optBoolean("up", false);
    }

    /**
     * turn: null = only read, true = on, false = off. A busy node (an HT-HD01 at high load) can time out or have its web
     * server answer "Access denied" when it cannot check the login in time, so those are tried again (3 tries, 20 s each).
     */
    static Result work(String host, String user, String pass, Boolean turn) {
        Result r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            r = workOnce(host, user, pass, turn);
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

    static Result workOnce(String host, String user, String pass, Boolean turn) {
        Result r = new Result();
        UbusApi u = new UbusApi(host, 20000);
        String usr = user == null || user.length() == 0 ? "root" : user;
        try {
            try {
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
            r.radio = morseRadio(u);
            if (r.radio == null) {
                r.error = "this node has no HaLow (morse) radio";
                return r;
            }
            if (turn != null) {
                try {
                    u.call("network.wireless", turn ? "up" : "down", new JSONObject().put("device", r.radio));
                } catch (UbusApi.UbusException e) {
                    if (e.status == 4 || e.status == 3) // no such method on an older OpenWrt: the wifi script does the same
                        u.call("file", "exec", new JSONObject().put("command", "/sbin/wifi").put("params", new org.json.JSONArray().put(turn ? "up" : "down").put(r.radio)));
                    else if (!e.noAnswer()) throw e;
                }
                for (int i = 0; i < 8; i++) { // the radio takes a few seconds
                    Thread.sleep(1500);
                    try {
                        r.up = isUp(u, r.radio);
                        if (r.up == turn) break;
                    } catch (UbusApi.UbusException e) {
                        if (!e.noAnswer()) throw e;
                    }
                }
            } else {
                r.up = isUp(u, r.radio);
            }
        } catch (UbusApi.UbusException e) {
            r.error = e.denied() ? "the login worked but this call was denied: " + e.getMessage() : e.getMessage();
            r.retry = e.noAnswer() && turn == null; // only re-read; a switch that got lost is re-read by the next open
        } catch (Exception e) {
            r.error = String.valueOf(e.getMessage());
        } finally {
            u.logout();
        }
        return r;
    }

    // ---- the dialog (OpenMANET Mesh page, home screen bar) ----

    public static void dialog(final Activity a) {
        if (MeshRadio.adminPass() == null) {
            MeshRadio.askLogin(a, false, new Runnable() {
                @Override
                public void run() {
                    if (MeshRadio.adminPass() != null) dialog(a);
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
                + "or back on. The phone stays connected to the node over its WiFi or Ethernet. Runtime only: if the node "
                + "restarts, HaLow comes back on.");
        box.addView(t);
        final TextView st = new TextView(a);
        st.setTextSize(18);
        st.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        box.addView(st);
        LinearLayout row = new LinearLayout(a);
        final Button off = new Button(a), on = new Button(a);
        off.setAllCaps(false);
        on.setAllCaps(false);
        off.setText("Turn HaLow OFF");
        on.setText("Turn HaLow ON");
        row.addView(off, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(on, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
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
        off.setEnabled(false);
        on.setEnabled(false);
        if (dn == null) {
            st.setText("Not connected to a mesh node directly (no OpenMANET gateway / DHCP server on this phone's WiFi or Ethernet).");
        } else {
            final String host = dn[0];
            final String nm = nodeName(host);
            final Handler ui = new Handler(Looper.getMainLooper());
            final View.OnClickListener act = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final boolean turnOn = v == on;
                    Runnable run = new Runnable() {
                        @Override
                        public void run() {
                            off.setEnabled(false);
                            on.setEnabled(false);
                            st.setText((turnOn ? "Turning HaLow on at " : "Turning HaLow off at ") + nm + "…");
                            st.setTextColor(0xffb0bec5);
                            call(ui, st, off, on, host, nm, turnOn);
                        }
                    };
                    if (turnOn) {
                        run.run();
                        return;
                    }
                    final Runnable r2 = run;
                    new AlertDialog.Builder(a).setTitle("HaLow off at " + nm + "?")
                            .setMessage("• " + nm + " leaves the mesh: it stops relaying, and nodes that reach the mesh only through "
                                    + "it drop out.\n• If " + nm + " is the mesh's internet gateway, the whole mesh loses the internet.\n"
                                    + "• This phone (and others on " + nm + "'s WiFi) can then only reach " + nm + " itself: no calls or "
                                    + "chat over the mesh.\n• Turn it back on here, from the red bar, or by restarting the node.")
                            .setPositiveButton("Turn HaLow off", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface di, int w) {
                                    r2.run();
                                }
                            }).setNegativeButton(android.R.string.cancel, null).show();
                }
            };
            off.setOnClickListener(act);
            on.setOnClickListener(act);
            st.setText("Reading " + nm + " (" + host + ", " + dn[1] + ")…");
            call(ui, st, off, on, host, nm, null);
        }
        new AlertDialog.Builder(a).setTitle("HaLow on my node").setView(box).setPositiveButton("Close", null).show();
        if (!sp().contains("halow_bar")) sp().edit().putBoolean("halow_bar", true).commit(); // used once: the bar shows from now on
        show.setChecked(barOn());
    }

    static String nodeName(String host) {
        String self = MeshNode.LOCAL.selfName();
        if (host.equals(MeshNode.LOCAL.host()) && self != null && self.length() > 0) return self;
        for (String n : MeshNode.LOCAL.names()) if (host.equals(MeshNode.LOCAL.ipOf(n))) return n; // from the mesh's node list
        String det = MeshRadio.detectedNode();
        return det != null && det.length() > 0 ? det : host;
    }

    private static void call(final Handler ui, final TextView st, final Button off, final Button on, final String host, final String nm,
                             final Boolean turn) {
        final String user = MeshRadio.user(), pass = MeshRadio.adminPass();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Result r = work(host, user, pass, turn);
                if (r.error == null) {
                    set(r.up ? "on" : "off", host, nm);
                    if (turn != null) NodeShare.log("HaLow turned " + (turn ? "on" : "off") + " at " + nm + (r.up == turn ? "" : " (not confirmed: radio is " + (r.up ? "up" : "down") + ")"));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r.error != null) {
                            st.setText(nm + ": " + r.error);
                            st.setTextColor(0xffffb74d);
                            off.setEnabled(true);
                            on.setEnabled(true);
                            return;
                        }
                        st.setText(r.up ? "● HaLow is ON at " + nm : "● HaLow is OFF at " + nm + " (radio silent)");
                        st.setTextColor(r.up ? 0xff66bb6a : 0xffef5350);
                        off.setEnabled(r.up);
                        on.setEnabled(!r.up);
                        if (turn != null && r.up != turn) st.append("\nNot confirmed yet: close this and open it again in a moment to re-read.");
                    }
                });
            }
        }, "halow-toggle").start();
    }
}
