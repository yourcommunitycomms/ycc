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
 * Mesh testing: the fixed station's own OpenMANET node (normally a Raspberry Pi base node), read-only. Only the
 * fixed-station phone polls, and only this one node, no faster than every 5 s, so the walkers' small Heltec nodes
 * are never asked anything. Calls (openmanetd's web API, the same Connect-RPC as the Mesh controller):
 * MeshNeighborService/ListMeshNeighbors (direct neighbours: name, MAC, signal, throughput) and
 * MeshTopologyService/GetMeshTopology (hops from this node to every node). The login is held in memory only
 * unless "Remember" is ticked; nothing is ever changed on the node.
 */
public final class MeshNode {
    private static final String TAG = "MeshNode";
    public static final int MIN_POLL_S = 5;

    /** A node as the fixed node sees it. */
    public static final class Info {
        public String name = "", mac = "";
        public int hops = -1; // mesh hops from the fixed node: 0 = the fixed node itself, 1 = direct neighbour
        public int signalDbm = Integer.MIN_VALUE;
        public int kbps = -1;
        public boolean neighbour;
    }

    private static volatile Thread thread;
    private static volatile String host, user, pass;
    private static volatile String selfName = "", lastError = "", lastNeighbourJson = "";
    private static volatile long lastOkMs;
    private static volatile Map<String, Info> nodes = new HashMap<String, Info>();

    private MeshNode() {
    }

    public static boolean polling() {
        return thread != null;
    }

    public static String host() {
        return host;
    }

    public static String selfName() {
        return selfName;
    }

    public static String lastError() {
        return lastError;
    }

    public static long lastOkMs() {
        return lastOkMs;
    }

    /** The neighbour answer as the node gave it (first 600 characters), for the report and the page. */
    public static String lastNeighbourJson() {
        return lastNeighbourJson;
    }

    public static int pollSec() {
        return Math.max(MIN_POLL_S, MeshTest.sp().getInt("node_poll", 5));
    }

    /** The node host to poll: the setting, else this phone's WiFi gateway (the node it is on). */
    public static String defaultHost(Context c) {
        String h = MeshTest.sp().getString("node_host", "").trim();
        if (h.length() > 0) return h;
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo i = w == null ? null : w.getDhcpInfo();
            int g = i == null ? 0 : (i.gateway != 0 ? i.gateway : i.serverAddress);
            if (g != 0) return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (RuntimeException ignored) {
        }
        return "10.41.0.1";
    }

    public static synchronized void start(String h, String u, String p) {
        stop();
        host = h;
        user = u;
        pass = p;
        lastError = "Connecting to " + h + "…";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mesh-node");
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        if (!MeshTest.sp().getBoolean("node_remember", false)) pass = null; // memory only
    }

    private static void loop() {
        OpenManetApi api = null;
        Thread me = Thread.currentThread();
        while (thread == me) {
            try {
                if (api == null || !api.loggedIn()) {
                    api = new OpenManetApi(host, 6000);
                    api.login(user, pass);
                }
                poll(api);
                lastOkMs = System.currentTimeMillis();
                lastError = "";
            } catch (OpenManetApi.ApiException e) {
                lastError = e.loginFailed() ? "Login refused by " + host : host + ": " + e.getMessage();
                if (e.loginFailed()) {
                    if (MeshTest.sp().getBoolean("node_remember", false)) MeshTest.sp().edit().remove("node_pass").commit();
                    thread = null;
                    Log.w(TAG, lastError);
                    return;
                }
                api = null;
            } catch (Exception e) {
                lastError = host + ": " + e.getMessage();
                api = null;
            }
            try {
                Thread.sleep(pollSec() * 1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (api != null) api.logout();
    }

    private static void poll(OpenManetApi api) throws Exception {
        Map<String, Info> next = new HashMap<String, Info>();
        JSONObject topo = api.call(OpenManetApi.TOPOLOGY + "GetMeshTopology", new JSONObject()).optJSONObject("topology");
        JSONArray tn = topo == null ? null : topo.optJSONArray("nodes");
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
                if (n.name.length() > 0) next.put(key(n.name), n);
            }
        }
        JSONObject nb = api.call("openmanet.service.v1.MeshNeighborService/ListMeshNeighbors", new JSONObject());
        String raw = nb.toString();
        if (!raw.equals(lastNeighbourJson) && lastNeighbourJson.length() == 0) Log.i(TAG, "ListMeshNeighbors: " + raw);
        lastNeighbourJson = raw.length() > 600 ? raw.substring(0, 600) + "…" : raw;
        JSONArray list = firstArray(nb);
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String name = clean(firstString(o, "hostname", "name", "nodeName", "sourceHostname", "currentHostname"));
                String mac = firstString(o, "macAddress", "mac", "primaryMac");
                Info n = next.get(key(name));
                if (n == null) {
                    for (Info x : next.values()) if (mac.length() > 0 && mac.equalsIgnoreCase(x.mac)) n = x;
                }
                if (n == null) {
                    n = new Info();
                    n.name = name.length() > 0 ? name : mac;
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
                if (kbps >= 0) n.kbps = kbps;
            }
        }
        nodes = next;
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

    private static String key(String n) {
        return n.toLowerCase(Locale.US);
    }

    /** What the fixed node knows of this node (by name), or null. Fresh only (last good poll within 30 s). */
    public static Info lookup(String nodeName) {
        if (nodeName == null || nodeName.length() == 0 || System.currentTimeMillis() - lastOkMs > 30000) return null;
        return nodes.get(key(clean(nodeName)));
    }

    /** Node names the fixed node knows (for the walker's "My mesh node" list). */
    public static List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Info n : nodes.values()) if (n.name.length() > 0) out.add(n.name);
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static List<Info> all() {
        return new ArrayList<Info>(nodes.values());
    }

    public static String rememberedPass() {
        return MeshTest.sp().getBoolean("node_remember", false) ? MeshTest.sp().getString("node_pass", null) : null;
    }
}
