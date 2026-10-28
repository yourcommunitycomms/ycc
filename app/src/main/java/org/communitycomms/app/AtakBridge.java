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
 * "ATAK compatible" (Settings, off by default): speaks ATAK's mesh defaults, which are NOT
 * encrypted. Our position goes out as Cursor on Target every 30 s on 239.2.3.1:6969 and ATAK
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
        boolean want = enabled(c) && !new Prefs(c).radioSilent();
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
                        handle(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            }
        }, name).start();
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

    static void sendPosition() {
        MulticastSocket s = saSock;
        android.location.Location l = where();
        if (s == null || l == null || RadioEngine.radioSilent) return;
        long now = System.currentTimeMillis();
        final double[] ob = FakeGps.obscure(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 9999999.0);
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

    /** Our Public channel message to ATAK's All Chat Rooms. */
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
                        + "<remarks source=\"BAO.F.ATAK." + myUid + "\" to=\"All Chat Rooms\" time=\"" + time(now) + "\">" + esc(text)
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
        if (type.startsWith("b-t-f")) {
            String text = find(REMARKS, xml), who = find(SENDER, xml);
            if (text == null) return;
            MessageLog.Entry m = new MessageLog.Entry();
            m.id = uid.hashCode() * 31L + text.hashCode();
            m.type = MessageLog.TYPE_TEXT;
            m.time = System.currentTimeMillis();
            m.creator = unesc(who == null ? "ATAK" : who) + " (ATAK)";
            int tag = ChatActivity.channelOnAir();
            m.channelTag = tag;
            m.body = unesc(text);
            MessageLog.get(app).put(m);
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

    /** ATAK users (and their markers) heard in the last 10 minutes. */
    public static List<Contact> contacts() {
        List<Contact> out = new ArrayList<Contact>();
        long now = System.currentTimeMillis();
        synchronized (contacts) {
            for (Contact c : contacts.values()) if (now - c.heardAt < 600000) out.add(c);
        }
        return out;
    }
}
