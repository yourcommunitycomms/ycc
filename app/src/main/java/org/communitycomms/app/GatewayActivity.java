package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * OpenMANET with no internet: phones connected to a node that hands out a default gateway try to
 * reach the internet through it and may drop mobile data. This turns the DHCP default gateway off
 * (or back on) on the node the phone is connected to. It logs in to the node's web interface
 * (ubus over HTTP, as LuCI uses), edits /etc/config/dhcp with a note of who did it, and restarts
 * the DHCP server. The password is used for this one change and never saved.
 */
public final class GatewayActivity extends Activity {
    static final String MARK = "# YCC gateway:";
    private EditText ip, user, pass, section;
    private TextView out;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("OpenMANET internet gateway");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * d);
        box.setPadding(p, p, p, p);
        TextView intro = new TextView(this);
        intro.setText("When the MANET has no internet, turn the node's DHCP default gateway off so phones keep using "
                + "mobile data (where there is any) and stop warning about no internet on this WiFi. Turn it back on "
                + "when a node has internet again.\n\nYou need the node's login (usually root). The password is not saved.");
        box.addView(intro);
        ip = field(box, "Node address (the phone's WiFi gateway)", guessGateway(this), InputType.TYPE_CLASS_TEXT);
        user = field(box, "User name", "root", InputType.TYPE_CLASS_TEXT);
        pass = field(box, "Password (not saved)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        section = field(box, "DHCP section (advanced)", "lan", InputType.TYPE_CLASS_TEXT);
        box.addView(FilesActivity.row(button("Check", 0), button("Turn gateway OFF", 1), button("Turn gateway ON", 2)));
        out = new TextView(this);
        out.setTextIsSelectable(true);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(out);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    private EditText field(LinearLayout box, String label, String value, int type) {
        TextView t = new TextView(this);
        t.setText(label);
        box.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        box.addView(e);
        return e;
    }

    private Button button(String text, final int action) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(action);
            }
        });
        return b;
    }

    /** The WiFi's gateway is normally the node itself (10.41.x.x on OpenMANET). */
    @SuppressWarnings("deprecation")
    static String guessGateway(Context c) {
        try {
            WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo i = w == null ? null : w.getDhcpInfo();
            int g = i == null ? 0 : (i.gateway != 0 ? i.gateway : i.serverAddress);
            if (g != 0) return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (RuntimeException ignored) {
        }
        return "10.41.0.1";
    }

    private void say(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                out.append(s + "\n");
            }
        });
    }

    private void run(final int action) {
        final String host = ip.getText().toString().trim(), u = user.getText().toString().trim();
        final String pw = pass.getText().toString(), sec = section.getText().toString().trim();
        final String who = new Prefs(this).callsign();
        out.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Ubus ub = new Ubus(host);
                    say("Logging in to " + host + " as " + u + "…");
                    ub.login(u, pw);
                    String conf = ub.read("/etc/config/dhcp");
                    boolean off = gatewayOff(conf, sec);
                    say("Now: default gateway is " + (off ? "OFF (no internet route given out)" : "ON (handed out by DHCP)"));
                    if (action == 0) return;
                    boolean wantOff = action == 1;
                    if (wantOff == off) {
                        say("Nothing to change.");
                        return;
                    }
                    String note = MARK + " " + (wantOff ? "off" : "on") + " by " + (who.length() > 0 ? who : "?") + " (" + u + ") "
                            + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
                    String changed = wantOff ? turnOff(conf, sec, note) : turnOn(conf, note);
                    if (changed == null) {
                        say("Could not find the DHCP section '" + sec + "' in /etc/config/dhcp.");
                        return;
                    }
                    ub.write("/etc/config/dhcp.ycc-backup", conf);
                    ub.write("/etc/config/dhcp", changed);
                    say("Saved /etc/config/dhcp (the old one is in dhcp.ycc-backup). Restarting the DHCP server…");
                    ub.exec("/etc/init.d/dnsmasq", "restart");
                    say("Done. Now turn WiFi off and on again (on every phone) so they pick up the change.");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            reconnectPrompt();
                        }
                    });
                } catch (Exception e) {
                    say("Failed: " + e.getMessage());
                }
            }
        }, "gateway").start();
        pass.setText(""); // not kept, even on screen
    }

    private void reconnectPrompt() {
        new AlertDialog.Builder(this)
                .setTitle("Reconnect WiFi")
                .setMessage("Disconnect and reconnect this WiFi (and ask everyone else to) so the new setting is used.")
                .setPositiveButton("WiFi settings", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    // ---- /etc/config/dhcp ----

    private static boolean isRouterOption(String line) {
        String t = line.trim();
        return (t.startsWith("list dhcp_option") || t.startsWith("option dhcp_option"))
                && (t.contains("'3'") || t.contains("'3,") || t.contains("\"3\"") || t.contains("\"3,")
                || t.contains("router"));
    }

    /** Off = the section has an active "dhcp_option '3'" with no address (tell clients there is no router). */
    static boolean gatewayOff(String conf, String sec) {
        boolean in = false;
        for (String line : conf.split("\n")) {
            String t = line.trim();
            if (t.startsWith("config ")) in = isSection(t, sec);
            else if (in && !t.startsWith("#") && isRouterOption(t) && (t.endsWith("'3'") || t.endsWith("\"3\""))) return true;
        }
        return false;
    }

    private static boolean isSection(String header, String sec) {
        return header.startsWith("config dhcp") && (header.endsWith("'" + sec + "'") || header.endsWith(" " + sec)
                || header.endsWith("\"" + sec + "\""));
    }

    /** Comments out any router lines in the section and adds "list dhcp_option '3'" with the note above it. */
    static String turnOff(String conf, String sec, String note) {
        StringBuilder sb = new StringBuilder();
        boolean in = false, found = false;
        for (String line : conf.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("config ")) {
                in = isSection(t, sec);
                sb.append(line).append('\n');
                if (in) {
                    found = true;
                    sb.append('\t').append(note).append('\n');
                    sb.append("\tlist dhcp_option '3'\n");
                }
                continue;
            }
            if (in && !t.startsWith("#") && isRouterOption(t)) {
                sb.append("\t#YCC# ").append(t).append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        if (!found) return null;
        return trimEnd(sb);
    }

    /** Removes the lines added when turning off and puts back the router lines it commented out. */
    static String turnOn(String conf, String note) {
        StringBuilder sb = new StringBuilder();
        boolean afterMark = false;
        for (String line : conf.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith(MARK) && t.contains(" off by ")) {
                sb.append('\t').append(note).append('\n');
                afterMark = true;
                continue;
            }
            if (afterMark && (t.equals("list dhcp_option '3'") || t.equals("list dhcp_option \"3\""))) {
                afterMark = false;
                continue;
            }
            afterMark = false;
            if (t.startsWith("#YCC# ")) {
                sb.append('\t').append(t.substring(6)).append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        return trimEnd(sb);
    }

    private static String trimEnd(StringBuilder sb) {
        String s = sb.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---- ubus over HTTP (the node's web interface) ----

    static final class Ubus {
        private final String url;
        private String sid = "00000000000000000000000000000000";
        private int id;

        Ubus(String host) {
            url = (host.startsWith("http") ? host : "http://" + host) + "/ubus";
        }

        private JSONArray call(String object, String method, JSONObject args) throws Exception {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0").put("id", ++id).put("method", "call");
            req.put("params", new JSONArray().put(sid).put(object).put(method).put(args));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try {
                OutputStream os = c.getOutputStream();
                os.write(req.toString().getBytes("UTF-8"));
                os.close();
                if (c.getResponseCode() == 404) throw new Exception("the node has no ubus web access (install uhttpd-mod-ubus or use LuCI)");
                InputStream in = c.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                JSONObject r = new JSONObject(new String(b.toByteArray(), "UTF-8"));
                if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message", "error"));
                JSONArray res = r.getJSONArray("result");
                int code = res.getInt(0);
                if (code == 6) throw new Exception("permission denied (wrong password, or that user may not do this)");
                if (code != 0) throw new Exception(object + "." + method + " failed (code " + code + ")");
                return res;
            } finally {
                c.disconnect();
            }
        }

        void login(String user, String pw) throws Exception {
            JSONArray r = call("session", "login", new JSONObject().put("username", user).put("password", pw));
            sid = r.getJSONObject(1).getString("ubus_rpc_session");
        }

        String read(String path) throws Exception {
            return call("file", "read", new JSONObject().put("path", path)).getJSONObject(1).getString("data");
        }

        void write(String path, String data) throws Exception {
            call("file", "write", new JSONObject().put("path", path).put("data", data));
        }

        void exec(String cmd, String arg) throws Exception {
            call("file", "exec", new JSONObject().put("command", cmd).put("params", new JSONArray().put(arg)));
        }
    }
}
