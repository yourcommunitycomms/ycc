package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyAgreement;

/**
 * Sharing the mesh nodes' ADMIN login (user + password, and the node list) from the Mesh controller to team phones,
 * so they can read radio data (Mesh radio, walk polling). OpenMANET's openmanetd has no read-only accounts (it logs
 * in system users through PAM and has no roles), so for now this hands out FULL admin access, with many warnings.
 *
 * Never tied to the channel key (knowing the channel name is not enough): over a PRIVATE channel the channel only
 * carries it. Each receiver makes a fresh P-256 key pair when it accepts, sends the public half, the admin's phone
 * shows its fingerprint (for an optional check by voice) and seals the login to that key only: ECDH with a fresh
 * key of its own, then encKey / macKey = HMAC-SHA256(secret, "YCCN1 enc" / "YCCN1 mac") and AES-CBC + HMAC-SHA256
 * (ProtectedCrypto.seal, works on Android 4.1). A QR is sealed with a share PIN of 6+ characters (PBKDF2, 20,000).
 *
 * Receivers never see the password (shown as ●●●●●), it is kept in this app's private storage only (no backups: the
 * app has allowBackup off; never in the setup QR, exports or logs) and used only for reading unless the receiver also
 * has the Mesh controller on (then a red banner says whose login it is). "Forget" clears it; the admin's "Revoke"
 * asks the phones that hear it to forget; the real revoke is a new password on every node.
 *
 * Chat kinds (sent 3 times, deduplicated): 32 offer, 33 accept (receiver's public key), 34 sealed login, 35 forget.
 */
public final class NodeShare {
    private static final String TAG = "NodeShare";
    public static final int KIND_OFFER = 32, KIND_ACCEPT = 33, KIND_LOGIN = 34, KIND_FORGET = 35, KIND_RO = 36;
    public static final String QR_PREFIX = "YCCNODE1:";
    public static final String EXTRA_QR = "nodeShareQr";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int QR_ITER = 20000;

    private NodeShare() {
    }

    static SharedPreferences sp() {
        return MeshTest.app.getSharedPreferences("nodeshare", Context.MODE_PRIVATE);
    }

    // ---- what this phone holds (receiver side) ----

    public static boolean held() {
        return MeshTest.app != null && sp().getString("p", null) != null;
    }

    static String user() {
        return sp().getString("u", "root");
    }

    /** The shared password, for the node API only: never shown. */
    static String pass() {
        return sp().getString("p", null);
    }

    public static String from() {
        return sp().getString("from", "");
    }

    public static long at() {
        return sp().getLong("at", 0);
    }

    public static String describe() {
        if (!held()) return "No shared node login on this phone.";
        return "Node login ●●●●● from " + from() + ", " + new java.text.SimpleDateFormat("d MMM HH:mm", Locale.US).format(new java.util.Date(at()))
                + " (user " + user() + "). Used to read radio data only" + (MeshControllerActivity.featureOn(MeshTest.app) ? "; the Mesh controller can use it too." : ".");
    }

    public static void forget(String why) {
        if (!held()) return;
        String f = from();
        sp().edit().remove("u").remove("p").remove("from").remove("at").commit();
        log(why + " (was from " + f + ")");
        MeshNode.LOCAL.stop();
    }

    static void store(String user, String pass, String from, JSONArray nodes) {
        sp().edit().putString("u", user).putString("p", pass).putString("from", from).putLong("at", System.currentTimeMillis()).commit();
        if (nodes != null && nodes.length() > 0) { // the admin's node list, if this phone has none yet (names and addresses only)
            SharedPreferences m = MeshRadio.ctl();
            if ("[]".equals(m.getString("nodes", "[]"))) m.edit().putString("nodes", nodes.toString()).commit();
        }
        log("Accepted the node admin login from " + from);
        MeshNode.LOCAL.stop();
        MeshRadio.ensure(MeshTest.app);
    }

    // ---- log (who shared and when; never a password) ----

    static void log(String line) {
        try {
            JSONArray a = new JSONArray(sp().getString("log", "[]"));
            a.put(new JSONObject().put("t", System.currentTimeMillis()).put("s", line));
            while (a.length() > 100) {
                JSONArray b = new JSONArray();
                for (int i = 1; i < a.length(); i++) b.put(a.get(i));
                a = b;
            }
            sp().edit().putString("log", a.toString()).commit();
        } catch (Exception ignored) {
        }
        Log.i(TAG, line);
    }

    public static String logText() {
        StringBuilder b = new StringBuilder();
        try {
            JSONArray a = new JSONArray(sp().getString("log", "[]"));
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                b.append(f.format(new java.util.Date(o.getLong("t")))).append("  ").append(o.getString("s")).append("\n");
            }
        } catch (Exception ignored) {
        }
        return b.length() == 0 ? "Nothing shared or received yet." : b.toString();
    }

    // ---- crypto ----

    static KeyPair newKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static PublicKey pub(byte[] x509) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
    }

    /** Six digits from a public key, to compare by voice. */
    public static String fingerprint(byte[] x509) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(x509);
            long v = ((h[0] & 0xffL) << 24 | (h[1] & 0xffL) << 16 | (h[2] & 0xffL) << 8 | (h[3] & 0xffL)) % 1000000L;
            return String.format(Locale.US, "%03d %03d", v / 1000, v % 1000);
        } catch (Exception e) {
            return "?";
        }
    }

    static ProtectedCrypto.Keys ecdhKeys(java.security.PrivateKey mine, PublicKey theirs, int id) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        byte[] s = ka.generateSecret();
        byte[] salt = ("YCCN1 " + id).getBytes(UTF8);
        byte[] master = ProtectedCrypto.hmac(salt, s);
        Arrays.fill(s, (byte) 0);
        ProtectedCrypto.Keys k = new ProtectedCrypto.Keys(ProtectedCrypto.hmac(master, "YCCN1 enc".getBytes(UTF8)),
                ProtectedCrypto.hmac(master, "YCCN1 mac".getBytes(UTF8)));
        Arrays.fill(master, (byte) 0);
        return k;
    }

    static byte[] loginJson(String user, String pass, String nodesJson) throws Exception {
        JSONObject o = new JSONObject().put("u", user).put("p", pass);
        if (nodesJson != null) {
            JSONArray in = new JSONArray(nodesJson), out = new JSONArray();
            for (int i = 0; i < in.length() && out.toString().length() < 500; i++) {
                JSONObject n = in.getJSONObject(i);
                out.put(new JSONObject().put("name", n.optString("name")).put("ip", n.optString("ip")));
            }
            o.put("n", out);
        }
        return o.toString().getBytes(UTF8);
    }

    // ---- the wire ----

    private static byte[] str(String s) {
        byte[] b = s.getBytes(UTF8);
        return b.length <= 32 ? b : Arrays.copyOf(b, 32);
    }

    private static String readStr(ByteBuffer b) {
        int n = b.get() & 0xff;
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, UTF8);
    }

    private static void send3(final RadioEngine e, final byte[] body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    e.sendTestBody(body);
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }
        }, "node-share-send").start();
    }

    private static final Set<String> seen = new HashSet<String>();

    private static boolean firstTime(int kind, long sender, int id) {
        synchronized (seen) {
            if (seen.size() > 300) seen.clear();
            return seen.add(kind + ":" + sender + ":" + id);
        }
    }

    static String me() {
        RadioService rs = RadioService.instance();
        return rs == null ? "" : new Prefs(rs).airName();
    }

    /** Where the login may travel: a YCC channel that is private (never Public, the cloud group or OM/TAK). */
    static String channelProblem() {
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null || rs.compat() != null ? null : rs.engine();
        if (e == null) return "Join a private YCC channel first (not Public, the cloud group or OM/TAK).";
        if (MeshTest.isPublic(e)) return "Not on a public channel: switch to a private channel (A or B) first.";
        if (e.listenOnly()) return "This channel is listen-only on this phone.";
        return null;
    }

    static RadioEngine engine() {
        RadioService rs = RadioService.instance();
        return rs == null || rs.compat() != null ? null : rs.engine();
    }

    // ---- admin side ----

    private static final class Outgoing {
        int id;
        String target, user, pass, nodes;
        long at;
        final Set<String> sentTo = new HashSet<String>();
    }

    private static volatile Outgoing out;

    /** The admin sends an offer (target "" = everyone on the channel). The login stays in memory until 10 minutes pass. */
    static void offer(String target, String user, String pass, String nodesJson) {
        RadioEngine e = engine();
        if (e == null || channelProblem() != null) return;
        Outgoing o = new Outgoing();
        o.id = new java.security.SecureRandom().nextInt() & 0x7fffffff;
        o.target = target;
        o.user = user;
        o.pass = pass;
        o.nodes = nodesJson;
        o.at = System.currentTimeMillis();
        out = o;
        byte[] t = str(target);
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 1 + t.length);
        b.put((byte) KIND_OFFER).put((byte) 1).putInt(o.id).put((byte) t.length).put(t);
        send3(e, b.array());
        log("Offered the node admin login to " + (target.length() == 0 ? "everyone on " + MeshTest.channelNow() : target));
    }

    static void revoke() {
        RadioEngine e = engine();
        out = null;
        if (e == null) return;
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 1);
        b.put((byte) KIND_FORGET).put((byte) 1).putInt(new java.security.SecureRandom().nextInt() & 0x7fffffff).put((byte) 0);
        send3(e, b.array());
        log("Sent Revoke (forget the shared login) on " + MeshTest.channelNow());
    }

    /** An accept arrived for our offer: ask the admin (fingerprint) before sealing the login to that key. */
    static void onAccept(RadioEngine e, long sender, String name, byte[] p, int off) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int id = b.getInt();
        String admin = readStr(b);
        int n = b.getShort() & 0xffff;
        final byte[] key = new byte[n];
        b.get(key);
        final Outgoing o = out;
        if (o == null || o.id != id || !admin.equalsIgnoreCase(me()) || !firstTime(KIND_ACCEPT, sender, id)) return;
        if (System.currentTimeMillis() - o.at > 600000) {
            out = null;
            return;
        }
        if (o.target.length() > 0 && !o.target.equalsIgnoreCase(name)) return;
        if (o.sentTo.contains(name)) return;
        final String who = name;
        post(new Ask() {
            @Override
            public void show(final Activity a) {
                new AlertDialog.Builder(a).setTitle(who + " accepted")
                        .setMessage(who + " accepted the node admin login. Its key check number is\n\n    " + fingerprint(key)
                                + "\n\nOptional: ask " + who + " to read the number on their screen. If it is the same, nobody "
                                + "swapped the key on the way. Send the login, sealed to " + who + "'s key only?")
                        .setPositiveButton("Send to " + who, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                sendLogin(o, who, key);
                                Toast.makeText(a, "Sealed login sent to " + who, Toast.LENGTH_SHORT).show();
                            }
                        }).setNegativeButton("Don't send", null).setCancelable(false).show();
            }
        });
    }

    static void sendLogin(Outgoing o, String who, byte[] theirKey) {
        RadioEngine e = engine();
        if (e == null || channelProblem() != null) return;
        try {
            KeyPair mine = newKeys();
            ProtectedCrypto.Keys k = ecdhKeys(mine.getPrivate(), pub(theirKey), o.id);
            byte[] sealed = ProtectedCrypto.seal(k, loginJson(o.user, o.pass, o.nodes));
            k.wipe();
            byte[] myPub = mine.getPublic().getEncoded();
            byte[] t = str(who);
            ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 1 + t.length + 2 + myPub.length + 2 + sealed.length);
            b.put((byte) KIND_LOGIN).put((byte) 1).putInt(o.id).put((byte) t.length).put(t);
            b.putShort((short) myPub.length).put(myPub).putShort((short) sealed.length).put(sealed);
            send3(e, b.array());
            o.sentTo.add(who);
            log("Sent the node admin login to " + who + " (key " + fingerprint(theirKey) + ")");
        } catch (Exception ex) {
            Log.w(TAG, "seal", ex);
        }
    }

    // ---- receiver side ----

    private static final Map<Integer, KeyPair> myKeys = new HashMap<Integer, KeyPair>();

    static void onOffer(RadioEngine e, final long sender, final String name, byte[] p, int off) {
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        final int id = b.getInt();
        String target = readStr(b);
        String me = me();
        if (name.equalsIgnoreCase(me) || MeshTest.isPublic(e) || !firstTime(KIND_OFFER, sender, id)) return;
        if (target.length() > 0 && !target.equalsIgnoreCase(me)) return;
        if (!MeshRadio.available(MeshTest.app)) return; // OpenMANET off: nothing to use it for
        log(name + " offered the node admin login");
        post(new Ask() {
            @Override
            public void show(final Activity a) {
                new AlertDialog.Builder(a).setTitle(name + " wants to give you the admin login for the mesh nodes")
                        .setMessage("This is FULL ADMIN access to every OpenMANET node (it could change their channel and "
                                + "passwords). YCC will only use it to READ radio data (OpenMANET Mesh, Mapping walk)"
                                + (MeshControllerActivity.featureOn(a) ? ", and your Mesh controller could use it too" : "")
                                + ". You will never see the password. Accept only if you know " + name + " and expected this.")
                        .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                accept(a, id, name);
                            }
                        }).setNegativeButton("Reject", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                log("Rejected the node admin login from " + name);
                            }
                        }).setCancelable(false).show();
            }
        });
    }

    static void accept(Activity a, int id, String admin) {
        RadioEngine e = engine();
        if (e == null) return;
        try {
            KeyPair kp = newKeys();
            synchronized (myKeys) {
                myKeys.put(id, kp);
            }
            byte[] pk = kp.getPublic().getEncoded();
            byte[] t = str(admin);
            ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 1 + t.length + 2 + pk.length);
            b.put((byte) KIND_ACCEPT).put((byte) 1).putInt(id).put((byte) t.length).put(t).putShort((short) pk.length).put(pk);
            send3(e, b.array());
            new AlertDialog.Builder(a).setTitle("Waiting for " + admin)
                    .setMessage("Your key check number is\n\n    " + fingerprint(pk) + "\n\n" + admin + " may ask you to read it out. The login "
                            + "arrives sealed to this phone's key only.").setPositiveButton("OK", null).show();
        } catch (Exception ex) {
            Toast.makeText(a, "Could not make a key: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void onLogin(RadioEngine e, long sender, final String name, byte[] p, int off) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int id = b.getInt();
        String target = readStr(b);
        if (!target.equalsIgnoreCase(me()) || !firstTime(KIND_LOGIN, sender, id)) return;
        byte[] theirPub = new byte[b.getShort() & 0xffff];
        b.get(theirPub);
        byte[] sealed = new byte[b.getShort() & 0xffff];
        b.get(sealed);
        KeyPair kp;
        synchronized (myKeys) {
            kp = myKeys.remove(id);
        }
        if (kp == null) return; // not one this phone accepted (or YCC restarted since)
        ProtectedCrypto.Keys k = ecdhKeys(kp.getPrivate(), pub(theirPub), id);
        byte[] plain = ProtectedCrypto.open(k, sealed);
        k.wipe();
        if (plain == null) {
            log("A login from " + name + " did not open (wrong key or changed on the way)");
            return;
        }
        JSONObject o = new JSONObject(new String(plain, UTF8));
        Arrays.fill(plain, (byte) 0);
        store(o.optString("u", "root"), o.getString("p"), name, o.optJSONArray("n"));
        post(new Ask() {
            @Override
            public void show(Activity a) {
                Toast.makeText(a, "Node login received from " + name + " (●●●●●). OpenMANET Mesh can read your node now.", Toast.LENGTH_LONG).show();
            }
        });
    }

    static void onForget(RadioEngine e, long sender, final String name, byte[] p, int off) {
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int id = b.getInt();
        if (!firstTime(KIND_FORGET, sender, id) || !held() || !name.equalsIgnoreCase(from())) return; // only the one who shared it
        forget("Forgot the shared login: " + name + " revoked it");
        post(new Ask() {
            @Override
            public void show(Activity a) {
                Toast.makeText(a, name + " revoked the shared node login: forgotten", Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- the read-only login: it can only read radio data, so it goes plainly on the private channel (no key exchange) ----

    /** Sends the read-only login to everyone on this private channel; each phone is asked before using it. */
    public static String shareRo(String user, String pass, String nodesJson) {
        String cp = channelProblem();
        if (cp != null) return cp;
        RadioEngine e = engine();
        try {
            byte[] js = loginJson(user, pass, nodesJson);
            ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + 2 + js.length);
            b.put((byte) KIND_RO).put((byte) 1).putInt(new java.security.SecureRandom().nextInt() & 0x7fffffff).putShort((short) js.length).put(js);
            send3(e, b.array());
            log("Shared the read-only node login (" + user + ") with everyone on " + MeshTest.channelNow());
            return null;
        } catch (Exception ex) {
            return String.valueOf(ex.getMessage());
        }
    }

    static void onRo(RadioEngine e, long sender, final String name, byte[] p, int off) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(p, off, p.length - off);
        if (b.get() != 1) return;
        int id = b.getInt();
        if (name.equalsIgnoreCase(me()) || MeshTest.isPublic(e) || !firstTime(KIND_RO, sender, id)) return;
        if (!MeshRadio.available(MeshTest.app)) return;
        byte[] js = new byte[b.getShort() & 0xffff];
        b.get(js);
        final JSONObject o = new JSONObject(new String(js, UTF8));
        final String u = o.optString("u", RoLogin.DEFAULT_USER), pw = RoLogin.norm(o.optString("p"));
        if (!RoLogin.valid(pw)) return;
        if (RoLogin.held() && u.equals(RoLogin.user()) && pw.equals(RoLogin.pass())) return; // already have it
        log(name + " shared the read-only node login (" + u + ")");
        post(new Ask() {
            @Override
            public void show(final Activity a) {
                new AlertDialog.Builder(a).setTitle(name + " shared the read-only node login")
                        .setMessage("Use the read-only login " + u + " to read your mesh node's radio data (OpenMANET Mesh, Mapping walk)? "
                                + "It can only read, never change a node." + (RoLogin.held() ? "\n\nIt replaces the read-only login this phone has now." : ""))
                        .setPositiveButton("Use it", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                RoLogin.store(u, pw, name, o.optJSONArray("n"));
                                Toast.makeText(a, "Read-only login from " + name + " saved", Toast.LENGTH_SHORT).show();
                            }
                        }).setNegativeButton("No", null).show();
            }
        });
    }

    /** From MeshTest's chat hook. True when the body was one of ours. */
    static boolean onChat(RadioEngine e, long sender, String name, int kind, byte[] p, int off) {
        try {
            if (kind == KIND_OFFER) onOffer(e, sender, name, p, off);
            else if (kind == KIND_ACCEPT) onAccept(e, sender, name, p, off);
            else if (kind == KIND_LOGIN) onLogin(e, sender, name, p, off);
            else if (kind == KIND_FORGET) onForget(e, sender, name, p, off);
            else if (kind == KIND_RO) onRo(e, sender, name, p, off);
            else return false;
        } catch (Exception ex) {
            Log.d(TAG, "bad share body: " + ex);
        }
        return true;
    }

    // ---- dialogs waiting for a screen ----

    interface Ask {
        void show(Activity a);
    }

    private static final ArrayDeque<Ask> asks = new ArrayDeque<Ask>();

    private static void post(Ask a) {
        synchronized (asks) {
            asks.add(a);
        }
    }

    /** Called every second by the open YCC screens (home, Mesh controller, Mesh radio): shows what is waiting. */
    public static void showPending(final Activity a) {
        if (a.isFinishing()) return;
        Ask next;
        synchronized (asks) {
            next = asks.poll();
        }
        if (next != null) next.show(a);
    }

    // ---- QR (sealed with a share PIN) ----

    static String makeQr(String user, String pass, String nodesJson, String pin) throws Exception {
        byte[] salt = ProtectedCrypto.random(16);
        ProtectedCrypto.Keys k = ProtectedCrypto.derive(pin.toCharArray(), salt, QR_ITER);
        byte[] sealed = ProtectedCrypto.seal(k, loginJson(user, pass, nodesJson));
        k.wipe();
        byte[] from = str(me());
        ByteBuffer b = ByteBuffer.allocate(1 + from.length + 16 + sealed.length);
        b.put((byte) from.length).put(from).put(salt).put(sealed);
        return QR_PREFIX + Base64.encodeToString(b.array(), Base64.NO_WRAP | Base64.URL_SAFE);
    }

    /** A scanned share QR: ask for the PIN, then the same Accept prompt. */
    public static void fromQr(final Activity a, final String text) {
        final byte[] raw;
        try {
            raw = Base64.decode(text.substring(QR_PREFIX.length()), Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (RuntimeException e) {
            Toast.makeText(a, "Not a node login QR", Toast.LENGTH_LONG).show();
            return;
        }
        final ByteBuffer b = ByteBuffer.wrap(raw);
        final String from = readStr(b);
        final EditText pin = new EditText(a);
        pin.setSingleLine(true);
        pin.setHint("Share PIN (from " + from + ")");
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(a).setTitle(from + " wants to give you the admin login for the mesh nodes")
                .setMessage("FULL ADMIN access to every OpenMANET node. YCC will only use it to READ radio data. You will never see "
                        + "the password. Enter the share PIN " + from + " told you, then Accept.")
                .setView(pin).setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            byte[] salt = new byte[16];
                            b.get(salt);
                            byte[] sealed = new byte[b.remaining()];
                            b.get(sealed);
                            ProtectedCrypto.Keys k = ProtectedCrypto.derive(pin.getText().toString().toCharArray(), salt, QR_ITER);
                            byte[] plain = ProtectedCrypto.open(k, sealed);
                            k.wipe();
                            if (plain == null) {
                                Toast.makeText(a, "Wrong PIN: nothing saved. Scan again.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            JSONObject o = new JSONObject(new String(plain, UTF8));
                            Arrays.fill(plain, (byte) 0);
                            store(o.optString("u", "root"), o.getString("p"), from + " (QR)", o.optJSONArray("n"));
                            Toast.makeText(a, "Node login saved (●●●●●) from " + from, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(a, "Could not open it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }).setNegativeButton("Reject", null).show();
    }

    // ---- the admin's share flow (Mesh controller > More) ----

    private static final String[] WARNINGS = {
            "I understand this is FULL ADMIN access to every mesh node: whoever has it can change the channel, the mesh "
                    + "name and passwords, or wipe the nodes' settings.",
            "I understand anyone who gets it (a lost or stolen phone, a copied QR and its PIN) can use it, whatever channel they are on.",
            "I understand it cannot be taken back: Revoke only reaches phones that hear it. The real fix is a new password on every node.",
            "I am sharing it only with people I trust.",
            "I will change the nodes' password after this event."};

    /** user / pass come from the Mesh controller's login (memory only); nodesJson is its node list (names and addresses). */
    public static void startShare(final Activity a, final String user, final String pass, final String nodesJson) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView head = new TextView(a);
        head.setText("OpenMANET nodes have no read-only login, so this shares the ADMIN login you are logged in with. The "
                + "receiving phones use it only to read radio data, but the login itself can do anything. It is sealed to each "
                + "receiving phone's own key (or a PIN for a QR), never to the channel name, and is never shown on any screen.");
        head.setTextColor(0xffff8a80);
        box.addView(head);
        final CheckBox[] ticks = new CheckBox[WARNINGS.length];
        final EditText typed = new EditText(a);
        final Button[] go = new Button[1];
        CompoundButton.OnCheckedChangeListener l = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (go[0] != null) go[0].setEnabled(ready(ticks, typed));
            }
        };
        for (int i = 0; i < WARNINGS.length; i++) {
            ticks[i] = new CheckBox(a);
            ticks[i].setText(WARNINGS[i]);
            ticks[i].setOnCheckedChangeListener(l);
            box.addView(ticks[i]);
        }
        typed.setSingleLine(true);
        typed.setHint("Type SHARE to go on");
        typed.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (go[0] != null) go[0].setEnabled(ready(ticks, typed));
            }
        });
        box.addView(typed);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Share the node ADMIN login").setView(sv)
                .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        chooseHow(a, user, pass, nodesJson);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
        go[0] = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
        go[0].setEnabled(false);
    }

    private static boolean ready(CheckBox[] ticks, EditText typed) {
        for (CheckBox c : ticks) if (!c.isChecked()) return false;
        return "SHARE".equals(typed.getText().toString().trim());
    }

    private static void chooseHow(final Activity a, final String user, final String pass, final String nodesJson) {
        String[] items = {"Send to everyone on this private channel (each phone must Accept)", "Send to one callsign…",
                "Show as a QR code (sealed with a share PIN)"};
        new AlertDialog.Builder(a).setTitle("How to share").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                if (w == 2) {
                    askPin(a, user, pass, nodesJson);
                    return;
                }
                String bad = channelProblem();
                if (bad != null) {
                    new AlertDialog.Builder(a).setTitle("Not sent").setMessage(bad).setPositiveButton("OK", null).show();
                    return;
                }
                if (w == 0) {
                    offer("", user, pass, nodesJson);
                    sentNote(a, "everyone on " + MeshTest.channelNow());
                } else pickCallsign(a, user, pass, nodesJson);
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void sentNote(Activity a, String who) {
        new AlertDialog.Builder(a).setTitle("Offer sent")
                .setMessage("Offered to " + who + ". Each phone that accepts sends its key; you then see its key check number and "
                        + "tap Send for each one. The offer lasts 10 minutes. Keep YCC open.").setPositiveButton("OK", null).show();
    }

    private static void pickCallsign(final Activity a, final String user, final String pass, final String nodesJson) {
        RadioEngine e = engine();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        if (e != null) for (RadioEngine.Station s : e.stations()) {
            if (s.name.startsWith("via ") || s.name.endsWith("(TAK)") || names.contains(s.name) || s.name.toLowerCase(Locale.US).contains("echo test")) continue;
            names.add(s.name);
        }
        if (names.isEmpty()) {
            Toast.makeText(a, "Nobody heard on this channel yet", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a).setTitle("Send to").setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                offer(names.get(w), user, pass, nodesJson);
                sentNote(a, names.get(w));
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void askPin(final Activity a, final String user, final String pass, final String nodesJson) {
        final EditText pin = new EditText(a);
        pin.setSingleLine(true);
        pin.setHint("Share PIN, 6 or more characters");
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(a).setTitle("Share PIN").setMessage("The QR is sealed with this PIN. Tell it to people separately "
                        + "(by voice), never on the same screen or in chat.").setView(pin)
                .setPositiveButton("Make QR", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String p = pin.getText().toString();
                        if (p.length() < 6) {
                            Toast.makeText(a, "The PIN needs 6 or more characters", Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            showQr(a, makeQr(user, pass, nodesJson, p));
                            log("Showed the node admin login as a PIN-sealed QR");
                        } catch (Exception e) {
                            Toast.makeText(a, "QR failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showQr(Activity a, String payload) throws Exception {
        a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); // no screenshots of it
        final Activity act = a;
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), 0);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(QrShowActivity.render(payload));
        iv.setAdjustViewBounds(true);
        box.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (300 * d)));
        final TextView left = new TextView(a);
        box.addView(left);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Node login QR (PIN-sealed)").setView(box)
                .setPositiveButton("Hide now", null).show();
        final Handler h = new Handler(Looper.getMainLooper());
        final long until = System.currentTimeMillis() + 60000;
        h.post(new Runnable() {
            @Override
            public void run() {
                long s = (until - System.currentTimeMillis()) / 1000;
                if (s <= 0 || !dlg.isShowing()) {
                    if (dlg.isShowing()) dlg.dismiss();
                    act.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    return;
                }
                left.setText("Hides in " + s + " s. Scan with YCC (Settings > Tools > Scan, or Feature menus > Scan a QR code); they also need the PIN. "
                        + "Screenshots are blocked.");
                h.postDelayed(this, 1000);
            }
        });
    }

    /** Admin: Revoke, with its warning. */
    public static void confirmRevoke(final Activity a) {
        new AlertDialog.Builder(a).setTitle("Revoke the shared login")
                .setMessage("Asks every phone on this channel that holds a login YOU shared to forget it. It only reaches phones "
                        + "that are on and hear it now, and anyone who copied it keeps it. The real revoke is a new password on every "
                        + "node: on each node's web page (LuCI, System > Administration > Router Password), or the Mesh controller "
                        + "when it can change it.")
                .setPositiveButton("Send Revoke", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String bad = channelProblem();
                        if (bad != null) {
                            Toast.makeText(a, bad, Toast.LENGTH_LONG).show();
                            return;
                        }
                        revoke();
                        Toast.makeText(a, "Revoke sent", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void showLog(Activity a) {
        new AlertDialog.Builder(a).setTitle("Node login sharing log").setMessage(describe() + "\n\n" + logText())
                .setPositiveButton("OK", null).show();
    }

    public static void confirmForget(final Activity a, final Runnable after) {
        if (!held()) {
            Toast.makeText(a, "No shared login on this phone", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a).setTitle("Forget the shared login").setMessage(describe() + "\n\nForget it on this phone?")
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        forget("Forgot the shared login (by hand)");
                        if (after != null) after.run();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
