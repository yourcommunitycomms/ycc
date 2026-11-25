package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.OpenManetApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesh controller (an Advanced feature, off by default): changes the HaLow channel (frequency and width), mesh
 * name and mesh password on every OpenMANET node, through each node's own openmanetd web API.
 *
 * The node login is never saved: it is asked for before a scan or a change, held in memory only, and wiped when
 * this page is left, the screen locks, or after 5 idle minutes (the user name is remembered). Nodes come from
 * the mesh itself (openmanetd's topology and node list, names looked up by multicast DNS) plus any added by hand,
 * and are changed in the order shown: farthest first by default, the node this phone is on last, so every node
 * can still be reached while the ones before it move. A node that does not answer stops the run until the user
 * picks Retry, Skip or Stop; a node left behind on the old settings is fixed with Retry failed, by joining its
 * own WiFi or LAN.
 */
public final class MeshControllerActivity extends Activity {
    private static final long IDLE_WIPE_MS = 5 * 60 * 1000L;
    private static final int TIMEOUT = 6000;

    static final int ST_UNKNOWN = 0, ST_ANSWERS = 1, ST_OK = 2, ST_LOGIN = 3, ST_DOWN = 4, ST_ERROR = 5;

    static final class Node {
        String name = "", ip = "", mac = "";
        int hops = -1;
        boolean self, gateway, inMesh;
        int state = ST_UNKNOWN;
        String detail = "";
        String radio;
        JSONObject settings; // as read from the node (no password)
        String channel = "", meshId = "", country = "";
        String enc = ""; // security as the node reports it (the mesh password itself is never readable)
        long readAt; // when channel/meshId/country were last read from the node (saved, so they show before a scan)
        String run = ""; // "", "sent", "done", "failed", "skipped"
        String user, pass; // a different login for this node (memory only)
        boolean selected = true;
        String addrFrom = ""; // where the address came from
        final List<String> log = new ArrayList<String>(); // the last check, step by step
        long checkedAt;

        String label() {
            return name.length() > 0 ? name : ip;
        }
    }

    /** One HaLow channel from the regulatory table. */
    static final class Chan {
        int width, number;
        String freq;

        @Override
        public String toString() {
            return "ch " + number + " · " + freq + " MHz · " + width + " MHz wide";
        }
    }

    private SharedPreferences sp;
    private final Handler ui = new Handler();
    private final List<Node> nodes = new ArrayList<Node>();
    private TextView status, plan;
    private LinearLayout list;
    private Button scanB, newB, runB, retryB;
    private float d;
    private volatile boolean busy;

    // Memory only: wiped by wipeLogin().
    private String user, pass;
    private String newChannel, newMeshId, newPassword;
    private String newCountry;
    private final Map<String, String[]> previous = new HashMap<String, String[]>(); // node -> {channel, meshId}

    private final Runnable idleWipe = new Runnable() {
        @Override
        public void run() {
            if (busy) {
                ui.postDelayed(this, 60000);
                return;
            }
            if (pass != null) {
                wipeLogin();
                setStatus("Login wiped after 5 idle minutes. Scan again to log in.");
            }
        }
    };

    /** Node names by address from the saved node list (for naming OpenMANET talk group talkers); read live. */
    public static CompatVoice.Namer namer(Context c) {
        final SharedPreferences sp = c.getApplicationContext().getSharedPreferences("meshctl", Context.MODE_PRIVATE);
        return new CompatVoice.Namer() {
            @Override
            public String name(String address) {
                try {
                    JSONArray a = new JSONArray(sp.getString("nodes", "[]"));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        if (address.equals(o.optString("ip"))) return o.optString("name", null);
                    }
                } catch (JSONException ignored) {
                }
                return null;
            }
        };
    }

    public static boolean featureOn(Context c) {
        return new Prefs(c).menuOn("meshctl");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Mesh controller");
        // No screen timeout here: a scan or run takes a while, and leaving the page wipes the login.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("meshctl", Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * d);
        root.setPadding(pad, pad, pad, pad);
        sharedBanner = new TextView(this);
        sharedBanner.setBackgroundColor(0xffc62828);
        sharedBanner.setTextColor(0xffffffff);
        sharedBanner.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        root.addView(sharedBanner);
        status = new TextView(this);
        status.setPadding(0, 0, 0, (int) (4 * d));
        root.addView(status);
        scanB = button("Scan", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askLogin(new Runnable() {
                    @Override
                    public void run() {
                        scan();
                    }
                });
            }
        });
        newB = button("New settings…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSettings();
            }
        });
        runB = button("Run", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRun();
            }
        });
        root.addView(row(scanB, newB, runB));
        retryB = button("Retry failed", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryFailed();
            }
        });
        root.addView(row(retryB, button("Add node", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addNode();
            }
        }), button("More…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                more();
            }
        })));
        plan = new TextView(this);
        plan.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        root.addView(plan);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        loadNodes();
        showPlan();
        render();
        setStatus(nodes.isEmpty()
                ? "Tap Scan and enter the nodes' login (it is not saved). This phone should be on one of the mesh's WiFi networks."
                : "Checking which saved nodes answer… Tap Scan and log in to read their settings.");
        if (!nodes.isEmpty()) quickCheck();
        TvFocus.watch(getWindow());
        showSharedBanner();
        ui.post(shareTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!busy) wipeLogin(); // left the page or the screen locked
    }

    private final Runnable shareTick = new Runnable() {
        @Override
        public void run() {
            NodeShare.showPending(MeshControllerActivity.this);
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wipeLogin();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        touchIdle();
    }

    private void touchIdle() {
        ui.removeCallbacks(idleWipe);
        if (pass != null) ui.postDelayed(idleWipe, IDLE_WIPE_MS);
    }

    private boolean usingShared;
    private TextView sharedBanner;

    /** A red line at the top while a login shared by someone else is held. */
    private void showSharedBanner() {
        if (sharedBanner == null) return;
        boolean on = NodeShare.held();
        sharedBanner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) sharedBanner.setText("Admin login from " + NodeShare.from() + " (shared with this phone; FULL admin on the nodes). "
                + "More > Forget the login shared with me.");
    }

    private void wipeLogin() {
        usingShared = false;
        pass = null;
        newPassword = null;
        for (Node n : nodes) n.pass = null;
        ui.removeCallbacks(idleWipe);
        if (newB != null) showPlan();
    }

    // ---- small UI helpers ----

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setOnClickListener(l);
        return bt;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false); // a two-line button label must not push that button down
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return r;
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    private void refresh() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    private void setBusy(final boolean b) {
        busy = b;
        ui.post(new Runnable() {
            @Override
            public void run() {
                scanB.setEnabled(!b);
                newB.setEnabled(!b);
                runB.setEnabled(!b);
                retryB.setEnabled(!b);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---- saved node list (names and addresses only, never a password) ----

    private void loadNodes() {
        nodes.clear();
        try {
            JSONArray a = new JSONArray(sp.getString("nodes", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Node n = new Node();
                n.name = o.optString("name", "");
                n.ip = o.optString("ip", "");
                n.mac = o.optString("mac", "");
                n.self = false;
                n.selected = o.optBoolean("on", true);
                n.channel = o.optString("ch", "");
                n.meshId = o.optString("mesh", "");
                n.country = o.optString("country", "");
                n.enc = o.optString("enc", "");
                n.readAt = o.optLong("readAt", 0);
                n.hops = o.optInt("hops", -1);
                nodes.add(n);
            }
        } catch (JSONException ignored) {
        }
        user = sp.getString("user", "root");
    }

    private void saveNodes() {
        JSONArray a = new JSONArray();
        for (Node n : nodes) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", n.name);
                o.put("ip", n.ip);
                o.put("mac", n.mac);
                o.put("on", n.selected);
                o.put("hops", n.hops); // mesh testing (Map mesh) estimates hop counts from these
                if (n.readAt > 0) { // the last settings read (no password: nodes never report it)
                    o.put("ch", n.channel);
                    o.put("mesh", n.meshId);
                    o.put("country", n.country);
                    o.put("enc", n.enc);
                    o.put("readAt", n.readAt);
                }
            } catch (JSONException ignored) {
            }
            a.put(o);
        }
        sp.edit().putString("nodes", a.toString()).commit();
    }

    // ---- the list ----

    private void render() {
        list.removeAllViews();
        if (nodes.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No nodes yet.");
            list.addView(t);
            return;
        }
        TextView now = meshNow();
        if (now != null) list.addView(now);
        TextView head = new TextView(this);
        head.setText("Change order (top first). The node this phone is on should be last.");
        head.setTextSize(12);
        list.addView(head);
        for (int i = 0; i < nodes.size(); i++) {
            final int idx = i;
            final Node n = nodes.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextSize(22);
            dot.setTextColor(color(n));
            dot.setPadding(0, 0, (int) (8 * d), 0);
            r.addView(dot);
            TextView t = new TextView(this);
            StringBuilder s = new StringBuilder();
            s.append(idx + 1).append(". ").append(n.label());
            if (!n.selected) s.append("  (left out)");
            s.append('\n').append(n.ip.length() > 0 ? n.ip : "no address yet");
            if (n.hops >= 0) s.append(n.hops == 0 ? " · this phone's node" : " · " + n.hops + (n.hops == 1 ? " hop" : " hops"));
            if (n.gateway) s.append(" · gateway");
            if (n.detail.length() > 0) s.append('\n').append(n.detail);
            else if (n.readAt > 0) { // saved from an earlier scan
                String fw = freqWidth(n.country, n.channel);
                s.append('\n').append("ch ").append(n.channel).append(fw != null ? fw : "").append(" · mesh ").append(n.meshId)
                        .append(" (read ").append(ago(n.readAt)).append(")");
            }
            if (n.run.length() > 0) s.append('\n').append(runText(n.run));
            t.setText(s.toString());
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nodeDetails(n);
                }
            });
            t.setFocusable(true);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(t);
            if (n.ip.length() > 0) col.addView(webLinks(n.ip, true)); // this node's own config pages
            r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button up = new Button(this);
            up.setText("▲");
            up.setEnabled(idx > 0);
            up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(idx, -1);
                }
            });
            Button down = new Button(this);
            down.setText("▼");
            down.setEnabled(idx < nodes.size() - 1);
            down.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(idx, 1);
                }
            });
            r.addView(up, new LinearLayout.LayoutParams((int) (48 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            r.addView(down, new LinearLayout.LayoutParams((int) (48 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            list.addView(r);
        }
    }

    /**
     * The mesh's current settings at the top of the list: what most nodes reported at their last read
     * (saved, so it shows before a scan), plus any node that differs. The mesh password can't be shown:
     * the nodes never report it and this phone never stores it.
     */
    private TextView meshNow() {
        Map<String, Integer> votes = new HashMap<String, Integer>();
        Node best = null;
        int bestVotes = 0, read = 0;
        long newest = 0;
        for (Node n : nodes) {
            if (n.readAt <= 0) continue;
            read++;
            newest = Math.max(newest, n.readAt);
            String k = n.channel + "|" + n.meshId + "|" + n.country;
            int v = (votes.containsKey(k) ? votes.get(k) : 0) + 1;
            votes.put(k, v);
            if (v > bestVotes) {
                bestVotes = v;
                best = n;
            }
        }
        if (best == null) return null;
        StringBuilder s = new StringBuilder();
        s.append("Mesh now (read ").append(ago(newest)).append(", ").append(bestVotes).append(" of ").append(read)
                .append(read == 1 ? " node" : " nodes").append(")\n");
        s.append("Mesh name: ").append(best.meshId.length() > 0 ? best.meshId : "?").append('\n');
        String fw = freqWidth(best.country, best.channel);
        s.append("Channel ").append(best.channel);
        if (fw != null) s.append(fw).append(" wide");
        s.append('\n');
        s.append("Country: ").append(best.country.length() > 0 ? best.country : "?");
        for (Node n : nodes) {
            if (n.readAt <= 0 || n == best) continue;
            if (!n.channel.equals(best.channel) || !n.meshId.equals(best.meshId) || !n.country.equals(best.country))
                s.append("\nDiffers: ").append(n.label()).append(" is on ch ").append(n.channel).append(", mesh ").append(n.meshId)
                        .append(n.country.equals(best.country) ? "" : ", country " + n.country);
        }
        TextView t = new TextView(this);
        t.setText(s.toString());
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(Color.rgb(31, 61, 43));
        int p = (int) (8 * d);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * d);
        t.setLayoutParams(lp);
        return t;
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    private static String runText(String run) {
        if ("sent".equals(run)) return "→ new settings sent";
        if ("done".equals(run)) return "✓ on the new settings";
        if ("failed".equals(run)) return "✗ not on the new settings (Retry failed)";
        if ("skipped".equals(run)) return "– skipped: still on the old settings";
        return run;
    }

    private static int color(Node n) {
        switch (n.state) {
            case ST_OK:
                return Color.rgb(0, 170, 0);
            case ST_ANSWERS:
                return Color.rgb(90, 160, 90);
            case ST_LOGIN:
            case ST_ERROR:
                return Color.rgb(230, 150, 0);
            case ST_DOWN:
                return Color.rgb(210, 30, 30);
            default:
                return Color.GRAY;
        }
    }

    private void move(int idx, int delta) {
        int j = idx + delta;
        if (busy || j < 0 || j >= nodes.size()) return;
        Collections.swap(nodes, idx, j);
        sp.edit().putBoolean("orderSet", true).commit();
        saveNodes();
        render();
    }

    private String detailsText(Node n) {
        StringBuilder s = new StringBuilder();
        s.append(n.label()).append(n.ip.length() > 0 ? "  " + n.ip : "").append('\n');
        String st;
        switch (n.state) {
            case ST_OK:
                st = "Green: logged in and settings read";
                break;
            case ST_ANSWERS:
                st = "Light green: answers, not logged in yet";
                break;
            case ST_LOGIN:
                st = "Amber: login refused";
                break;
            case ST_ERROR:
                st = "Amber: answered with an error";
                break;
            case ST_DOWN:
                st = "Red: no answer";
                break;
            default:
                st = "Grey: not checked yet";
        }
        s.append(st).append('\n');
        if (n.detail.length() > 0) s.append(n.detail).append('\n');
        if (n.run.length() > 0) s.append(runText(n.run)).append('\n');
        s.append('\n');
        List<String> copy;
        synchronized (n.log) {
            copy = new ArrayList<String>(n.log);
        }
        if (copy.isEmpty()) {
            s.append("No check run yet on this page. Tap Refresh / try again: it checks the address, ping, the web API port (8080), "
                    + "the login, the HaLow radio, its settings and its live status, one step at a time.");
        } else {
            s.append("Last check").append(n.checkedAt > 0 ? " at " + android.text.format.DateFormat.format("HH:mm:ss", n.checkedAt) : "").append(":\n");
            for (String l : copy) s.append(l).append('\n');
        }
        return s.toString();
    }

    /** Tapping a node: what was checked and how it went, with Refresh / try again. */
    private void nodeDetails(final Node n) {
        final TextView t = new TextView(this);
        int p = (int) (16 * d);
        t.setPadding(p, (int) (8 * d), p, 0);
        t.setTextSize(14);
        t.setText(detailsText(n));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(t);
        if (n.ip.length() > 0) {
            View links = webLinks(n.ip, false);
            links.setPadding(p, (int) (6 * d), p, 0);
            inner.addView(links);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(inner);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(n.label()).setView(sv)
                .setPositiveButton("Refresh / try again", null)
                .setNeutralButton("More…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        nodeMenu(n);
                    }
                }).setNegativeButton("Close", null).create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                final Button b = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (busy) {
                            toast("Wait for the scan or run to finish");
                            return;
                        }
                        askLogin(new Runnable() {
                            @Override
                            public void run() {
                                b.setEnabled(false);
                                b.setText("Checking…");
                                recheck(n, new Runnable() {
                                    @Override
                                    public void run() {
                                        if (dlg.isShowing()) t.setText(detailsText(n));
                                    }
                                }, new Runnable() {
                                    @Override
                                    public void run() {
                                        b.setEnabled(true);
                                        b.setText("Refresh / try again");
                                        if (dlg.isShowing()) t.setText(detailsText(n));
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
        dlg.show();
    }

    /** Re-checks one node in the background; onStep runs (on the UI thread) as the log grows, onDone at the end. */
    private void recheck(final Node n, final Runnable onStep, final Runnable onDone) {
        setBusy(true);
        setStatus("Checking " + n.label() + "…");
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                onStep.run();
                if (busy) ui.postDelayed(this, 400);
            }
        };
        ui.postDelayed(ticker, 400);
        new Thread(new Runnable() {
            @Override
            public void run() {
                lookUp(n);
                boolean ok = readNode(n);
                saveNodes();
                setStatus(n.label() + (ok ? ": read OK. " : ": " + n.detail + ". ") + "Tap it for the step-by-step check.");
                setBusy(false);
                refresh();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ui.removeCallbacks(ticker);
                        onDone.run();
                    }
                });
            }
        }).start();
    }

    private void nodeMenu(final Node n) {
        if (busy) return;
        final String[] items = {n.selected ? "Leave this node out of changes" : "Include this node in changes",
                "Use a different login for this node", "Change its address", "Open its web page", "Remove from the list"};
        new AlertDialog.Builder(this).setTitle(n.label()).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int which) {
                if (which == 0) {
                    n.selected = !n.selected;
                    saveNodes();
                    render();
                } else if (which == 1) {
                    nodeLogin(n);
                } else if (which == 2) {
                    editAddress(n);
                } else if (which == 3) {
                    if (n.ip.length() > 0) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + n.ip + ":8080/")));
                        } catch (RuntimeException e) {
                            toast("No web browser on this phone");
                        }
                    }
                } else {
                    nodes.remove(n);
                    saveNodes();
                    render();
                }
            }
        }).show();
    }

    /**
     * Two links for a node: its OpenMANET config (LuCI, http://node/) and the OpenMANET portal (https://node:8081/,
     * a self-signed certificate: the browser warns once). Small on the list rows, full buttons in the details sheet.
     */
    private View webLinks(final String ip, boolean compact) {
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false);
        Button luci = new Button(this);
        luci.setText(compact ? "Config" : "OpenMANET config (LuCI)");
        Button portal = new Button(this);
        portal.setText(compact ? "Portal" : "OpenMANET portal (:8081)");
        for (Button b : new Button[]{luci, portal}) {
            b.setAllCaps(false);
            if (compact) {
                b.setTextSize(12);
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                b.setPadding((int) (8 * d), (int) (2 * d), (int) (8 * d), (int) (2 * d));
            }
        }
        luci.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("http://" + ip + "/");
            }
        });
        portal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("The portal uses its own certificate: accept the browser's warning once (or use http://" + ip + ":8080/)");
                openUrl("https://" + ip + ":8081/");
            }
        });
        if (compact) {
            r.addView(luci, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            r.addView(portal, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            r.setOrientation(LinearLayout.VERTICAL);
            r.addView(luci);
            r.addView(portal);
        }
        return r;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast("No web browser on this phone");
        }
    }

    private void editAddress(final Node n) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(n.ip);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Address of " + n.label()).setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        n.ip = e.getText().toString().trim();
                        n.addrFrom = "typed in by hand";
                        saveNodes();
                        render();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void addNode() {
        if (busy) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, (int) (8 * d), p, 0);
        final EditText name = new EditText(this);
        name.setHint("Name (e.g. HT03)");
        name.setSingleLine(true);
        final EditText ip = new EditText(this);
        ip.setHint("Address (e.g. 10.41.254.12)");
        ip.setSingleLine(true);
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(ip);
        new AlertDialog.Builder(this).setTitle("Add a node").setView(box)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        Node n = new Node();
                        n.name = name.getText().toString().trim();
                        n.ip = ip.getText().toString().trim();
                        if (n.ip.length() == 0 && n.name.length() == 0) return;
                        if (n.ip.length() > 0) n.addrFrom = "typed in by hand";
                        nodes.add(0, n);
                        saveNodes();
                        render();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void more() {
        String[] items = {"Suggest an order (farthest first)", "Check which nodes answer (no login)",
                "Undo: put the last settings back", sp.contains("savedPass") ? "Forget the saved login (remembered on this phone)" : "Forget the login now",
                "About the Mesh controller", "OpenMANET Mesh (my node's neighbours and signal, read-only)",
                "Share the node ADMIN login to team phones…", "Revoke the shared login (ask phones to forget it)…",
                "Node login sharing log", NodeShare.held() ? "Forget the login shared with me" : "(no login shared with me)",
                "Read-only login for team phones (one password, all nodes)…"};
        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int which) {
                if (busy) return;
                if (which == 0) {
                    suggestOrder();
                    saveNodes();
                    render();
                } else if (which == 1) {
                    quickCheck();
                } else if (which == 2) {
                    undo();
                } else if (which == 3) {
                    boolean saved = sp.contains("savedPass");
                    sp.edit().remove("savedPass").commit();
                    wipeLogin();
                    setStatus(saved ? "Saved login removed from this phone." : "Login forgotten.");
                } else if (which == 5) {
                    MeshRadioActivity.open(MeshControllerActivity.this);
                } else if (which == 6) {
                    askLogin(new Runnable() {
                        @Override
                        public void run() {
                            if (usingShared) {
                                toast("This login was shared with you by " + NodeShare.from() + ": only the admin who has it can share it.");
                                return;
                            }
                            NodeShare.startShare(MeshControllerActivity.this, user, pass, sp.getString("nodes", "[]"));
                        }
                    });
                } else if (which == 7) {
                    NodeShare.confirmRevoke(MeshControllerActivity.this);
                } else if (which == 8) {
                    NodeShare.showLog(MeshControllerActivity.this);
                } else if (which == 10) {
                    askLogin(new Runnable() {
                        @Override
                        public void run() {
                            java.util.List<RoLogin.Target> ts = new java.util.ArrayList<RoLogin.Target>();
                            for (Node n : nodes) if (n.ip != null && n.ip.length() > 0) ts.add(new RoLogin.Target(n.label(), n.ip));
                            RoLogin.adminDialog(MeshControllerActivity.this, ts, new RoLogin.Login() {
                                @Override
                                public String user() {
                                    return user;
                                }

                                @Override
                                public String pass() {
                                    return pass;
                                }
                            }, sp.getString("nodes", "[]"));
                        }
                    });
                } else if (which == 9) {
                    if (NodeShare.held()) NodeShare.confirmForget(MeshControllerActivity.this, new Runnable() {
                        @Override
                        public void run() {
                            if (usingShared) wipeLogin();
                            showSharedBanner();
                        }
                    });
                } else {
                    new AlertDialog.Builder(MeshControllerActivity.this).setTitle("Mesh controller")
                            .setMessage("Changes the HaLow channel (frequency and width), mesh name and mesh password on "
                                    + "every OpenMANET node, using each node's own web API (openmanetd).\n\n"
                                    + "• The login is never saved: it is asked for before each scan or change and wiped when "
                                    + "you leave this page, the screen locks, or after 5 idle minutes.\n"
                                    + "• Green: logged in and read. Light green: answers (log in to read). Amber: login failed or "
                                    + "an error. Red: no answer. Grey: not checked.\n"
                                    + "• Nodes change in the order shown, one after another (farthest first, this phone's node "
                                    + "last). Each node leaves the old mesh a few seconds after it gets the new settings.\n"
                                    + "• A node that misses the change stays on the old settings and drops out. Retry failed: "
                                    + "join that node's own WiFi (or its LAN port) and send it the new settings directly.\n"
                                    + "• Only channels allowed in the nodes' country are offered. Both ends of every link need "
                                    + "the same channel, mesh name and mesh password.\n"
                                    + "• Nodes do not switch back on their own if something goes wrong: keep a way to reach "
                                    + "them (their WiFi or LAN).")
                            .setPositiveButton("OK", null).show();
                }
            }
        }).show();
    }

    // ---- login (memory only) ----

    private void askLogin(final Runnable then) {
        if (busy) return;
        if (pass == null && sp.contains("savedPass")) pass = sp.getString("savedPass", null); // "Remember on this phone"
        if (pass == null && NodeShare.held()) { // the admin login a team admin shared with this phone (never shown)
            user = NodeShare.user();
            pass = NodeShare.pass();
            usingShared = true;
            showSharedBanner();
        }
        if (pass != null) {
            touchIdle();
            then.run();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, (int) (8 * d), p, 0);
        TextView note = new TextView(this);
        note.setText("The nodes' login (as on their web page). Unless you tick Remember below, it is not saved and is wiped when you leave this page.");
        box.addView(note);
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setHint("User");
        u.setText(user == null ? "root" : user);
        final EditText pw = new EditText(this);
        pw.setSingleLine(true);
        pw.setHint("Password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(u);
        box.addView(pw);
        final CheckBox remember = new CheckBox(this);
        remember.setText("Remember on this phone (kept in YCC's private storage behind the app lock; not in backups; "
                + "the lost-phone wipe erases it). Off: asked again each time.");
        remember.setChecked(false);
        box.addView(remember);
        new AlertDialog.Builder(this).setTitle("Log in to the nodes").setView(box)
                .setPositiveButton("Log in", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        user = u.getText().toString().trim();
                        if (user.length() == 0) user = "root";
                        sp.edit().putString("user", user).commit();
                        pass = pw.getText().toString();
                        if (remember.isChecked()) sp.edit().putString("savedPass", pass).commit();
                        else sp.edit().remove("savedPass").commit();
                        touchIdle();
                        then.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void nodeLogin(final Node n) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, (int) (8 * d), p, 0);
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setHint("User");
        u.setText(n.user != null ? n.user : user);
        final EditText pw = new EditText(this);
        pw.setSingleLine(true);
        pw.setHint("Password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(u);
        box.addView(pw);
        new AlertDialog.Builder(this).setTitle("Login for " + n.label() + " (not saved)").setView(box)
                .setPositiveButton("Use", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        n.user = u.getText().toString().trim();
                        n.pass = pw.getText().toString();
                        touchIdle();
                    }
                }).setNeutralButton("Same as the others", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        n.user = null;
                        n.pass = null;
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private OpenManetApi loginTo(Node n, String host) throws IOException {
        OpenManetApi api = new OpenManetApi(host, TIMEOUT);
        String u = n != null && n.pass != null ? n.user : user;
        String p = n != null && n.pass != null ? n.pass : pass;
        if (p == null) throw new IOException("No login");
        api.login(u == null || u.length() == 0 ? "root" : u, p);
        return api;
    }

    // ---- quick check (no login) ----

    private void quickCheck() {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int up = 0;
                for (Node n : nodes) {
                    if (n.ip.length() == 0 && n.name.length() > 0) {
                        String a = OpenManetApi.mdnsLookup(n.name, 1500);
                        if (a != null) n.ip = a;
                    }
                    boolean ok = n.ip.length() > 0 && OpenManetApi.answers(n.ip, 2500);
                    if (ok) up++;
                    if (n.state != ST_OK || !ok) n.state = ok ? ST_ANSWERS : ST_DOWN;
                    if (!ok) n.detail = "No answer";
                    refresh();
                }
                saveNodes();
                setStatus(up + " of " + nodes.size() + " saved nodes answer. Tap Scan and log in to read their settings.");
                setBusy(false);
            }
        }).start();
    }

    // ---- scan: find every node and read its HaLow settings ----

    private String phoneGateway() {
        try {
            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo i = w == null ? null : w.getDhcpInfo();
            int g = i == null ? 0 : (i.gateway != 0 ? i.gateway : i.serverAddress);
            if (g != 0) return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String cleanName(String h) {
        if (h == null) return "";
        return h.replaceAll("_(wlh\\d+|bat\\d+|br-[a-z]+|eth\\d+|phy\\d+-ap\\d+)$", "");
    }

    private Node find(String name, String mac) {
        for (Node n : nodes) {
            if (mac != null && mac.length() > 0 && mac.equalsIgnoreCase(n.mac)) return n;
            if (name != null && name.length() > 0 && name.equalsIgnoreCase(n.name)) return n;
        }
        return null;
    }

    private void scan() {
        setBusy(true);
        setStatus("Scanning…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doScan();
                } finally {
                    setBusy(false);
                    refresh();
                }
            }
        }).start();
    }

    private void doScan() {
        // 1. Log in to the node this phone is on (its WiFi gateway), or else any saved node.
        List<String> entries = new ArrayList<String>();
        String gw = phoneGateway();
        if (gw != null) entries.add(gw);
        for (Node n : nodes) if (n.ip.length() > 0 && !entries.contains(n.ip)) entries.add(n.ip);
        OpenManetApi entry = null;
        String why = "This phone is not on a WiFi network and no nodes are saved.";
        for (String h : entries) {
            setStatus("Logging in to " + h + "…");
            try {
                entry = loginTo(null, h);
                break;
            } catch (OpenManetApi.ApiException e) {
                why = e.loginFailed() ? "Login refused by " + h + " (wrong user or password)." : h + ": " + e.getMessage();
                if (e.loginFailed()) {
                    pass = null;
                    if (sp.contains("savedPass")) {
                        sp.edit().remove("savedPass").commit();
                        why += " The saved login was wrong, so it has been forgotten.";
                    }
                    break;
                }
            } catch (IOException e) {
                why = h + ": " + e.getMessage();
            }
        }
        if (entry == null) {
            setStatus("Scan stopped. " + why);
            return;
        }
        // 2. The mesh's nodes from the topology (names, hops) and the node list (addresses).
        int found = 0;
        for (Node n : nodes) {
            n.inMesh = false;
            n.self = false;
            if (n.addrFrom.length() == 0) n.addrFrom = "saved from an earlier scan";
        }
        try {
            JSONObject topo = entry.call(OpenManetApi.TOPOLOGY + "GetMeshTopology", new JSONObject()).optJSONObject("topology");
            JSONArray tn = topo == null ? null : topo.optJSONArray("nodes");
            if (tn != null) {
                for (int i = 0; i < tn.length(); i++) {
                    JSONObject o = tn.getJSONObject(i);
                    String name = cleanName(o.optString("hostname", ""));
                    String mac = o.optString("mac", "");
                    Node n = find(name, mac);
                    if (n == null) {
                        n = new Node();
                        nodes.add(n);
                    }
                    if (name.length() > 0) n.name = name;
                    if (mac.length() > 0) n.mac = mac;
                    n.hops = o.optInt("hopsFromSelf", o.optBoolean("isSelf") ? 0 : -1);
                    n.self = o.optBoolean("isSelf", false);
                    n.gateway = o.optBoolean("isGateway", false);
                    n.inMesh = true;
                    if (n.self) {
                        n.ip = entry.host();
                        n.addrFrom = "the node this phone logged in to";
                    }
                    found++;
                }
                // For Map mesh hop estimates: which node the hop counts are measured from, and when.
                sp.edit().putString("selfIp", entry.host()).putLong("topoAt", System.currentTimeMillis()).commit();
            }
        } catch (IOException e) {
            setStatus("Could not read the mesh layout from " + entry.host() + ": " + e.getMessage());
        } catch (JSONException ignored) {
        }
        try {
            JSONArray ln = entry.call(OpenManetApi.NODES + "ListNodes", new JSONObject()).optJSONArray("nodes");
            if (ln != null) {
                for (int i = 0; i < ln.length(); i++) {
                    JSONObject o = ln.getJSONObject(i);
                    Node n = find(cleanName(o.optString("hostname", "")), o.optString("mac", ""));
                    String ip = o.optString("ipaddr", "");
                    if (n != null && ip.length() > 0 && !n.self) {
                        n.ip = ip;
                        n.addrFrom = "the mesh's node list";
                    }
                }
            }
        } catch (IOException ignored) {
        } catch (JSONException ignored) {
        }
        if (!sp.getBoolean("orderSet", false)) suggestOrder();
        refresh();
        // 3. Each node: address (node list, else name.local, else saved), log in, read its HaLow radio.
        int ok = 0;
        for (Node n : nodes) {
            setStatus("Reading " + n.label() + "…");
            lookUp(n);
            if (readNode(n)) ok++;
            refresh();
        }
        saveNodes();
        setStatus("Scan done: " + ok + " of " + nodes.size() + " nodes read (" + found + " in the mesh layout from "
                + entry.host() + "). " + (ok == nodes.size() ? "Next: New settings…" : "Tap a node to fix its address or login."));
        entry.logout();
    }

    /** Looks the node's name up by multicast DNS (name.local); keeps the old address when nothing answers. */
    private void lookUp(Node n) {
        if (n.self || n.name.length() == 0) return;
        String a = OpenManetApi.mdnsLookup(n.name, 2000);
        if (a != null) {
            n.ip = a;
            n.addrFrom = n.name + ".local lookup";
        }
    }

    /** One line in a node's check log: "+0.4 s  ✓ Login: token OK". */
    private static void step(Node n, long t0, boolean ok, String what) {
        String line = String.format(Locale.US, "+%.1f s  %s %s", (System.currentTimeMillis() - t0) / 1000.0, ok ? "✓" : "✗", what);
        synchronized (n.log) {
            n.log.add(line);
        }
    }

    /** Ping once with the phone's own ping (no root needed on most phones). Returns the time text or null. */
    private static String ping(String ip) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "1", "-W", "2", ip});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, time = null;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("time=");
                if (i >= 0) time = line.substring(i + 5).trim();
            }
            return p.waitFor() == 0 ? (time != null ? time : "reply") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Logs in to one node and reads its HaLow radio, keeping a step-by-step log. True when read. */
    private boolean readNode(Node n) {
        long t0 = System.currentTimeMillis();
        synchronized (n.log) {
            n.log.clear();
        }
        n.checkedAt = t0;
        if (n.ip.length() == 0) {
            step(n, t0, false, "Address: none (not in the node list, " + n.name + ".local not found). Tap Change its address.");
            n.state = ST_DOWN;
            n.detail = "No address: tap it to add one";
            return false;
        }
        step(n, t0, true, "Address: " + n.ip + (n.addrFrom.length() > 0 ? " (" + n.addrFrom + ")" : ""));
        if (n.hops >= 0) step(n, t0, true, "Mesh: " + (n.hops == 0 ? "the node this phone logged in to" : n.hops + " hop(s) from the node this phone is on")
                + (n.inMesh ? "" : " (last scan)"));
        else step(n, t0, false, "Mesh: not in the mesh layout from the last scan");
        String pt = ping(n.ip);
        step(n, t0, pt != null, pt != null ? "Ping: reply " + pt : "Ping: no reply (some phones can't ping; not fatal)");
        boolean port = OpenManetApi.answers(n.ip, 4000);
        step(n, t0, port, port ? "Web API port 8080: open" : "Web API port 8080: no answer");
        if (!port) {
            n.state = ST_DOWN;
            n.detail = "No answer at " + n.ip + (pt != null ? " (pings, but its web API is not answering)" : "");
            return false;
        }
        OpenManetApi api = null;
        String stage = "Login";
        try {
            api = loginTo(n, n.ip);
            step(n, t0, true, "Login: token OK (user " + (n.pass != null ? n.user + ", this node's own login" : user) + ")");
            stage = "Radio list";
            n.radio = api.radioName("WIFI_BAND_S1G");
            if (n.radio == null) {
                step(n, t0, false, "Radio list: no HaLow (S1G) radio");
                n.state = ST_ERROR;
                n.detail = "No HaLow radio found";
                return false;
            }
            step(n, t0, true, "Radio list: HaLow radio is " + n.radio);
            stage = "Settings";
            JSONObject r = api.radioSettings(n.radio);
            n.settings = r.optJSONObject("settings");
            if (n.settings == null) n.settings = new JSONObject();
            n.channel = n.settings.optString("channel", "");
            n.meshId = n.settings.optString("meshId", "");
            n.country = n.settings.optString("country", "");
            String fw0 = freqWidth(n.country, n.channel);
            step(n, t0, n.channel.length() > 0, "Settings: channel " + n.channel + (fw0 != null ? fw0.replace(" · ", ", ") : "") + ", mesh " + n.meshId + ", country " + n.country
                    + ", security " + n.settings.optString("encryption", "?").replace("WIFI_ENCRYPTION_", ""));
            String extra = "";
            stage = "Live status";
            try {
                JSONObject st = api.radioStatus(n.radio);
                if (st != null) {
                    int f = st.optInt("frequency", 0);
                    String bw = st.optString("bandwidth", "");
                    extra = (f > 0 ? " · " + fmtFreq(f) : "") + (bw.length() > 0 ? " · " + bw : "");
                    step(n, t0, st.optBoolean("active", true), "Live status: " + (st.optBoolean("active", true) ? "radio up" : "radio DOWN")
                            + (extra.length() > 0 ? extra + " (as the driver reports it)" : "") + ", " + st.optInt("meshPeers", 0) + " mesh peer(s) heard");
                }
            } catch (IOException e) {
                step(n, t0, false, "Live status: " + e.getMessage());
            }
            n.state = ST_OK;
            // Frequency and width from the HaLow channel table (the live status reports a 20 MHz width for HaLow).
            String fw = freqWidth(n.country, n.channel);
            n.detail = "ch " + n.channel + (fw != null ? fw : extra) + " · mesh " + n.meshId + (n.country.length() > 0 ? " · " + n.country : "");
            n.enc = n.settings.optString("encryption", "");
            n.readAt = System.currentTimeMillis();
            saveNodes();
            return true;
        } catch (OpenManetApi.ApiException e) {
            if (e.loginFailed()) {
                step(n, t0, false, stage + ": refused (HTTP " + e.code + ") – wrong user or password for this node");
                n.state = ST_LOGIN;
                n.detail = "Login failed (tap it to use a different login)";
            } else if (e.noAnswer()) {
                step(n, t0, false, stage + ": no response (" + e.getMessage() + ")");
                n.state = ST_DOWN;
                n.detail = stage + ": no response from " + n.ip;
            } else {
                step(n, t0, false, stage + ": error " + (e.code > 0 ? "HTTP " + e.code + " " : "") + e.getMessage());
                n.state = ST_ERROR;
                n.detail = stage + ": " + e.getMessage();
            }
            return false;
        } catch (IOException e) {
            step(n, t0, false, stage + ": " + e.getMessage());
            n.state = ST_ERROR;
            n.detail = e.getMessage();
            return false;
        } finally {
            if (api != null) api.logout();
        }
    }

    private static String fmtFreq(int f) {
        // openmanetd reports kHz for HaLow on some builds and MHz on others
        if (f > 100000) return String.format(Locale.US, "%.1f MHz", f / 1000.0);
        return f + " MHz";
    }

    /** Farthest first, the node this phone is on last. */
    private void suggestOrder() {
        Collections.sort(nodes, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                if (a.self != b.self) return a.self ? 1 : -1;
                return b.hops - a.hops;
            }
        });
    }

    // ---- new settings ----

    /** " · 926.5 MHz · 1 MHz" for a channel in a country, or null when the table doesn't have it. */
    private String freqWidth(String country, String ch) {
        if (country == null || country.length() == 0 || ch == null) return null;
        for (Chan c : channels(country)) if (String.valueOf(c.number).equals(ch)) return " · " + c.freq + " MHz · " + c.width + " MHz";
        return null;
    }

    private final Map<String, List<Chan>> chanCache = new HashMap<String, List<Chan>>();

    private List<Chan> channels(String country) {
        String key = country.toUpperCase(Locale.US);
        synchronized (chanCache) {
            List<Chan> c = chanCache.get(key);
            if (c != null) return c;
            c = loadChannels(key);
            chanCache.put(key, c);
            return c;
        }
    }

    private List<Chan> loadChannels(String country) {
        List<Chan> out = new ArrayList<Chan>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(getAssets().open("s1g_channels.csv"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] f = line.split(",");
                if (f.length < 4 || !f[0].equalsIgnoreCase(country)) continue;
                Chan c = new Chan();
                c.width = Integer.parseInt(f[1].trim());
                c.number = Integer.parseInt(f[2].trim());
                c.freq = f[3].trim();
                out.add(c);
            }
        } catch (IOException ignored) {
        } catch (NumberFormatException ignored) {
        } finally {
            if (r != null) try {
                r.close();
            } catch (IOException ignored) {
            }
        }
        Collections.sort(out, new Comparator<Chan>() {
            @Override
            public int compare(Chan a, Chan b) {
                return a.number - b.number;
            }
        });
        return out;
    }

    private String meshCountry() {
        Map<String, Integer> count = new HashMap<String, Integer>();
        String best = null;
        int bestN = 0;
        for (Node n : nodes) {
            if (n.country.length() == 0) continue;
            int c = (count.containsKey(n.country) ? count.get(n.country) : 0) + 1;
            count.put(n.country, c);
            if (c > bestN) {
                bestN = c;
                best = n.country;
            }
        }
        return best;
    }

    private String currentChannel() {
        for (Node n : nodes) if (n.channel.length() > 0) return n.channel;
        return "";
    }

    private String currentMeshId() {
        for (Node n : nodes) if (n.meshId.length() > 0) return n.meshId;
        return "";
    }

    private void newSettings() {
        if (busy) return;
        final String country = meshCountry();
        if (country == null) {
            toast("Scan first (with the login), so the nodes' country and current settings are known");
            return;
        }
        final List<Chan> all = channels(country);
        if (all.isEmpty()) {
            toast("No HaLow channels known for country " + country);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, (int) (8 * d), p, 0);
        TextView note = new TextView(this);
        String nowFw = freqWidth(country, currentChannel());
        note.setText("Country " + country + " (from the nodes). Now: ch " + currentChannel() + (nowFw != null ? nowFw : "") + ", mesh " + currentMeshId()
                + ".\nIn HaLow the channel sets both the frequency and the width.");
        box.addView(note);
        TextView wl = new TextView(this);
        wl.setText("Width");
        box.addView(wl);
        final List<Integer> widths = new ArrayList<Integer>();
        for (Chan c : all) if (!widths.contains(c.width)) widths.add(c.width);
        Collections.sort(widths);
        List<String> wlabels = new ArrayList<String>();
        for (int w : widths) wlabels.add(w + " MHz");
        final Spinner wsp = new Spinner(this);
        wsp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, wlabels));
        box.addView(wsp);
        TextView cl = new TextView(this);
        cl.setText("Channel");
        box.addView(cl);
        final Spinner csp = new Spinner(this);
        box.addView(csp);
        final List<Chan> shown = new ArrayList<Chan>();
        String want = newChannel != null ? newChannel : currentChannel();
        int wantWidth = widths.get(0);
        for (Chan c : all) if (String.valueOf(c.number).equals(want)) wantWidth = c.width;
        final String wantF = want;
        wsp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                shown.clear();
                for (Chan c : all) if (c.width == widths.get(pos)) shown.add(c);
                csp.setAdapter(new ArrayAdapter<Chan>(MeshControllerActivity.this, android.R.layout.simple_spinner_dropdown_item, shown));
                for (int i = 0; i < shown.size(); i++) if (String.valueOf(shown.get(i).number).equals(wantF)) csp.setSelection(i);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        wsp.setSelection(widths.indexOf(wantWidth));
        TextView ml = new TextView(this);
        ml.setText("Mesh name (mesh ID)");
        box.addView(ml);
        final EditText mesh = new EditText(this);
        mesh.setSingleLine(true);
        mesh.setText(newMeshId != null ? newMeshId : currentMeshId());
        box.addView(mesh);
        TextView pl = new TextView(this);
        pl.setText("New mesh password (blank = keep the current one; at least 8 characters; not saved)");
        box.addView(pl);
        final EditText mpw = new EditText(this);
        mpw.setSingleLine(true);
        mpw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (newPassword != null) mpw.setText(newPassword);
        box.addView(mpw);
        CheckBox show = new CheckBox(this);
        show.setText("Show the password");
        show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                mpw.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                mpw.setSelection(mpw.getText().length());
            }
        });
        box.addView(show);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("New mesh settings").setView(sv)
                .setPositiveButton("Use these", null).setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        newChannel = null;
                        newMeshId = null;
                        newPassword = null;
                        showPlan();
                    }
                }).setNegativeButton("Cancel", null).create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Object sel = csp.getSelectedItem();
                        String m = mesh.getText().toString().trim();
                        String pw = mpw.getText().toString();
                        if (!(sel instanceof Chan)) {
                            toast("Pick a channel");
                            return;
                        }
                        if (m.length() == 0 || m.length() > 32) {
                            toast("The mesh name needs 1 to 32 characters");
                            return;
                        }
                        if (pw.length() > 0 && (pw.length() < 8 || pw.length() > 63)) {
                            toast("The mesh password needs 8 to 63 characters");
                            return;
                        }
                        newChannel = String.valueOf(((Chan) sel).number);
                        newMeshId = m;
                        newPassword = pw.length() > 0 ? pw : null;
                        newCountry = country;
                        dlg.dismiss();
                        showPlan();
                    }
                });
            }
        });
        dlg.show();
    }

    private String chanText(String country, String ch) {
        if (country == null || ch == null) return "ch " + ch;
        for (Chan c : channels(country)) {
            if (String.valueOf(c.number).equals(ch)) return "ch " + ch + " (" + c.freq + " MHz, " + c.width + " MHz wide)";
        }
        return "ch " + ch;
    }

    private void showPlan() {
        if (plan == null) return;
        if (newChannel == null) {
            plan.setText("New settings: none yet (Scan, then New settings…)");
            return;
        }
        plan.setText("New settings: " + chanText(newCountry, newChannel) + ", mesh " + newMeshId + ", "
                + (newPassword != null ? "new mesh password" : "mesh password unchanged"));
    }

    // ---- run ----

    private boolean changes(Node n) {
        return !newChannel.equals(n.channel) || !newMeshId.equals(n.meshId) || newPassword != null;
    }

    private void confirmRun() {
        if (busy) return;
        if (newChannel == null) {
            toast("Set the New settings first");
            return;
        }
        if (pass == null) {
            askLogin(new Runnable() {
                @Override
                public void run() {
                    toast("Logged in. Scan again before running, so the settings are fresh.");
                }
            });
            return;
        }
        final List<Node> todo = new ArrayList<Node>();
        List<String> notReady = new ArrayList<String>();
        for (Node n : nodes) {
            if (!n.selected) continue;
            if (n.state != ST_OK || n.settings == null) notReady.add(n.label() + " (" + (n.detail.length() > 0 ? n.detail : "not read") + ")");
            else if (changes(n)) todo.add(n);
        }
        if (todo.isEmpty()) {
            toast(notReady.isEmpty() ? "Every node already has these settings" : "No node is ready: scan again");
            return;
        }
        StringBuilder m = new StringBuilder();
        m.append("Send ").append(chanText(newCountry, newChannel)).append(", mesh ").append(newMeshId)
                .append(newPassword != null ? ", new mesh password" : "").append(" to, in this order:\n");
        for (int i = 0; i < todo.size(); i++) m.append(i + 1).append(". ").append(todo.get(i).label()).append('\n');
        m.append("\nEach node leaves the current mesh a few seconds after it gets them.");
        if (!notReady.isEmpty()) {
            m.append("\n\n⚠ Not ready, and will be left on the old settings (they drop out of the mesh until fixed by hand):\n");
            for (String s : notReady) m.append("• ").append(s).append('\n');
        }
        boolean selfLast = !todo.isEmpty() && (todo.get(todo.size() - 1).self || !hasSelf(todo));
        if (!selfLast) m.append("\n⚠ The node this phone is on is not last: nodes after it may become unreachable.");
        new AlertDialog.Builder(this).setTitle(notReady.isEmpty() ? "Change the mesh?" : "Change the mesh? (warning)")
                .setMessage(m.toString())
                .setPositiveButton(notReady.isEmpty() ? "Start" : "Start anyway", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        run(todo);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private static boolean hasSelf(List<Node> l) {
        for (Node n : l) if (n.self) return true;
        return false;
    }

    /** Asks the user on the UI thread and waits for the answer (run thread only). Returns the button index. */
    private int ask(final String title, final String msg, final String... buttons) {
        final int[] ans = {-1};
        final Object lock = new Object();
        ui.post(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder b = new AlertDialog.Builder(MeshControllerActivity.this).setTitle(title).setMessage(msg).setCancelable(false);
                DialogInterface.OnClickListener l = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        int idx = which == DialogInterface.BUTTON_POSITIVE ? 0 : which == DialogInterface.BUTTON_NEUTRAL ? 1 : 2;
                        synchronized (lock) {
                            ans[0] = idx;
                            lock.notifyAll();
                        }
                    }
                };
                if (buttons.length > 0) b.setPositiveButton(buttons[0], l);
                if (buttons.length > 1) b.setNeutralButton(buttons[1], l);
                if (buttons.length > 2) b.setNegativeButton(buttons[2], l);
                b.show();
            }
        });
        synchronized (lock) {
            while (ans[0] < 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    return buttons.length - 1;
                }
            }
        }
        return ans[0];
    }

    private JSONObject buildSettings(Node n, String channel, String meshId, String password) throws JSONException {
        JSONObject s = new JSONObject(n.settings.toString());
        s.remove("password");
        s.put("channel", channel);
        s.put("meshId", meshId);
        s.put("ssid", meshId); // openmanetd puts a mesh's name in mesh_id; ssid must not be empty
        s.put("mode", "WIFI_MODE_MESH");
        if (password != null) {
            s.put("password", password);
            if (!s.has("encryption") || "WIFI_ENCRYPTION_UNSPECIFIED".equals(s.optString("encryption"))) s.put("encryption", "WIFI_ENCRYPTION_SAE");
        }
        return s;
    }

    private void run(final List<Node> todo) {
        final String ch = newChannel, mesh = newMeshId, pw = newPassword;
        for (Node n : nodes) n.run = "";
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doRun(todo, ch, mesh, pw);
                } finally {
                    setBusy(false);
                    refresh();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            touchIdle();
                        }
                    });
                }
            }
        }).start();
    }

    private void doRun(List<Node> todo, String ch, String mesh, String pw) {
        // 1. Check every node again just before anything changes: nothing has moved yet, so stopping is safe.
        for (int i = 0; i < todo.size(); i++) {
            Node n = todo.get(i);
            setStatus("Checking " + n.label() + " before the change…");
            while (!readNode(n)) {
                refresh();
                int a = ask("Not ready: " + n.label(), n.detail + "\n\nNothing has been changed yet.", "Check again", "Leave it out", "Stop");
                if (a == 2) {
                    setStatus("Stopped before any change. Nothing was changed.");
                    return;
                }
                if (a == 1) {
                    n.run = "skipped";
                    break;
                }
            }
            refresh();
        }
        // 2. Send, in order. Remember each node's old channel and name for Undo.
        previous.clear();
        int sent = 0;
        for (int i = 0; i < todo.size(); i++) {
            Node n = todo.get(i);
            if ("skipped".equals(n.run)) continue;
            while (true) {
                setStatus("Sending the new settings to " + n.label() + " (" + (i + 1) + " of " + todo.size() + ")…");
                try {
                    OpenManetApi api = loginTo(n, n.ip);
                    String[] old = {n.channel, n.meshId};
                    api.updateRadio(n.radio, buildSettings(n, ch, mesh, pw));
                    previous.put(n.label(), old);
                    n.run = "sent";
                    sent++;
                    break;
                } catch (Exception e) {
                    String msg = e.getMessage();
                    boolean noAns = e instanceof OpenManetApi.ApiException && ((OpenManetApi.ApiException) e).noAnswer();
                    int a = ask(n.label() + " did not take the settings", (msg == null ? "" : msg)
                                    + (noAns ? "\n\nIt may have changed anyway; the check at the end will show." : "")
                                    + "\n\n" + sent + " node(s) already have the new settings.",
                            "Retry", "Skip it", "Stop here");
                    if (a == 1) {
                        n.run = "skipped";
                        break;
                    }
                    if (a == 2) {
                        n.run = "skipped";
                        for (int j = i + 1; j < todo.size(); j++) todo.get(j).run = "skipped";
                        refresh();
                        if (sent == 0) {
                            setStatus("Stopped. Nothing was changed.");
                            return;
                        }
                        i = todo.size();
                        break;
                    }
                }
            }
            refresh();
            sleep(1500);
        }
        if (sent == 0) {
            setStatus("Nothing was sent.");
            return;
        }
        // 3. Let the mesh settle, then check each node is on the new settings.
        for (int s = 45; s > 0; s -= 5) {
            setStatus("Sent to " + sent + " node(s). Waiting " + s + " s for the mesh to settle…");
            sleep(5000);
        }
        int okCount = 0, failed = 0;
        for (Node n : todo) {
            if (!"sent".equals(n.run)) continue;
            boolean ok = false;
            for (int attempt = 0; attempt < 4 && !ok; attempt++) {
                setStatus("Checking " + n.label() + " on the new settings…");
                if (!n.self && n.name.length() > 0) {
                    String a = OpenManetApi.mdnsLookup(n.name, 2000);
                    if (a != null) n.ip = a;
                }
                if (readNode(n) && ch.equals(n.channel) && mesh.equals(n.meshId)) ok = true;
                else sleep(10000);
            }
            n.run = ok ? "done" : "failed";
            if (ok) okCount++;
            else failed++;
            refresh();
        }
        saveNodes();
        int skipped = 0;
        for (Node n : todo) if ("skipped".equals(n.run)) skipped++;
        setStatus("Done: " + okCount + " on the new settings" + (failed > 0 ? ", " + failed + " not confirmed (Retry failed)" : "")
                + (skipped > 0 ? ", " + skipped + " skipped (still on the old settings)" : "") + ".");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- retry a node left behind ----

    /**
     * Retry failed: after a run, re-sends the new settings to a node left behind. Otherwise (no run yet, or none
     * left behind) re-checks every node that is not green, with progress and a result.
     */
    private void retryFailed() {
        if (busy) return;
        final List<Node> left = new ArrayList<Node>();
        if (newChannel != null) for (Node n : nodes) if ("failed".equals(n.run) || "skipped".equals(n.run)) left.add(n);
        if (left.isEmpty()) {
            final List<Node> bad = new ArrayList<Node>();
            for (Node n : nodes) if (n.state != ST_OK) bad.add(n);
            if (bad.isEmpty()) {
                setStatus(nodes.isEmpty() ? "Nothing to retry: no nodes yet. Tap Scan." : "Nothing to retry: every node is green and no change was left unfinished.");
                toast("Nothing to retry");
                return;
            }
            askLogin(new Runnable() {
                @Override
                public void run() {
                    recheckAll(bad);
                }
            });
            return;
        }
        String[] names = new String[left.size()];
        for (int i = 0; i < left.size(); i++) names[i] = left.get(i).label() + " – " + runText(left.get(i).run);
        new AlertDialog.Builder(this).setTitle("Retry which node?").setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int which) {
                final Node n = left.get(which);
                askLogin(new Runnable() {
                    @Override
                    public void run() {
                        retryNode(n);
                    }
                });
            }
        }).show();
    }

    private void recheckAll(final List<Node> bad) {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                StringBuilder still = new StringBuilder();
                for (int i = 0; i < bad.size(); i++) {
                    Node n = bad.get(i);
                    setStatus("Retrying " + n.label() + " (" + (i + 1) + " of " + bad.size() + ")…");
                    n.state = ST_UNKNOWN;
                    n.detail = "Checking…";
                    refresh();
                    lookUp(n);
                    if (readNode(n)) ok++;
                    else still.append(still.length() > 0 ? "; " : "").append(n.label()).append(": ").append(n.detail);
                    refresh();
                }
                saveNodes();
                setStatus("Retry done: " + ok + " of " + bad.size() + " now green." + (still.length() > 0 ? " Still not: " + still
                        + ". Tap a node for its step-by-step check." : ""));
                setBusy(false);
                refresh();
            }
        }).start();
    }

    private void retryNode(final Node n) {
        new AlertDialog.Builder(this).setTitle("Retry " + n.label())
                .setMessage(n.label() + " is probably still on the old settings, so the rest of the mesh can't reach it.\n\n"
                        + "1. Go near it and join its own WiFi (or plug into its LAN port).\n2. Come back here and tap Try now.\n\n"
                        + "Tries " + (n.ip.length() > 0 ? n.ip + " and " : "") + "the WiFi gateway this phone is on.")
                .setPositiveButton("Try now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        setBusy(true);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    doRetry(n);
                                } finally {
                                    setBusy(false);
                                    refresh();
                                }
                            }
                        }).start();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void doRetry(Node n) {
        List<String> hosts = new ArrayList<String>();
        if (n.ip.length() > 0) hosts.add(n.ip);
        String gw = phoneGateway();
        if (gw != null && !hosts.contains(gw)) hosts.add(gw);
        String last = "No address to try";
        for (String h : hosts) {
            setStatus("Trying " + n.label() + " at " + h + "…");
            String keep = n.ip;
            n.ip = h;
            if (!readNode(n)) {
                last = n.detail;
                n.ip = keep;
                continue;
            }
            if (newChannel.equals(n.channel) && newMeshId.equals(n.meshId) && newPassword == null) {
                n.run = "done";
                setStatus(n.label() + " already has the new settings. Rejoin your usual WiFi and Scan.");
                return;
            }
            try {
                OpenManetApi api = loginTo(n, h);
                String[] old = {n.channel, n.meshId};
                api.updateRadio(n.radio, buildSettings(n, newChannel, newMeshId, newPassword));
                previous.put(n.label(), old);
                n.run = "done";
                setStatus("Sent to " + n.label() + ". It should join the new mesh within a minute. Rejoin your usual WiFi and Scan to check.");
                return;
            } catch (Exception e) {
                last = e.getMessage();
            }
        }
        setStatus("Could not reach " + n.label() + ": " + last);
    }

    // ---- undo: send the remembered channel and mesh name back ----

    private void undo() {
        if (previous.isEmpty()) {
            toast("Nothing to undo (only the last run on this page is remembered)");
            return;
        }
        String[] any = previous.values().iterator().next();
        final String ch = any[0], mesh = any[1];
        askLogin(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MeshControllerActivity.this).setTitle("Undo")
                        .setMessage("Send " + chanText(meshCountry(), ch) + " and mesh " + mesh + " back to the nodes changed last "
                                + "time, in the same order?\n\nA changed mesh password can't be put back (it is never stored): "
                                + "set it again with New settings… if needed.")
                        .setPositiveButton("Undo", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int w) {
                                newChannel = ch;
                                newMeshId = mesh;
                                newPassword = null;
                                newCountry = meshCountry();
                                showPlan();
                                setStatus("Undo set up as the New settings. Scan, then Run.");
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }
}
