package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Mesh testing (Settings > Advanced and Region features > Mesh testing, all off by default): shared parts of
 * Play audio to channel ({@link TestAudio}), Echo Test ({@link EchoTest}), Map mesh ({@link MeshMap}) and
 * Mapping walk ({@link MeshWalk}).
 *
 * It is the receive hook ({@link RadioEngine.RxHook}): it keeps a small per-sender link record (sequence numbers
 * and arrival times of the last packets) for loss and jitter, works out how a packet arrived (straight off the
 * network, through a relay, or a hop count) and hands voice, positions and probes to the tools.
 */
public final class MeshTest implements RadioEngine.RxHook {
    static final String TAG = "MeshTest";
    /** Advanced feature keys (Prefs.MENU_KEYS), each off by default and carried in setup QR codes. */
    public static final String K_AUDIO = "testaudio", K_ECHO = "echotest", K_MAP = "meshmap";
    /** Hop results: unknown, and "came through a relay / the internet" (never counted as coverage). */
    public static final int HOPS_UNKNOWN = -1, HOPS_RELAY = -2;
    /** Chat body kinds (after MessageCenter's 1-9 and TrackShare's 9): older apps ignore unknown kinds. */
    public static final int KIND_PROBE = 30, KIND_PROBE_ACK = 31;

    private static final MeshTest INSTANCE = new MeshTest();    static Context app;

    private MeshTest() {
    }

    /** Once, at app start. */
    public static void init(Context c) {
        app = c.getApplicationContext();
        RadioEngine.rxHook = INSTANCE;
        MeshMap.load(app);
    }

    static SharedPreferences sp() {
        return app.getSharedPreferences("meshtest", Context.MODE_PRIVATE);
    }

    public static boolean anyOn(Prefs p) {
        return p.menuOn(K_AUDIO) || p.menuOn(K_ECHO) || p.menuOn(K_MAP);
    }

    /** Lab testing only: packets through Extra peers (e.g. emulators linked by a relay) count as if straight off the network. */
    public static boolean labPeers() {
        return app != null && sp().getBoolean("labPeers", false);
    }

    // ---- which channel, and the public channel rule ----

    /**
     * The channel on air is one everyone can hear: Public, a signed Public, the cloud group, or the OpenMANET /
     * TAK talk group (not encrypted). Mesh testing is for private channels unless the user allows it.
     */
    public static boolean onPublicChannel() {
        RadioService rs = RadioService.instance();
        if (rs == null) return false;
        if (rs.compat() != null) return true;
        RadioEngine e = rs.engine();
        return e != null && isPublic(e);
    }

    static boolean isPublic(RadioEngine e) {
        return e.publicLike || e.signedChannel() || RadioService.onPublic();
    }

    /** "Channel A" style name of what is on air now, or null when not on a channel. */
    public static String channelNow() {
        RadioService rs = RadioService.instance();
        if (rs == null) return null;
        if (rs.compat() != null) return "OpenMANET / TAK talk group";
        RadioEngine e = rs.engine();
        if (e == null) return null;
        return "channel " + label(e);
    }

    /** A channel's short name (A, B, Public, Cloud): never the channel name itself, which is its passcode. */
    public static String label(RadioEngine e) {
        if (app == null || e == null) return "?";
        Prefs p = new Prefs(app);
        String n = e.channelName();
        if (n.equals(p.channel(Prefs.SLOT_A).trim()) || n.equals(p.effectiveChannel(p.channel(Prefs.SLOT_A)).trim())) return "A";
        if (n.equals(p.channel(Prefs.SLOT_B).trim()) || n.equals(p.effectiveChannel(p.channel(Prefs.SLOT_B)).trim())) return "B";
        if (n.equals(p.publicKey().trim())) return "Public";
        if (e.publicLike) return "Cloud";
        return "tag " + Integer.toHexString(e.channelTag());
    }

    public static boolean allowPublic(String tool) {
        return sp().getBoolean("pub_" + tool, false);
    }

    public static void setAllowPublic(String tool, boolean on) {
        sp().edit().putBoolean("pub_" + tool, on).commit();
    }

    /**
     * Before a tool starts: on a private channel go straight on; on a public one refuse unless "Allow on public
     * channels" is ticked, and then warn every time.
     */
    public static void checkChannel(final Activity a, String tool, String what, final Runnable go) {
        if (!onPublicChannel()) {
            go.run();
            return;
        }
        if (!allowPublic(tool)) {
            new AlertDialog.Builder(a).setTitle("Private channels only")
                    .setMessage("You are on " + channelNow() + ", which anyone can hear. " + what
                            + " is for private channels. To use it here, tick \"Allow on public channels\" first.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(a).setTitle("Public channel")
                .setMessage(publicWarning(what))
                .setPositiveButton("Go ahead", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        go.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    static String publicWarning(String what) {
        return what + " on " + channelNow() + ": EVERYONE on this channel will hear it, and it uses airtime for "
                + "all users of the network while it runs. Only do this when the people using the channel expect it.";
    }

    // ---- per-sender link record ----

    /** The last packets heard from one sender on one channel: for loss, jitter and "came straight in". */
    static final class Link {
        static final int KEEP = 64;
        final ArrayDeque<long[]> pk = new ArrayDeque<long[]>(); // {seq, arrivalMs}
        int lastVoiceSeq = Integer.MIN_VALUE;
        long lastVoiceMs;
        double jitter = -1; // RFC 3550 style, ms (voice frames and probes)
        long jitterAt;
        boolean lastViaRelay;
        String lastFrom;
        long lastMs;

        synchronized void add(int seq, long now) {
            pk.addLast(new long[]{seq, now});
            while (pk.size() > KEEP) pk.removeFirst();
            lastMs = now;
        }

        /** Loss over the packets of the last 2 minutes, 0..1, or -1 with too few to tell. */
        synchronized double loss(long now) {
            int n = 0;
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            java.util.HashSet<Long> uniq = new java.util.HashSet<Long>();
            for (long[] p : pk) {
                if (now - p[1] > 120000) continue;
                n++;
                uniq.add(p[0]);
                min = Math.min(min, p[0]);
                max = Math.max(max, p[0]);
            }
            if (n < 4) return -1;
            long span = max - min + 1;
            if (span <= 0 || span > 100000) return -1;
            return Math.max(0, 1 - uniq.size() / (double) span);
        }

        synchronized void voice(int seq, long now) {
            if (lastVoiceSeq != Integer.MIN_VALUE && seq - lastVoiceSeq == 1 && now - lastVoiceMs < 2000) {
                jitterStep((now - lastVoiceMs) - RadioEngine.FRAME_SAMPLES * 1000L / RadioEngine.SAMPLE_RATE, now);
            }
            if (seq > lastVoiceSeq || lastVoiceSeq == Integer.MIN_VALUE) {
                lastVoiceSeq = seq;
                lastVoiceMs = now;
            }
        }

        synchronized void jitterStep(double d, long now) {
            d = Math.abs(d);
            jitter = jitter < 0 ? d : jitter + (d - jitter) / 16.0;
            jitterAt = now;
        }

        /** Jitter in ms from the last 2 minutes, or -1 when unknown. */
        synchronized int jitterMs(long now) {
            return jitter < 0 || now - jitterAt > 120000 ? -1 : (int) Math.round(jitter);
        }
    }

    private static final Map<String, Link> links = new HashMap<String, Link>();

    static Link link(RadioEngine e, long sender) {
        String k = e.channelTag() + ":" + sender;
        synchronized (links) {
            Link l = links.get(k);
            if (l == null) {
                if (links.size() > 500) prune();
                l = new Link();
                links.put(k, l);
            }
            return l;
        }
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        Iterator<Link> it = links.values().iterator();
        while (it.hasNext()) if (now - it.next().lastMs > 600000) it.remove();
    }

    /** 0 good, 1 fair, 2 poor, -1 unknown (from loss 0..1 or -1, and jitter ms or -1). */
    public static int grade(double loss, int jitterMs) {
        if (loss < 0 && jitterMs < 0) return -1;
        if ((loss >= 0.15) || jitterMs >= 120) return 2;
        if ((loss >= 0.05) || jitterMs >= 60) return 1;
        return 0;
    }

    public static String gradeWord(int g) {
        return g == 0 ? "good" : g == 1 ? "fair" : g == 2 ? "poor" : "unknown";
    }

    public static int gradeColour(int g) {
        return g == 0 ? 0xff43a047 : g == 1 ? 0xffffb300 : g == 2 ? 0xffe53935 : 0xff9e9e9e;
    }

    // ---- how did it arrive: straight in, a relay, or a hop count ----

    /**
     * Hops for a packet from this address: HOPS_RELAY when it came through this phone's tunnel / internet / cloud
     * relay or an Extra peer (never coverage); the hop count a relayed YCC position carried (2+); else an estimate
     * from the OpenMANET mesh layout the Mesh controller last read (1 = same node / plain WiFi, 2, 3...), or
     * HOPS_UNKNOWN when the layout cannot place the sender.
     */
    public static int hops(RadioEngine e, InetAddress from, int packetHops) {
        if (e.viaRelay(from) && !(labPeers() && from != null && !from.isLoopbackAddress())) return HOPS_RELAY;
        if (packetHops >= 2) return packetHops;
        return topologyHops(from == null ? null : from.getHostAddress());
    }

    /**
     * From the Mesh controller's saved scan: each node's hops from the node the scan logged in to (normally the one
     * this phone is on) and its address. A sender is placed on a node when exactly one node shares its /24 network;
     * on a mesh where every node and phone share one bridged network (common with batman-adv) it cannot be placed:
     * unknown. Without any saved mesh layout, a packet straight off the network is taken as direct (plain WiFi).
     */
    static int topologyHops(String ip) {
        if (ip == null || app == null) return HOPS_UNKNOWN;
        SharedPreferences m = app.getSharedPreferences("meshctl", Context.MODE_PRIVATE);
        if (m.getLong("topoAt", 0) == 0) return 1;
        String net = net24(ip);
        try {
            JSONArray a = new JSONArray(m.getString("nodes", "[]"));
            int found = -2, matches = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String nip = o.optString("ip", "");
                if (nip.length() == 0) continue;
                if (nip.equals(ip)) return Math.max(0, o.optInt("hops", -1)) + 1; // a node itself
                if (net != null && net.equals(net24(nip))) {
                    matches++;
                    found = o.optInt("hops", -1);
                }
            }
            if (matches == 1 && found >= 0) return found + 1;
        } catch (Exception ignored) {
        }
        return HOPS_UNKNOWN;
    }

    static String net24(String ip) {
        int i = ip == null ? -1 : ip.lastIndexOf('.');
        return i <= 0 || ip.indexOf(':') >= 0 ? null : ip.substring(0, i);
    }

    public static String hopsWord(int h) {
        return h == HOPS_RELAY ? "via relay/internet" : h < 0 ? "hops unknown" : h <= 1 ? "direct" : h + " hops";
    }

    // ---- the receive hook ----

    @Override
    public void onPacket(RadioEngine e, long sender, String name, int type, int seq, InetAddress from) {
        if (app == null) return;
        long now = System.currentTimeMillis();
        Link l = link(e, sender);
        l.add(seq, now);
        l.lastViaRelay = e.viaRelay(from);
        l.lastFrom = from == null ? null : from.getHostAddress();
        if (type != org.communitycomms.app.net.Packet.TYPE_VOICE) EchoTest.onOtherPacket(e, sender, seq);
    }

    @Override
    public void onVoiceFrame(RadioEngine e, long sender, String name, int seq, int tx, short[] pcm, boolean late, InetAddress from) {
        if (app == null) return;
        // Lab only (a hidden setting, no screen): pretend some frames were lost, to check the loss figures.
        int drop = sp().getInt("labDropPct", 0);
        if (drop > 0 && !late && (((seq * 0x9E3779B1) >>> 8) % 100) < drop) return; // every copy of the same frame
        if (!late) link(e, sender).voice(seq, System.currentTimeMillis());
        EchoTest.onFrame(e, sender, name, seq, tx, pcm, late, from);
    }

    @Override
    public void onVoiceEnd(RadioEngine e, long sender, String name, int tx) {
        if (app == null) return;
        EchoTest.onEnd(e, sender, tx);
    }

    @Override
    public void onLocation(RadioEngine e, RadioEngine.Station s, InetAddress from, int hops) {
        if (app == null) return;
        MeshMap.onLocation(e, s, from, hops);
    }

    @Override
    public boolean onChat(RadioEngine e, long sender, String name, int seq, InetAddress from, byte[] p, int off) {
        if (app == null || off >= p.length) return false;
        int kind = p[off] & 0xff;
        try {
            if (kind == KIND_PROBE) {
                MeshWalk.onProbe(e, sender, name, from, p, off + 1);
                return true;
            }
            if (kind == KIND_PROBE_ACK) {
                MeshWalk.onAnswer(e, sender, name, p, off + 1);
                return true;
            }
            if (kind == org.communitycomms.app.msg.MessageCenter.KIND_TEXT) EchoTest.onChat(e, sender, name, from, p, off + 1);
        } catch (RuntimeException ex) {
            Log.d(TAG, "bad test body: " + ex);
            return kind == KIND_PROBE || kind == KIND_PROBE_ACK;
        }
        return false;
    }

    // ---- the home screen banner ----

    /** What is running now, for the home banner ("" = nothing). */
    public static String bannerText() {
        StringBuilder b = new StringBuilder();
        if (TestAudio.running()) b.append("Transmitting test audio");
        if (EchoTest.running()) b.append(b.length() > 0 ? " · " : "").append("Echo Test running");
        if (MeshWalk.running()) b.append(b.length() > 0 ? " · " : "").append("Mapping walk: ").append(MeshWalk.statusWord());
        return b.toString();
    }

    /** Stops every mesh test tool that is running (the banner's Stop). */
    public static void stopAll() {
        TestAudio.stop("stopped");
        EchoTest.stop();
        MeshWalk.stop();
    }
}
