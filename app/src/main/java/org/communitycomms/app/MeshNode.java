package org.communitycomms.app;

import android.content.Context;
import android.util.Log;

import org.communitycomms.app.net.OpenManetApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only polling of an OpenMANET node's own view of the mesh (openmanetd's web API, the same Connect-RPC as the
 * Mesh controller): MeshTopologyService/GetMeshTopology (hops to every node, next hops), MeshNeighborService/
 * ListMeshNeighbors (direct neighbours: name, MAC, signal, throughput, last seen) and DashboardService/
 * GetDashboardStatus (host name, load, uptime). Nothing is ever changed on a node.
 *
 * Two pollers: {@link #FIXED}, the fixed station's base node (Mesh testing, every 5 s or slower) and {@link #LOCAL},
 * the node this phone is on (Mesh radio page, the title bar signal icon, and a walker during a Mapping walk). Each
 * has one thread, so never more than one request is in flight, and it backs off to 30 s when the node is slow or
 * busy. Logins are held in memory unless "Remember" is ticked. The static methods below are the fixed station's.
 */
public final class MeshNode {
    private static final String TAG = "MeshNode";
    public static final int MIN_POLL_S = 5;

    /** A node as the polled node sees it. */
    public static final class Info {
        public String name = "", mac = "";
        public int hops = -1; // mesh hops from the polled node: 0 = itself, 1 = direct neighbour
        public int signalDbm = Integer.MIN_VALUE;
        public int kbps = -1;
        public long lastSeenMs = -1;
        public String nextHop = "";
        public boolean neighbour;
        /** The radio link rates to this neighbour (openmanetd tx / rx bitrate), kbit/s, or -1. */
        public int txKbps = -1, rxKbps = -1;
    }

    public static final class Poller {
        final String name, prefPrefix;
        volatile Thread thread;
        volatile String host, user, pass;
        volatile String selfName = "", lastError = "", lastNeighbourJson = "", load = "", uptime = "";
        volatile long lastOkMs;
        volatile Map<String, Info> nodes = new HashMap<String, Info>();
        volatile boolean busy;
        volatile Map<String, String> ips = new HashMap<String, String>();
        volatile Runnable afterPoll;
        int polls;
        /** How often to poll now, in seconds (decided by the owner of the poller each loop). */
        volatile IntervalRule rule;
        /** The read-only "ycc-ro" login (OpenWrt /ubus), tried first when set; the admin login (openmanetd) is the fallback. */
        volatile String roUser, roPass;
        volatile long roBrokenUntil;
        volatile String via = "";

        /** "read-only login" or "admin login": what the last good read used. */
        public String via() {
            return via;
        }

        public synchronized void startWith(String h, String ru, String rp, String u, String p, IntervalRule r) {
            roUser = ru;
            roPass = rp;
            roBrokenUntil = 0;
            start(h, u, p, r);
        }

        Poller(String name, String prefPrefix) {
            this.name = name;
            this.prefPrefix = prefPrefix;
        }

        public boolean polling() {
            return thread != null;
        }

        public String host() {
            return host;
        }

        public String selfName() {
            return selfName;
        }

        public String lastError() {
            return lastError;
        }

        public long lastOkMs() {
            return lastOkMs;
        }

        public boolean busy() {
            return busy;
        }

        public String load() {
            return load;
        }

        public String uptime() {
            return uptime;
        }

        public String lastNeighbourJson() {
            return lastNeighbourJson;
        }

        public boolean fresh(long maxAgeMs) {
            return lastOkMs > 0 && System.currentTimeMillis() - lastOkMs < maxAgeMs;
        }

        public synchronized void start(String h, String u, String p, IntervalRule r) {
            Thread old = thread;
            thread = null;
            if (old != null) old.interrupt();
            host = h;
            user = u;
            pass = p;
            rule = r;
            lastError = "Connecting to " + h + "…";
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "mesh-node-" + name);
            thread = t;
            t.start();
        }

        public synchronized void stop() {
            Thread t = thread;
            thread = null;
            if (t != null) t.interrupt();
            if (!MeshTest.sp().getBoolean(prefPrefix + "remember", false)) pass = null; // memory only
        }

        /** Poll a different node with the same login (this phone's own node, once found). */
        public void retarget(String h) {
            if (h == null || h.equals(host)) return;
            Log.i(TAG, name + ": now polling " + h);
            host = h;
            selfName = "";
            nodes = new HashMap<String, Info>();
            lastOkMs = 0;
            nudge();
        }

        private final Object waitLock = new Object();
        private boolean pollNow;

        /** Poll again now (the Mesh radio page opening): ends the wait, never a request in flight. */
        public void nudge() {
            synchronized (waitLock) {
                pollNow = true;
                waitLock.notifyAll();
            }
        }

        private void loop() {
            OpenManetApi api = null;
            ub = null;
            Thread me = Thread.currentThread();
            while (thread == me) {
                int every = rule == null ? 10 : rule.seconds();
                if (every > 0) {
                    long t0 = System.currentTimeMillis();
                    boolean done = false;
                    if (roUser != null && roPass != null && t0 > roBrokenUntil) {
                        try {
                            if (ub == null || !ub.loggedIn() || !host.equals(ub.host())) {
                                if (ub != null) ub.logout();
                                ub = new org.communitycomms.app.net.UbusApi(host, 6000);
                                ub.login(roUser, roPass);
                            }
                            pollUbus(ub);
                            lastOkMs = System.currentTimeMillis();
                            lastError = "";
                            via = "read-only login";
                            busy = lastOkMs - t0 > 3000;
                            done = true;
                        } catch (org.communitycomms.app.net.UbusApi.UbusException e) {
                            ub = null;
                            busy = e.noAnswer();
                            String why = e.loginFailed() ? "read-only login refused by " + host + " (not set up on it, or another password)" : host + " (read-only): " + e.getMessage();
                            Log.w(TAG, name + ": " + why);
                            if (pass != null && !e.noAnswer()) roBrokenUntil = t0 + 300000; // use the admin login for 5 minutes
                            else {
                                lastError = why;
                                done = true;
                            }
                        } catch (Exception e) {
                            ub = null;
                            lastError = host + " (read-only): " + e.getMessage();
                            busy = true;
                            done = true;
                        }
                    }
                    if (!done && pass == null) {
                        lastError = "No login for " + host;
                        done = true;
                    }
                    if (!done) try {
                        if (api == null || !api.loggedIn() || !host.equals(api.host())) {
                            if (api != null) api.logout();
                            api = new OpenManetApi(host, 6000);
                            api.login(user, pass);
                        }
                        poll(api);
                        lastOkMs = System.currentTimeMillis();
                        lastError = "";
                        via = "admin login";
                        // A slow answer (over 3 s) means a busy node: ease off.
                        busy = lastOkMs - t0 > 3000;
                    } catch (OpenManetApi.ApiException e) {
                        lastError = e.loginFailed() ? "Login refused by " + host : host + ": " + e.getMessage();
                        if (e.loginFailed()) {
                            if (MeshTest.sp().getBoolean(prefPrefix + "remember", false)) MeshTest.sp().edit().remove(prefPrefix + "pass").commit();
                            thread = null;
                            Log.w(TAG, name + ": " + lastError);
                            return;
                        }
                        busy = e.noAnswer();
                        api = null;
                        Log.w(TAG, name + ": " + lastError, e);
                    } catch (Exception e) {
                        lastError = host + ": " + e.getMessage();
                        busy = true;
                        api = null;
                        Log.w(TAG, name + ": " + lastError, e);
                    }
                }
                int wait = every <= 0 ? 5 : busy ? Math.max(30, every) : every;
                try {
                    synchronized (waitLock) {
                        if (!pollNow) waitLock.wait(wait * 1000L);
                        pollNow = false;
                    }
                } catch (InterruptedException e) {
                    if (thread != me) break; // stopped
                }
            }
            if (api != null) api.logout();
            if (ub != null) ub.logout();
        }

        private org.communitycomms.app.net.UbusApi ub;

        /**
         * The read-only way (OpenWrt /ubus with the ycc-ro login): mesh11s get_topology (HaLow neighbours and signal),
         * YCC's small script /usr/libexec/ycc-ro-radio (batman neighbours and originators as JSON, plus the alfred
         * bat-hosts names) and system info (load, uptime). batman knows only the next hop, so hops are 1 or "2+".
         */
        private void pollUbus(org.communitycomms.app.net.UbusApi u) throws Exception {
            Map<String, Info> next = new HashMap<String, Info>();
            JSONObject topo = u.call("mesh11s", "get_topology", null);
            Map<String, Integer> signal = new HashMap<String, Integer>();
            JSONArray nb11s = topo.optJSONArray("neighbors");
            if (nb11s != null) for (int i = 0; i < nb11s.length(); i++) {
                JSONObject o = nb11s.getJSONObject(i);
                if (o.has("signal")) signal.put(o.optString("mac").toLowerCase(Locale.US), o.optInt("signal"));
            }
            JSONObject ni = topo.optJSONObject("nodeinfo");
            String self = ni == null ? "" : clean(ni.optString("hostname", ""));
            Map<String, String> names = new HashMap<String, String>();
            JSONArray bn = null, bo = null;
            try {
                JSONObject ex = u.call("file", "exec", new JSONObject().put("command", "/usr/libexec/ycc-ro-radio"));
                JSONObject r = new JSONObject(ex.optString("stdout", "{}"));
                bn = r.optJSONArray("n");
                bo = r.optJSONArray("o");
                for (String line : r.optString("h", "").split("[|\\n]")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2 && p[0].indexOf(':') > 0) names.put(p[0].toLowerCase(Locale.US), clean(p[1]));
                }
            } catch (Exception e) {
                Log.d(TAG, name + ": no batman script: " + e.getMessage());
            }
            if (self.length() > 0) {
                Info s = new Info();
                s.name = self;
                s.hops = 0;
                selfName = self;
                next.put(key(self), s);
            }
            if (bn != null) for (int i = 0; i < bn.length(); i++) {
                JSONObject o = bn.getJSONObject(i);
                String mac = o.optString("neigh_address").toLowerCase(Locale.US);
                Info n = infoFor(next, names, mac);
                n.neighbour = true;
                n.hops = 1;
                n.kbps = o.optInt("throughput", -1);
                n.lastSeenMs = o.optLong("last_seen_msecs", -1);
                Integer sg = signal.get(mac);
                if (sg != null) n.signalDbm = sg;
            }
            for (Map.Entry<String, Integer> en : signal.entrySet()) { // HaLow neighbours batman has not listed
                Info n = infoFor(next, names, en.getKey());
                n.neighbour = true;
                n.hops = 1;
                n.signalDbm = en.getValue();
            }
            if (bo != null) for (int i = 0; i < bo.length(); i++) {
                JSONObject o = bo.getJSONObject(i);
                if (!o.optBoolean("best", false)) continue;
                String orig = o.optString("orig_address").toLowerCase(Locale.US), via = o.optString("neigh_address").toLowerCase(Locale.US);
                Info n = infoFor(next, names, orig);
                if (orig.equals(via)) n.hops = 1;
                else {
                    if (!n.neighbour) n.hops = 2; // two or more: batman only knows the next hop
                    n.nextHop = names.containsKey(via) ? names.get(via) : via;
                }
            }
            try {
                JSONObject s = u.call("system", "info", null);
                long up = s.optLong("uptime", -1);
                uptime = up < 0 ? "" : up >= 86400 ? (up / 86400) + "d " + (up % 86400 / 3600) + "h" : (up / 3600) + "h " + (up % 3600 / 60) + "m";
                JSONArray ld = s.optJSONArray("load");
                load = ld == null ? "" : String.format(Locale.US, "%.2f (1 min)", ld.optLong(0) / 65536.0);
            } catch (Exception ignored) {
            }
            lastNeighbourJson = bn == null ? "" : bn.toString();
            nodes = next;
            Runnable after = afterPoll;
            if (after != null) after.run();
        }

        private static Info infoFor(Map<String, Info> next, Map<String, String> names, String mac) {
            String nm = names.containsKey(mac) ? names.get(mac) : mac;
            Info n = next.get(key(nm));
            if (n == null) {
                n = new Info();
                n.name = nm;
                n.mac = mac;
                next.put(key(nm), n);
            }
            return n;
        }

        private void poll(OpenManetApi api) throws Exception {
            Map<String, Info> next = new HashMap<String, Info>();
            Map<String, String> macToName = new HashMap<String, String>();
            JSONObject topo = api.call(OpenManetApi.TOPOLOGY + "GetMeshTopology", new JSONObject()).optJSONObject("topology");
            JSONArray tn = topo == null ? null : topo.optJSONArray("nodes");
            List<String[]> hopsVia = new ArrayList<String[]>();
            if (tn != null) {
                for (int i = 0; i < tn.length(); i++) {
                    JSONObject o = tn.getJSONObject(i);
                    Info n = new Info();
                    n.name = clean(o.optString("hostname", ""));
                    n.mac = o.optString("mac", "");
                    n.hops = o.optInt("hopsFromSelf", o.optBoolean("isSelf") ? 0 : -1);
                    if (o.optBoolean("isSelf", false)) {
                        n.hops = 0;
                        selfName = n.name;
                    }
                    String nh = firstString(o, "nextHopMac", "nextHop", "nextHopHostname");
                    if (nh.length() > 0) hopsVia.add(new String[]{key(n.name), nh});
                    if (n.mac.length() > 0) macToName.put(n.mac.toLowerCase(Locale.US), n.name);
                    if (n.name.length() > 0) next.put(key(n.name), n);
                }
            }
            for (String[] hv : hopsVia) {
                Info n = next.get(hv[0]);
                String via = macToName.get(hv[1].toLowerCase(Locale.US));
                if (n != null) n.nextHop = via != null ? via : clean(hv[1]);
            }
            JSONObject nb = api.call("openmanet.service.v1.MeshNeighborService/ListMeshNeighbors", new JSONObject());
            String raw = nb.toString();
            boolean first = lastNeighbourJson.length() == 0;
            if (first) Log.i(TAG, name + " ListMeshNeighbors: " + raw);
            lastNeighbourJson = raw.length() > 600 ? raw.substring(0, 600) + "…" : raw;
            JSONArray list = firstArray(nb);
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    String nm = clean(firstString(o, "neighbor", "hostname", "name", "nodeName", "sourceHostname", "currentHostname"));
                    String mac = firstString(o, "hardwareAddress", "macAddress", "mac", "primaryMac");
                    Info n = next.get(key(nm));
                    if (n == null) {
                        for (Info x : next.values()) if (mac.length() > 0 && mac.equalsIgnoreCase(x.mac)) n = x;
                    }
                    if (n == null) {
                        n = new Info();
                        n.name = nm.length() > 0 ? nm : mac;
                        n.mac = mac;
                        next.put(key(n.name), n);
                    }
                    n.neighbour = true;
                    n.hops = 1;
                    int sig = firstInt(o, Integer.MIN_VALUE, "signalDbm", "signalStrength", "signalAverage", "signal", "rssi");
                    if (sig != Integer.MIN_VALUE) n.signalDbm = sig;
                    int kbps = firstInt(o, -1, "throughputKbps", "expectedThroughputKbps");
                    if (kbps < 0) {
                        double mbps = o.optDouble("throughputMbps", -1);
                        if (mbps >= 0) kbps = (int) Math.round(mbps * 1000);
                    }
                    if (kbps < 0 && o.has("throughput")) { // openmanetd 1.4.0: bits per second (2400000 = 2.4 Mbit/s)
                        long bps = (long) o.optDouble("throughput", -1);
                        if (bps >= 0) kbps = (int) (bps / 1000);
                    }
                    if (kbps >= 0) n.kbps = kbps;
                    n.lastSeenMs = firstInt(o, -1, "lastSeenMsecs", "lastSeenMs", "lastSeen");
                    JSONObject tx = o.optJSONObject("tx"), rx = o.optJSONObject("rx");
                    if (tx != null) n.txKbps = tx.optInt("bitrateKbps", -1);
                    if (rx != null) n.rxKbps = rx.optInt("bitrateKbps", -1);
                }
            }
            if (first) {
                StringBuilder sb = new StringBuilder();
                for (Info i : next.values()) if (i.neighbour) sb.append(i.name).append(" ").append(i.signalDbm).append(" dBm ").append(i.kbps).append(" kbit/s tx ")
                        .append(i.txKbps).append(" rx ").append(i.rxKbps).append(" seen ").append(i.lastSeenMs).append(" ms; ");
                Log.i(TAG, name + " neighbours read as: " + sb);
            }
            // Load and uptime: cheap, and only for this phone's own node (the Mesh radio page).
            if (this == LOCAL) {
                try {
                    JSONObject d = api.call("openmanet.dashboard.v1.DashboardService/GetDashboardStatus", new JSONObject());
                    JSONObject res = d.optJSONObject("systemResources");
                    if (res != null) {
                        double cpu = res.optDouble("cpuLoadPercent", -1);
                        load = cpu >= 0 ? Math.round(cpu) + "% CPU" : "";
                        Object up = res.opt("uptime");
                        uptime = up == null ? "" : up.toString().replace("\"", "");
                    }
                    JSONObject dev = d.optJSONObject("deviceInfo");
                    if (dev != null && selfName.length() == 0) selfName = clean(dev.optString("hostname", ""));
                } catch (Exception ignored) {
                    // older openmanetd: no dashboard
                }
                // Node addresses (to move to this phone's own node once known): now and then, not every poll.
                if (ips.isEmpty() || ++polls % 30 == 0) {
                    try {
                        JSONArray ln = api.call(OpenManetApi.NODES + "ListNodes", new JSONObject()).optJSONArray("nodes");
                        Map<String, String> m = new HashMap<String, String>();
                        if (ln != null) {
                            for (int i = 0; i < ln.length(); i++) {
                                JSONObject o = ln.getJSONObject(i);
                                String ip = o.optString("ipaddr", "");
                                if (ip.length() > 0) m.put(key(clean(o.optString("hostname", ""))), ip);
                            }
                        }
                        ips = m;
                    } catch (Exception ignored) {
                    }
                }
            }
            nodes = next;
            Runnable after = afterPoll;
            if (after != null) after.run();
        }

        /** What this node knows of another node (by name), or null. Only while fresh (last good poll within 45 s). */
        public Info lookup(String nodeName) {
            if (nodeName == null || nodeName.length() == 0 || !fresh(45000)) return null;
            return nodes.get(key(clean(nodeName)));
        }

        public List<String> names() {
            List<String> out = new ArrayList<String>();
            for (Info n : nodes.values()) if (n.name.length() > 0) out.add(n.name);
            java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
            return out;
        }

        public List<Info> all() {
            List<Info> l = new ArrayList<Info>(nodes.values());
            java.util.Collections.sort(l, new java.util.Comparator<Info>() {
                @Override
                public int compare(Info a, Info b) {
                    int ha = a.hops < 0 ? 99 : a.hops, hb = b.hops < 0 ? 99 : b.hops;
                    return ha != hb ? ha - hb : a.name.compareToIgnoreCase(b.name);
                }
            });
            return l;
        }

        /** The strongest neighbour's signal, or MIN_VALUE (the title bar icon). */
        public int bestSignal() {
            int best = Integer.MIN_VALUE;
            if (!fresh(180000)) return best;
            for (Info n : nodes.values()) if (n.neighbour && n.signalDbm != Integer.MIN_VALUE) best = Math.max(best, n.signalDbm);
            return best;
        }

        /** A node's address from the mesh's node list (ListNodes), or null. */
        public String ipOf(String nodeName) {
            return nodeName == null ? null : ips.get(key(clean(nodeName)));
        }

        public String rememberedPass() {
            return MeshTest.sp().getBoolean(prefPrefix + "remember", false) ? MeshTest.sp().getString(prefPrefix + "pass", null) : null;
        }
    }

    /** How often a poller should ask now (seconds; 0 or less = not now, check again in 5 s). */
    public interface IntervalRule {
        int seconds();
    }

    /** The fixed station's base node (Mesh testing). */
    public static final Poller FIXED = new Poller("fixed", "node_");
    /** The node this phone is on (Mesh radio, title bar icon, walker). */
    public static final Poller LOCAL = new Poller("local", "local_");

    private MeshNode() {
    }

    // ---- the fixed station's poller (what Mesh testing used before) ----

    public static boolean polling() {
        return FIXED.polling();
    }

    public static String host() {
        return FIXED.host();
    }

    public static String selfName() {
        return FIXED.selfName();
    }

    public static String lastError() {
        return FIXED.lastError();
    }

    public static long lastOkMs() {
        return FIXED.lastOkMs();
    }

    public static String lastNeighbourJson() {
        return FIXED.lastNeighbourJson();
    }

    public static int pollSec() {
        return Math.max(MIN_POLL_S, MeshTest.sp().getInt("node_poll", 5));
    }

    public static void start(String h, String u, String p) {
        FIXED.start(h, u, p, new IntervalRule() {
            @Override
            public int seconds() {
                return pollSec();
            }
        });
    }

    public static void stop() {
        FIXED.stop();
    }

    public static Info lookup(String nodeName) {
        return FIXED.lookup(nodeName);
    }

    public static List<String> names() {
        return FIXED.names();
    }

    public static List<Info> all() {
        return FIXED.all();
    }

    public static String rememberedPass() {
        return FIXED.rememberedPass();
    }

    /** The phone's WiFi gateway (often the node it is on, or the mesh gateway on a flat network), else 10.41.0.1. */
    public static String gateway(Context c) {
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo i = w == null ? null : w.getDhcpInfo();
            int g = i == null ? 0 : (i.gateway != 0 ? i.gateway : i.serverAddress);
            if (g != 0) return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (RuntimeException ignored) {
        }
        return "10.41.0.1";
    }

    /** The node host to poll for the fixed station: the setting, else this phone's WiFi gateway. */
    public static String defaultHost(Context c) {
        String h = MeshTest.sp().getString("node_host", "").trim();
        return h.length() > 0 ? h : gateway(c);
    }

    private static JSONArray firstArray(JSONObject o) {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            JSONArray a = o.optJSONArray(it.next());
            if (a != null) return a;
        }
        return null;
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.length() > 0) return v;
        }
        return "";
    }

    private static int firstInt(JSONObject o, int def, String... keys) {
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                return (int) Math.round(Double.parseDouble(o.get(k).toString()));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    static String clean(String h) {
        return h == null ? "" : h.replaceAll("_(wlh\\d+|bat\\d+|br-[a-z]+|eth\\d+|phy\\d+-ap\\d+)$", "");
    }

    static String key(String n) {
        return n.toLowerCase(Locale.US);
    }
}
