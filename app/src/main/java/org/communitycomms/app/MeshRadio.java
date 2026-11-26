package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.net.OpenManetApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * This phone's own OpenMANET node, read-only: which node the phone is on ("My mesh node", found from the WiFi name or
 * picked), and {@link MeshNode#LOCAL} polling it for its neighbours (signal, throughput, last seen), the mesh layout
 * (hops, next hops) and its load. Used by the Mesh radio page, the title bar signal icon and the Mapping walk (the
 * walker's side of the link). Polls every 10 s while the page is open or a walk runs, every minute (settable) for the
 * icon, and not at all in Ultra low / Away / Offline or with the screen off. One request at a time; 30 s when busy.
 *
 * The login is the Mesh controller's: the user name it keeps, and its saved password only if Remember was ticked
 * there or here. Otherwise the password is held in memory until YCC is closed.
 */
public final class MeshRadio {
    private static final String TAG = "MeshRadio";
    static volatile boolean viewOpen;
    private static volatile String pass;
    private static volatile String detected = "", detectedFrom = "";
    private static volatile long lastDetectMs;

    private MeshRadio() {
    }

    static SharedPreferences ctl() {
        return MeshTest.app.getSharedPreferences("meshctl", Context.MODE_PRIVATE);
    }

    /** OpenMANET is on (its channel choice or the Mesh controller). */
    public static boolean available(Context c) {
        Prefs p = new Prefs(c);
        return p.showOpenManet() || p.menuOn("meshctl") || p.menuOn(MeshTest.K_MAP); // OpenMANET, Mesh controller or Map mesh / walk
    }

    private static volatile boolean meshLan;
    private static volatile long meshLanAt;

    /** This phone has an address on the OpenMANET network (Settings MANET range, 10.41.0.0/16 by default); checked every 5 s at most. */
    public static boolean onMeshLan(Context c) {
        long now = System.currentTimeMillis();
        if (now - meshLanAt > 5000) {
            meshLanAt = now;
            try {
                meshLan = RadioService.inRange(RadioService.localIpv4(), new Prefs(c).manetRange());
            } catch (RuntimeException e) {
                meshLan = false;
            }
        }
        return meshLan;
    }

    public static boolean iconOn() {
        return MeshTest.sp().getBoolean("radio_icon", true);
    }

    public static int bgSec() {
        return Math.max(30, Math.min(600, MeshTest.sp().getInt("radio_bg", 60)));
    }

    public static int walkSec() {
        return Math.max(5, Math.min(60, MeshTest.sp().getInt("walk_local_every", 10)));
    }

    public static boolean walkLocal() {
        return MeshTest.sp().getBoolean("walk_local", true);
    }

    private static final MeshNode.IntervalRule RULE = new MeshNode.IntervalRule() {
        @Override
        public int seconds() {
            if (MeshTest.app == null) return 0;
            if (viewOpen) return 10;
            if (MeshWalk.running() && walkLocal()) return walkSec();
            if (iconOn() && available(MeshTest.app) && onMeshLan(MeshTest.app) && PowerMode.meshIconPollOk()) return bgSec(); // off the mesh: no background polling
            return 0;
        }
    };

    /** True when only a login shared by the team admin is held (no own login). */
    static boolean usingShared() {
        return pass == null && ctl().getString("savedPass", null) == null && NodeShare.held();
    }

    public static String user() {
        return usingShared() ? NodeShare.user() : ctl().getString("user", "root");
    }

    private static String savedPass() {
        return ctl().getString("savedPass", null);
    }

    public static boolean hasLogin() {
        return pass != null || savedPass() != null || NodeShare.held() || RoLogin.held();
    }

    /** The admin password held now (own, remembered or shared), or null. */
    static String adminPass() {
        return pass != null ? pass : savedPass() != null ? savedPass() : NodeShare.pass();
    }

    /** An admin login (own, remembered or shared), not counting the read-only one. */
    public static boolean hasAdmin() {
        return pass != null || savedPass() != null || NodeShare.held();
    }

    public static boolean polling() {
        return MeshNode.LOCAL.polling();
    }

    /** Start polling if there is a login and nothing is polling yet (the page opening, a walk starting, the app starting). */
    private static volatile long lastWebName;

    /** WiFi changed: find my node again from the new gateway and restart the node polling there. */
    public static void networkChanged(Context c) {
        if (MeshTest.app == null) return;
        detected = "";
        detectedFrom = "";
        lastWebName = 0;
        MeshNode.LOCAL.stop();
        ensure(c);
    }

    /**
     * No login needed: the node at my gateway / DHCP server names itself in its web page title ("HT01 - LuCI", even on
     * the 403 page), so "my node" is known before (or without) any login. At most every 30 s, off the main thread.
     */
    public static void nameFromWeb(final Context c) {
        if (picked() || !available(c) || !onMeshLan(c) || System.currentTimeMillis() - lastWebName < 30000) return;
        lastWebName = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] dh = detectHost(c);
                if (dh == null) return;
                java.net.HttpURLConnection h = null;
                try {
                    h = (java.net.HttpURLConnection) new java.net.URL("http://" + dh[0] + "/cgi-bin/luci/").openConnection();
                    h.setConnectTimeout(5000);
                    h.setReadTimeout(5000);
                    int code = h.getResponseCode();
                    java.io.InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                    if (in == null) return;
                    byte[] buf = new byte[4096];
                    int n, got = 0;
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    while (got < 16384 && (n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        got += n;
                    }
                    in.close();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<title>\\s*([^<]*?)\\s*-\\s*LuCI\\s*</title>").matcher(bo.toString("UTF-8"));
                    if (m.find() && m.group(1).trim().length() > 0 && !picked()) {
                        String nm = MeshNode.clean(m.group(1).trim());
                        if (!nm.equals(detected)) Log.i(TAG, "my node by " + dh[1] + " (its web page): " + nm);
                        detected = nm;
                        detectedFrom = dh[1];
                    }
                } catch (Exception e) {
                    Log.d(TAG, "web name: " + e.getMessage());
                } finally {
                    if (h != null) h.disconnect();
                }
            }
        }, "mesh-node-name").start();
    }

    public static void ensure(Context c) {
        nameFromWeb(c);
        if (MeshNode.LOCAL.polling() || !available(c)) return;
        String p = pass != null ? pass : savedPass() != null ? savedPass() : NodeShare.pass();
        boolean ro = RoLogin.held(); // the read-only login first; the admin login (if any) is the fallback
        if (p == null && !ro) return;
        MeshNode.LOCAL.afterPoll = AFTER;
        MeshNode.LOCAL.startWith(firstHost(c), ro ? RoLogin.user() : null, ro ? RoLogin.pass() : null, user(), p, RULE);
    }

    public static void login(Context c, String u, String p, boolean remember) {
        pass = p;
        SharedPreferences.Editor ed = ctl().edit().putString("user", u.length() == 0 ? "root" : u);
        if (remember) ed.putString("savedPass", p);
        ed.commit();
        MeshNode.LOCAL.stop();
        ensure(c);
    }

    /** Forget the login (memory and, if remembered, the Mesh controller's saved one) and stop polling. */
    public static void logout() {
        pass = null;
        ctl().edit().remove("savedPass").commit();
        MeshNode.LOCAL.stop();
    }

    /** Where to start: my node's known address, else the WiFi gateway (any node shows the whole mesh layout). */
    private static String firstHost(Context c) {
        String h = MeshTest.sp().getString("local_host", "").trim();
        if (h.length() > 0) return h;
        if (picked()) {
            String ip = savedIp(myNode());
            if (ip != null) return ip;
        }
        String[] dh = detectHost(c);
        if (dh != null) return dh[0];
        String lab = MeshTest.sp().getString("labFirstHost", "").trim(); // emulator tests only (hidden): the stand-in "gateway"
        return lab.length() > 0 ? lab : MeshNode.gateway(c);
    }

    /** The gateway of the local LAN (any type, e.g. USB Ethernet) whose address is in the MANET range: {gw, "gateway x (eth0)"}. */
    @android.annotation.TargetApi(21)
    static String[] lanGateway(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            String range = new Prefs(c).manetRange();
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                java.util.List<java.net.Inet4Address> mine = new java.util.ArrayList<java.net.Inet4Address>();
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof java.net.Inet4Address) mine.add((java.net.Inet4Address) la.getAddress());
                }
                if (mine.isEmpty() || !RadioService.inRange(mine, range)) continue;
                for (android.net.RouteInfo r : lp.getRoutes()) {
                    java.net.InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof java.net.Inet4Address && !g.isAnyLocalAddress()) {
                        return new String[]{g.getHostAddress(), "gateway " + g.getHostAddress() + " (" + lp.getInterfaceName() + ")"};
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String ip4(int a) {
        return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
    }

    /**
     * The node this phone is on, from its WiFi lease: every OpenMANET node runs its own DHCP pool on its access point
     * (e.g. the Pi 4 10.41.0.100-115, HT01 10.41.0.116-131) and by default gives itself as the gateway. So: the DHCP
     * server when it differs from the gateway (a network where every node hands out one shared gateway), else the
     * gateway. {host, "gateway 10.41.254.238"} or null. Hidden emulator test prefs: labGateway, labDhcp.
     */
    static String[] detectHost(Context c) {
        String lg = MeshTest.sp().getString("labGateway", "").trim(), ld = MeshTest.sp().getString("labDhcp", "").trim();
        String gw = null, srv = null;
        if (lg.length() > 0) {
            gw = lg;
            srv = ld.length() > 0 ? ld : null;
        } else {
            try {
                android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                android.net.DhcpInfo i = w == null ? null : w.getDhcpInfo();
                if (i != null) {
                    if (i.gateway != 0) gw = ip4(i.gateway);
                    if (i.serverAddress != 0) srv = ip4(i.serverAddress);
                }
            } catch (RuntimeException ignored) {
            }
        }
        boolean wifiOnMesh = false;
        try {
            java.util.List<java.net.Inet4Address> g = new java.util.ArrayList<java.net.Inet4Address>();
            if (gw != null) g.add((java.net.Inet4Address) java.net.InetAddress.getByName(gw));
            wifiOnMesh = !g.isEmpty() && RadioService.inRange(g, new Prefs(c).manetRange());
        } catch (Exception ignored) {
        }
        if (lg.length() == 0 && !wifiOnMesh && android.os.Build.VERSION.SDK_INT >= 21) { // WiFi not on the mesh: USB Ethernet or another LAN that is
            String[] lp = lanGateway(c);
            if (lp != null) return lp;
        }
        if (srv != null && gw != null && !srv.equals(gw)) return new String[]{srv, "DHCP server " + srv};
        if (gw != null) return new String[]{gw, "gateway " + gw};
        if (srv != null) return new String[]{srv, "DHCP server " + srv};
        return null;
    }

    // ---- which node this phone is on ----

    /** "My mesh node": picked (Mapping walk / Mesh radio), else found from the WiFi name; "" = not known. */
    public static String myNode() {
        String m = MeshTest.sp().getString("walk_node", "").trim();
        return m.length() > 0 ? m : detected;
    }

    /** The node found by the gateway / WiFi name ("" = not found), whether or not one is picked. */
    public static String detectedNode() {
        return detected;
    }

    public static boolean picked() {
        return MeshTest.sp().getString("walk_node", "").trim().length() > 0;
    }

    /** How my node was found, for the page: "picked", "from WiFi localnetHT01", or why not. */
    public static String howFound() {
        if (picked()) return "picked";
        if (detected.length() > 0) return "found by " + detectedFrom;
        String s = ssid();
        if (s.length() == 0) return "WiFi name not readable (location permission, or not on WiFi): pick it";
        if (MeshNode.LOCAL.names().isEmpty()) return "WiFi " + s + ": waiting for the mesh's node list";
        return "WiFi " + s + " matches no node name: pick it";
    }

    /** Is LOCAL polling my own node now (so its figures are this phone's side of the link)? */
    public static boolean onMyNode() {
        String m = myNode();
        return m.length() > 0 && m.equalsIgnoreCase(MeshNode.LOCAL.selfName()) && MeshNode.LOCAL.fresh(Math.max(90, RULE.seconds() * 4) * 1000L);
    }

    static String ssid() {
        String lab = MeshTest.sp().getString("labSsid", "").trim(); // emulator tests only (hidden)
        if (lab.length() > 0) return lab;
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) MeshTest.app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo i = w == null ? null : w.getConnectionInfo();
            String s = i == null ? null : i.getSSID();
            if (s == null || s.contains("unknown ssid") || s.equals("0x")) return "";
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            return s.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String norm(String s) {
        return s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private static int common(String a, String b) {
        int best = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = 0; j < b.length(); j++) {
                int k = 0;
                while (i + k < a.length() && j + k < b.length() && a.charAt(i + k) == b.charAt(j + k)) k++;
                best = Math.max(best, k);
            }
        }
        return best;
    }

    /**
     * The node whose name best matches the WiFi name (OpenMANET nodes' access points are named after the node, e.g.
     * "localnetHT01" for HT01, "localnetRPI4GW" for RPI4-Gateway): the longest shared run of letters/digits, at least
     * 4, and clearly better than the next node. Null when unsure.
     */
    static String matchSsid(String ssid, List<String> names) {
        String s = norm(ssid);
        if (s.length() < 3) return null;
        String best = null;
        int b1 = 0, b2 = 0;
        for (String n : names) {
            String nn = norm(n);
            int c = s.contains(nn) && nn.length() >= 3 ? 100 + nn.length() : common(s, nn);
            if (c > b1) {
                b2 = b1;
                b1 = c;
                best = n;
            } else if (c > b2) b2 = c;
        }
        return b1 >= 4 && b1 > b2 ? best : null;
    }

    private static String savedIp(String node) {
        if (node == null || node.length() == 0) return null;
        try {
            JSONArray a = new JSONArray(ctl().getString("nodes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (node.equalsIgnoreCase(o.optString("name")) && o.optString("ip").length() > 0) return o.optString("ip");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** After each poll (on the poll thread): find my node and move the polling to it. */
    private static final Runnable AFTER = new Runnable() {
        @Override
        public void run() {
            List<String> names = MeshNode.LOCAL.names();
            boolean fixedHost = MeshTest.sp().getString("local_host", "").trim().length() > 0;
            String[] dh = picked() || fixedHost ? null : detectHost(MeshTest.app);
            if (dh != null) { // by the WiFi lease: the node at the gateway / DHCP server is mine
                if (!dh[0].equals(MeshNode.LOCAL.host())) {
                    MeshNode.LOCAL.retarget(dh[0]); // moved to another node's access point
                    return;
                }
                String self = MeshNode.LOCAL.selfName();
                if (self.length() > 0) {
                    if (!self.equals(detected)) Log.i(TAG, "my node by " + dh[1] + ": " + self);
                    detected = self;
                    detectedFrom = dh[1];
                    return;
                }
            }
            if (!picked()) {
                long now = System.currentTimeMillis();
                if (now - lastDetectMs > 20000 || detected.length() == 0) {
                    lastDetectMs = now;
                    String s = ssid();
                    String m = s.length() == 0 ? null : matchSsid(s, names);
                    if (m != null && !m.equals(detected)) Log.i(TAG, "my node from WiFi " + s + ": " + m);
                    detected = m == null ? "" : m;
                    detectedFrom = "WiFi name " + s;
                }
            }
            String mine = myNode();
            if (mine.length() == 0 || mine.equalsIgnoreCase(MeshNode.LOCAL.selfName())) return;
            if (MeshTest.sp().getString("local_host", "").trim().length() > 0) return; // a fixed address was set
            String ip = MeshNode.LOCAL.ipOf(mine);
            if (ip == null) ip = savedIp(mine);
            if (ip == null) ip = OpenManetApi.mdnsLookup(mine, 1500);
            if (ip != null) MeshNode.LOCAL.retarget(ip);
            else Log.i(TAG, "no address for my node " + mine);
        }
    };

    // ---- my node's view of a link ----

    /** My node's side of the path toward another node: hops, next hop and the signal/throughput to that next hop. */
    public static final class Toward {
        public int hops = -1, signalDbm = Integer.MIN_VALUE, kbps = -1;
        public String nextHop = "";
    }

    public static Toward toward(String node) {
        if (node == null || node.length() == 0 || !onMyNode()) return null;
        MeshNode.Info f = MeshNode.LOCAL.lookup(node);
        if (f == null) return null;
        Toward t = new Toward();
        t.hops = f.hops;
        MeshNode.Info via = f.neighbour ? f : f.nextHop.length() > 0 ? MeshNode.LOCAL.lookup(f.nextHop) : null;
        t.nextHop = f.neighbour ? f.name : f.nextHop;
        if (f.hops == 0) t.nextHop = "(same node)";
        if (via != null && via.neighbour) {
            t.signalDbm = via.signalDbm;
            t.kbps = via.kbps;
        }
        return t;
    }

    /** The title bar icon: 0 to 4 bars from my node's best neighbour signal, or -1 = nothing to show. */
    public static int bars() {
        if (!MeshNode.LOCAL.polling()) return -1;
        int s = MeshNode.LOCAL.bestSignal();
        if (s == Integer.MIN_VALUE) return MeshNode.LOCAL.fresh(180000) ? 0 : -1;
        return s >= -60 ? 4 : s >= -70 ? 3 : s >= -80 ? 2 : s >= -90 ? 1 : 0;
    }

    /** One line about my node's neighbours, for the walk page. */
    public static String neighboursLine(int max) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (MeshNode.Info i : MeshNode.LOCAL.all()) {
            if (!i.neighbour) continue;
            if (n++ >= max) {
                b.append(", …");
                break;
            }
            b.append(b.length() > 0 ? ", " : "").append(i.name).append(i.signalDbm != Integer.MIN_VALUE ? " " + i.signalDbm + " dBm" : "")
                    .append(i.kbps >= 0 ? " " + kbit(i.kbps) : "");
        }
        return b.length() == 0 ? "no neighbours seen" : b.toString();
    }

    static String kbit(int kbps) {
        return kbps >= 10000 ? (kbps / 1000) + " Mbit/s" : kbps >= 1000 ? String.format(Locale.US, "%.1f Mbit/s", kbps / 1000.0) : kbps + " kbit/s";
    }

    // ---- login dialog (shared by the Mesh radio page and the walk) ----

    /** Ask for the node login (once; held in memory unless Remember is ticked). then runs after OK or Skip. */
    public static void askLogin(final Activity a, final boolean forWalk, final Runnable then) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        TextView n = new TextView(a);
        n.setText((forWalk ? "To also read YOUR node's side of the link (its neighbours, signal and next hop) during the walk, enter the "
                + "nodes' login. Or skip: the walk still works from the fixed station.\n\n" : "")
                + "The nodes' login (as on their web page, the same as the Mesh controller's). Only read: nothing is changed on a node. "
                + "Held in memory until YCC closes unless Remember is ticked.");
        box.addView(n);
        final EditText u = new EditText(a);
        u.setSingleLine(true);
        u.setText(user());
        box.addView(u);
        final EditText pw = new EditText(a);
        pw.setSingleLine(true);
        pw.setHint("Password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pw);
        final CheckBox rem = new CheckBox(a);
        rem.setText("Remember on this phone (shared with the Mesh controller)");
        box.addView(rem);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Log in to the mesh nodes").setView(box)
                .setPositiveButton("Log in", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        login(a, u.getText().toString().trim(), pw.getText().toString(), rem.isChecked());
                        if (then != null) then.run();
                    }
                });
        if (forWalk) {
            b.setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    if (then != null) then.run();
                }
            });
        } else b.setNegativeButton(android.R.string.cancel, null);
        b.setNeutralButton("Read-only password…", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                RoLogin.enterDialog(a, then);
            }
        });
        b.show();
    }
}
