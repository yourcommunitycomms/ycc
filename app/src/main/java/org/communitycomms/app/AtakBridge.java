package org.communitycomms.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.communitycomms.app.geo.FakeGps;
import org.communitycomms.app.geo.LocationHelper;
import org.communitycomms.app.msg.MessageLog;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "TAK compatible" (Settings, off by default): speaks TAK's mesh defaults, which are NOT
 * encrypted. Our position goes out as Cursor on Target every 30 s on 239.2.3.1:6969 and TAK
 * users' positions are shown on our maps; GeoChat "All Chat Rooms" on 224.10.10.1:17012 comes into
 * Chat, and what we write on the Public channel goes to it. Private channels never leave the app.
 */
public final class AtakBridge {
    private AtakBridge() {
    }

    public static final String SA_GROUP = "239.2.3.1", CHAT_GROUP = "224.10.10.1";
    public static final int SA_PORT = 6969, CHAT_PORT = 17012;

    public static final class Contact {
        public String uid, callsign, type;
        public double lat, lon;
        public long heardAt;
    }

    private static volatile boolean running;
    private static MulticastSocket saSock, chatSock;
    private static WifiManager.MulticastLock lock;
    private static String myUid;
    private static final Map<String, Contact> contacts = new HashMap<String, Contact>();
    private static Context app;

    public static boolean enabled(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("atakOn", false);
    }

    /** Starts or stops to match the setting (RadioService calls this every couple of seconds). */
    public static synchronized void apply(Context c) {
        // TAK compatible, or on the OM/TAK channel with "Broadcast my callsign" (its chat is TAK's All Chat Rooms).
        RadioService rs = RadioService.instance();
        Prefs pp = new Prefs(c);
        boolean onOmTak = rs != null && rs.compat() != null && pp.omCallsign();
        boolean want = (enabled(c) || onOmTak) && !pp.radioSilent();
        if (want && !running) start(c);
        else if (!want && running) stop();
    }

    private static void start(Context c) {
        app = c.getApplicationContext();
        android.content.SharedPreferences sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        myUid = sp.getString("atakUid", "");
        if (myUid.length() == 0) {
            myUid = "YCC-" + Long.toHexString(new SecureRandom().nextLong());
            sp.edit().putString("atakUid", myUid).commit();
        }
        try {
            WifiManager w = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (w != null) {
                lock = w.createMulticastLock("atak");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            saSock = new MulticastSocket(SA_PORT);
            saSock.joinGroup(InetAddress.getByName(SA_GROUP));
            saSock.setTimeToLive(8);
            chatSock = new MulticastSocket(CHAT_PORT);
            chatSock.joinGroup(InetAddress.getByName(CHAT_GROUP));
            chatSock.setTimeToLive(8);
        } catch (Exception e) {
            android.util.Log.w("AtakBridge", "cannot start: " + e);
            stop();
            return;
        }
        running = true;
        listen(saSock, "atak-sa");
        listen(chatSock, "atak-chat");
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    sendPosition();
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "atak-pos").start();
    }

    private static synchronized void stop() {
        running = false;
        if (saSock != null) saSock.close();
        if (chatSock != null) chatSock.close();
        saSock = chatSock = null;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    private static void listen(final MulticastSocket s, String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[65536];
                while (running) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        byte[] d = p.getData();
                        int len = p.getLength();
                        // TAK protocol v1 on the mesh: 0xbf 0x01 0xbf, then a protobuf TakMessage (newer ATAK / WinTAK).
                        if (len > 3 && (d[0] & 0xff) == 0xbf && (d[1] & 0xff) == 0x01 && (d[2] & 0xff) == 0xbf) {
                            String x = protoToXml(d, 3, len);
                            if (x != null) handle(x);
                        } else {
                            handle(new String(d, 0, len, "UTF-8"));
                        }
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            }
        }, name).start();
    }

    // ---- TAK protocol v1 (protobuf) -> the CoT XML handle() reads ----

    private static long varint(byte[] b, int[] pos, int end) {
        long r = 0;
        int shift = 0;
        while (pos[0] < end && shift < 64) {
            int c = b[pos[0]++] & 0xff;
            r |= (long) (c & 0x7f) << shift;
            if (c < 0x80) break;
            shift += 7;
        }
        return r;
    }

    /** TakMessage { 2: CotEvent { 1 type, 5 uid, 9 how, 10 lat, 11 lon, 15 Detail { 1 xmlDetail, 2 Contact { 2 callsign } } } }. */
    static String protoToXml(byte[] b, int off, int end) {
        try {
            int[] pos = {off};
            while (pos[0] < end) {
                long key = varint(b, pos, end);
                int f = (int) (key >>> 3), w = (int) (key & 7);
                if (w == 2) {
                    int l = (int) varint(b, pos, end);
                    if (f == 2) return cotToXml(b, pos[0], Math.min(end, pos[0] + l));
                    pos[0] += l;
                } else if (!skip(b, pos, end, w)) {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.d("AtakBridge", "bad TAK message: " + e);
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
        return null;
    }

    private static boolean skip(byte[] b, int[] pos, int end, int w) {
        if (w == 0) varint(b, pos, end);
        else if (w == 1) pos[0] += 8;
        else if (w == 5) pos[0] += 4;
        else return false;
        return true;
    }

    private static String cotToXml(byte[] b, int off, int end) throws java.io.UnsupportedEncodingException {
        String type = "", uid = "", how = "", xmlDetail = "", callsign = "";
        double lat = 0, lon = 0;
        int[] pos = {off};
        while (pos[0] < end) {
            long key = varint(b, pos, end);
            int f = (int) (key >>> 3), w = (int) (key & 7);
            if (w == 2) {
                int l = (int) varint(b, pos, end);
                int s = pos[0];
                pos[0] += l;
                if (f == 1) type = new String(b, s, l, "UTF-8");
                else if (f == 5) uid = new String(b, s, l, "UTF-8");
                else if (f == 9) how = new String(b, s, l, "UTF-8");
                else if (f == 15) { // Detail
                    int[] dp = {s};
                    int de = s + l;
                    while (dp[0] < de) {
                        long dk = varint(b, dp, de);
                        int df = (int) (dk >>> 3), dw = (int) (dk & 7);
                        if (dw != 2) {
                            if (!skip(b, dp, de, dw)) break;
                            continue;
                        }
                        int dl = (int) varint(b, dp, de);
                        int ds = dp[0];
                        dp[0] += dl;
                        if (df == 1) xmlDetail = new String(b, ds, dl, "UTF-8");
                        else if (df == 2) { // Contact { 1 endpoint, 2 callsign }
                            int[] cp = {ds};
                            while (cp[0] < ds + dl) {
                                long ck = varint(b, cp, ds + dl);
                                if ((ck & 7) != 2) {
                                    if (!skip(b, cp, ds + dl, (int) (ck & 7))) break;
                                    continue;
                                }
                                int cl = (int) varint(b, cp, ds + dl);
                                if ((ck >>> 3) == 2) callsign = new String(b, cp[0], cl, "UTF-8");
                                cp[0] += cl;
                            }
                        }
                    }
                }
            } else if (w == 1) {
                double v = Double.longBitsToDouble(java.nio.ByteBuffer.wrap(b, pos[0], 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong());
                pos[0] += 8;
                if (f == 10) lat = v;
                else if (f == 11) lon = v;
            } else if (!skip(b, pos, end, w)) {
                break;
            }
        }
        if (uid.length() == 0 || type.length() == 0) return null;
        return "<event version=\"2.0\" uid=\"" + esc(uid) + "\" type=\"" + esc(type) + "\" how=\"" + esc(how) + "\">"
                + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\"/>", lat, lon)
                + "<detail>" + xmlDetail + (callsign.length() > 0 ? "<contact callsign=\"" + esc(callsign) + "\"/>" : "")
                + "</detail></event>";
    }

    // ---- sending ----

    private static String time(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String callsign() {
        String c = new Prefs(app).callsign();
        if (c.length() == 0) c = "YCC";
        return FakeGps.enabled(app) ? c + " (Fake)" : c;
    }

    private static void send(MulticastSocket s, String group, int port, String xml) {
        try {
            byte[] b = xml.getBytes("UTF-8");
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName(group), port));
        } catch (Exception ignored) {
        }
    }

    private static android.location.Location where() {
        android.location.LocationManager lm = (android.location.LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        return lm == null ? FakeGps.location(app) : LocationHelper.bestKnown(lm);
    }

    /** Channel link: a YCC user's position to TAK, as its own contact (fixed id per name). */
    public static void sendRelayed(String uidPart, String callsign, double lat, double lon, float accuracy) {
        MulticastSocket s = saSock;
        if (s == null || RadioEngine.radioSilent) return;
        long now = System.currentTimeMillis();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"YCC-link-" + esc(uidPart) + "\" type=\"a-f-G-U-C\" how=\"m-g\" time=\"" + time(now)
                + "\" start=\"" + time(now) + "\" stale=\"" + time(now + 120000) + "\">"
                + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"9999999.0\" ce=\"%.1f\" le=\"9999999.0\"/>", lat, lon, accuracy)
                + "<detail><contact callsign=\"" + esc(callsign) + "\"/><__group name=\"Purple\" role=\"Team Member\"/>"
                + "<remarks>Relayed by YCC (channel link)</remarks></detail></event>";
        send(s, SA_GROUP, SA_PORT, xml);
    }

    /** "Share once" on OM/TAK: this position now, whatever Share my location with TAK says. False when not running. */
    public static boolean sendPositionOnce(final android.location.Location l) {
        if (saSock == null || RadioEngine.radioSilent || l == null) return false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendPositionXml(saSock, l, System.currentTimeMillis());
            }
        }, "tak-once").start();
        return true;
    }

    static void sendPosition() {
        MulticastSocket s = saSock;
        android.location.Location l = where();
        if (s == null || RadioEngine.radioSilent) return;
        long now = System.currentTimeMillis();
        sendNodes(s, now);
        if (++poiRound % 10 == 1) sendPois(s, now); // every 5 minutes (changes go at once: poisChanged)
        // No fix yet, or position sharing off (the default: callsign only): still announce, with an unknown position
        // (TAK lists the contact; it has nothing to put on the map).
        if (!new Prefs(app).atakPosition()) l = null;
        sendPositionXml(s, l, now);
    }

    private static int poiRound;
    private static final java.util.Set<Long> poisSent = new java.util.HashSet<Long>();

    /**
     * "YCC map points in TAK" (OM/TAK settings, off by default: NOT encrypted): YCC's map points go to TAK as live
     * markers named "name (YCC A)", stale at the point's time to live, sent at once when one changes and again every
     * 5 minutes for TAK users who join later. A point removed in YCC is deleted in TAK. Points that came from TAK,
     * personal points and expired ones are never sent; at most the 150 newest go.
     */
    private static void sendPois(MulticastSocket s, long now) {
        if (!new Prefs(app).yccPoisToTak()) { // turned off: take back what was sent
            for (Long gone : poisSent) send(s, SA_GROUP, SA_PORT, TakPoi.deleteCot(gone, now));
            poisSent.clear();
            return;
        }
        java.util.List<org.communitycomms.app.geo.Poi> all = org.communitycomms.app.geo.PoiStore.get(app).all();
        java.util.Set<Long> live = new java.util.HashSet<Long>();
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 150; i--) {
            org.communitycomms.app.geo.Poi p = all.get(i);
            if (TakPoi.takUid(p.note) != null || org.communitycomms.app.geo.Poi.isPersonal(p.note) || p.expired(now)) continue;
            String xml = TakPoi.toCot(p, myUid, callsign(), now, channelOf(p.id));
            if (xml == null) continue;
            send(s, SA_GROUP, SA_PORT, xml);
            live.add(p.id);
            n++;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
        for (Long gone : poisSent) if (!live.contains(gone)) send(s, SA_GROUP, SA_PORT, TakPoi.deleteCot(gone, now));
        poisSent.clear();
        poisSent.addAll(live);
    }

    /** "A", "B", "Public"...: the YCC channel a point was logged on, or "" when not known. */
    private static String channelOf(long id) {
        org.communitycomms.app.msg.MessageLog.Entry m = org.communitycomms.app.msg.MessageLog.get(app).find(id);
        if (m == null || m.channelTag == 0) return "";
        int[] slots = {Prefs.SLOT_A, Prefs.SLOT_B, Prefs.SLOT_PUBLIC};
        for (int s : slots) {
            Integer t = org.communitycomms.app.geo.PoiFilter.tag(app, s);
            if (t != null && t == m.channelTag) return Prefs.letter(s);
        }
        return "";
    }

    /** A YCC point changed or was removed: send it to TAK now instead of at the next round. */
    public static void poisChanged() {
        final MulticastSocket s = saSock;
        if (s == null || app == null || RadioEngine.radioSilent || (!new Prefs(app).yccPoisToTak() && poisSent.isEmpty())) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendPois(s, System.currentTimeMillis());
            }
        }, "tak-pois").start();
    }

    /** One SA (position) message: l null = callsign only, position unknown. */
    private static void sendPositionXml(MulticastSocket s, android.location.Location l, long now) {
        if (s == null) return;
        final double[] ob = l == null ? new double[]{0, 0, 9999999.0}
                : FakeGps.obscure(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 9999999.0);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + myUid + "\" type=\"a-f-G-U-C\" how=\"m-g\" time=\"" + time(now)
                + "\" start=\"" + time(now) + "\" stale=\"" + time(now + 120000) + "\">"
                + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"9999999.0\" ce=\"%.1f\" le=\"9999999.0\"/>",
                ob[0], ob[1], ob[2])
                + "<detail><contact callsign=\"" + esc(callsign()) + "\"/>"
                + "<__group name=\"Cyan\" role=\"Team Member\"/>"
                + "<takv device=\"" + esc(android.os.Build.MODEL) + "\" platform=\"Your Community Communications\" os=\""
                + android.os.Build.VERSION.SDK_INT + "\" version=\"1\"/></detail></event>";
        send(s, SA_GROUP, SA_PORT, xml);
    }

    /** Our Public channel message to TAK's All Chat Rooms. */
    /**
     * "Show OpenMANET nodes in TAK" (Settings > Network, off by default): the Mesh controller's saved nodes go to TAK
     * as contacts, one fixed ID per node so a second phone doing the same only updates them. Plain CoT on the SA group:
     * TAK Vx is not touched. Positions are unknown (the nodes do not report one here).
     */
    private static void sendNodes(MulticastSocket s, long now) {
        if (app == null || !app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("atakNodes", false)) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(app.getSharedPreferences("meshctl", Context.MODE_PRIVATE).getString("nodes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String name = o.optString("name", ""), ip = o.optString("ip", "");
                if (name.length() == 0) continue;
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<event version=\"2.0\" uid=\"OpenMANET-" + esc(name) + "\" type=\"a-f-G-U-C\" how=\"m-g\" time=\"" + time(now)
                        + "\" start=\"" + time(now) + "\" stale=\"" + time(now + 120000) + "\">"
                        + "<point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                        + "<detail><contact callsign=\"" + esc(name + " (OpenMANET node)") + "\"/>"
                        + "<__group name=\"Cyan\" role=\"Team Member\"/>"
                        + "<remarks>OpenMANET node" + (ip.length() > 0 ? " at " + esc(ip) : "") + ", announced by YCC</remarks>"
                        + "<takv device=\"OpenMANET node\" platform=\"Your Community Communications\" os=\"0\" version=\"1\"/></detail></event>";
                send(s, SA_GROUP, SA_PORT, xml);
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    public static void sendChat(final String text) {
        final MulticastSocket s = chatSock;
        if (s == null || RadioEngine.radioSilent) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.location.Location l = where();
                long now = System.currentTimeMillis();
                String id = Long.toHexString(new SecureRandom().nextLong());
                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<event version=\"2.0\" uid=\"GeoChat." + myUid + ".All Chat Rooms." + id + "\" type=\"b-t-f\" how=\"h-g-i-g-o\" time=\""
                        + time(now) + "\" start=\"" + time(now) + "\" stale=\"" + time(now + 86400000L) + "\">"
                        + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>",
                        l == null ? 0 : l.getLatitude(), l == null ? 0 : l.getLongitude())
                        + "<detail><__chat parent=\"RootContactGroup\" groupOwner=\"false\" chatroom=\"All Chat Rooms\" id=\"All Chat Rooms\" senderCallsign=\""
                        + esc(callsign()) + "\"><chatgrp uid0=\"" + myUid + "\" uid1=\"All Chat Rooms\" id=\"All Chat Rooms\"/></__chat>"
                        + "<link uid=\"" + myUid + "\" type=\"a-f-G-U-C\" relation=\"p-p\"/>"
                        + "<remarks source=\"BAO.F.TAK." + myUid + "\" to=\"All Chat Rooms\" time=\"" + time(now) + "\">" + esc(text)
                        + "</remarks></detail></event>";
                send(s, CHAT_GROUP, CHAT_PORT, xml);
            }
        }, "atak-chat-tx").start();
    }

    // ---- receiving ----

    private static final Pattern UID = Pattern.compile("<event[^>]*\\buid=['\"]([^'\"]+)['\"]");
    private static final Pattern TYPE = Pattern.compile("<event[^>]*\\btype=['\"]([^'\"]+)['\"]");
    private static final Pattern POINT = Pattern.compile("<point[^>]*\\blat=['\"]([-0-9.]+)['\"][^>]*\\blon=['\"]([-0-9.]+)['\"]");
    private static final Pattern CALLSIGN = Pattern.compile("<contact[^>]*\\bcallsign=['\"]([^'\"]*)['\"]");
    private static final Pattern SENDER = Pattern.compile("senderCallsign=['\"]([^'\"]*)['\"]");
    private static final Pattern REMARKS = Pattern.compile("<remarks[^>]*>([\\s\\S]*?)</remarks>");

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static void handle(String xml) {
        String uid = find(UID, xml), type = find(TYPE, xml);
        if (uid == null || type == null || uid.equals(myUid) || uid.contains(myUid)) return; // our own, looped back
        if (!type.startsWith("a-f-G-U-C") && !uid.startsWith("YCC")) android.util.Log.d("AtakBridge", "CoT in: " + type + " " + uid);
        if (type.startsWith("b-t-f")) {
            String text = find(REMARKS, xml), who = find(SENDER, xml);
            if (text == null) return;
            MessageLog.Entry m = new MessageLog.Entry();
            m.id = uid.hashCode() * 31L + text.hashCode();
            m.type = MessageLog.TYPE_TEXT;
            m.time = System.currentTimeMillis();
            m.creator = unesc(who == null ? "TAK" : who) + " (TAK)";
            m.channelTag = Prefs.OMTAK_TAG; // TAK chat belongs to the OM/TAK channel, whatever this phone is on
            m.body = unesc(text);
            MessageLog.get(app).put(m);
            return;
        }
        // Shared markers and drawings -> YCC map points (and their deletes).
        if (TakPoi.isDelete(type) || TakPoi.isMarker(type, xml)) {
            if (uid.startsWith("YCC") || uid.contains(".YCC-")) return; // one of ours relayed back
            if (!new Prefs(app).takMarkers()) return;
            android.util.Log.d("AtakBridge", "TAK marker " + type + ": " + (xml.length() > 600 ? xml.substring(0, 600) : xml));
            RadioService rs = RadioService.instance();
            if (TakPoi.isDelete(type)) {
                String gone = TakPoi.deletedUid(xml);
                if (gone != null && rs != null) rs.onTakPoiDeleted(TakPoi.idFor(gone));
                return;
            }
            org.communitycomms.app.geo.Poi p = TakPoi.toPoi(uid, type, xml);
            if (p != null && rs != null) rs.onTakPoi(p);
            return;
        }
        if (!type.startsWith("a-")) return;
        Matcher pm = POINT.matcher(xml);
        if (!pm.find()) return;
        Contact c = new Contact();
        c.uid = uid;
        c.type = type;
        try {
            c.lat = Double.parseDouble(pm.group(1));
            c.lon = Double.parseDouble(pm.group(2));
        } catch (NumberFormatException e) {
            return;
        }
        if (Math.abs(c.lat) > 90 || Math.abs(c.lon) > 180 || (c.lat == 0 && c.lon == 0)) return;
        String cs = find(CALLSIGN, xml);
        c.callsign = cs == null || cs.length() == 0 ? uid : unesc(cs);
        c.heardAt = System.currentTimeMillis();
        synchronized (contacts) {
            contacts.put(uid, c);
        }
    }

    /** TAK users (and their markers) heard in the last 10 minutes. */
    public static List<Contact> contacts() {
        List<Contact> out = new ArrayList<Contact>();
        long now = System.currentTimeMillis();
        synchronized (contacts) {
            for (Contact c : contacts.values()) if (now - c.heardAt < 600000) out.add(c);
        }
        return out;
    }
}
