package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.dryad.SheetStore;
import org.communitycomms.app.net.Tunnel;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings dialogs for tunnel comms. A remote phone gets the relay's internet address and port
 * and a DRYAD row as its key; the relay gets one line per remote (port, name, the same row).
 * Both take effect on the next join (changing them re-joins).
 */
public final class TunnelSetup {
    private TunnelSetup() {
    }

    public static final int FIRST_PORT = 9991;

    interface Picked {
        void onPicked(String rowText, String label);
    }

    static String describe(Context c) {
        String live = Tunnel.describe();
        if (live.length() > 0) return live;
        Prefs p = new Prefs(c);
        if (p.tunnelServer().length() > 0) return p.tunnelClientOn()
                ? "Remote: through " + p.tunnelServer() + " (connects when you join a channel)"
                : "Remote: " + p.tunnelServer() + " set up but off";
        int n = remoteLines(p).size();
        if (n > 0) return "Relay " + (p.tunnelRelayOn() ? "on" : "off") + ": " + n + " remote(s) set (opens when you join a channel)";
        return "Off";
    }

    private static List<String> remoteLines(Prefs p) {
        List<String> out = new ArrayList<String>();
        for (String l : p.tunnelRemotes().split("\n")) if (l.split("\\|", 3).length == 3) out.add(l);
        return out;
    }

    /** DRYAD sheet, then row: the row's letters are the key. */
    static void pickRow(final Activity a, final Picked done) {
        final List<SheetStore.Entry> sheets = new SheetStore(a).loadAll(false);
        if (sheets.isEmpty()) {
            Toast.makeText(a, "No DRYAD sheets on this phone. Make or receive one first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[sheets.size()];
        for (int i = 0; i < names.length; i++) names[i] = sheets.get(i).sheet.name;
        new AlertDialog.Builder(a)
                .setTitle("DRYAD sheet for the tunnel key")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final SheetStore.Entry e = sheets.get(w);
                        final String[] rows = new String[26];
                        for (int i = 0; i < 26; i++) rows[i] = "Row " + (char) ('A' + i);
                        new AlertDialog.Builder(a)
                                .setTitle("Row (each remote person gets their own)")
                                .setItems(rows, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int r) {
                                        char letter = (char) ('A' + r);
                                        done.onPicked(new String(e.sheet.row(letter)), e.sheet.name + " row " + letter);
                                    }
                                })
                                .show();
                    }
                })
                .show();
    }

    // ---- remote phone ----

    static void client(final Activity a, final Runnable changed) {
        final Prefs p = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        TextView help = new TextView(a);
        help.setText("Use this only on a phone away from the group's WiFi. Everything this phone sends and hears "
                + "goes through the relay over the internet instead.\n\nRelay internet address and the port given to you:");
        box.addView(help);
        final EditText server = new EditText(a);
        server.setHint("e.g. 203.0.113.7:9991");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(p.tunnelServer());
        box.addView(server);
        final String[] key = {p.tunnelKey()};
        final Button row = new Button(a);
        row.setAllCaps(false);
        row.setText(key[0].length() == 0 ? "Choose the DRYAD row (key)…" : "Key set (tap to change)");
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                pickRow(a, new Picked() {
                    @Override
                    public void onPicked(String rowText, String label) {
                        key[0] = rowText;
                        row.setText("Key: " + label);
                    }
                });
            }
        });
        box.addView(row);
        final CheckBox use = new CheckBox(a);
        use.setText("Use the relay now (otherwise it stays set up but off; with nobody heard you are asked)");
        use.setChecked(p.tunnelClientOn());
        box.addView(use);
        new AlertDialog.Builder(a)
                .setTitle("Tunnel comms (remote phone)")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String s = server.getText().toString().trim();
                        int c = s.lastIndexOf(':');
                        boolean portOk = false;
                        try {
                            int port = Integer.parseInt(s.substring(c + 1));
                            portOk = c > 0 && port > 0 && port < 65536;
                        } catch (RuntimeException ignored) {
                        }
                        if (!portOk || key[0].length() == 0) {
                            Toast.makeText(a, "Needs address:port and a DRYAD row", Toast.LENGTH_LONG).show();
                            return;
                        }
                        p.setTunnel(s, key[0]);
                        p.setTunnelClientOn(use.isChecked());
                        if (use.isChecked()) p.setTunnelRelayOn(false); // one phone is either a remote or the relay
                        Toast.makeText(a, use.isChecked() ? "Saved. It connects when you are on a channel."
                                : "Saved, but off: tick \"Use the relay now\" to connect through it.", Toast.LENGTH_LONG).show();
                        changed.run();
                    }
                })
                .setNeutralButton("Turn off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        p.setTunnel("", "");
                        changed.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- relay (commander tablet) ----

    static void relay(final Activity a, final Runnable changed) {
        final Prefs p = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final CheckBox on = new CheckBox(a);
        on.setText("Relay on (while this phone is on a channel)");
        on.setChecked(p.tunnelRelayOn());
        box.addView(on);
        TextView list = new TextView(a);
        StringBuilder sb = new StringBuilder();
        List<String> lines = remoteLines(p);
        if (lines.isEmpty()) sb.append("No remote people yet.");
        for (String l : lines) {
            String[] f = l.split("\\|", 3);
            sb.append("• ").append(f[1]).append(": port ").append(f[0]).append(" (UDP and TCP)\n");
        }
        sb.append("\nForward each port, UDP and TCP, from the internet router to this phone at ")
                .append(localAddresses()).append(". Give each person the router's internet address, their port and their DRYAD row.");
        list.setText(sb);
        box.addView(list);
        new AlertDialog.Builder(a)
                .setTitle("Tunnel relay")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        p.setTunnelRelayOn(on.isChecked());
                        if (on.isChecked()) p.setTunnel("", "");
                        changed.run();
                    }
                })
                .setNeutralButton("Add / remove…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        p.setTunnelRelayOn(on.isChecked());
                        editRemotes(a, changed);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void editRemotes(final Activity a, final Runnable changed) {
        final Prefs p = new Prefs(a);
        final List<String> lines = remoteLines(p);
        String[] items = new String[lines.size() + 1];
        items[0] = "+ Add a remote person";
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\\|", 3);
            items[i + 1] = "Remove " + f[1] + " (port " + f[0] + ")";
        }
        new AlertDialog.Builder(a)
                .setTitle("Remote people")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (w == 0) {
                            addRemote(a, lines, changed);
                        } else {
                            lines.remove(w - 1);
                            save(p, lines);
                            changed.run();
                            relay(a, changed);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void addRemote(final Activity a, final List<String> lines, final Runnable changed) {
        final Prefs p = new Prefs(a);
        int next = FIRST_PORT;
        for (String l : lines) {
            try {
                next = Math.max(next, Integer.parseInt(l.split("\\|")[0].trim()) + 1);
            } catch (RuntimeException ignored) {
            }
        }
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final EditText name = new EditText(a);
        name.setHint("Who (e.g. callsign)");
        name.setSingleLine(true);
        box.addView(name);
        final EditText port = new EditText(a);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(next));
        box.addView(port);
        final String[] key = {""};
        final String[] keyLabel = {""};
        final Button row = new Button(a);
        row.setAllCaps(false);
        row.setText("Choose their DRYAD row (key)…");
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                pickRow(a, new Picked() {
                    @Override
                    public void onPicked(String rowText, String label) {
                        key[0] = rowText;
                        keyLabel[0] = label;
                        row.setText("Key: " + label);
                    }
                });
            }
        });
        box.addView(row);
        new AlertDialog.Builder(a)
                .setTitle("Add a remote person")
                .setView(box)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int pt;
                        try {
                            pt = Integer.parseInt(port.getText().toString().trim());
                        } catch (RuntimeException e) {
                            pt = 0;
                        }
                        String n = name.getText().toString().trim().replace("|", " ").replace("\n", " ");
                        if (pt <= 1024 || pt > 65535 || key[0].length() == 0) {
                            Toast.makeText(a, "Needs a port above 1024 and a DRYAD row", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (pt == p.port()) {
                            Toast.makeText(a, "That is the radio's own port: pick another", Toast.LENGTH_LONG).show();
                            return;
                        }
                        lines.add(pt + "|" + (n.length() == 0 ? "Remote" : n) + " (" + keyLabel[0] + ")|" + key[0]);
                        save(p, lines);
                        changed.run();
                        relay(a, changed);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void save(Prefs p, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        p.setTunnelRemotes(sb.toString());
    }

    // ---- internet relay (VPS) ----

    static String describeVps(Context c) {
        Prefs p = new Prefs(c);
        Tunnel.Client v = Tunnel.vps;
        if (v != null) {
            String s = v.describe();
            if (v.everUp() && !v.linkUp() && !v.standby) s = RadioService.RELAY_DOWN + " · " + s;
            return s;
        }
        if (p.vpsServer().length() == 0) return "Internet relay: not set up";
        String role = "on".equals(p.manetRole()) ? "gateway (on the MANET)" : "off".equals(p.manetRole()) ? "remote (off the MANET)"
                : "auto (" + (RadioService.onManet(p) ? "on the MANET now: gateway" : "off the MANET now: remote") + ")";
        if ("direct".equals(p.relayMode())) return "Internet relay: set up but Relay mode is Direct";
        return "Internet relay (" + p.relayMode() + "): " + role + ", connects when you join a channel";
    }

    static void vps(final Activity a, final Runnable changed) {
        final Prefs p = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        sv.addView(box);
        TextView help = new TextView(a);
        help.setText("An always-on relay server on the internet links MANETs and people away from them. A gateway phone on each "
                + "MANET forwards only the channels marked \"share to internet\" (globe icon on the Channels page). Everything "
                + "stays encrypted end to end: the relay never has channel names.\n\nRelay mode:");
        box.addView(help);
        final android.widget.RadioGroup mode = new android.widget.RadioGroup(a);
        final String[] modes = {"direct", "vps", "both"};
        String[] modeNames = {"Direct (the tunnel above only)", "Internet relay (VPS) only", "Both (sent both ways, repeats dropped)"};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setId(1000 + i);
            rb.setText(modeNames[i]);
            mode.addView(rb);
        }
        int mi = java.util.Arrays.asList(modes).indexOf(p.relayMode());
        mode.check(1000 + Math.max(0, mi));
        box.addView(mode);
        TextView l1 = new TextView(a);
        l1.setText("\nInternet relay address and the port given to you (or your site):");
        box.addView(l1);
        final EditText server = new EditText(a);
        server.setHint("address:port");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(p.vpsServer());
        box.addView(server);
        final String[] key = {p.vpsKey()};
        final Button row = new Button(a);
        row.setAllCaps(false);
        row.setText(key[0].length() == 0 ? "Choose the DRYAD row (key)…" : "Key set (tap to change)");
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                pickRow(a, new Picked() {
                    @Override
                    public void onPicked(String rowText, String label) {
                        key[0] = rowText;
                        row.setText("Key: " + label);
                    }
                });
            }
        });
        box.addView(row);
        TextView l2 = new TextView(a);
        l2.setText("\nThis phone:");
        box.addView(l2);
        final android.widget.RadioGroup role = new android.widget.RadioGroup(a);
        final String[] roles = {"auto", "on", "off"};
        String[] roleNames = {"Auto-detect (by this phone's WiFi address)", "I am on the MANET (gateway: bridges shared channels)",
                "I am off the MANET (remote: connects straight to the relay)"};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setId(2000 + i);
            rb.setText(roleNames[i]);
            role.addView(rb);
        }
        role.check(2000 + Math.max(0, java.util.Arrays.asList(roles).indexOf(p.manetRole())));
        box.addView(role);
        TextView l3 = new TextView(a);
        l3.setText("MANET address range for auto-detect (OpenMANET: " + Prefs.DEFAULT_MANET_RANGE + "):");
        box.addView(l3);
        final EditText range = new EditText(a);
        range.setSingleLine(true);
        range.setText(p.manetRange());
        box.addView(range);
        TextView now = new TextView(a);
        now.setTextSize(12);
        StringBuilder ips = new StringBuilder();
        for (java.net.Inet4Address ad : RadioService.localIpv4()) ips.append(ips.length() > 0 ? ", " : "").append(ad.getHostAddress());
        now.setText("This phone now: " + (ips.length() == 0 ? "no network" : ips) + ". Only one gateway per MANET is active; "
                + "others stand by and take over if it goes quiet.");
        box.addView(now);
        final CheckBox tone = new CheckBox(a);
        tone.setText("Gateway: tone with the \"link down\" notification (silent otherwise)");
        tone.setChecked(p.linkTone());
        box.addView(tone);
        final CheckBox speak = new CheckBox(a);
        speak.setText("Gateway: say \"Link down\" / \"Link restored\" (text as voice)");
        speak.setChecked(p.linkSpeak());
        box.addView(speak);
        new AlertDialog.Builder(a)
                .setTitle("Internet relay")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String m = modes[Math.max(0, mode.getCheckedRadioButtonId() - 1000)];
                        String s = server.getText().toString().trim();
                        if (!"direct".equals(m)) {
                            int c = s.lastIndexOf(':');
                            boolean portOk = false;
                            try {
                                int port = Integer.parseInt(s.substring(c + 1));
                                portOk = c > 0 && port > 0 && port < 65536;
                            } catch (RuntimeException ignored) {
                            }
                            if (!portOk || key[0].length() == 0) {
                                Toast.makeText(a, "Needs address:port and a DRYAD row", Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                        String r = range.getText().toString().trim();
                        if (r.length() > 0 && !r.matches("\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?")) {
                            Toast.makeText(a, "Range like " + Prefs.DEFAULT_MANET_RANGE, Toast.LENGTH_LONG).show();
                            return;
                        }
                        p.setVps(m, s, key[0]);
                        p.setManet(roles[Math.max(0, role.getCheckedRadioButtonId() - 2000)], r);
                        p.setLinkAlerts(tone.isChecked(), speak.isChecked());
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.applyVpsNow();
                        changed.run();
                    }
                })
                .setNeutralButton("Sites…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        sites(a);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Site status: every site and remote the internet relay knows, green / amber / red / grey. */
    static void sites(final Activity a) {
        final Tunnel.Client v = Tunnel.vps;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (v == null) {
            sb.append("Not connected to an internet relay. Set it up here, then join a channel.");
        } else {
            sb.append(v.describe()).append("\n\n");
            long now = System.currentTimeMillis();
            if (v.sites.isEmpty()) sb.append("No site list from the relay yet.");
            boolean stale = v.sitesMs > 0 && now - v.sitesMs > Tunnel.DOWN_MS;
            for (Tunnel.Site s : v.sites) {
                long ago = s.lastHeardMs == 0 ? -1 : (now - s.lastHeardMs) / 1000;
                int colour;
                String st;
                if (stale) {
                    colour = 0xff9e9e9e;
                    st = "unknown (relay not heard)";
                } else if (s.state == 2) {
                    colour = 0xffe53935;
                    st = "offline";
                } else if (s.state == 1 && ago >= 12) {
                    colour = 0xffffb300;
                    st = "late";
                } else if (s.state == 1) {
                    colour = 0xff43a047;
                    st = "online";
                } else {
                    colour = 0xff9e9e9e;
                    st = "never seen";
                }
                int start = sb.length();
                sb.append("● ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(colour), start, start + 1, 0);
                sb.append(s.name).append(s.kind == 1 ? " (site)" : " (remote)").append(": ").append(st);
                if (ago >= 0) sb.append(", last heard ").append(RadioService.outageWords(ago * 1000)).append(" ago");
                sb.append('\n');
            }
        }
        TextView tv = new TextView(a);
        float d = a.getResources().getDisplayMetrics().density;
        tv.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        tv.setTextSize(15);
        tv.setText(sb);
        new AlertDialog.Builder(a).setTitle("Internet relay sites").setView(tv)
                .setPositiveButton("Refresh", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        sites(a);
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    /** This phone's addresses on the local network, for the router's port forward. */
    static String localAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress ad : java.util.Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof java.net.Inet4Address) {
                        if (sb.length() > 0) sb.append(" or ");
                        sb.append(ad.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "(no network)" : sb.toString();
    }
}
