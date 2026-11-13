package org.communitycomms.app.camera;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds cameras on the local network, fully offline: an ONVIF WS-Discovery probe (UDP multicast
 * 239.255.255.250:3702), and a sweep of this phone's /24 for TCP 9000 (Reolink's own "Baichuan"
 * port), 554 (RTSP) and 80/443 (web). With a user name and password it then asks each one
 * GetDevInfo for the model, name and number of lenses. Runs on the calling (background) thread.
 */
public final class ReolinkScan {
    public interface Progress {
        void onProgress(String what);
    }

    public static final class Found {
        public String ip;
        public boolean onvif, port9000, port554, port80, port443;
        public String onvifInfo = "";
        /** Filled in when the camera answered GetDevInfo. */
        public ReolinkApi.DevInfo info;
        public int httpPort;
        public boolean https;
        public String problem;

        public boolean likelyReolink() {
            return port9000 || info != null || onvifInfo.toLowerCase(java.util.Locale.US).contains("reolink");
        }

        public String describe() {
            StringBuilder s = new StringBuilder(ip);
            if (info != null) {
                s.append("  ").append(info.model);
                if (info.name.length() > 0 && !info.name.equals(info.model)) s.append(" \"").append(info.name).append('"');
                if (info.channels > 1) s.append(", ").append(info.channels).append(" lenses");
            } else if (onvifInfo.length() > 0) {
                s.append("  ").append(onvifInfo);
            }
            List<String> p = new ArrayList<String>();
            if (port9000) p.add("9000");
            if (port554) p.add("554");
            if (port80) p.add("80");
            if (port443) p.add("443");
            if (!p.isEmpty() || onvif) s.append("\n   ").append(onvif ? "ONVIF" + (p.isEmpty() ? "" : ", ") : "")
                    .append(p.isEmpty() ? "" : "ports " + join(p));
            if (info == null && problem != null) s.append("\n   ").append(problem);
            if (info == null && port9000 && !port80 && !port443) {
                // Newer Reolink firmware ships with HTTP, HTTPS and RTSP off: only its own port 9000 answers.
                s.append("\n   Only Reolink's own port answers. If this is a Reolink camera, in the Reolink app open its Settings > Network > Advanced > "
                        + "Port Settings, turn on HTTP (or HTTPS) and RTSP, then scan again");
            }
            return s.toString();
        }
    }

    private static String join(List<String> l) {
        StringBuilder s = new StringBuilder();
        for (String x : l) s.append(s.length() > 0 ? ", " : "").append(x);
        return s.toString();
    }

    private ReolinkScan() {
    }

    /** IPv4 addresses of this phone on the LAN (Wi-Fi, Ethernet, USB tether), not mobile data's carrier NAT. */
    public static List<Inet4Address> localAddresses() {
        List<Inet4Address> out = new ArrayList<Inet4Address>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e == null) return out;
            for (NetworkInterface ni : Collections.list(e)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(java.util.Locale.US);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) out.add((Inet4Address) a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<Found> scan(Context ctx, String user, String pass, final Progress p, long maxMs) {
        final long deadline = System.currentTimeMillis() + maxMs;
        final Map<String, Found> found = Collections.synchronizedMap(new LinkedHashMap<String, Found>());
        p.onProgress("Asking ONVIF cameras to answer…");
        wsDiscovery(ctx, found, 3000);
        List<Inet4Address> mine = localAddresses();
        List<String> targets = new ArrayList<String>();
        List<String> self = new ArrayList<String>();
        for (Inet4Address a : mine) {
            byte[] b = a.getAddress();
            self.add(a.getHostAddress());
            String prefix = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
            for (int i = 1; i < 255; i++) if (!targets.contains(prefix + i)) targets.add(prefix + i);
            if (targets.size() >= 254 * 3) break;
        }
        targets.removeAll(self);
        p.onProgress("Checking " + targets.size() + " addresses for camera ports…");
        ExecutorService pool = Executors.newFixedThreadPool(32);
        final int[] done = {0};
        final int total = targets.size();
        for (final String ip : targets) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (System.currentTimeMillis() > deadline) return;
                    // 350 ms missed Wi-Fi cameras on a real network: 900 ms (the scan then takes about 15 s).
                    boolean b9000 = open(ip, 9000, 900);
                    boolean b554 = open(ip, 554, b9000 ? 1500 : 900);
                    Found f = found.get(ip);
                    if (b9000 || b554 || f != null) {
                        if (f == null) {
                            f = new Found();
                            f.ip = ip;
                            found.put(ip, f);
                        }
                        f.port9000 |= b9000;
                        f.port554 |= b554;
                        f.port80 = open(ip, 80, 1500);
                        f.port443 = open(ip, 443, 1500);
                    }
                    int d;
                    synchronized (done) {
                        d = ++done[0];
                    }
                    if (d % 32 == 0) p.onProgress("Checked " + d + " of " + total + " addresses…");
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(Math.max(1000, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        List<Found> list;
        synchronized (found) {
            list = new ArrayList<Found>(found.values());
        }
        if (pass != null && pass.length() > 0) {
            for (Found f : list) {
                if (!(f.port80 || f.port443)) continue;
                p.onProgress("Asking " + f.ip + " what it is…");
                confirm(f, 0, user, pass);
            }
        }
        // Likely Reolink cameras first.
        List<Found> sorted = new ArrayList<Found>();
        for (Found f : list) if (f.likelyReolink()) sorted.add(f);
        for (Found f : list) if (!f.likelyReolink()) sorted.add(f);
        return sorted;
    }

    /** GetDevInfo with the sign-in, trying HTTP then HTTPS (or the port given). */
    public static void confirm(Found f, int port, String user, String pass) {
        try {
            Object[] r = ReolinkApi.probe(f.ip, port, user, pass);
            f.httpPort = (Integer) r[0];
            f.https = (Boolean) r[1];
            f.info = (ReolinkApi.DevInfo) r[2];
            f.problem = null;
        } catch (Exception e) {
            f.problem = e.getMessage();
        }
    }

    private static boolean open(String ip, int port, int ms) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), ms);
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

    private static void wsDiscovery(Context ctx, Map<String, Found> found, int listenMs) {
        WifiManager.MulticastLock lock = null;
        DatagramSocket s = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("ycc-camera-scan");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            s = new DatagramSocket();
            s.setSoTimeout(400);
            String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" "
                    + "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                    + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" "
                    + "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                    + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
                    + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                    + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                    + "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
            byte[] b = probe.getBytes("UTF-8");
            InetAddress group = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(b, b.length, group, 3702));
            long end = System.currentTimeMillis() + listenMs;
            boolean resent = false;
            byte[] buf = new byte[16384];
            while (System.currentTimeMillis() < end) {
                if (!resent && System.currentTimeMillis() > end - listenMs / 2) {
                    s.send(new DatagramPacket(b, b.length, group, 3702)); // UDP: send it twice
                    resent = true;
                }
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(in);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String ip = in.getAddress().getHostAddress();
                String xml = new String(in.getData(), 0, in.getLength(), "UTF-8");
                Found f = found.get(ip);
                if (f == null) {
                    f = new Found();
                    f.ip = ip;
                    found.put(ip, f);
                }
                f.onvif = true;
                String hw = scope(xml, "hardware/"), nm = scope(xml, "name/");
                f.onvifInfo = (nm + (hw.length() > 0 && !hw.equals(nm) ? " " + hw : "")).trim();
            }
        } catch (Exception ignored) {
            // no multicast on this network: the port sweep still runs
        } finally {
            if (s != null) s.close();
            if (lock != null) lock.release();
        }
    }

    /** "onvif://www.onvif.org/name/Front%20Door" → "Front Door". */
    private static String scope(String xml, String what) {
        int i = xml.indexOf("onvif://www.onvif.org/" + what);
        if (i < 0) return "";
        i += ("onvif://www.onvif.org/" + what).length();
        int j = i;
        while (j < xml.length() && " <\t\r\n".indexOf(xml.charAt(j)) < 0) j++;
        try {
            return java.net.URLDecoder.decode(xml.substring(i, j), "UTF-8");
        } catch (Exception e) {
            return xml.substring(i, j);
        }
    }
}
