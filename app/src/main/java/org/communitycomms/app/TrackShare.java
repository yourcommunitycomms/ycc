package org.communitycomms.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.communitycomms.app.geo.Track;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Path tracking over the air: a live path sent to chosen people (a commander), and the commander's
 * "Track" button asking someone for theirs. Chat bodies of kind KIND_TRACK (9) on a private channel only
 * (never Public); older versions ignore the kind. Addressed by callsign: other phones on the channel
 * drop paths not meant for them. Layout after the kind byte (big endian):
 * <pre>
 * 1 POINTS : to(len8 + UTF-8, names joined by ',') trackId(8, start ms) flags(1: 1 paused, 2 stopped)
 *            mode(1) laps(2) movingSec(4) distanceM(4) steps(4, -1 none) count(2)
 *            then count Ã— { lat(4, deg Ã— 1e6) lon(4) ageSec(4) newSegment(1) }
 * 2 REQUEST: to(len8 + UTF-8)          "show me your path" (the person is asked first)
 * 3 REPLY  : to(len8 + UTF-8) code(1)  1 declined, 2 not tracking and did not start
 * </pre>
 * Received paths are kept in memory only (gone on restart, wipe or Clear mission data).
 */
public final class TrackShare {
    private TrackShare() {
    }

    public static final int KIND_TRACK = 9;
    private static final int SUB_POINTS = 1, SUB_REQUEST = 2, SUB_REPLY = 3;
    private static final int PER_PACKET = 60, WHOLE_MAX = 2000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Handler main = new Handler(Looper.getMainLooper());

    // ---- sending ----

    private static RadioEngine engine(Context c) {
        RadioService s = RadioService.instance();
        if (s == null || s.engine() == null || !RadioService.privateSlot(new Prefs(c).activeSlot())) return null;
        return s.engine();
    }

    /** True when a path can go out now (on air on a private channel). */
    public static boolean canSend(Context c) {
        return engine(c) != null;
    }

    private static byte[] to(Set<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            if (b.length() > 0) b.append(',');
            b.append(n);
        }
        byte[] t = b.toString().getBytes(UTF8);
        if (t.length > 200) t = java.util.Arrays.copyOf(t, 200);
        return t;
    }

    /** Points from index `from` on, in packets of 60. */
    static void sendPoints(Context c, Track t, int from, Set<String> names) {
        RadioEngine e = engine(c);
        if (e == null || names.isEmpty()) return;
        List<Track.Point> pts;
        Track.Stats s;
        boolean paused, stopped;
        synchronized (t) {
            pts = new ArrayList<Track.Point>(t.points.subList(Math.min(from, t.points.size()), t.points.size()));
            s = t.stats(System.currentTimeMillis());
            paused = t.paused();
            stopped = !t.running();
        }
        byte[] to = to(names);
        int flags = (paused ? 1 : 0) | (stopped ? 2 : 0);
        long now = System.currentTimeMillis();
        int i = 0;
        do {
            int n = Math.min(PER_PACKET, pts.size() - i);
            ByteBuffer b = ByteBuffer.allocate(2 + 1 + to.length + 8 + 1 + 1 + 2 + 4 + 4 + 4 + 2 + n * 13);
            b.put((byte) KIND_TRACK).put((byte) SUB_POINTS).put((byte) to.length).put(to).putLong(t.start)
                    .put((byte) flags).put((byte) t.mode).putShort((short) Math.min(s.lapCount, 32767))
                    .putInt((int) (s.moving / 1000)).putInt((int) s.distanceM).putInt(s.steps).putShort((short) n);
            for (int k = 0; k < n; k++) {
                Track.Point p = pts.get(i + k);
                b.putInt((int) Math.round(p.lat * 1e6)).putInt((int) Math.round(p.lon * 1e6))
                        .putInt((int) Math.max(0, (now - p.t) / 1000)).put((byte) (p.newSegment ? 1 : 0));
            }
            e.sendChatBody(b.array(), n <= 4 ? 2 : 1); // small live updates twice for loss
            i += n;
        } while (i < pts.size());
    }

    /** Everything recorded so far (the last 2000 points), to one person. */
    static void sendWhole(Context c, Track t, String name) {
        Set<String> one = new HashSet<String>();
        one.add(name);
        int n;
        synchronized (t) {
            n = t.points.size();
        }
        sendPoints(c, t, Math.max(0, n - WHOLE_MAX), one);
    }

    /** Commander: ask this person for their path. False when not on air on a private channel. */
    public static boolean request(Context c, String name) {
        RadioEngine e = engine(c);
        if (e == null) return false;
        byte[] to = to(Collections.singleton(name));
        ByteBuffer b = ByteBuffer.allocate(3 + to.length);
        b.put((byte) KIND_TRACK).put((byte) SUB_REQUEST).put((byte) to.length).put(to);
        e.sendChatBody(b.array(), 2);
        return true;
    }

    static void reply(Context c, String name, int code) {
        RadioEngine e = engine(c);
        if (e == null) return;
        byte[] to = to(Collections.singleton(name));
        ByteBuffer b = ByteBuffer.allocate(4 + to.length);
        b.put((byte) KIND_TRACK).put((byte) SUB_REPLY).put((byte) to.length).put(to).put((byte) code);
        e.sendChatBody(b.array(), 2);
    }

    // ---- receiving ----

    /** A path someone sent this phone. */
    public static final class Remote {
        public final String name;
        public long trackId, heard;
        public int mode, laps, steps = -1;
        public long movingMs;
        public double distanceM;
        public boolean paused, stopped;
        /** Sorted by time: {lat, lon, time ms, newSegment 0/1}. */
        public final List<double[]> points = new ArrayList<double[]>();

        Remote(String name) {
            this.name = name;
        }
    }

    private static final Map<String, Remote> remotes = new HashMap<String, Remote>();
    /** Callsigns whose path the commander chose to show (the Track button). */
    private static final Set<String> shown = new HashSet<String>();

    public static synchronized boolean shown(String name) {
        return shown.contains(name);
    }

    public static synchronized void setShown(String name, boolean on) {
        if (on) shown.add(name);
        else shown.remove(name);
    }

    public static synchronized Set<String> shownNames() {
        return new HashSet<String>(shown);
    }

    public static synchronized Remote remote(String name) {
        return remotes.get(name);
    }

    /** Copies of the paths to draw (shown ones only). */
    public static synchronized List<Remote> shownRemotes() {
        List<Remote> out = new ArrayList<Remote>();
        for (String n : shown) {
            Remote r = remotes.get(n);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Wipe and Clear mission data. */
    public static synchronized void clear() {
        remotes.clear();
        shown.clear();
    }

    /** Someone asked this phone for its path: set by the screen in front. */
    public interface Asker {
        void askForTrack(String from);
    }

    public static volatile Asker asker;
    /** Shown when a request is turned down or the person is not tracking (set by the screen in front). */
    public static volatile Runnable changed;

    private static boolean forMe(Context c, String to) {
        Prefs p = new Prefs(c);
        String air = p.airName(), call = p.callsign();
        for (String n : to.split(",")) {
            n = n.trim();
            if (n.equalsIgnoreCase(air) || n.equalsIgnoreCase(call)) return true;
        }
        return false;
    }

    /** A KIND_TRACK chat body (b is just past the kind byte). */
    public static void onMessage(final Context c, final String from, ByteBuffer b) {
        int sub = b.get() & 0xff;
        byte[] tb = new byte[b.get() & 0xff];
        b.get(tb);
        if (!forMe(c, new String(tb, UTF8))) return;
        if (sub == SUB_POINTS) onPoints(from, b);
        else if (sub == SUB_REQUEST) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRequest(c, from);
                }
            });
        } else if (sub == SUB_REPLY) {
            final int code = b.get() & 0xff;
            if (repeat("reply " + from)) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(c, code == 2 ? from + " is not tracking their path and did not start"
                            : from + " did not share their path", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private static void onPoints(String from, ByteBuffer b) {
        long id = b.getLong();
        int flags = b.get() & 0xff, mode = b.get() & 0xff, laps = b.getShort();
        long moving = (b.getInt() & 0xffffffffL) * 1000;
        int dist = b.getInt(), steps = b.getInt(), n = b.getShort() & 0xffff;
        long now = System.currentTimeMillis();
        synchronized (TrackShare.class) {
            Remote r = remotes.get(from);
            if (r == null || r.trackId != id) {
                if (r != null && id < r.trackId) return; // an old track's late packet
                r = new Remote(from);
                r.trackId = id;
                remotes.put(from, r);
            }
            r.heard = now;
            r.mode = mode;
            r.laps = laps;
            r.movingMs = moving;
            r.distanceM = dist;
            r.steps = steps;
            r.paused = (flags & 1) != 0;
            r.stopped = (flags & 2) != 0;
            for (int i = 0; i < n; i++) {
                double lat = b.getInt() / 1e6, lon = b.getInt() / 1e6;
                long t = now - (b.getInt() & 0xffffffffL) * 1000;
                int seg = b.get();
                insert(r.points, new double[]{lat, lon, t, seg});
            }
        }
        Runnable ch = changed;
        if (ch != null) main.post(ch);
    }

    /** Sorted by time; a point already held (same place within 2 s) is skipped. */
    private static void insert(List<double[]> pts, double[] p) {
        int i = pts.size();
        while (i > 0 && pts.get(i - 1)[2] > p[2] + 2000) i--;
        for (int k = Math.max(0, i - 3); k < Math.min(pts.size(), i + 3); k++) {
            double[] q = pts.get(k);
            if (Math.abs(q[2] - p[2]) <= 2000 && Math.abs(q[0] - p[0]) < 1e-5 && Math.abs(q[1] - p[1]) < 1e-5) return;
        }
        pts.add(i, p);
        if (pts.size() > 20000) pts.remove(0);
    }

    /** Requests and replies are sent twice for loss: the copy is ignored (per sender, 20 s). */
    private static final Map<String, Long> lastAsk = new HashMap<String, Long>();

    private static boolean repeat(String key) {
        long now = System.currentTimeMillis();
        synchronized (lastAsk) {
            Long t = lastAsk.get(key);
            lastAsk.put(key, now);
            return t != null && now - t < 20000;
        }
    }

    private static void onRequest(Context c, String from) {
        if (repeat("ask " + from)) return;
        if (TrackService.running() && TrackService.shareTo().contains(from)) {
            TrackService.addShare(from); // already sharing with them: just send it all again
            return;
        }
        Asker a = asker;
        if (a != null) a.askForTrack(from);
        else notifyRequest(c, from);
    }

    /** No screen open: a notification that opens the map, which asks. */
    private static void notifyRequest(Context c, String from) {
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        android.app.Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel("trackask", "Path requests",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
            b = new android.app.Notification.Builder(c, "trackask");
        } else {
            b = new android.app.Notification.Builder(c);
        }
        int fl = android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(c, 75,
                new android.content.Intent(c, MapActivity.class).putExtra(MapActivity.EXTRA_TRACK_ASK, from)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP), fl | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle(from + " asks to see your path").setContentText("Tap to answer")
                .setSmallIcon(R.drawable.ic_stat_radio).setContentIntent(open).setAutoCancel(true);
        nm.notify(4712, android.os.Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification());
    }
}
