package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.communitycomms.app.geo.LanTiles;
import org.communitycomms.app.net.Tunnel;

/**
 * Shortcuts > Servers: checks each server this phone is set to use, only when opened or on Check again (never by
 * itself): the update address, the LAN storage, the secondary map server, the Cloud group relay, the internet relay
 * and the tunnel relay. Each gets a green (answered), red (no answer or refused) or grey (not set) dot. The checks
 * only read (GET, or a TCP connect that is closed at once): nothing on a server is changed. A LAN section shows the
 * phone's WiFi, addresses and gateway. Passwords are never shown.
 */
final class ServerStatus {
    private ServerStatus() {
    }

    static final int GREY = 0, GREEN = 1, RED = 2;

    static final class Item {
        final String name, address;
        int state = GREY;
        String detail = "Not set";

        Item(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The Shortcuts row: the last check, if any (nothing is checked here). */
    static String summary(Context c) {
        long at = sp(c).getLong("srvCheckAt", 0);
        if (at == 0) return "Servers: tap to check";
        int ok = sp(c).getInt("srvCheckOk", 0), n = sp(c).getInt("srvCheckSet", 0);
        String t = android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(at));
        return n == 0 ? "Servers: none set up (checked " + t + ")" : "Servers: " + ok + " of " + n + " OK at " + t + " (tap to check)";
    }

    /** Red if the last check had a failure, for the Shortcuts row colour; 0 = no colour. */
    static int lastColour(Context c) {
        if (sp(c).getLong("srvCheckAt", 0) == 0 || sp(c).getInt("srvCheckSet", 0) == 0) return 0;
        return sp(c).getInt("srvCheckOk", 0) < sp(c).getInt("srvCheckSet", 0) ? 0xffef9a9a : 0xffa5d6a7;
    }

    static java.util.List<Item> items(Activity a) {
        Prefs p = new Prefs(a);
        java.util.List<Item> l = new java.util.ArrayList<Item>();
        l.add(new Item("Update / mission server", noUser(UpdateFromUrl.url(a))));
        LanTiles lan = new LanTiles(a);
        l.add(new Item("LAN storage (local cache server)", lan.configured() ? noUser(lan.base()) : ""));
        LanTiles lan2 = LanTiles.secondary(a);
        l.add(new Item("Secondary map server", lan2 != null && lan2.configured() ? noUser(lan2.base()) : ""));
        l.add(new Item("Cloud group relay" + (p.cloudSet() && p.cloudName().length() > 0 ? " (" + p.cloudName() + ")" : ""),
                p.cloudRelaySet() ? p.cloudRelay().trim() : ""));
        l.add(new Item("Internet relay (Cloud Server)", p.vpsServer().length() > 0 ? p.vpsServer() : ""));
        if (p.tunnelServer().length() > 0) l.add(new Item("Tunnel relay", p.tunnelServer()));
        return l;
    }

    /** Runs one check (on a background thread). */
    static void check(Activity a, int i, Item it) {
        if (it.address.length() == 0) {
            it.state = GREY;
            it.detail = "Not set";
            return;
        }
        long t0 = System.currentTimeMillis();
        if (i == 0) {
            String u = UpdateFromUrl.url(a);
            String v = u.substring(0, u.lastIndexOf('/') + 1) + "version.txt";
            if (u.contains("/Personal/")) v = u.substring(0, u.indexOf("/Personal/")) + "/Updates/version.txt";
            int code = httpCode(v);
            ms(it, t0, code == 200, code == 200 ? "" : code < 0 ? "No answer" : code == 404 ? "Answered, but no version.txt there (404)" : "Answered " + code);
        } else if (i == 1 || i == 2) {
            LanTiles l = i == 1 ? new LanTiles(a) : LanTiles.secondary(a);
            String[] err = {""};
            int code = l == null ? -1 : l.probeRoot(err);
            boolean ok = code >= 200 && code < 400;
            ms(it, t0, ok, ok ? (l.hasPassword() || l.user().length() > 0 ? "login accepted" : "") : code == 401 || code == 403
                    ? "Login failed (" + code + ")" : code < 0 ? "No answer" + (err[0].length() > 0 ? " (" + err[0] + ")" : "") : "Answered " + code);
        } else {
            Tunnel.Client live = i == 3 ? Tunnel.cloud : i == 4 ? Tunnel.vps : Tunnel.client;
            boolean linkUp = live != null && live.connected();
            boolean tcp = tcpOpen(it.address);
            if (linkUp) ms(it, t0, true, "relay answering, connected");
            else if (tcp) ms(it, t0, true, "relay port open" + (live == null ? " (not in use now)" : ", link not up yet"));
            else ms(it, t0, false, live == null ? "No answer (not in use now)" : "No answer; " + live.state);
        }
    }

    private static void ms(Item it, long t0, boolean ok, String why) {
        it.state = ok ? GREEN : RED;
        it.detail = ok ? "OK " + (System.currentTimeMillis() - t0) + " ms" + (why.length() > 0 ? ", " + why : "") : why;
    }

    private static int httpCode(String u) {
        java.net.HttpURLConnection h = null;
        try {
            for (int hop = 0; hop < 4; hop++) { // GitHub's release links redirect to another host
                h = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(6000);
                h.setReadTimeout(8000);
                int code = h.getResponseCode();
                if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                    u = new java.net.URL(new java.net.URL(u), h.getHeaderField("Location")).toString();
                    h.disconnect();
                    continue;
                }
                return code;
            }
        } catch (Exception ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
        return -1;
    }

    private static boolean tcpOpen(String hostPort) {
        int c = hostPort.lastIndexOf(':');
        if (c <= 0) return false;
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(hostPort.substring(0, c).replace("[", "").replace("]", ""),
                    Integer.parseInt(hostPort.substring(c + 1).trim())), 5000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** An address without any user:password@ part. */
    private static String noUser(String u) {
        if (u == null) return "";
        return u.replaceFirst("://[^/@]*@", "://");
    }

    /** WiFi name, the phone's addresses, the gateway, OpenMANET. */
    static String lanInfo(Activity a) {
        StringBuilder sb = new StringBuilder();
        Prefs p = new Prefs(a);
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (w != null && w.isWifiEnabled()) {
                android.net.wifi.WifiInfo wi = w.getConnectionInfo();
                String ssid = wi == null ? null : wi.getSSID();
                if (ssid == null || ssid.contains("unknown ssid")) ssid = "(name hidden by Android: needs location on)";
                sb.append("WiFi: ").append(ssid.replace("\"", "")).append('\n');
                android.net.DhcpInfo d = w.getDhcpInfo();
                if (d != null && d.gateway != 0) sb.append("Gateway: ").append(ip4(d.gateway)).append('\n');
            } else sb.append("WiFi: off\n");
        } catch (Exception e) {
            sb.append("WiFi: unknown\n");
        }
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress ad : java.util.Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof java.net.Inet4Address) sb.append("This phone: ").append(ad.getHostAddress()).append(" (").append(ni.getName()).append(")\n");
                }
            }
        } catch (Exception ignored) {
        }
        sb.append("OpenMANET: ").append(p.showOpenManet() ? "on (MANET range " + p.manetRange() + ")" : "off");
        return sb.toString();
    }

    private static String ip4(int i) {
        return (i & 255) + "." + (i >> 8 & 255) + "." + (i >> 16 & 255) + "." + (i >> 24 & 255);
    }

    /** The Server status window; checks start as soon as it opens. */
    static void show(final Activity a, final Runnable closed) {
        final float d = a.getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows);
        TextView h = new TextView(a);
        h.setText("This phone on the network");
        h.setTextSize(13);
        h.setTextColor(0xff99aabb);
        h.setPadding(0, (int) (14 * d), 0, (int) (2 * d));
        box.addView(h);
        final TextView lan = new TextView(a);
        lan.setTextSize(14);
        lan.setTextColor(0xffe8f5e9);
        box.addView(lan);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Server status")
                .setView(sv)
                .setNeutralButton("Check again", null)
                .setPositiveButton("Close", null).create();
        final Runnable run = new Runnable() {
            @Override
            public void run() {
                runChecks(a, rows, lan, d);
            }
        };
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                Button again = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                again.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        run.run();
                    }
                });
                run.run();
            }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                if (closed != null) closed.run();
            }
        });
        dlg.show();
    }

    private static void runChecks(final Activity a, final LinearLayout rows, final TextView lan, final float d) {
        final java.util.List<Item> list = items(a);
        final java.util.List<TextView[]> views = new java.util.ArrayList<TextView[]>();
        rows.removeAllViews();
        for (Item it : list) {
            LinearLayout r = new LinearLayout(a);
            r.setBaselineAligned(false); // a multi-line text beside the dot must not be cut off at the bottom
            r.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
            TextView dot = new TextView(a);
            dot.setText("●");
            dot.setTextSize(20);
            dot.setTextColor(0xff9e9e9e);
            dot.setPadding(0, 0, (int) (10 * d), 0);
            TextView t = new TextView(a);
            t.setTextSize(14);
            t.setTextColor(0xffe8f5e9);
            r.addView(dot);
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            rows.addView(r);
            views.add(new TextView[]{dot, t});
            paint(it, dot, t, it.address.length() > 0 ? "Checking…" : "Not set");
        }
        lan.setText(lanInfo(a));
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < list.size(); i++) {
                    final int k = i;
                    check(a, i, list.get(i));
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            paint(list.get(k), views.get(k)[0], views.get(k)[1], list.get(k).detail);
                        }
                    });
                }
                int ok = 0, set = 0;
                for (Item it : list) {
                    if (it.address.length() > 0) set++;
                    if (it.state == GREEN) ok++;
                }
                sp(a).edit().putLong("srvCheckAt", System.currentTimeMillis()).putInt("srvCheckOk", ok).putInt("srvCheckSet", set).commit();
            }
        }, "server-status").start();
    }

    private static void paint(Item it, TextView dot, TextView t, String detail) {
        dot.setTextColor(it.state == GREEN ? 0xff43a047 : it.state == RED ? 0xffe53935 : 0xff9e9e9e);
        t.setText(it.name + "\n" + (it.address.length() > 0 ? it.address + "\n" : "") + detail);
    }
}
