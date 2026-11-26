package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesh radio: what this phone's OpenMANET node sees, read-only. Its neighbours (signal, throughput, last seen), the
 * mesh layout (hops and next hop to each node), the node's load and uptime, and each contact's link (YCC's own loss
 * and jitter, plus the node's view of the contact's node when it is known). Needs only OpenMANET turned on, not Mesh
 * testing. Without a login it shows YCC's own figures and a "Log in for radio data" button.
 */
public final class MeshRadioActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float d;
    private TextView head, neighbours, topology, contacts;
    private Button loginB;

    public static void open(Context c) {
        c.startActivity(new Intent(c, MeshRadioActivity.class));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("OpenMANET Mesh");
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        root.setPadding(pad, pad, pad, pad);
        head = text(root, "", 15);
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false);
        loginB = button(r, "Log in for radio data", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!MeshRadio.usingShared() && !MeshRadio.hasAdmin()) {
                    MeshRadio.askLogin(MeshRadioActivity.this, false, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } else if (MeshRadio.usingShared()) {
                    NodeShare.confirmForget(MeshRadioActivity.this, null);
                } else if (MeshRadio.hasLogin()) {
                    new AlertDialog.Builder(MeshRadioActivity.this).setTitle("Log out")
                            .setMessage("Stop reading the node and forget the login (also the Mesh controller's remembered one)?")
                            .setPositiveButton("Log out", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface di, int w) {
                                    MeshRadio.logout();
                                    refresh();
                                }
                            }).setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    MeshRadio.askLogin(MeshRadioActivity.this, false, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
            }
        });
        button(r, "My node…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickNode();
            }
        });
        button(r, "Settings…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings();
            }
        });
        root.addView(r);
        LinearLayout r2 = new LinearLayout(this);
        button(r2, "Read-only login (type / QR)…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RoLogin.enterDialog(MeshRadioActivity.this, null);
            }
        });
        button(r2, "HaLow on / off…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HalowToggle.dialog(MeshRadioActivity.this);
            }
        });
        root.addView(r2);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        heading(body, "Neighbours (heard directly by my node)");
        neighbours = text(body, "", 14);
        heading(body, "Mesh layout (from my node)");
        topology = text(body, "", 14);
        heading(body, "Contacts on this channel");
        contacts = text(body, "", 14);
        TextView foot = text(body, "Read-only (only HaLow on / off changes anything, and only when you press it). Read every 10 s while this page is open; the title bar "
                + "icon reads it every " + MeshRadio.bgSec() + " s (not in Ultra low, Away or with the screen off). A busy node is read "
                + "every 30 s. Signal: -60 dBm or better is strong, below -85 is weak.", 12);
        foot.setTextColor(0xffb0bec5);
        setContentView(root);
        TvFocus.watch(getWindow());
        String qr = getIntent().getStringExtra(NodeShare.EXTRA_QR);
        if (qr != null && b == null) NodeShare.fromQr(this, qr);
        String roQr = getIntent().getStringExtra(RoLogin.EXTRA_QR);
        if (roQr != null && b == null) RoLogin.fromQr(this, roQr);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MeshRadio.viewOpen = true;
        MeshRadio.ensure(this);
        MeshNode.LOCAL.nudge();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MeshRadio.viewOpen = false;
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            NodeShare.showPending(MeshRadioActivity.this);
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private TextView text(LinearLayout p, String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, (int) (2 * d), 0, (int) (2 * d));
        p.addView(t);
        return t;
    }

    private void heading(LinearLayout p, String s) {
        TextView t = text(p, s, 16);
        t.setTextColor(0xfff0c75e);
        t.setPadding(0, (int) (12 * d), 0, (int) (2 * d));
    }

    private Button button(LinearLayout r, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setOnClickListener(l);
        r.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return b;
    }

    static String bars(int dbm) {
        if (dbm == Integer.MIN_VALUE) return "";
        int n = dbm >= -60 ? 4 : dbm >= -70 ? 3 : dbm >= -80 ? 2 : dbm >= -90 ? 1 : 0;
        return "▂▄▆█".substring(0, n) + "    ".substring(n); // lit bars only (the empty-bar glyph is missing on some phones)
    }

    private static String ago(long ms) {
        if (ms < 0) return "";
        long s = ms / 1000;
        return s < 60 ? s + " s ago" : s < 3600 ? s / 60 + " min ago" : s / 3600 + " h ago";
    }

    private void refresh() {
        MeshNode.Poller p = MeshNode.LOCAL;
        boolean login = MeshRadio.hasLogin();
        loginB.setText(MeshRadio.usingShared() ? "Forget shared" : MeshRadio.hasAdmin() ? "Log out" : "Admin login");
        StringBuilder h = new StringBuilder();
        String mine = MeshRadio.myNode();
        h.append("My node: ").append(mine.length() > 0 ? mine : "not known").append(" (").append(MeshRadio.howFound()).append(")");
        if (!MeshRadio.available(this)) h.append("\nTurn OpenMANET on in Settings to use this page.");
        else if (!login) h.append("\nNot logged in: showing YCC's own figures only. Log in to read the node's radio data.");
        else if (MeshRadio.usingShared()) h.append("\nUsing the node login ●●●●● shared by ").append(NodeShare.from()).append(" (read only).");
        else {
            if (p.host() != null) h.append("\nReading ").append(p.selfName().length() > 0 ? p.selfName() + " at " : "").append(p.host());
            if (p.lastOkMs() > 0) h.append(", read ").append(ago(System.currentTimeMillis() - p.lastOkMs()));
            if (p.lastOkMs() > 0 && p.via().length() > 0) h.append(" with the ").append(p.via());
            if (p.busy()) h.append("\nNode busy or slow: reading it every 30 s");
            if (p.lastError().length() > 0) h.append("\n").append(p.lastError());
            if (p.load().length() > 0 || p.uptime().length() > 0)
                h.append("\nLoad: ").append(p.load().length() > 0 ? p.load() : "?").append(" · up ").append(p.uptime().length() > 0 ? p.uptime() : "?");
            if (mine.length() > 0 && p.selfName().length() > 0 && !mine.equalsIgnoreCase(p.selfName()))
                h.append("\n(Reading ").append(p.selfName()).append(" until ").append(mine).append("'s address is found)");
        }
        h.append("\n").append(lansText());
        head.setText(h.toString());
        boolean fresh = login && p.fresh(60000);
        StringBuilder nb = new StringBuilder(), tp = new StringBuilder();
        if (fresh) {
            for (MeshNode.Info i : p.all()) {
                if (i.neighbour) {
                    nb.append(nb.length() > 0 ? "\n" : "").append(i.name).append("  ")
                            .append(i.signalDbm != Integer.MIN_VALUE ? bars(i.signalDbm) + " " + i.signalDbm + " dBm" : "signal ?")
                            .append(i.kbps >= 0 ? " · " + MeshRadio.kbit(i.kbps) : "")
                            .append(i.txKbps >= 0 || i.rxKbps >= 0 ? " · link out " + (i.txKbps < 0 ? "?" : MeshRadio.kbit(i.txKbps))
                                    + " / in " + (i.rxKbps < 0 ? "?" : MeshRadio.kbit(i.rxKbps)) : "")
                            .append(i.lastSeenMs >= 0 ? " · seen " + ago(i.lastSeenMs) : "");
                }
                if (i.hops != 0) {
                    tp.append(tp.length() > 0 ? "\n" : "").append(i.name).append(": ")
                            .append(i.unreachable ? "unreachable now" : i.hops < 0 ? "hops ?" : i.hops == 1 ? "1 hop (direct)" : i.hops + " hops")
                            .append(i.nextHop.length() > 0 && i.hops != 1 ? ", via " + i.nextHop : "");
                }
            }
            if (nb.length() == 0) nb.append("None heard.");
            if (tp.length() == 0) tp.append("No other nodes in the mesh.");
        } else {
            String why = !login ? "Log in to see them." : "Waiting for the node…";
            nb.append(why);
            tp.append(why);
        }
        neighbours.setText(nb.toString());
        topology.setText(tp.toString());
        contacts.setText(contactsText(fresh));
    }

    /** Each heard contact: YCC's own loss and jitter, and the node's view of their node when it is known. */
    private String contactsText(boolean fresh) {
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        if (e == null) return "Not on a YCC channel.";
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        List<String> done = new ArrayList<String>();
        for (RadioEngine.Station s : e.stations()) {
            if (s.name.startsWith("via ") || done.contains(s.name)) continue;
            done.add(s.name);
            MeshTest.Link l = MeshTest.link(e, s.id);
            double loss = l.loss(now);
            int jit = l.jitterMs(now);
            int g = MeshTest.grade(loss, jit);
            b.append(b.length() > 0 ? "\n" : "").append(s.name).append(": ").append(MeshTest.gradeWord(g))
                    .append(loss >= 0 ? String.format(Locale.US, ", loss %.0f%%", loss * 100) : "")
                    .append(jit >= 0 ? ", jitter " + jit + " ms" : "")
                    .append(l.lastViaRelay ? ", via a relay" : netOf(l.lastFrom).length() > 0 ? ", on " + netOf(l.lastFrom) : "");
            String node = nodeOf(s.name);
            if (node != null) {
                b.append("\n    node ").append(node);
                MeshNode.Info i = fresh ? MeshNode.LOCAL.lookup(node) : null;
                if (i != null) {
                    b.append(": ").append(i.hops == 0 ? "same node as me" : i.hops == 1 ? "my neighbour" : i.hops < 0 ? "hops ?" : i.hops + " hops");
                    if (i.neighbour && i.signalDbm != Integer.MIN_VALUE) b.append(", ").append(i.signalDbm).append(" dBm");
                    if (i.neighbour && i.kbps >= 0) b.append(", ").append(MeshRadio.kbit(i.kbps));
                }
            }
        }
        return b.length() == 0 ? "Nobody heard yet." : b.toString();
    }

    /** This phone's local networks: interface, address, and which is on the OpenMANET network. */
    private String lansText() {
        StringBuilder b = new StringBuilder("This phone's networks: ");
        String range = new Prefs(this).manetRange();
        int n = 0;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof java.net.Inet4Address)) continue;
                    byte[] ab = ia.getAddress().getAddress();
                    if ((ab[0] & 0xff) == 192 && ab[1] == 0 && ab[2] == 0 && (ab[3] & 0xff) < 8) continue; // mobile data's IPv4 helper (CLAT), not a LAN
                    java.util.List<java.net.Inet4Address> one = new java.util.ArrayList<java.net.Inet4Address>();
                    one.add((java.net.Inet4Address) ia.getAddress());
                    boolean mesh = RadioService.inRange(one, range);
                    String nm = ni.getName().toLowerCase(java.util.Locale.US);
                    String kind = nm.startsWith("rmnet") || nm.startsWith("ccmni") || nm.startsWith("v4-") || nm.startsWith("clat") || nm.startsWith("pdp")
                            ? "Mobile" : MainActivity.kindOf(ni.getName());
                    b.append(n++ > 0 ? ", " : "").append(kind).append(" ").append(ia.getAddress().getHostAddress())
                            .append("/").append(ia.getNetworkPrefixLength()).append(mesh ? " (OpenMANET)" : "");
                }
            }
        } catch (Exception ignored) {
        }
        return n == 0 ? "No network." : b.toString();
    }

    /** Which of this phone's networks an address is on ("WiFi", "Ethernet"...), or "". */
    private static String netOf(String ip) {
        if (ip == null) return "";
        try {
            byte[] f = java.net.InetAddress.getByName(ip).getAddress();
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof java.net.Inet4Address) || f.length != 4) continue;
                    byte[] a = ia.getAddress().getAddress();
                    int bits = ia.getNetworkPrefixLength(), i = 0;
                    boolean same = true;
                    for (; bits >= 8 && i < 4; bits -= 8, i++) if (a[i] != f[i]) same = false;
                    if (same && bits > 0 && i < 4) {
                        int m = (0xff << (8 - bits)) & 0xff;
                        if ((a[i] & m) != (f[i] & m)) same = false;
                    }
                    if (same) return MainActivity.kindOf(ni.getName());
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** A contact's node, when known: from their Mapping walk probes (Map mesh) on this phone. */
    private static String nodeOf(String name) {
        String node = null;
        long t = 0;
        for (MeshMap.Point p : MeshMap.all()) {
            if (p.node != null && p.node.length() > 0 && name.equals(p.name) && p.t > t) {
                t = p.t;
                node = p.node;
            }
        }
        return node;
    }

    private void pickNode() {
        final List<String> items = new ArrayList<String>();
        items.add("Auto (gateway / DHCP server, then the WiFi name)");
        for (String n : MeshNode.LOCAL.names()) if (!items.contains(n)) items.add(n);
        for (String n : MeshWalk.nodeList()) if (!items.contains(n)) items.add(n);
        new AlertDialog.Builder(this).setTitle("My mesh node (the node this phone's WiFi is on)")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MeshTest.sp().edit().putString("walk_node", w == 0 ? "" : items.get(w)).commit();
                        MeshNode.LOCAL.nudge();
                        refresh();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final CheckBox icon = new CheckBox(this);
        icon.setText("Signal icon in the title bar (taps open this page)");
        icon.setChecked(MeshRadio.iconOn());
        box.addView(icon);
        TextView l1 = new TextView(this);
        l1.setText("Icon reads the node every (seconds, 30 to 600)");
        box.addView(l1);
        final EditText every = new EditText(this);
        every.setSingleLine(true);
        every.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        every.setText(String.valueOf(MeshRadio.bgSec()));
        box.addView(every);
        TextView l2 = new TextView(this);
        l2.setText("My node's address (blank = find it: WiFi name, then the mesh's node list or NAME.local)");
        box.addView(l2);
        final EditText host = new EditText(this);
        host.setSingleLine(true);
        host.setHint("e.g. 10.41.0.5");
        host.setText(MeshTest.sp().getString("local_host", ""));
        box.addView(host);
        if (NodeShare.held()) {
            TextView sh = new TextView(this);
            sh.setText(NodeShare.describe());
            box.addView(sh);
            Button fg = new Button(this);
            fg.setAllCaps(false);
            fg.setText("Forget the shared login");
            fg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NodeShare.confirmForget(MeshRadioActivity.this, null);
                }
            });
            box.addView(fg);
        }
        new AlertDialog.Builder(this).setTitle("OpenMANET Mesh settings").setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int s = 60;
                        try {
                            s = Integer.parseInt(every.getText().toString().trim());
                        } catch (NumberFormatException ignored) {
                        }
                        String h = host.getText().toString().trim();
                        String old = MeshTest.sp().getString("local_host", "");
                        MeshTest.sp().edit().putBoolean("radio_icon", icon.isChecked()).putInt("radio_bg", Math.max(30, Math.min(600, s)))
                                .putString("local_host", h).commit();
                        if (!h.equals(old)) {
                            if (h.length() > 0) MeshNode.LOCAL.retarget(h);
                            else {
                                MeshNode.LOCAL.stop();
                                MeshRadio.ensure(MeshRadioActivity.this);
                            }
                        }
                        refresh();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
