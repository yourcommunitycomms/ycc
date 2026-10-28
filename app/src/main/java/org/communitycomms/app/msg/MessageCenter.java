package org.communitycomms.app.msg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.communitycomms.app.Prefs;
import org.communitycomms.app.RadioEngine;
import org.communitycomms.app.geo.Poi;
import org.communitycomms.app.geo.PoiStore;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Chat, reports and catch-up sync on the channel on air. Bodies ride in encrypted TYPE_CHAT
 * packets (after the sender's callsign). Big-endian, shared with the future Windows app:
 *
 * <pre>
 *  kind(1)
 *   1 TEXT    id(8) ageMs(8) creatorLen(1) creator textLen(2) text(UTF-8)
 *   2 REPORT  id(8) ageMs(8) creatorLen(1) creator dataLen(2) deflated Report.toText()
 *   3 DIGEST  flags(1, bit0 = "answer with what I lack") count(1), then per entry:
 *             type(1) id(8) version(4) ageSec(4) creatorLen(1) creator labelLen(1) label
 *   4 REQUEST count(1), then per entry: type(1) id(8)
 *   5 ACK     same layout as TEXT; an automatic "message received" reply. Never itself acknowledged.
 *   6 PRIVATE id(8) ageMs(8) creatorLen(1) creator toFingerprint(8) sealedLen(2) PrivateKeys.seal(text)
 *   7 KEY     pubLen(2) public key DER (X.509): announces this phone's key for private messages
 * Private messages are never put in digests or resent, and never auto-acknowledged.
 * </pre>
 *
 * Ages, not clock times, are sent so phones with wrong clocks still agree. A digest is metadata
 * only ("X sent a report at T"); a phone that lacks a text, report or POI asks for it with a
 * REQUEST and any holder resends it. Files are never resent this way: a missed file shows up in
 * the chat as "missed" so the sender can be asked to send it again.
 */
public final class MessageCenter {
    public interface Air {
        /** The engine on air, or null. */
        RadioEngine engine();

        /** Shown and spoken to the user, e.g. for a new message. */
        void onNewMessage(MessageLog.Entry e);
    }

    public static final int KIND_TEXT = 1, KIND_REPORT = 2, KIND_DIGEST = 3, KIND_REQUEST = 4, KIND_ACK = 5, KIND_PRIVATE = 6, KIND_KEY = 7;
    /** Largest body we put in one UDP packet (FileTransfer uses the same size for its chunks). */
    public static final int MAX_BODY = 1150;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Air air;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    /** Id → when someone last (re)sent it on air, so only one holder answers a request. */
    private final Map<Long, Long> recentlyOnAir = new HashMap<Long, Long>();
    private final Set<Long> requested = new HashSet<Long>();

    public MessageCenter(Context c, Air air) {
        this.ctx = c.getApplicationContext();
        this.air = air;
    }

    /** The engine these messages go out on (the channel on air, or a listened one). */
    public RadioEngine onAir() {
        return air.engine();
    }

    private MessageLog log() {
        return MessageLog.get(ctx);
    }

    private String me() {
        String n = new Prefs(ctx).callsign();
        return n.length() == 0 ? "?" : n;
    }

    // ---- sending ----

    /** Sends a text message now. Returns false when not on air. */
    public boolean sendText(String text) {
        RadioEngine e = air.engine();
        if (e == null) return false;
        MessageLog.Entry m = new MessageLog.Entry();
        m.id = random.nextLong();
        m.type = MessageLog.TYPE_TEXT;
        m.time = System.currentTimeMillis();
        m.creator = me();
        m.channelTag = e.channelTag();
        m.body = text;
        m.mine = true;
        log().put(m);
        transmit(e, m);
        // ATAK compatible: Public channel messages also go to ATAK's All Chat Rooms (never private channels).
        if (org.communitycomms.app.RadioService.onPublic()) org.communitycomms.app.AtakBridge.sendChat(text);
        return true;
    }

    // ---- pinned announcements (a TEXT starting "[pin] "; older phones just show the text) ----

    public static final String PIN = "[pin] ", UNPIN = "[unpin:";

    public static boolean isPin(MessageLog.Entry m) {
        return m.type == MessageLog.TYPE_TEXT && m.body.startsWith(PIN);
    }

    public static boolean isUnpin(MessageLog.Entry m) {
        return m.type == MessageLog.TYPE_TEXT && m.body.startsWith(UNPIN);
    }

    /** Ids taken down by an "[unpin:id]" message among these entries. */
    public static Set<Long> unpinned(List<MessageLog.Entry> all) {
        Set<Long> out = new HashSet<Long>();
        for (MessageLog.Entry m : all) {
            if (!isUnpin(m)) continue;
            try {
                out.add(new java.math.BigInteger(m.body.substring(UNPIN.length(), m.body.indexOf(']')), 16).longValue());
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /** An announcement pinned at the top of everyone's Chat, and sent again to anyone who joins later. */
    public boolean sendPin(String text) {
        return sendText(PIN + text);
    }

    public boolean unpin(long id) {
        return sendText(UNPIN + Long.toHexString(id) + "]");
    }

    private long pinsResentAt;

    /**
     * Someone new was heard on the channel: send them this phone's own pinned announcements again
     * (same ids, so everyone else ignores the copies). At most once every 30 s.
     */
    public void resendPins() {
        RadioEngine e = air.engine();
        long now = System.currentTimeMillis();
        if (e == null || now - pinsResentAt < 30000) return;
        pinsResentAt = now;
        List<MessageLog.Entry> all = log().all(e.channelTag());
        Set<Long> down = unpinned(all);
        for (MessageLog.Entry m : all) {
            if (m.mine && isPin(m) && !down.contains(m.id)) transmit(e, m);
        }
    }

    /** Bytes the report takes on air (must be at most MAX_BODY). */
    public static int reportSize(Report r) {
        return 20 + 33 + deflate(r.toText().getBytes(UTF8)).length;
    }

    /**
     * Sends a report now (at <= 0) or queues it for a time. A queued report goes out on the
     * channel that is on air now, as soon as it is due and that channel is on air again.
     */
    public MessageLog.Entry sendReport(Report r, long at, int channelTag) {
        MessageLog.Entry m = new MessageLog.Entry();
        m.id = random.nextLong();
        m.type = MessageLog.TYPE_REPORT;
        m.creator = me();
        m.channelTag = channelTag;
        m.body = r.toText();
        m.mine = true;
        if (at > System.currentTimeMillis()) {
            m.time = at;
            m.state = MessageLog.STATE_SCHEDULED;
            log().put(m);
            return m;
        }
        m.time = System.currentTimeMillis();
        log().put(m);
        RadioEngine e = air.engine();
        if (e != null && e.channelTag() == channelTag) transmit(e, m);
        else {
            m.state = MessageLog.STATE_SCHEDULED; // not on that channel: goes when it is
            log().update(m);
        }
        return m;
    }

    /** Called every few seconds: sends scheduled reports that are due on the channel on air. */
    public void sendDue() {
        RadioEngine e = air.engine();
        if (e == null || RadioEngine.radioSilent) return; // scheduled reports wait for the silence to end
        long now = System.currentTimeMillis();
        for (MessageLog.Entry m : log().scheduled()) {
            if (m.time > now || m.channelTag != e.channelTag()) continue;
            m.state = MessageLog.STATE_DONE;
            m.time = now;
            log().update(m);
            transmit(e, m);
        }
    }

    /** Sends an entry's full body (twice: broadcast has no retries). */
    private void transmit(RadioEngine e, MessageLog.Entry m) {
        byte[] body = encodeFull(m);
        if (body == null) return;
        synchronized (recentlyOnAir) {
            recentlyOnAir.put(m.id, System.currentTimeMillis());
        }
        e.sendChatBody(body, 2);
    }

    private static byte[] encodeFull(MessageLog.Entry m) {
        byte[] c = trunc(m.creator.getBytes(UTF8), 32);
        byte[] data;
        int kind;
        if (m.type == MessageLog.TYPE_TEXT) {
            kind = m.ack ? KIND_ACK : KIND_TEXT;
            data = trunc(m.body.getBytes(UTF8), 1000);
        } else if (m.type == MessageLog.TYPE_REPORT) {
            kind = KIND_REPORT;
            data = deflate(m.body.getBytes(UTF8));
        } else {
            return null;
        }
        long age = Math.max(0, System.currentTimeMillis() - m.time);
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 8 + 1 + c.length + 2 + data.length);
        b.put((byte) kind).putLong(m.id).putLong(age).put((byte) c.length).put(c).putShort((short) data.length).put(data);
        return b.array();
    }

    // ---- receiving ----

    /** A TYPE_CHAT body from another station (fromName = who put it on air). */
    public void onMessage(String fromName, byte[] p, int off) {
        RadioEngine e = air.engine();
        if (e == null || off >= p.length) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
            int kind = b.get() & 0xff;
            // Dual watch: a listen-only channel takes in messages and reports, nothing that needs a reply.
            // (Its catch-up answers, DIGEST without ask, are taken in too, when the channel requests catch-ups.)
            if (e.isMonitor() && kind != KIND_TEXT && kind != KIND_REPORT && kind != KIND_ACK && kind != KIND_DIGEST) return;
            if (kind == KIND_TEXT || kind == KIND_REPORT || kind == KIND_ACK) onFull(kind, b, e.channelTag());
            else if (kind == KIND_DIGEST) onDigest(b, e);
            else if (kind == KIND_REQUEST) onRequest(b, e);
            else if (kind == KIND_KEY) onKey(fromName, b);
            else if (kind == KIND_PRIVATE) onPrivate(b, e.channelTag());
            else if (kind == KIND_VOICE) onVoice(b, e);
        } catch (RuntimeException ignored) {
            // malformed
        }
    }

    private void onFull(int kind, ByteBuffer b, int tag) {
        MessageLog.Entry m = new MessageLog.Entry();
        m.id = b.getLong();
        m.time = System.currentTimeMillis() - Math.max(0, b.getLong());
        m.creator = str(b, b.get() & 0xff);
        byte[] data = new byte[b.getShort() & 0xffff];
        b.get(data);
        synchronized (recentlyOnAir) {
            recentlyOnAir.put(m.id, System.currentTimeMillis());
        }
        synchronized (requested) {
            requested.remove(m.id);
        }
        m.type = kind == KIND_REPORT ? MessageLog.TYPE_REPORT : MessageLog.TYPE_TEXT;
        m.ack = kind == KIND_ACK;
        m.body = kind == KIND_REPORT ? inflate(data) : new String(data, UTF8);
        if (m.body == null) return;
        m.channelTag = tag;
        MessageLog.Entry old = log().find(m.id);
        boolean wasMissing = old != null && old.state == MessageLog.STATE_MISSING;
        if (log().put(m)) {
            if (!wasMissing && !m.ack) air.onNewMessage(m);
            maybeAcknowledge(m);
        }
    }

    // ---- private messages ----

    /** Public keys heard on the channel: callsign → DER. */
    private final Map<String, byte[]> keys = new HashMap<String, byte[]>();

    /** Callsigns we can send a private message to (they announced a key on this channel). */
    public List<String> privateRecipients() {
        synchronized (keys) {
            List<String> out = new ArrayList<String>(keys.keySet());
            java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
            return out;
        }
    }

    /** Announces this phone's public key (on join, every minute, and when someone new asks). */
    public void sendKey() {
        final RadioEngine e = air.engine();
        if (e == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.security.KeyPair kp = PrivateKeys.get(ctx); // slow the first time
                if (kp == null) return;
                byte[] der = kp.getPublic().getEncoded();
                ByteBuffer b = ByteBuffer.allocate(3 + der.length);
                b.put((byte) KIND_KEY).putShort((short) der.length).put(der);
                e.sendChatBody(b.array(), 1);
            }
        }, "pm-key").start();
    }

    private void onKey(String fromName, ByteBuffer b) {
        byte[] der = new byte[b.getShort() & 0xffff];
        b.get(der);
        boolean isNew;
        synchronized (keys) {
            isNew = !keys.containsKey(fromName);
            keys.put(fromName, der);
        }
        // A newcomer may not have ours yet: answer once, after a random pause.
        if (isNew) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendKey();
                }
            }, 500 + random.nextInt(2000));
        }
    }

    /** Sends text only `to` can read. Returns null if sent, else why not. */
    public String sendPrivate(final String to, final String text) {
        final RadioEngine e = air.engine();
        if (e == null) return "Join a channel first";
        if (RadioEngine.radioSilent) return "Radio silent: private message not sent";
        final byte[] der;
        synchronized (keys) {
            der = keys.get(to);
        }
        if (der == null) return to + " has not shared a private-message key on this channel yet";
        final MessageLog.Entry m = new MessageLog.Entry();
        m.id = random.nextLong();
        m.type = MessageLog.TYPE_TEXT;
        m.time = System.currentTimeMillis();
        m.creator = me();
        m.channelTag = e.channelTag();
        m.body = text;
        m.mine = true;
        m.privateTo = to;
        log().put(m);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] sealed = PrivateKeys.seal(der, trunc(text.getBytes(UTF8), 700));
                    byte[] c = trunc(m.creator.getBytes(UTF8), 32);
                    ByteBuffer b = ByteBuffer.allocate(1 + 8 + 8 + 1 + c.length + 8 + 2 + sealed.length);
                    b.put((byte) KIND_PRIVATE).putLong(m.id).putLong(0).put((byte) c.length).put(c)
                            .putLong(PrivateKeys.fingerprint(der)).putShort((short) sealed.length).put(sealed);
                    e.sendChatBody(b.array(), 2);
                } catch (Exception ex) {
                    android.util.Log.e("MessageCenter", "private send failed", ex);
                }
            }
        }, "pm-send").start();
        return null;
    }

    private void onPrivate(ByteBuffer b, final int tag) {
        final long id = b.getLong();
        final long time = System.currentTimeMillis() - Math.max(0, b.getLong());
        final String creator = str(b, b.get() & 0xff);
        long toFp = b.getLong();
        final long fp = toFp;
        final byte[] sealed = new byte[b.getShort() & 0xffff];
        b.get(sealed);
        if (log().find(id) != null) return; // second copy
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.security.KeyPair kp = PrivateKeys.get(ctx);
                if (kp == null || fp != PrivateKeys.fingerprint(kp.getPublic().getEncoded())) return; // not for us
                byte[] plain = PrivateKeys.open(kp.getPrivate(), sealed);
                if (plain == null) return;
                final MessageLog.Entry m = new MessageLog.Entry();
                m.id = id;
                m.type = MessageLog.TYPE_TEXT;
                m.time = time;
                m.creator = creator;
                m.channelTag = tag;
                m.body = new String(plain, UTF8);
                m.privateTo = me();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (log().put(m)) air.onNewMessage(m);
                    }
                });
            }
        }, "pm-open").start();
    }

    // ---- acknowledgement mode (range testing / debug) ----

    /** Only messages this fresh are acknowledged, so catch-up resends of old ones are not. */
    private static final long ACK_MAX_AGE_MS = 60000;
    private static final int ACK_MAX_PER_MINUTE = 20;
    private final Set<Long> acked = new HashSet<Long>();
    private final java.util.ArrayDeque<Long> ackTimes = new java.util.ArrayDeque<Long>();

    /**
     * With the setting on, replies to a received text or report with "ACK ...". Loop protection:
     * acks are their own kind and are never acknowledged, each message is acked once, only fresh
     * messages are acked, and at most ACK_MAX_PER_MINUTE acks go out per minute.
     */
    private void maybeAcknowledge(final MessageLog.Entry m) {
        RadioEngine on = air.engine();
        if (on == null || on.isMonitor() || m.ack || m.mine || m.privateTo.length() > 0 || !new Prefs(ctx).autoAck()) return;
        long now = System.currentTimeMillis();
        final long delay = now - m.time;
        if (delay > ACK_MAX_AGE_MS || m.creator.equals(me())) return;
        synchronized (acked) {
            if (!acked.add(m.id)) return;
            while (!ackTimes.isEmpty() && now - ackTimes.peekFirst() > 60000) ackTimes.removeFirst();
            if (ackTimes.size() >= ACK_MAX_PER_MINUTE) return;
            ackTimes.addLast(now);
        }
        // Small random pause so several phones acking the same message do not collide.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                RadioEngine e = air.engine();
                if (e == null || e.channelTag() != m.channelTag) return;
                String what;
                if (m.type == MessageLog.TYPE_REPORT) {
                    Report r = Report.parse(m.body);
                    what = "report " + (r == null ? "" : r.title);
                } else {
                    what = "\"" + (m.body.length() > 40 ? m.body.substring(0, 40) + "…" : m.body) + "\"";
                }
                String sent = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(m.time));
                MessageLog.Entry a = new MessageLog.Entry();
                a.id = random.nextLong();
                a.type = MessageLog.TYPE_TEXT;
                a.ack = true;
                a.time = System.currentTimeMillis();
                a.creator = me();
                a.channelTag = e.channelTag();
                a.body = "ACK from " + me() + ": got " + m.creator + "'s " + what + " (sent " + sent + ", heard after "
                        + Math.max(0, delay / 1000) + " s)";
                a.mine = true;
                log().put(a);
                transmit(e, a);
            }
        }, 200 + random.nextInt(800));
    }

    // ---- catch-up sync ----

    private static final class DigestItem {
        int type, version;
        long id, time;
        String creator, label;
    }

    /** Channels page slot of a listen-only channel's center (-1 = the channel on air, or unknown). */
    public int slot = -1;

    /** Longest window a catch-up may ask for (a day). */
    public static final int MAX_WINDOW_MIN = 1440;

    /** Broadcasts what this phone has from the last windowMin minutes; ask = others answer with what we lack. */
    public void sendDigest(int windowMin, boolean ask) {
        RadioEngine e = air.engine();
        if (e == null) return;
        windowMin = Math.min(MAX_WINDOW_MIN, Math.max(1, windowMin));
        long since = System.currentTimeMillis() - windowMin * 60000L;
        askWindowMin = windowMin;
        sendDigestOf(e, collect(e, since), ask);
    }

    /** Window of the digest being sent: goes in the flags byte so others answer from that far back. */
    private int askWindowMin;

    /** Minutes since the newest message from someone else on the channel on air (0 = none known). */
    public int minutesSinceLastReceived() {
        RadioEngine e = air.engine();
        if (e == null) return 0;
        long t = log().lastReceived(e.channelTag());
        return t == 0 ? 0 : (int) Math.min(MAX_WINDOW_MIN, (System.currentTimeMillis() - t) / 60000 + 1);
    }

    private List<DigestItem> collect(RadioEngine e, long since) {
        List<DigestItem> out = new ArrayList<DigestItem>();
        Set<Long> livePois = new HashSet<Long>();
        for (Poi p : PoiStore.get(ctx).all()) livePois.add(p.id);
        for (MessageLog.Entry m : log().since(since, e.channelTag())) {
            if (m.type == MessageLog.TYPE_POI && (m.removed || !livePois.contains(m.id))) continue;
            if (m.privateTo.length() > 0 || m.local || m.path.contains("/rec-")) continue; // private or this phone's own recording: never advertised
            DigestItem d = new DigestItem();
            d.type = m.type;
            d.id = m.id;
            d.version = m.version;
            d.time = m.time;
            d.creator = m.creator;
            d.label = label(m);
            out.add(d);
        }
        return out;
    }

    private static String label(MessageLog.Entry m) {
        if (m.type == MessageLog.TYPE_REPORT) {
            Report r = Report.parse(m.body);
            return r == null ? "report" : r.title;
        }
        if (m.type == MessageLog.TYPE_FILE) return m.body;
        if (m.type == MessageLog.TYPE_POI) return m.body;
        return "";
    }

    private void sendDigestOf(RadioEngine e, List<DigestItem> items, boolean ask) {
        long now = System.currentTimeMillis();
        int i = 0;
        // Always send at least one packet when asking, so others know to answer even if we hold nothing.
        do {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int count = 0;
            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            while (i < items.size() && count < 255) {
                byte[] one = encodeItem(items.get(i), now);
                if (entries.size() + one.length > MAX_BODY - 3) break;
                entries.write(one, 0, one.length);
                count++;
                i++;
            }
            bo.write(KIND_DIGEST);
            // Flags: bit 0 = ask; bits 1-7 = how far back to answer from, in 12-minute steps (0 = the
            // receiver's own window). Older phones read only bit 0.
            int steps = Math.min(127, (askWindowMin + 11) / 12);
            bo.write((ask ? 1 : 0) | (ask ? steps << 1 : 0));
            bo.write(count);
            byte[] en = entries.toByteArray();
            bo.write(en, 0, en.length);
            e.sendChatBody(bo.toByteArray(), 1);
        } while (i < items.size());
    }

    private static byte[] encodeItem(DigestItem d, long now) {
        byte[] c = trunc(d.creator.getBytes(UTF8), 32), l = trunc(d.label.getBytes(UTF8), 40);
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 4 + 4 + 1 + c.length + 1 + l.length);
        b.put((byte) d.type).putLong(d.id).putInt(d.version).putInt((int) Math.max(0, (now - d.time) / 1000))
                .put((byte) c.length).put(c).put((byte) l.length).put(l);
        return b.array();
    }

    private void onDigest(ByteBuffer b, final RadioEngine e) {
        int flags = b.get() & 0xff;
        boolean ask = (flags & 1) != 0;
        int askedMin = (flags >> 1) * 12; // 0 = not said (older phones)
        int count = b.get() & 0xff;
        long now = System.currentTimeMillis();
        final List<long[]> want = new ArrayList<long[]>();
        Set<Long> theirs = new HashSet<Long>();
        Map<Long, Integer> theirVersion = new HashMap<Long, Integer>();
        for (int i = 0; i < count; i++) {
            DigestItem d = new DigestItem();
            d.type = b.get() & 0xff;
            d.id = b.getLong();
            d.version = b.getInt();
            d.time = now - (b.getInt() & 0xffffffffL) * 1000;
            d.creator = str(b, b.get() & 0xff);
            d.label = str(b, b.get() & 0xff);
            theirs.add(d.id);
            theirVersion.put(d.id, d.version);
            if (requesting(e) && lacks(d)) {
                if (d.type == MessageLog.TYPE_FILE) {
                    addMissing(d, e.channelTag());
                } else {
                    addMissing(d, e.channelTag());
                    synchronized (requested) {
                        if (requested.add(d.id)) want.add(new long[]{d.type, d.id});
                    }
                }
            }
        }
        if (!want.isEmpty()) {
            // A short random pause, so several phones that missed the same thing send one request.
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendRequest(want);
                }
            }, 200 + random.nextInt(1500));
        }
        if (ask && !e.isMonitor()) { // a listen-only channel never answers
            Prefs prefs = new Prefs(ctx);
            int window = Math.min(MAX_WINDOW_MIN, Math.max(prefs.catchUpWindowMin(), askedMin));
            List<DigestItem> mine = collect(e, now - window * 60000L);
            final List<DigestItem> missing = new ArrayList<DigestItem>();
            for (DigestItem d : mine) {
                Integer v = theirVersion.get(d.id);
                if (!theirs.contains(d.id) || (d.type == MessageLog.TYPE_POI && v != null && v < d.version)) missing.add(d);
            }
            if (!missing.isEmpty()) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        RadioEngine now = air.engine();
                        if (now != null) sendDigestOf(now, missing, false);
                    }
                }, 300 + random.nextInt(2500));
            }
        }
    }

    // ---- voice catch-up: missed live voice from phones that record all voice ----

    /**
     * KIND_VOICE: flags(1: bit0 ask, bits 1-7 window in 12-minute steps) count(1), then per recording
     * id(8, RadioEngine.voiceId: the same on every phone) ageSec(4) seconds(2) creatorLen(1) creator.
     * Ask = "these are the recordings I have, send me others". A phone that holds more waits a random
     * 0.3-2.8 s, leaves out any another phone has just announced, announces its own (no ask) and then
     * sends each as a file named VOICEREC-(id in hex).wav. Announced clips that do not arrive are asked
     * for again once. Older phones ignore this kind.
     */
    public static final int KIND_VOICE = 8;
    public static final String VOICE_FILE_PREFIX = "VOICEREC-";
    private static final int VOICE_MAX_FILES = 40;
    private static final long VOICE_MAX_BYTES = 10L * 1024 * 1024;
    /** Recording id -> when another phone announced it (so this one does not send it too). */
    private final Map<Long, Long> voiceAnnounced = new HashMap<Long, Long>();
    private long voiceReaskedAt;

    private static final class VoiceItem {
        long id, time;
        int seconds;
        String creator;
        java.io.File file;
    }

    private List<VoiceItem> collectVoice(RadioEngine e, long since) {
        List<VoiceItem> out = new ArrayList<VoiceItem>();
        for (MessageLog.Entry m : log().since(since, e.channelTag())) {
            if (m.type != MessageLog.TYPE_FILE || !m.local || !"audio/wav".equals(m.mime)) continue;
            java.io.File f = new java.io.File(m.path);
            String n = f.getName();
            if (!(n.startsWith("rec-") || n.startsWith(VOICE_FILE_PREFIX)) || !f.isFile()) continue;
            VoiceItem v = new VoiceItem();
            v.id = m.id;
            v.time = m.time;
            v.creator = m.creator;
            v.seconds = (int) Math.max(1, (f.length() - 44) / (2L * RadioEngine.SAMPLE_RATE));
            v.file = f;
            out.add(v);
        }
        return out;
    }

    /** Coming back to the app: lists the recordings this phone has from the last windowMin minutes. */
    public void sendVoiceDigest(int windowMin, boolean ask) {
        RadioEngine e = air.engine();
        if (e == null || e.isMonitor()) return;
        windowMin = Math.min(MAX_WINDOW_MIN, Math.max(1, windowMin));
        sendVoiceList(e, collectVoice(e, System.currentTimeMillis() - windowMin * 60000L), ask, windowMin);
    }

    private void sendVoiceList(RadioEngine e, List<VoiceItem> items, boolean ask, int windowMin) {
        long now = System.currentTimeMillis();
        int i = 0;
        do {
            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            int count = 0;
            while (i < items.size() && count < 255) {
                VoiceItem v = items.get(i);
                byte[] c = trunc(v.creator.getBytes(UTF8), 32);
                ByteBuffer one = ByteBuffer.allocate(8 + 4 + 2 + 1 + c.length);
                one.putLong(v.id).putInt((int) Math.max(0, (now - v.time) / 1000)).putShort((short) Math.min(32767, v.seconds))
                        .put((byte) c.length).put(c);
                if (entries.size() + one.capacity() > MAX_BODY - 3) break;
                entries.write(one.array(), 0, one.capacity());
                count++;
                i++;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bo.write(KIND_VOICE);
            bo.write((ask ? 1 : 0) | (ask ? Math.min(127, (windowMin + 11) / 12) << 1 : 0));
            bo.write(count);
            byte[] en = entries.toByteArray();
            bo.write(en, 0, en.length);
            e.sendChatBody(bo.toByteArray(), 1);
        } while (i < items.size());
    }

    private void onVoice(ByteBuffer b, final RadioEngine e) {
        int flags = b.get() & 0xff;
        boolean ask = (flags & 1) != 0;
        int askedMin = (flags >> 1) * 12;
        int count = b.get() & 0xff;
        final long now = System.currentTimeMillis();
        final Set<Long> theirs = new HashSet<Long>();
        List<VoiceItem> listed = new ArrayList<VoiceItem>();
        for (int i = 0; i < count; i++) {
            VoiceItem v = new VoiceItem();
            v.id = b.getLong();
            v.time = now - (b.getInt() & 0xffffffffL) * 1000;
            v.seconds = b.getShort() & 0xffff;
            v.creator = str(b, b.get() & 0xff);
            theirs.add(v.id);
            listed.add(v);
        }
        if (ask) {
            Prefs prefs = new Prefs(ctx);
            if (!prefs.recordAllVoice()) return; // only phones that keep recordings answer
            int window = Math.min(MAX_WINDOW_MIN, Math.max(prefs.catchUpWindowMin(), askedMin));
            final List<VoiceItem> offer = new ArrayList<VoiceItem>();
            long bytes = 0;
            for (VoiceItem v : collectVoice(e, now - window * 60000L)) {
                if (theirs.contains(v.id) || offer.size() >= VOICE_MAX_FILES || bytes + v.file.length() > VOICE_MAX_BYTES) continue;
                offer.add(v);
                bytes += v.file.length();
            }
            if (offer.isEmpty()) return;
            final int win = window;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    RadioEngine now2 = air.engine();
                    if (now2 == null) return;
                    List<VoiceItem> mine = new ArrayList<VoiceItem>();
                    synchronized (voiceAnnounced) {
                        for (VoiceItem v : offer) {
                            Long t = voiceAnnounced.get(v.id);
                            if (t == null || System.currentTimeMillis() - t > 60000) mine.add(v); // nobody else is sending it
                        }
                    }
                    if (mine.isEmpty()) return;
                    sendVoiceList(now2, mine, false, win); // announce first, so others leave these out
                    sendVoiceFiles(mine, 0);
                }
            }, 300 + random.nextInt(2500));
            return;
        }
        // Someone announced recordings they are about to send: do not send those, and show what is coming.
        final List<Long> coming = new ArrayList<Long>();
        synchronized (voiceAnnounced) {
            for (VoiceItem v : listed) voiceAnnounced.put(v.id, now);
        }
        for (VoiceItem v : listed) {
            if (log().find(v.id) != null) continue;
            MessageLog.Entry m = new MessageLog.Entry();
            m.id = v.id;
            m.type = MessageLog.TYPE_FILE;
            m.time = v.time;
            m.creator = v.creator;
            m.channelTag = e.channelTag();
            m.body = "Missed voice from " + v.creator + " (" + Math.max(1, v.seconds) + " s), fetching…";
            m.mime = "audio/wav";
            m.local = true;
            m.state = MessageLog.STATE_MISSING;
            log().put(m);
            coming.add(v.id);
        }
        if (coming.isEmpty()) return;
        // Gaps: anything announced that has not arrived is asked for again, once.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean gap = false;
                for (long id : coming) {
                    MessageLog.Entry m = log().find(id);
                    if (m != null && m.state == MessageLog.STATE_MISSING) gap = true;
                }
                if (gap && System.currentTimeMillis() - voiceReaskedAt > 5 * 60000L) {
                    voiceReaskedAt = System.currentTimeMillis();
                    sendVoiceDigest(Math.max(new Prefs(ctx).catchUpWindowMin(), minutesSinceLastReceived()), true);
                }
            }
        }, 30000 + 4000L * coming.size());
    }

    /** Messages, reports, map points and voice recordings on the channel between two times, not by `who`. */
    public int countSince(long from, long to, String who) {
        RadioEngine e = air.engine();
        if (e == null) return 0;
        int n = 0;
        for (MessageLog.Entry m : log().since(from, e.channelTag())) {
            if (m.time > to || m.privateTo.length() > 0 || who.equalsIgnoreCase(m.creator)) continue;
            if (m.type == MessageLog.TYPE_TEXT && !m.ack && !m.local || m.type == MessageLog.TYPE_REPORT
                    || m.type == MessageLog.TYPE_POI || isRecording(m)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isRecording(MessageLog.Entry m) {
        if (m.type != MessageLog.TYPE_FILE || !m.local || !"audio/wav".equals(m.mime)) return false;
        String n = new java.io.File(m.path).getName();
        return n.startsWith("rec-") || n.startsWith(VOICE_FILE_PREFIX);
    }

    /** Puts everything since a time on the channel again (commander "send" to someone back from offline). */
    public void resendSince(long since) {
        RadioEngine e = air.engine();
        if (e == null) return;
        for (MessageLog.Entry m : log().since(since, e.channelTag())) {
            if (m.privateTo.length() > 0) continue;
            if (m.type == MessageLog.TYPE_TEXT && !m.ack && !m.local || m.type == MessageLog.TYPE_REPORT) transmit(e, m);
            else if (m.type == MessageLog.TYPE_POI) resend(MessageLog.TYPE_POI, m.id);
        }
        List<VoiceItem> voice = collectVoice(e, since);
        if (!voice.isEmpty()) {
            if (voice.size() > VOICE_MAX_FILES) voice = voice.subList(voice.size() - VOICE_MAX_FILES, voice.size());
            sendVoiceList(e, voice, false, MAX_WINDOW_MIN); // announced, so other recorders do not send them too
            sendVoiceFiles(voice, 0);
        }
    }

    /** Sends announced recordings one after another, so a long catch-up does not flood the WiFi. */
    private void sendVoiceFiles(final List<VoiceItem> items, final int i) {
        if (i >= items.size()) return;
        org.communitycomms.app.net.FileTransfer ft = org.communitycomms.app.RadioService.files();
        if (ft == null) return;
        VoiceItem v = items.get(i);
        try {
            ft.send(v.file, VOICE_FILE_PREFIX + String.format(java.util.Locale.US, "%016x", v.id) + ".wav", "audio/wav");
        } catch (Exception ignored) {
        }
        // About 16 KB a second of recording: wait roughly as long as the clip takes to go out.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendVoiceFiles(items, i + 1);
            }
        }, 1500 + Math.min(20000, v.file.length() / 40));
    }

    /** This channel asks for what it lacks only when set to Request catch-ups (Channels page). */
    private boolean requesting(RadioEngine e) {
        Prefs p = new Prefs(ctx);
        int s = e.isMonitor() ? slot : p.activeSlot();
        return s >= 0 && p.channelCatchUp(s);
    }

    private boolean lacks(DigestItem d) {
        MessageLog.Entry m = log().find(d.id);
        if (d.type == MessageLog.TYPE_POI) {
            if (m != null && m.version >= d.version) return false; // includes our tombstones
            for (Poi p : PoiStore.get(ctx).all()) if (p.id == d.id && p.version >= d.version) return false;
            return true;
        }
        return m == null || m.state == MessageLog.STATE_MISSING && d.type != MessageLog.TYPE_FILE && !requested.contains(d.id);
    }

    private void addMissing(DigestItem d, int tag) {
        if (d.type == MessageLog.TYPE_POI) return; // POIs simply arrive; no placeholder needed
        MessageLog.Entry m = new MessageLog.Entry();
        m.id = d.id;
        m.type = d.type;
        m.version = d.version;
        m.time = d.time;
        m.creator = d.creator;
        m.channelTag = tag;
        m.body = d.label;
        m.state = MessageLog.STATE_MISSING;
        log().put(m);
    }

    private void sendRequest(List<long[]> want) {
        RadioEngine e = air.engine();
        if (e == null) return;
        for (int start = 0; start < want.size(); start += 100) {
            int n = Math.min(100, want.size() - start);
            ByteBuffer b = ByteBuffer.allocate(2 + n * 9);
            b.put((byte) KIND_REQUEST).put((byte) n);
            for (int i = 0; i < n; i++) b.put((byte) want.get(start + i)[0]).putLong(want.get(start + i)[1]);
            e.sendChatBody(b.array(), 1);
        }
        // Forget the request after a while, so the next digest can ask again if nobody answered.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (requested) {
                    requested.clear();
                }
            }
        }, 30000);
    }

    private void onRequest(ByteBuffer b, RadioEngine e) {
        int n = b.get() & 0xff;
        for (int i = 0; i < n; i++) {
            final int type = b.get() & 0xff;
            final long id = b.getLong();
            final long asked = System.currentTimeMillis();
            // Random pause; skip if someone else resent it in the meantime.
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (recentlyOnAir) {
                        Long last = recentlyOnAir.get(id);
                        if (last != null && last >= asked) return;
                    }
                    resend(type, id);
                }
            }, 100 + random.nextInt(1500));
        }
    }

    private void resend(int type, long id) {
        RadioEngine e = air.engine();
        if (e == null) return;
        if (type == MessageLog.TYPE_POI) {
            for (Poi p : PoiStore.get(ctx).all()) {
                if (p.id == id) {
                    synchronized (recentlyOnAir) {
                        recentlyOnAir.put(id, System.currentTimeMillis());
                    }
                    e.sendPoiBody(p.encode());
                }
            }
            return;
        }
        MessageLog.Entry m = log().find(id);
        if (m != null && m.state == MessageLog.STATE_DONE && m.channelTag == e.channelTag() && m.privateTo.length() == 0) transmit(e, m);
    }

    /** A POI was sent or arrived on air (for request suppression). */
    public void notePoiOnAir(long id) {
        synchronized (recentlyOnAir) {
            recentlyOnAir.put(id, System.currentTimeMillis());
        }
    }

    // ---- helpers ----

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
        d.end();
        return bo.toByteArray();
    }

    static String inflate(byte[] in) {
        Inflater f = new Inflater();
        f.setInput(in);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try {
            while (!f.finished()) {
                int n = f.inflate(buf);
                if (n == 0 && (f.needsInput() || f.needsDictionary())) break;
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024) return null;
            }
            return new String(bo.toByteArray(), UTF8);
        } catch (java.util.zip.DataFormatException e) {
            return null;
        } finally {
            f.end();
        }
    }

    private static String str(ByteBuffer b, int n) {
        byte[] x = new byte[n];
        b.get(x);
        return new String(x, UTF8);
    }

    private static byte[] trunc(byte[] b, int max) {
        if (b.length <= max) return b;
        byte[] t = new byte[max];
        System.arraycopy(b, 0, t, 0, max);
        return t;
    }
}
