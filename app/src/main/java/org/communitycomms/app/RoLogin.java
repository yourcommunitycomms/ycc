package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.UbusApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The read-only "ycc-ro" login for team phones: ONE user and ONE password, the same on every node, chosen by the admin.
 * The password is Morse friendly (A-Z and 0-9 only, one case, 8 to 32 characters) so it can be read out over another
 * radio and typed in. The Mesh controller installs it on each node with the root login over OpenWrt's /ubus: an rpcd
 * login (MD5-crypt password) allowed only the ACL group "ycc-ro" (mesh11s get_topology, iwinfo, system info, and
 * running YCC's small read-only script that prints the batman tables). Phones use it first for Mesh radio and walk
 * polling. It can be typed in, read from a plain QR ("YCCRO1:"), or set by the setup QR / server Phone setup.
 */
public final class RoLogin {
    public static final String QR_PREFIX = "YCCRO1:";
    public static final String EXTRA_QR = "roLoginQr";
    public static final String DEFAULT_USER = "ycc-ro";
    static final String ACL_PATH = "/usr/share/rpcd/acl.d/ycc-ro.json";
    static final String SCRIPT_PATH = "/usr/libexec/ycc-ro-radio";
    private static final String[] WORDS = {"MAPLE", "RIVER", "STONE", "EAGLE", "CEDAR", "OTTER", "TIGER", "LEMON", "OCEAN", "HONEY",
            "PILOT", "ROBIN", "CLOUD", "AMBER", "TULIP", "BISON", "COMET", "DELTA", "FALCON", "GRAPE", "HAZEL", "IVORY",
            "JADE", "KOALA", "LUNAR", "MANGO", "NORTH", "OLIVE", "PEARL", "QUAIL", "RAVEN", "SOLAR", "TORCH", "URBAN",
            "VELVET", "WALNUT", "YACHT", "ZEBRA", "BADGER", "CANYON"};

    private RoLogin() {
    }

    static SharedPreferences sp() {
        return MeshTest.app.getSharedPreferences("rologin", Context.MODE_PRIVATE);
    }

    public static boolean held() {
        return MeshTest.app != null && sp().getString("p", "").length() > 0 && sp().getBoolean("use", true);
    }

    public static String user() {
        return sp().getString("u", DEFAULT_USER);
    }

    public static String pass() {
        return sp().getString("p", "");
    }

    public static String from() {
        return sp().getString("from", "");
    }

    /** Upper case, letters and digits only. */
    public static String norm(String p) {
        return p == null ? "" : p.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    public static boolean valid(String p) {
        return p != null && p.matches("[A-Z0-9]{8,32}");
    }

    public static String suggest() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        String a = WORDS[r.nextInt(WORDS.length)], b;
        do {
            b = WORDS[r.nextInt(WORDS.length)];
        } while (b.equals(a));
        return a + b + String.format(Locale.US, "%02d", r.nextInt(100));
    }

    public static void store(String user, String pass, String from, JSONArray nodes) {
        String u = user == null || user.trim().length() == 0 ? DEFAULT_USER : user.trim();
        sp().edit().putString("u", u).putString("p", norm(pass)).putString("from", from == null ? "" : from)
                .putLong("at", System.currentTimeMillis()).putBoolean("use", true).commit();
        if (nodes != null && nodes.length() > 0) {
            SharedPreferences m = MeshRadio.ctl();
            if ("[]".equals(m.getString("nodes", "[]"))) m.edit().putString("nodes", nodes.toString()).commit();
        }
        NodeShare.log("Read-only node login set" + (from == null || from.length() == 0 ? "" : " (" + from + ")"));
        MeshNode.LOCAL.stop();
        MeshRadio.ensure(MeshTest.app);
    }

    /** From a setup code or the APK stamp: {"u", "p", "use"}. Prefs only (works before the app is fully up). */
    public static void applySetup(Context c, JSONObject ro) {
        if (ro == null) return;
        String p = norm(ro.optString("p"));
        if (!valid(p)) return;
        String u = ro.optString("u", DEFAULT_USER).trim();
        c.getApplicationContext().getSharedPreferences("rologin", Context.MODE_PRIVATE).edit()
                .putString("u", u.length() == 0 ? DEFAULT_USER : u).putString("p", p).putString("from", "setup")
                .putBoolean("use", ro.optInt("use", 1) == 1).putLong("at", System.currentTimeMillis()).commit();
        if (MeshTest.app != null) {
            MeshNode.LOCAL.stop();
            MeshRadio.ensure(MeshTest.app);
        }
    }

    public static void forget() {
        sp().edit().remove("p").remove("from").commit();
        NodeShare.log("Read-only node login forgotten");
        MeshNode.LOCAL.stop();
        MeshRadio.ensure(MeshTest.app);
    }

    // ---- QR (plain: it is read-only) ----

    public static String qr(String user, String pass, String nodesJson) {
        try {
            JSONObject o = new JSONObject().put("u", user).put("p", pass);
            if (nodesJson != null) {
                JSONArray in = new JSONArray(nodesJson), out = new JSONArray();
                for (int i = 0; i < in.length() && out.toString().length() < 400; i++) {
                    JSONObject n = in.getJSONObject(i);
                    out.put(new JSONObject().put("name", n.optString("name")).put("ip", n.optString("ip")));
                }
                if (out.length() > 0) o.put("n", out);
            }
            return QR_PREFIX + Base64.encodeToString(o.toString().getBytes("UTF-8"), Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            return null;
        }
    }

    public static void fromQr(final Activity a, String text) {
        try {
            final JSONObject o = new JSONObject(new String(Base64.decode(text.substring(QR_PREFIX.length()), Base64.NO_WRAP | Base64.URL_SAFE), "UTF-8"));
            final String p = norm(o.optString("p"));
            if (!valid(p)) throw new Exception("not a valid password");
            new AlertDialog.Builder(a).setTitle("Read-only node login")
                    .setMessage("Use the read-only login " + o.optString("u", DEFAULT_USER) + " / " + p + " to read your mesh node's radio data?")
                    .setPositiveButton("Use it", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            store(o.optString("u", DEFAULT_USER), p, "QR", o.optJSONArray("n"));
                            Toast.makeText(a, "Read-only login saved", Toast.LENGTH_SHORT).show();
                        }
                    }).setNegativeButton(android.R.string.cancel, null).show();
        } catch (Exception e) {
            Toast.makeText(a, "Not a read-only login QR: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void showQr(Activity a, String payload) {
        try {
            float d = a.getResources().getDisplayMetrics().density;
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(QrShowActivity.render(payload));
            iv.setAdjustViewBounds(true);
            iv.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), 0);
            new AlertDialog.Builder(a).setTitle("Read-only login QR").setView(iv)
                    .setMessage("Scan with YCC (Feature menus > Scan a QR code, or OpenMANET Mesh > Read-only login > Read QR).")
                    .setPositiveButton("Close", null).show();
        } catch (Exception e) {
            Toast.makeText(a, "QR failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void scan(Activity a) {
        a.startActivity(new Intent(a, QrScanActivity.class));
    }

    // ---- the phone's own entry (Mesh radio, walk) ----

    /** "Enter read-only password": user prefilled, password typed in after hearing it. */
    public static void enterDialog(final Activity a, final Runnable after) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView t = new TextView(a);
        t.setText("The read-only login your team uses for the mesh nodes (letters and numbers only). It can only read radio "
                + "data. " + (held() ? "Now: " + user() + " / " + pass() + (from().length() > 0 ? " (" + from() + ")" : "") : "None set yet."));
        box.addView(t);
        final EditText u = new EditText(a);
        u.setSingleLine(true);
        u.setText(held() ? user() : DEFAULT_USER);
        box.addView(u);
        final EditText p = new EditText(a);
        p.setSingleLine(true);
        p.setHint("Read-only password");
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        p.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(32)});
        box.addView(p);
        LinearLayout row = new LinearLayout(a);
        Button read = new Button(a);
        read.setAllCaps(false);
        read.setText("Read QR");
        read.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan(a);
            }
        });
        row.addView(read, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (held()) {
            Button show = new Button(a);
            show.setAllCaps(false);
            show.setText("Show QR");
            show.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showQr(a, qr(user(), pass(), MeshRadio.ctl().getString("nodes", "[]")));
                }
            });
            row.addView(show, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button fg = new Button(a);
            fg.setAllCaps(false);
            fg.setText("Forget");
            fg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    forget();
                    Toast.makeText(a, "Read-only login forgotten", Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(fg, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(row);
        new AlertDialog.Builder(a).setTitle("Read-only node login").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String pw = norm(p.getText().toString());
                        if (pw.length() == 0) return;
                        if (!valid(pw)) {
                            Toast.makeText(a, "Letters and numbers only, 8 to 32 of them", Toast.LENGTH_LONG).show();
                            return;
                        }
                        store(u.getText().toString(), pw, "typed in", null);
                        Toast.makeText(a, "Read-only login saved", Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- installing it on the nodes (Mesh controller, root login over /ubus) ----

    static String aclJson(String group) throws Exception {
        JSONObject ubus = new JSONObject()
                .put("mesh11s", new JSONArray().put("get_topology"))
                .put("iwinfo", new JSONArray().put("devices").put("info").put("assoclist"))
                .put("system", new JSONArray().put("info").put("board"))
                .put("file", new JSONArray().put("exec"));
        JSONObject file = new JSONObject().put(SCRIPT_PATH, new JSONArray().put("exec"));
        JSONObject g = new JSONObject().put("description", "YCC read-only radio data (installed by the YCC Mesh controller)")
                .put("read", new JSONObject().put("ubus", ubus).put("file", file));
        return new JSONObject().put(group, g).toString(2);
    }

    static final String SCRIPT = "#!/bin/sh\n"
            + "# YCC read-only radio data (installed by the YCC Mesh controller). Takes no arguments, changes nothing.\n"
            + "printf '{\"n\":'; batctl meshif bat0 nj 2>/dev/null || printf '[]'\n"
            + "printf ',\"o\":'; batctl meshif bat0 oj 2>/dev/null || printf '[]'\n"
            + "printf ',\"h\":\"'; grep -v '^#' /tmp/bat-hosts 2>/dev/null | tr -d '\"\\\\' | tr '\\n' '|'; printf '\"}\\n'\n";

    /** Puts (or updates) the read-only login on one node. Returns null when done, else why not. */
    static String install(String host, String rootUser, String rootPass, String roUser, String roPass) {
        UbusApi u = new UbusApi(host, 8000);
        try {
            u.login(rootUser == null || rootUser.length() == 0 ? "root" : rootUser, rootPass);
            u.call("file", "write", new JSONObject().put("path", ACL_PATH).put("data", aclJson("ycc-ro")));
            u.call("file", "write", new JSONObject().put("path", SCRIPT_PATH).put("data", SCRIPT).put("mode", 493));
            String hash = Md5Crypt.crypt(roPass, Md5Crypt.newSalt());
            String section = findSection(u, roUser);
            if (section == null) {
                JSONObject vals = new JSONObject().put("username", roUser).put("password", hash)
                        .put("read", new JSONArray().put("ycc-ro")).put("write", new JSONArray());
                u.call("uci", "add", new JSONObject().put("config", "rpcd").put("type", "login").put("values", vals));
            } else {
                u.call("uci", "set", new JSONObject().put("config", "rpcd").put("section", section)
                        .put("values", new JSONObject().put("password", hash).put("read", new JSONArray().put("ycc-ro")).put("write", new JSONArray())));
            }
            u.call("uci", "commit", new JSONObject().put("config", "rpcd"));
            try {
                u.call("rc", "init", new JSONObject().put("name", "rpcd").put("action", "reload"));
            } catch (UbusApi.UbusException e) {
                if (!e.noAnswer()) throw e; // rpcd may drop this very call while it reloads
            }
            u.logout();
            Thread.sleep(2500);
            UbusApi check = new UbusApi(host, 8000);
            check.login(roUser, roPass);
            check.call("mesh11s", "get_topology", null);
            check.logout();
            return null;
        } catch (UbusApi.UbusException e) {
            return e.loginFailed() ? "login refused (root login, or the read-only check)" : e.getMessage();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static String remove(String host, String rootUser, String rootPass, String roUser) {
        UbusApi u = new UbusApi(host, 8000);
        try {
            u.login(rootUser == null || rootUser.length() == 0 ? "root" : rootUser, rootPass);
            String section = findSection(u, roUser);
            if (section != null) {
                u.call("uci", "delete", new JSONObject().put("config", "rpcd").put("section", section));
                u.call("uci", "commit", new JSONObject().put("config", "rpcd"));
            }
            try {
                u.call("file", "remove", new JSONObject().put("path", ACL_PATH));
            } catch (UbusApi.UbusException ignored) {
            }
            try {
                u.call("file", "remove", new JSONObject().put("path", SCRIPT_PATH));
            } catch (UbusApi.UbusException ignored) {
            }
            try {
                u.call("rc", "init", new JSONObject().put("name", "rpcd").put("action", "reload"));
            } catch (UbusApi.UbusException e) {
                if (!e.noAnswer()) throw e;
            }
            u.logout();
            return null;
        } catch (UbusApi.UbusException e) {
            return e.loginFailed() ? "root login refused" : e.getMessage();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String findSection(UbusApi u, String roUser) throws Exception {
        JSONObject all = u.call("uci", "get", new JSONObject().put("config", "rpcd")).optJSONObject("values");
        if (all == null) return null;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = all.optJSONObject(k);
            if (s != null && "login".equals(s.optString(".type")) && roUser.equals(s.optString("username"))) return k;
        }
        return null;
    }

    /** One node to work on. */
    public static final class Target {
        public final String name, host;
        String result; // null = not done, "" = OK, else the reason

        public Target(String name, String host) {
            this.name = name;
            this.host = host;
        }
    }

    // ---- the admin's page (Mesh controller > More) ----

    public interface Login {
        String user();

        String pass();
    }

    public static void adminDialog(final Activity a, final List<Target> targets, final Login root, final String nodesJson) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView t = new TextView(a);
        t.setText("One read-only login for the whole team, the same on every node. It can only read radio data (neighbours, "
                + "signal, layout, load), never change anything. Letters and numbers only, so it can be read out over another radio.");
        box.addView(t);
        final EditText u = new EditText(a);
        u.setSingleLine(true);
        u.setText(sp().getString("u", DEFAULT_USER));
        box.addView(u);
        final EditText p = new EditText(a);
        p.setSingleLine(true);
        p.setTextSize(22);
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        p.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(32)});
        String cur = sp().getString("p", "");
        p.setText(cur.length() > 0 ? cur : suggest());
        box.addView(p);
        final CheckBox use = new CheckBox(a);
        use.setText("This phone also uses it (OpenMANET Mesh, walk)");
        use.setChecked(sp().getBoolean("use", true));
        box.addView(use);
        LinearLayout r1 = new LinearLayout(a), r2 = new LinearLayout(a);
        Button sug = btn(a, r1, "Suggest");
        Button qrB = btn(a, r1, "Show QR");
        Button readB = btn(a, r1, "Read QR");
        Button setB = btn(a, r2, "Set on all nodes");
        Button rmB = btn(a, r2, "Remove from nodes");
        box.addView(r1);
        box.addView(r2);
        final TextView status = new TextView(a);
        status.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(status);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        sug.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.setText(suggest());
            }
        });
        qrB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pw = norm(p.getText().toString());
                if (!valid(pw)) {
                    Toast.makeText(a, "Letters and numbers only, 8 to 32 of them", Toast.LENGTH_LONG).show();
                    return;
                }
                showQr(a, qr(u.getText().toString().trim(), pw, nodesJson));
            }
        });
        readB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan(a);
            }
        });
        setB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String pw = norm(p.getText().toString());
                final String us = u.getText().toString().trim().length() == 0 ? DEFAULT_USER : u.getText().toString().trim();
                if (!valid(pw)) {
                    Toast.makeText(a, "Letters and numbers only, 8 to 32 of them", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!us.matches("[A-Za-z0-9_-]{2,32}") || "root".equals(us)) {
                    Toast.makeText(a, "Pick another user name (not root)", Toast.LENGTH_LONG).show();
                    return;
                }
                sp().edit().putString("u", us).putString("p", pw).putBoolean("use", use.isChecked()).putString("from", "this phone").commit();
                run(a, targets, status, root, true, us, pw);
            }
        });
        rmB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String us = u.getText().toString().trim().length() == 0 ? DEFAULT_USER : u.getText().toString().trim();
                new AlertDialog.Builder(a).setTitle("Remove the read-only login").setMessage("Remove " + us + " from every node in the list? "
                        + "Team phones then fall back to the admin login if they have one, or show YCC's own figures.")
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                run(a, targets, status, root, false, us, null);
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        status.setText(targets.isEmpty() ? "No nodes in the list yet: Scan first." : targets.size() + " nodes in the list.");
        new AlertDialog.Builder(a).setTitle("Read-only login for team phones").setView(sv)
                .setPositiveButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String pw = norm(p.getText().toString());
                        if (valid(pw)) sp().edit().putString("u", u.getText().toString().trim().length() == 0 ? DEFAULT_USER : u.getText().toString().trim())
                                .putString("p", pw).putBoolean("use", use.isChecked()).commit();
                        MeshNode.LOCAL.stop();
                        MeshRadio.ensure(a);
                    }
                }).show();
    }

    private static Button btn(Activity a, LinearLayout row, String s) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(s);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    /** Node by node, one at a time; each line green ✓ or red ✗ with the reason. Retry = press the button again (done nodes are kept). */
    private static void run(final Activity a, final List<Target> targets, final TextView status, final Login root, final boolean install,
                            final String us, final String pw) {
        if (targets.isEmpty()) {
            status.setText("No nodes in the list yet: Scan first.");
            return;
        }
        if (root.pass() == null) {
            status.setText("Log in (Scan) first: the admin login is needed to set it up.");
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        final String ru = root.user(), rp = root.pass();
        final List<Target> todo = new ArrayList<Target>();
        for (Target t : targets) if (t.result == null || t.result.length() > 0 || !install) todo.add(t);
        if (todo.isEmpty()) for (Target t : targets) todo.add(t); // all done before: do them all again (a new password)
        for (Target t : todo) t.result = null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final Target t : todo) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText(lines(targets, (install ? "Setting up " : "Removing from ") + t.name + "…"));
                        }
                    });
                    String r = install ? install(t.host, ru, rp, us, pw) : remove(t.host, ru, rp, us);
                    t.result = r == null ? "" : r;
                    NodeShare.log((install ? "Read-only login set on " : "Read-only login removed from ") + t.name + (r == null ? "" : ": FAILED " + r));
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        int bad = 0;
                        for (Target t : targets) if (t.result != null && t.result.length() > 0) bad++;
                        status.setText(lines(targets, bad == 0 ? "Done." : bad + " failed: fix them (node on? same admin password?) and press the button again "
                                + "to retry only those, or leave them out."));
                    }
                });
            }
        }, "ro-login-nodes").start();
    }

    private static String lines(List<Target> targets, String tail) {
        StringBuilder b = new StringBuilder();
        for (Target t : targets) {
            b.append(t.result == null ? "○ " : t.result.length() == 0 ? "✓ " : "✗ ").append(t.name).append(" (").append(t.host).append(")")
                    .append(t.result != null && t.result.length() > 0 ? ": " + t.result : "").append("\n");
        }
        return b.append(tail).toString();
    }
}
