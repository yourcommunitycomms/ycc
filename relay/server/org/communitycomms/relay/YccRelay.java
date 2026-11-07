package org.communitycomms.relay;

import org.communitycomms.app.crypto.ChannelCrypto;
import org.communitycomms.app.net.RelayAdmin;
import org.communitycomms.app.net.Tunnel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Headless YCC internet relay (runs on a small VPS as ycc-relay.jar).
 * <p>
 * Same tunnel frames as the app's direct relay (Tunnel.java, docs/PROTOCOL.md section 11). Every
 * user (a site = a MANET's gateway phone, or a single remote phone) has its own key; all of them
 * share one port, and each frame's key ID says whose key to check it with. Each user keeps its own
 * session and replay window. Packets are forwarded by channel tag between everyone connected. The
 * relay keeps no audio or message history and never has channel keys: radio packets stay
 * end-to-end encrypted phone to phone. Frames failing the key check are dropped silently.
 * <p>
 * Users are added, renamed and removed from the admin's phone (Relay admin screen, RelayAdmin
 * packets sealed with the admin key) and kept in the state file. The admin key is made on first
 * start and kept in its own file, readable by the relay's user only.
 * <p>
 * Heartbeat: every connected phone sends a HELLO every 5 s; 30 s without one = down, the first
 * one back = up again with the outage length. Shorter drops are logged only. The relay tells
 * everyone the site list (SITES frame) every 10 s and on any change.
 * <p>
 * Config file (one entry per line, # for comments):
 * <pre>
 *   port=47100                                (UDP and TCP, for every user and the admin)
 *   state=/var/lib/ycc-relay/users.conf       (users, written by the relay)
 *   adminkey=/var/lib/ycc-relay/admin.key     (made on first start)
 *   status=127.0.0.1:47199
 *   history=/var/lib/ycc-relay/history.log
 *   site|HQ|47100|DRYADROWLETTERS             (old style lines: taken into the users once, and
 *   remote|ALPHA1|47101|DRYADROWLETTERS        their own ports kept open for phones set up before)
 * </pre>
 * The status page binds to 127.0.0.1 only (reach it over an SSH tunnel): plain text, users,
 * up/down, last seen, and the down/up and admin history.
 * <p>
 * Usage: java -jar ycc-relay.jar /etc/ycc-relay/relay.conf
 * <br>   java -jar ycc-relay.jar --admin-text /etc/ycc-relay/relay.conf host:port   (prints the admin QR text)
 * <br>   java -jar ycc-relay.jar --new-admin-key /etc/ycc-relay/relay.conf          (replaces the admin key)
 */
public final class YccRelay {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long SHORT_GAP_MS = 12000;
    static final String VERSION = "2";

    static final class User {
        String kind; // "site" or "remote"
        String name;
        String key;
        int keyId;
        long created;
        long savedSeen; // last seen, from the state file
        int legacyPort; // 0, or the port an old style config line gave it
        Tunnel.Remote remote;
        int state; // 0 never, 1 up, 2 down
        long downSince;
        long upSince;
        boolean gapping;
        long lastSeenRx;

        long seen() {
            long rx = remote == null ? 0 : remote.lastRx();
            return Math.max(rx, savedSeen);
        }
    }

    private final List<User> users = new CopyOnWriteArrayList<User>();
    private final Set<String> imported = new LinkedHashSet<String>();
    private final LinkedList<String> history = new LinkedList<String>();
    private String statusAddr = "127.0.0.1:47199";
    private int mainPort = 47100;
    private boolean legacyPorts = true;
    private File historyFile;
    private File stateFile = new File("/var/lib/ycc-relay/users.conf");
    private File adminKeyFile = new File("/var/lib/ycc-relay/admin.key");
    private final long startedMs = System.currentTimeMillis();
    private Tunnel.Relay relay;
    private Tunnel.Mux mux;
    private RelayAdmin.Server admin;
    private long adminKeyMtime;
    private volatile boolean sitesChanged;
    private final Set<Integer> listening = new HashSet<Integer>();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--admin-text")) {
            YccRelay y = new YccRelay();
            y.readConf(new File(args.length > 1 ? args[1] : defaultConf()), null);
            String[] k = y.loadAdminKey(true);
            String addr = args.length > 2 ? args[2] : "RELAY-ADDRESS:" + y.mainPort;
            System.out.println(RelayAdmin.QR_PREFIX + addr + "|" + k[0]);
            return;
        }
        if (args.length > 0 && args[0].equals("--new-admin-key")) {
            YccRelay y = new YccRelay();
            y.readConf(new File(args.length > 1 ? args[1] : defaultConf()), null);
            y.writeAdminKey(RelayAdmin.newAdminKey(), null);
            System.out.println("New admin key written to " + y.adminKeyFile + " (a running relay picks it up within 5 s; the old one stops working).");
            System.out.println("Show it as text for a QR code with --admin-text.");
            return;
        }
        new YccRelay().run(new File(args.length > 0 ? args[0] : defaultConf()));
    }

    private static String defaultConf() {
        return new File(jarDir(), "relay.conf").getPath();
    }

    private static File jarDir() {
        try {
            return new File(YccRelay.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }

    /** Reads the config; old style lines go into legacy (kind, name, port, row) when it is not null. */
    private void readConf(File conf, List<String[]> legacy) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(conf), UTF8));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && line.indexOf('|') < 0) {
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("status")) statusAddr = v;
                else if (k.equals("history")) historyFile = new File(v);
                else if (k.equals("state")) stateFile = new File(v);
                else if (k.equals("adminkey")) adminKeyFile = new File(v);
                else if (k.equals("legacyports")) legacyPorts = !v.equalsIgnoreCase("off");
                else if (k.equals("port")) {
                    try {
                        mainPort = Integer.parseInt(v);
                    } catch (NumberFormatException x) {
                        System.out.println("relay.conf line " + n + ": bad port");
                    }
                } else System.out.println("relay.conf line " + n + " ignored (unknown setting " + k + ")");
                continue;
            }
            String[] f = line.split("\\|", 4);
            if (f.length < 4 || !(f[0].trim().equals("site") || f[0].trim().equals("remote")) || f[3].trim().length() == 0) {
                System.out.println("relay.conf line " + n + " ignored (want kind|name|port|DRYAD row)");
                continue;
            }
            if (legacy != null) legacy.add(new String[]{f[0].trim(), cleanName(f[1]), f[2].trim(), f[3].trim()});
        }
        r.close();
    }

    private void run(File conf) throws Exception {
        List<String[]> legacy = new ArrayList<String[]>();
        readConf(conf, legacy);
        loadHistory();
        relay = Tunnel.Relay.startShared();
        mux = new Tunnel.Mux(relay);
        loadState();
        // Old style config lines: taken in once (a user the admin later removes stays removed).
        boolean changed = false;
        for (String[] l : legacy) {
            int id = Tunnel.keyId(l[3]);
            String tag = Integer.toHexString(id);
            if (imported.contains(tag)) continue;
            imported.add(tag);
            if (byId(id) != null) continue;
            User u = new User();
            u.kind = l[0];
            u.name = uniqueName(l[1]);
            u.key = l[3];
            u.keyId = id;
            u.created = System.currentTimeMillis();
            try {
                u.legacyPort = Integer.parseInt(l[2]);
            } catch (NumberFormatException e) {
                u.legacyPort = 0;
            }
            users.add(u);
            changed = true;
            System.out.println("  took in " + u.kind + " " + u.name + " from relay.conf");
        }
        for (User u : users) attach(u);
        if (changed) saveState();
        String[] keys = loadAdminKey(true);
        admin = new RelayAdmin.Server(new RelayAdmin.Handler() {
            @Override
            public String run(String[] lines, String from, boolean viaNewKey) {
                return adminCommand(lines, from, viaNewKey);
            }
        });
        admin.setKeys(keys[0], keys[1]);
        mux.admin = new Tunnel.Mux.Admin() {
            @Override
            public byte[] onAdmin(byte[] b, int len, String from) {
                return admin.onPacket(b, len, from);
            }
        };
        openPorts();
        for (String p : mux.problems) System.out.println("  " + p);
        System.out.println("  " + users.size() + " user(s) on port " + mainPort + " UDP+TCP");
        event("relay started with " + users.size() + " user(s) (version " + VERSION + ")");
        startStatus();
        long lastSites = 0, lastSave = System.currentTimeMillis(), lastKeyCheck = 0;
        while (true) {
            long now = System.currentTimeMillis();
            boolean ch = sitesChanged;
            sitesChanged = false;
            for (User u : users) ch |= check(u, now);
            if (ch || now - lastSites >= 10000) {
                lastSites = now;
                byte[] body = sitesBody(now);
                for (User u : users) if (u.remote != null) u.remote.sendSites(body);
            }
            if (now - lastKeyCheck >= 5000) {
                lastKeyCheck = now;
                reloadAdminKeyIfChanged();
            }
            if (now - lastSave >= 15 * 60000L) {
                lastSave = now;
                saveState(); // keeps "last seen" across restarts
            }
            Thread.sleep(1000);
        }
    }

    private void openPorts() {
        if (listening.add(mainPort)) mux.listen(mainPort);
        if (!legacyPorts) return;
        for (User u : users) {
            if (u.legacyPort > 0 && listening.add(u.legacyPort)) mux.listen(u.legacyPort);
        }
    }

    /** Derives the user's key and puts it on the shared port. */
    private void attach(User u) throws Exception {
        ChannelCrypto k = Tunnel.key(u.key);
        u.keyId = k.channelTag;
        u.remote = relay.addShared(u.name, k);
        mux.put(u.keyId, u.remote);
    }

    private User byId(int id) {
        for (User u : users) if (u.keyId == id) return u;
        return null;
    }

    private User byIdText(String hex) {
        for (User u : users) if (idText(u.keyId).equalsIgnoreCase(hex.trim())) return u;
        return null;
    }

    static String idText(int id) {
        return String.format("%08x", id);
    }

    static String cleanName(String s) {
        String n = s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return n.length() > 30 ? n.substring(0, 30).trim() : n;
    }

    private String uniqueName(String n) {
        String base = n.length() == 0 ? "USER" : n, out = base;
        for (int i = 2; nameTaken(out, null); i++) out = base + " " + i;
        return out;
    }

    private boolean nameTaken(String n, User except) {
        for (User u : users) if (u != except && u.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    // ---- admin commands ----

    private synchronized String adminCommand(String[] l, String from, boolean viaNewKey) {
        String cmd = l.length > 0 ? l[0].trim() : "";
        if (cmd.equals("#repeat")) {
            event("admin: a repeated request from " + from + " was not run again");
            return "";
        }
        if (viaNewKey) {
            // The first command with the key from "rotate": it replaces the old key now.
            try {
                String[] k = loadAdminKey(false);
                writeAdminKey(k[1], null);
                admin.setKeys(k[1], null);
                event("admin: new admin key in use (old key stopped), from " + from);
            } catch (Exception e) {
                return "err\ncould not save the new admin key: " + e.getMessage();
            }
        }
        try {
            if (cmd.equals("ping")) return "ok\nYCC relay version " + VERSION + ", " + users.size() + " user(s), port " + mainPort;
            if (cmd.equals("list")) {
                StringBuilder sb = new StringBuilder("ok");
                long now = System.currentTimeMillis();
                for (User u : users) {
                    long seen = u.seen();
                    boolean online = u.remote != null && u.remote.lastRx() > 0 && now - u.remote.lastRx() < Tunnel.DOWN_MS;
                    sb.append('\n').append(idText(u.keyId)).append('|').append(u.kind).append('|').append(u.name).append('|')
                            .append(online ? 1 : 0).append('|').append(seen == 0 ? -1 : (now - seen) / 1000).append('|')
                            .append(u.legacyPort);
                }
                return sb.toString();
            }
            if (cmd.equals("add")) {
                if (l.length < 3) return "err\nadd needs a kind and a name";
                String kind = l[1].trim();
                if (!kind.equals("site") && !kind.equals("remote")) return "err\nkind must be site or remote";
                String name = cleanName(l[2]);
                if (name.length() == 0) return "err\nthe name is empty";
                if (nameTaken(name, null)) return "err\nthere is already a user called " + name;
                if (users.size() >= 250) return "err\nthe relay is full (250 users)";
                User u = new User();
                u.kind = kind;
                u.name = name;
                u.created = System.currentTimeMillis();
                do {
                    u.key = RelayAdmin.newKey(28); // 131 bits
                    u.keyId = Tunnel.keyId(u.key);
                } while (u.keyId == 0 || byId(u.keyId) != null);
                attach(u);
                users.add(u);
                saveState();
                sitesChanged = true;
                event("admin: added " + kind + " " + name + " (id " + idText(u.keyId) + "), from " + from);
                return "ok\n" + idText(u.keyId) + "\n" + u.key;
            }
            if (cmd.equals("key")) {
                User u = l.length > 1 ? byIdText(l[1]) : null;
                if (u == null) return "err\nno such user";
                event("admin: setup key read for " + u.kind + " " + u.name + ", from " + from);
                return "ok\n" + u.key;
            }
            if (cmd.equals("remove")) {
                User u = l.length > 1 ? byIdText(l[1]) : null;
                if (u == null) return "err\nno such user";
                users.remove(u);
                mux.remove(u.keyId);
                if (u.remote != null) relay.removeShared(u.remote, "removed by the relay admin");
                saveState();
                sitesChanged = true;
                event("admin: removed " + u.kind + " " + u.name + " (id " + idText(u.keyId) + "), cut off now, from " + from);
                return "ok";
            }
            if (cmd.equals("rename")) {
                User u = l.length > 2 ? byIdText(l[1]) : null;
                if (u == null) return "err\nno such user";
                String name = cleanName(l[2]);
                if (name.length() == 0) return "err\nthe name is empty";
                if (nameTaken(name, u)) return "err\nthere is already a user called " + name;
                String old = u.name;
                u.name = name;
                saveState();
                sitesChanged = true;
                event("admin: renamed " + u.kind + " " + old + " to " + name + ", from " + from);
                return "ok";
            }
            if (cmd.equals("rotate")) {
                String k = RelayAdmin.newAdminKey();
                String[] cur = loadAdminKey(false);
                writeAdminKey(cur[0], k);
                admin.setKeys(cur[0], k);
                event("admin: admin key change started (the old key works until the new one is first used), from " + from);
                return "ok\n" + k;
            }
            return "err\nunknown command " + cmd;
        } catch (Exception e) {
            return "err\n" + e.getMessage();
        }
    }

    // ---- state file and admin key file (relay user only) ----

    private void loadState() throws IOException {
        if (!stateFile.exists()) return;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(stateFile), UTF8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("imported|")) {
                imported.add(line.substring(9).trim());
                continue;
            }
            if (!line.startsWith("user|")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 8) continue;
            User u = new User();
            u.kind = f[1];
            u.name = f[2];
            u.key = f[3];
            try {
                u.created = Long.parseLong(f[4]);
                u.savedSeen = Long.parseLong(f[5]);
                u.legacyPort = Integer.parseInt(f[6]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (u.key.length() > 0 && (u.kind.equals("site") || u.kind.equals("remote"))) users.add(u);
        }
        r.close();
    }

    private synchronized void saveState() {
        StringBuilder sb = new StringBuilder("# YCC relay users, written by the relay (private: holds every user's key)\n");
        sb.append("# user|kind|name|key|created|last seen|old port|\n");
        for (User u : users) {
            sb.append("user|").append(u.kind).append('|').append(u.name).append('|').append(u.key).append('|')
                    .append(u.created).append('|').append(u.seen()).append('|').append(u.legacyPort).append("|\n");
        }
        for (String i : imported) sb.append("imported|").append(i).append('\n');
        try {
            writePrivate(stateFile, sb.toString());
        } catch (IOException e) {
            System.out.println("could not save " + stateFile + ": " + e.getMessage());
        }
    }

    /** {current, pending or ""}; makes a key if there is none and create is set. */
    private String[] loadAdminKey(boolean create) throws IOException {
        if (!adminKeyFile.exists()) {
            if (!create) throw new IOException("no admin key file");
            writeAdminKey(RelayAdmin.newAdminKey(), null);
            System.out.println("Made a new admin key in " + adminKeyFile + " (show it with --admin-text)");
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(adminKeyFile), UTF8));
        String cur = "", pend = "", line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#") || line.length() == 0) continue;
            if (line.startsWith("pending=")) pend = RelayAdmin.normalize(line.substring(8));
            else if (cur.length() == 0) cur = RelayAdmin.normalize(line);
        }
        r.close();
        adminKeyMtime = adminKeyFile.lastModified();
        return new String[]{cur, pend};
    }

    private void writeAdminKey(String cur, String pending) throws IOException {
        writePrivate(adminKeyFile, "# YCC relay admin key (private)\n" + cur + "\n" + (pending == null ? "" : "pending=" + pending + "\n"));
        adminKeyMtime = adminKeyFile.lastModified();
    }

    /** --new-admin-key run by hand while the relay runs. */
    private void reloadAdminKeyIfChanged() {
        if (adminKeyFile.lastModified() == adminKeyMtime) return;
        try {
            String[] k = loadAdminKey(false);
            admin.setKeys(k[0], k[1]);
            event("admin: admin key file changed on the server, new key loaded");
        } catch (Exception e) {
            System.out.println("admin key file: " + e.getMessage());
        }
    }

    /** Writes via a temporary file, readable and writable by this user only. */
    private static void writePrivate(File f, String text) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        File tmp = new File(dir, f.getName() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            setOwnerOnly(tmp);
            o.write(text.getBytes(UTF8));
            o.getFD().sync();
        } finally {
            o.close();
        }
        setOwnerOnly(tmp);
        try {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setOwnerOnly(File f) {
        try {
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            // not a POSIX file system (Windows test runs)
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
    }

    /** Up/down with the 30 s rule; true if the state changed. */
    private boolean check(User e, long now) {
        if (e.remote == null) return false;
        long rx = e.remote.lastRx();
        if (rx == 0) return false;
        long silent = now - rx;
        if (silent >= Tunnel.DOWN_MS) {
            if (e.state != 2) {
                e.state = 2;
                e.downSince = rx;
                e.gapping = false;
                event(e.kind + " " + e.name + " DOWN (last heard " + ago(silent) + " ago)");
                return true;
            }
            return false;
        }
        if (e.state != 1) {
            boolean back = e.state == 2;
            e.state = 1;
            e.upSince = now;
            event(e.kind + " " + e.name + (back ? " UP after " + ago(now - e.downSince) + " down" : " UP (first contact)")
                    + " from " + e.remote.peer());
            e.lastSeenRx = rx;
            return true;
        }
        if (silent >= SHORT_GAP_MS) e.gapping = true;
        else if (e.gapping && rx != e.lastSeenRx) {
            e.gapping = false;
            event(e.kind + " " + e.name + " short drop (under 30 s, not alerted)");
        }
        e.lastSeenRx = rx;
        return false;
    }

    private byte[] sitesBody(long now) {
        List<byte[]> parts = new ArrayList<byte[]>();
        int len = 1;
        for (User e : users) {
            if (parts.size() >= 255) break;
            byte[] nm = e.name.getBytes(UTF8);
            int nl = Math.min(nm.length, 40);
            byte[] b = new byte[7 + nl];
            b[0] = (byte) (e.kind.equals("site") ? 1 : 2);
            b[1] = (byte) e.state;
            long rx = e.remote == null ? 0 : e.remote.lastRx();
            long sec = rx == 0 ? 0 : Math.min(0xffffffffL, (now - rx) / 1000);
            b[2] = (byte) (sec >>> 24);
            b[3] = (byte) (sec >>> 16);
            b[4] = (byte) (sec >>> 8);
            b[5] = (byte) sec;
            b[6] = (byte) nl;
            System.arraycopy(nm, 0, b, 7, nl);
            parts.add(b);
            len += b.length;
        }
        byte[] out = new byte[len];
        out[0] = (byte) parts.size();
        int o = 1;
        for (byte[] b : parts) {
            System.arraycopy(b, 0, out, o, b.length);
            o += b.length;
        }
        return out;
    }

    // ---- history ----

    private synchronized void event(String what) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "  " + what;
        System.out.println(line);
        history.add(line);
        while (history.size() > 500) history.removeFirst();
        if (historyFile != null) {
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(historyFile, true), UTF8);
                w.write(line + "\n");
                w.close();
            } catch (IOException e) {
                System.out.println("history file: " + e.getMessage());
                historyFile = null;
            }
        }
    }

    private void loadHistory() {
        if (historyFile == null || !historyFile.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(historyFile), UTF8));
            String l;
            while ((l = r.readLine()) != null) {
                history.add(l);
                if (history.size() > 500) history.removeFirst();
            }
            r.close();
        } catch (IOException e) {
            System.out.println("history file: " + e.getMessage());
        }
    }

    // ---- status page (127.0.0.1 only) ----

    private void startStatus() {
        int c = statusAddr.lastIndexOf(':');
        final String host = c > 0 ? statusAddr.substring(0, c) : "127.0.0.1";
        final int port = Integer.parseInt(statusAddr.substring(c + 1));
        final ServerSocket ss;
        try {
            InetAddress a = InetAddress.getByName(host);
            if (!a.isLoopbackAddress()) {
                System.out.println("status address must be loopback; using 127.0.0.1");
                a = InetAddress.getByName("127.0.0.1");
            }
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(a, port));
        } catch (IOException e) {
            System.out.println("status page not available: " + e.getMessage());
            return;
        }
        System.out.println("status page on http://" + host + ":" + port + "/ (local only)");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Socket s = ss.accept();
                        s.setSoTimeout(3000);
                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
                            String l;
                            while ((l = in.readLine()) != null && l.length() > 0) {
                                // request headers ignored: one page only
                            }
                            byte[] body = status().getBytes(UTF8);
                            OutputStream o = s.getOutputStream();
                            o.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(UTF8));
                            o.write(body);
                            o.flush();
                        } finally {
                            s.close();
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "status");
        t.setDaemon(true);
        t.start();
    }

    private synchronized String status() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("YCC internet relay (version ").append(VERSION).append("), up ").append(ago(now - startedMs))
                .append(", port ").append(mainPort).append(", listening on ").append(listening).append("\n");
        for (String p : mux.problems) sb.append("PROBLEM: ").append(p).append('\n');
        sb.append('\n');
        sb.append(String.format(Locale.US, "%-7s %-16s %-8s %-6s %-14s %s%n", "KIND", "NAME", "ID", "STATE", "LAST SEEN", "DETAIL"));
        for (User e : users) {
            long rx = e.remote == null ? 0 : e.remote.lastRx();
            long seen = e.seen();
            String st = e.state == 1 ? "UP" : e.state == 2 ? "DOWN" : "never";
            String when = seen == 0 ? "-" : ago(now - seen) + " ago";
            String detail = rx == 0 ? "" : e.remote.transport() + " " + e.remote.peer() + ", " + e.remote.channels() + " channel(s), "
                    + e.remote.framesIn + " frames";
            if (e.state == 1) detail = "up " + ago(now - e.upSince) + "; " + detail;
            if (e.state == 2) detail = "down " + ago(now - e.downSince) + "; " + detail;
            if (e.legacyPort > 0 && e.legacyPort != mainPort) detail += " (old port " + e.legacyPort + " also open)";
            sb.append(String.format(Locale.US, "%-7s %-16s %-8s %-6s %-14s %s%n", e.kind, e.name, idText(e.keyId), st, when, detail));
        }
        sb.append("\nHistory (newest last):\n");
        int skip = Math.max(0, history.size() - 200);
        int i = 0;
        for (String h : history) if (i++ >= skip) sb.append(h).append('\n');
        return sb.toString();
    }

    static String ago(long ms) {
        long s = ms / 1000;
        if (s < 120) return s + " s";
        if (s < 7200) return (s / 60) + " min " + (s % 60) + " s";
        if (s < 172800) return (s / 3600) + " h " + (s % 3600 / 60) + " min";
        return (s / 86400) + " d " + (s % 86400 / 3600) + " h";
    }
}
