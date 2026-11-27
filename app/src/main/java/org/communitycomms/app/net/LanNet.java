package org.communitycomms.app.net;

import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * Opens HTTP connections to devices on a local network (mesh nodes, LAN servers) on the WiFi / Ethernet network
 * they are on. A LAN without internet (an OpenMANET node whose HaLow is off, or a mesh with no gateway) is not the
 * default network while mobile data is up, so an ordinary connection to 10.41.x.x would go out over mobile data and
 * hang until it times out. Android 5+; older phones (no mobile-data split) use the ordinary connection.
 */
public final class LanNet {
    private LanNet() {
    }

    public static volatile Context app;
    /** How the last LAN connection went out, for error messages: "WiFi wlan0" or "default route (no LAN match)". */
    public static volatile String lastRoute = "";

    /** The WiFi network held by our own request (see keepWifi), or null. */
    private static volatile android.net.Network heldWifi;

    /**
     * Android treats a WiFi with no internet as a "background" network while mobile data is up, and ordinary apps may
     * not send traffic over a background network at all, even on sockets bound to it. Filing our own request for
     * "a WiFi network, internet not needed" makes the mesh WiFi satisfy a foreground request, so the app can use it
     * (and Android keeps it). Called once at app start; the request lasts while the app runs.
     */
    @android.annotation.TargetApi(21)
    public static void keepWifi(Context c) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.requestNetwork(req, new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network n) {
                    heldWifi = n;
                }

                @Override
                public void onLost(android.net.Network n) {
                    if (n.equals(heldWifi)) heldWifi = null;
                }
            });
        } catch (RuntimeException e) {
            android.util.Log.w("LanNet", "cannot request the WiFi network: " + e.getMessage());
        }
    }
    static boolean privateV4(String host) {
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 192 && b == 168 || a == 172 && b >= 16 && b <= 31 || a == 169 && b == 254;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** host:port@network -> until when a connection over that WiFi / Ethernet network is known to work (no probe again). */
    private static final java.util.Map<String, Long> okUntil = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    /** ...and until when it is known NOT to work (straight to the ordinary route, e.g. through a VPN). */
    private static final java.util.Map<String, Long> badUntil = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * A private address goes over the WiFi / Ethernet network it belongs to (or that network's gateway) first. If that
     * cannot connect (a quick TCP probe, 3 s), the ordinary route is used instead (e.g. a VPN or mobile network that
     * does reach it). A working path is remembered for a minute, so most calls cost no probe.
     */
    public static HttpURLConnection open(URL u) throws IOException {
        if (Build.VERSION.SDK_INT >= 21 && app != null && privateV4(u.getHost())) {
            android.net.Network n = lanFor(u.getHost());
            if (n != null) {
                int port = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
                String key = u.getHost() + ":" + port + "@" + n;
                Long ok = okUntil.get(key);
                if (ok != null && ok > System.currentTimeMillis()) return (HttpURLConnection) n.openConnection(u);
                Long bad = badUntil.get(key);
                if (bad != null && bad > System.currentTimeMillis()) { // failed a moment ago (e.g. a VPN that does not let apps bypass it): no 3 s wait again
                    lastRoute = "default route (the WiFi / Ethernet path failed less than a minute ago)";
                    return (HttpURLConnection) u.openConnection();
                }
                String via = lastRoute;
                java.net.Socket s = null;
                try {
                    s = n.getSocketFactory().createSocket();
                    s.connect(new java.net.InetSocketAddress(u.getHost(), port), 3000);
                    okUntil.put(key, System.currentTimeMillis() + 60000);
                    badUntil.remove(key);
                    lastRoute = via;
                    return (HttpURLConnection) n.openConnection(u);
                } catch (IOException e) {
                    okUntil.remove(key);
                    badUntil.put(key, System.currentTimeMillis() + 60000);
                    lastRoute = "default route (tried " + via + " first: " + e.getClass().getSimpleName() + ")";
                } finally {
                    if (s != null) try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                return (HttpURLConnection) u.openConnection();
            }
            lastRoute = "default route (no WiFi / Ethernet network holds " + u.getHost() + ")";
        }
        return (HttpURLConnection) u.openConnection();
    }

    /** How a connection to this host would go out, for progress / error text: "via WiFi wlan0" or "default route …". */
    public static String routeFor(String host) {
        if (Build.VERSION.SDK_INT < 21 || app == null || !privateV4(host)) return "default route";
        return lanFor(host) != null ? lastRoute : "default route: no WiFi / Ethernet network holds " + host;
    }

    /** The WiFi / Ethernet network whose own subnet holds this address, or null (then the ordinary route). */
    @android.annotation.TargetApi(21)
    static android.net.Network lanFor(String host) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            byte[] want = InetAddress.getByName(host).getAddress();
            android.net.Network viaGw = null; // a WiFi / Ethernet with its own gateway: a private address beyond it (another LAN behind the node) is reached there, never over mobile data
            String viaGwName = null;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !(nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    byte[] mine = la.getAddress().getAddress();
                    if (mine.length != 4) continue;
                    int bits = la.getPrefixLength();
                    if (sameNet(mine, want, bits)) {
                        lastRoute = "via " + (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WiFi " : "Ethernet ") + lp.getInterfaceName();
                        return n;
                    }
                }
                for (android.net.RouteInfo r : lp.getRoutes()) {
                    if (viaGw == null && r.isDefaultRoute() && r.getGateway() != null && r.getGateway().getAddress().length == 4) {
                        viaGw = n;
                        viaGwName = (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WiFi " : "Ethernet ") + lp.getInterfaceName() + " (its gateway " + r.getGateway().getHostAddress() + ")";
                    }
                }
                for (android.net.RouteInfo r : lp.getRoutes()) { // e.g. a /16 route through the node for the whole mesh
                    if (r.getDestination() != null && r.getDestination().getAddress().getAddress().length == 4 && r.getDestination().getPrefixLength() > 0
                            && sameNet(r.getDestination().getAddress().getAddress(), want, r.getDestination().getPrefixLength())) {
                        lastRoute = "via " + (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WiFi " : "Ethernet ") + lp.getInterfaceName() + " (route)";
                        return n;
                    }
                }
            }
            if (viaGw != null) {
                lastRoute = "via " + viaGwName;
                return viaGw;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean sameNet(byte[] a, byte[] b, int bits) {
        for (int i = 0; i < 4; i++) {
            int m = bits >= 8 ? 0xff : bits <= 0 ? 0 : (0xff << (8 - bits)) & 0xff;
            if ((a[i] & m) != (b[i] & m)) return false;
            bits -= 8;
        }
        return true;
    }
}
