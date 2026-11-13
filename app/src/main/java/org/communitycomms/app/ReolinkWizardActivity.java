package org.communitycomms.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.communitycomms.app.camera.ReolinkApi;
import org.communitycomms.app.camera.ReolinkCam;
import org.communitycomms.app.camera.ReolinkScan;

import java.util.ArrayList;
import java.util.List;

/**
 * Adding Reolink cameras, step by step: scan the LAN (or type an address) → pick cameras →
 * user name and password → test (sign in and fetch a picture) → pick lenses on dual-lens models →
 * stream for the full-screen view → options (bring to front on a trigger, linked floodlight) → save.
 */
public final class ReolinkWizardActivity extends Activity {
    private static final int SCAN = 0, LOGIN = 1, TEST = 2, LENSES = 3, STREAM = 4, OPTIONS = 5, SAVE = 6;
    private static final String[] TITLES = {"1. Find cameras", "2. Sign in", "3. Test", "4. Lenses", "5. Stream",
            "6. Options", "7. Save"};

    private static final class Cand {
        String ip;
        int port; // typed by hand, else 0 = try 80 then 443
        ReolinkScan.Found found;
        boolean picked;
        ReolinkApi.DevInfo info;
        int httpPort;
        boolean https;
        String problem;
        Bitmap thumb;
        /** Signed in over Reolink's own port 9000 (no HTTP on this camera). */
        boolean bc;
    }

    private static final class Lens {
        Cand cand;
        int ch;
        boolean picked = true;
        Bitmap thumb;
        boolean flood;
        String name;
        boolean show = true, link;
    }

    private final List<Cand> cands = new ArrayList<Cand>();
    private final List<Lens> lenses = new ArrayList<Lens>();
    private int step;
    private String user, pass, stream = "sub";
    private boolean busy, scanned;
    private LinearLayout body;
    private TextView title;
    private Button back, next;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Add Reolink cameras");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // a scan takes up to half a minute
        d = getResources().getDisplayMetrics().density;
        user = ReolinkCam.defaultUser(this);
        pass = ReolinkCam.defaultPass(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(12), px(8), px(12), px(8));
        title = new TextView(this);
        title.setTextSize(20);
        root.addView(title);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        // The address box must not take the focus (and pop up the keyboard over the whole wizard).
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        back = new Button(this);
        back.setText("Back");
        next = new Button(this);
        next.setText("Next");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                if (step == SCAN) finish();
                else go(step == STREAM && !anyMultiLens() ? TEST : step - 1);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!busy) onNext();
            }
        });
        root.addView(FilesActivity.row(back, next));
        setContentView(root);
        go(SCAN);
    }

    private int px(float dp) {
        return (int) (dp * d);
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, px(6), 0, px(6));
        body.addView(t);
        return t;
    }

    private void go(int s) {
        step = s;
        title.setText(TITLES[s]);
        body.removeAllViews();
        body.requestFocus();
        back.setText(s == SCAN ? "Cancel" : "Back");
        next.setText(s == SAVE ? "Save" : "Next");
        next.setEnabled(true);
        switch (s) {
            case SCAN:
                showScan();
                break;
            case LOGIN:
                showLogin();
                break;
            case TEST:
                showTest();
                break;
            case LENSES:
                showLenses();
                break;
            case STREAM:
                showStream();
                break;
            case OPTIONS:
                showOptions();
                break;
            default:
                showSave();
        }
    }

    private void onNext() {
        switch (step) {
            case SCAN:
                if (picked().isEmpty()) {
                    text("Tick at least one camera, or add one by its address.");
                    return;
                }
                go(LOGIN);
                break;
            case LOGIN:
                go(TEST);
                break;
            case TEST: {
                boolean ok = false;
                for (Cand c : picked()) ok |= c.info != null;
                if (!ok) return;
                buildLenses();
                go(anyMultiLens() ? LENSES : STREAM);
                break;
            }
            case LENSES:
                go(STREAM);
                break;
            case STREAM:
                go(OPTIONS);
                break;
            case OPTIONS:
                go(SAVE);
                break;
            default:
                save();
        }
    }

    private List<Cand> picked() {
        List<Cand> l = new ArrayList<Cand>();
        for (Cand c : cands) if (c.picked) l.add(c);
        return l;
    }

    // ---- 1. scan ----

    private void showScan() {
        text("Finds cameras on this network (no internet needed): ONVIF discovery, then a check of every "
                + "address for Reolink's port 9000 and the video ports 554 / 80. Takes up to half a minute.");
        Button scan = new Button(this);
        scan.setText(scanned ? "Scan again" : "Scan the network");
        body.addView(scan);
        final TextView progress = text("");
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);
        renderCands(list);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                busy = true;
                progress.setText("Starting…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<ReolinkScan.Found> found = ReolinkScan.scan(ReolinkWizardActivity.this, user, pass,
                                new ReolinkScan.Progress() {
                                    @Override
                                    public void onProgress(final String what) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                progress.setText(what);
                                            }
                                        });
                                    }
                                }, 30000);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                busy = false;
                                scanned = true;
                                for (ReolinkScan.Found f : found) {
                                    Cand c = byIp(f.ip);
                                    if (c == null) {
                                        c = new Cand();
                                        c.ip = f.ip;
                                        // With a password the scan signs in: tick only the cameras that answered.
                                        c.picked = (pass.length() > 0 ? f.info != null : f.likelyReolink()) && !saved(f.ip);
                                        cands.add(c);
                                    }
                                    c.found = f;
                                    if (f.info != null) {
                                        c.info = f.info;
                                        c.httpPort = f.httpPort;
                                        c.https = f.https;
                                    }
                                }
                                progress.setText(found.isEmpty() ? "No cameras found. Check the phone is on the same network, "
                                        + "or add one by its address below." : "Found " + found.size() + ". Tick the ones to add.");
                                renderCands(list);
                            }
                        });
                    }
                }, "reolink-scan").start();
            }
        });
        text("Add by address (the camera's address from your router or the Reolink app; add :port after it if its web port is not 80/443):");
        final EditText ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setHint("Camera address");
        Button add = new Button(this);
        add.setText("Add");
        LinearLayout row = new LinearLayout(this);
        row.addView(ip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
        row.addView(add, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        body.addView(row);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = ip.getText().toString().trim().replaceFirst("^[a-z]+://", "");
                if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                if (s.length() == 0) return;
                if (!s.matches("[A-Za-z0-9.\\-]+(:[0-9]+)?")) {
                    text("That address has a character that cannot be in an address: please check it.");
                    return;
                }
                int port = 0;
                int colon = s.lastIndexOf(':');
                if (colon > 0) {
                    try {
                        port = Integer.parseInt(s.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        port = 0;
                    }
                    s = s.substring(0, colon);
                }
                Cand c = byIp(s);
                if (c == null) {
                    c = new Cand();
                    c.ip = s;
                    cands.add(c);
                }
                c.port = port;
                c.picked = true;
                ip.setText("");
                renderCands(list);
            }
        });
    }

    private boolean saved(String ip) {
        for (ReolinkCam c : ReolinkCam.load(this)) if (c.host.equals(ip)) return true;
        return false;
    }

    private Cand byIp(String ip) {
        for (Cand c : cands) if (c.ip.equals(ip)) return c;
        return null;
    }

    private void renderCands(LinearLayout list) {
        list.removeAllViews();
        for (final Cand c : cands) {
            CheckBox cb = new CheckBox(this);
            String s = c.found != null ? c.found.describe() : c.ip + (c.port > 0 ? ":" + c.port : "") + "  (added by hand)";
            if (saved(c.ip)) s += "\n   already saved";
            cb.setText(s);
            cb.setChecked(c.picked);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    c.picked = on;
                }
            });
            list.addView(cb);
        }
    }

    // ---- 2. sign in ----

    private EditText userField, passField;

    private void showLogin() {
        text("The camera's own user name and password (as set in the Reolink app). Kept only on this phone: "
                + "never in the setup QR, shared settings or logs.");
        userField = new EditText(this);
        userField.setSingleLine(true);
        userField.setHint("User name");
        userField.setText(user);
        body.addView(userField);
        passField = new EditText(this);
        passField.setSingleLine(true);
        passField.setHint("Password");
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setText(pass);
        body.addView(passField);
        final CheckBox showPw = new CheckBox(this);
        showPw.setText("Show password");
        showPw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                passField.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            }
        });
        body.addView(showPw);
        final CheckBox remember = new CheckBox(this);
        remember.setText("Use these for the next cameras too");
        remember.setChecked(true);
        body.addView(remember);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                if (step == LOGIN) {
                    user = userField.getText().toString().trim();
                    if (user.length() == 0) user = "admin";
                    pass = passField.getText().toString();
                    if (remember.isChecked()) ReolinkCam.setDefaults(ReolinkWizardActivity.this, user, pass);
                    for (Cand c : cands) {
                        c.info = null; // test again with these
                        c.thumb = null;
                    }
                }
                onNext();
            }
        });
    }

    // ---- 3. test ----

    private void showTest() {
        next.setEnabled(false);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);
        final List<Cand> todo = picked();
        final TextView[] rows = new TextView[todo.size()];
        final ImageView[] pics = new ImageView[todo.size()];
        for (int i = 0; i < todo.size(); i++) {
            rows[i] = new TextView(this);
            rows[i].setText(todo.get(i).ip + ": signing in…");
            pics[i] = new ImageView(this);
            pics[i].setAdjustViewBounds(true);
            pics[i].setMaxHeight(px(160));
            list.addView(rows[i]);
            list.addView(pics[i]);
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < todo.size(); i++) {
                    final Cand c = todo.get(i);
                    try {
                        Object[] r = ReolinkApi.probe(c.ip, c.port, user, pass);
                        c.httpPort = (Integer) r[0];
                        c.https = (Boolean) r[1];
                        c.info = (ReolinkApi.DevInfo) r[2];
                        c.problem = null;
                        ReolinkApi api = new ReolinkApi(c.ip, c.httpPort, c.https, user, pass);
                        try {
                            c.thumb = ReolinkMonitor.decode(api.snap(0, 640, 360), 480);
                        } finally {
                            api.logout();
                        }
                        if (c.thumb == null) c.problem = "signed in, but the picture could not be read";
                    } catch (ReolinkApi.ApiException e) {
                        c.problem = e.getMessage();
                    } catch (Exception e) {
                        c.problem = e.getMessage();
                        // No HTTP answer: try Reolink's own port 9000 (cameras like the E1 have nothing else).
                        if (c.info == null && (c.found == null || c.found.port9000)) tryBc(c);
                    }
                    final int k = i;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String s = c.ip + ": ";
                            if (c.info != null) {
                                s += "OK, " + c.info.model + (c.info.name.length() > 0 ? " \"" + c.info.name + "\"" : "")
                                        + (c.info.channels > 1 ? ", " + c.info.channels + " lenses" : "")
                                        + (c.https ? " (HTTPS)" : "");
                                if (c.problem != null) s += "\n   picture: " + c.problem;
                            } else {
                                s += "not added: " + c.problem;
                            }
                            rows[k].setText(s);
                            rows[k].setTextColor(c.info != null ? 0xffa5d6a7 : 0xffff8a80);
                            if (c.thumb != null) pics[k].setImageBitmap(c.thumb);
                        }
                    });
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        boolean ok = false;
                        for (Cand c : todo) ok |= c.info != null;
                        next.setEnabled(ok);
                        if (!ok) text("None answered. Go back to check the password, or the address and port.");
                    }
                });
            }
        }, "reolink-test").start();
    }

    private void tryBc(Cand c) {
        org.communitycomms.app.camera.BcClient b = new org.communitycomms.app.camera.BcClient();
        try {
            b.connect(c.ip, 9000, 5000);
            b.login(user, pass, 10000);
            String di = b.deviceInfo(8000);
            ReolinkApi.DevInfo info = new ReolinkApi.DevInfo();
            String model = org.communitycomms.app.camera.BcClient.tag(di, "type");
            String nm = org.communitycomms.app.camera.BcClient.tag(di, "name");
            info.model = model == null ? "Reolink (port 9000)" : model;
            info.name = nm == null ? "" : nm;
            info.channels = b.channels;
            c.thumb = ReolinkMonitor.decode(b.snapshot(0, "sub", 15000), 480);
            b.logout();
            c.info = info;
            c.bc = true;
            c.httpPort = 9000;
            c.problem = null;
        } catch (Exception e) {
            c.problem = (c.problem == null ? "" : c.problem + "; ") + "port 9000: " + e.getMessage();
        } finally {
            b.close();
        }
    }

    private void buildLenses() {
        lenses.clear();
        for (Cand c : picked()) {
            if (c.info == null) continue;
            for (int ch = 0; ch < c.info.channels; ch++) {
                Lens l = new Lens();
                l.cand = c;
                l.ch = ch;
                l.thumb = ch == 0 ? c.thumb : null;
                String base = c.info.name.length() > 0 ? c.info.name : c.info.model.length() > 0 ? c.info.model : "Camera";
                l.name = base + (c.info.channels > 1 ? " lens " + (ch + 1) : "");
                lenses.add(l);
            }
        }
        // Floodlight check (and the other lenses' pictures) in the background while the user reads on.
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Lens l : new ArrayList<Lens>(lenses)) {
                    if (l.cand.bc) continue; // port 9000 cameras: picture already taken; no floodlight check over that port
                    ReolinkApi api = new ReolinkApi(l.cand.ip, l.cand.httpPort, l.cand.https, user, pass);
                    try {
                        l.flood = api.hasFlood(l.ch);
                        l.link = l.flood;
                    } catch (Exception ignored) {
                    }
                    if (l.thumb == null) {
                        try {
                            l.thumb = ReolinkMonitor.decode(api.snap(l.ch, 640, 360), 480);
                        } catch (Exception ignored) {
                        }
                    }
                    api.logout();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (step == LENSES || step == OPTIONS) go(step);
                        }
                    });
                }
            }
        }, "reolink-lenses").start();
    }

    private boolean anyMultiLens() {
        for (Cand c : picked()) if (c.info != null && c.info.channels > 1) return true;
        return false;
    }

    // ---- 4. lenses ----

    private void showLenses() {
        text("Dual-lens cameras (and recorders) have a picture for each lens. Each lens you tick becomes its own camera tile.");
        for (final Lens l : lenses) {
            if (l.cand.info.channels < 2) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText(l.name);
            cb.setChecked(l.picked);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    l.picked = on;
                }
            });
            body.addView(cb);
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(px(140));
            if (l.thumb != null) iv.setImageBitmap(l.thumb);
            else text("   (picture loading…)");
            body.addView(iv);
        }
    }

    // ---- 5. stream ----

    private void showStream() {
        text("The small tiles always use still pictures from the camera (every 1-2 seconds), which work on every phone. "
                + "For the full-screen view of a camera:");
        RadioGroup g = new RadioGroup(this);
        String[] keys = {"sub", "main", "snap"};
        String[] labels = {"Sub stream (recommended): live video, smaller, plays on most phones",
                "Main stream: best picture, but often H.265, which older phones cannot play (falls back to pictures)",
                "Pictures only: no live video"};
        for (int i = 0; i < keys.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(100 + i);
            r.setText(labels[i]);
            g.addView(r);
            if (keys[i].equals(stream)) g.check(100 + i);
        }
        final String[] k = keys;
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int id) {
                stream = k[id - 100];
            }
        });
        body.addView(g);
    }

    // ---- 6. options ----

    private void showOptions() {
        text("Bring to front: when this camera sees motion or a person / vehicle / animal, its picture fills the "
                + "Tactical videos and Intercom screens until it has been quiet for a while.\n"
                + "Linked floodlight: when any linked camera is triggered, every linked camera with a floodlight turns it on, "
                + "then off again after the set time (Settings on the camera list).");
        for (final Lens l : lenses) {
            if (!l.picked) continue;
            final EditText name = new EditText(this);
            name.setSingleLine(true);
            name.setText(l.name);
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    l.name = e.toString().trim();
                }
            });
            body.addView(name);
            CheckBox show = new CheckBox(this);
            show.setText("Bring to front when triggered");
            show.setChecked(l.show);
            show.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    l.show = on;
                }
            });
            body.addView(show);
            CheckBox link = new CheckBox(this);
            link.setText("Linked floodlight" + (l.flood ? " (this lens has a floodlight)" : " (no floodlight found: its triggers still turn on the others)"));
            link.setChecked(l.link);
            link.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    l.link = on;
                }
            });
            body.addView(link);
            View gap = new View(this);
            body.addView(gap, new LinearLayout.LayoutParams(1, px(12)));
        }
    }

    // ---- 7. save ----

    private void showSave() {
        int n = 0;
        StringBuilder s = new StringBuilder("These will be added (shared by Tactical videos and the Intercom):\n");
        for (Lens l : lenses) {
            if (!l.picked) continue;
            n++;
            s.append("\n• ").append(l.name.length() > 0 ? l.name : "Camera")
                    .append(l.show ? ", to front on trigger" : "").append(l.link ? ", linked floodlight" : "");
        }
        s.append("\n\nFull screen: ").append(stream.equals("sub") ? "sub stream" : stream.equals("main") ? "main stream" : "pictures only");
        if (n == 0) s = new StringBuilder("No lenses ticked: go back and tick at least one.");
        text(s.toString());
        next.setEnabled(n > 0);
    }

    private void save() {
        List<ReolinkCam> all = ReolinkCam.load(this);
        for (Lens l : lenses) {
            if (!l.picked) continue;
            ReolinkCam c = new ReolinkCam();
            c.host = l.cand.ip;
            c.httpPort = l.cand.httpPort;
            c.https = l.cand.https;
            c.user = user;
            c.pass = pass;
            c.model = l.cand.info.model;
            c.channels = l.cand.info.channels;
            c.channel = l.ch;
            c.name = l.name;
            c.fullStream = stream;
            c.showOnTrigger = l.show;
            c.linkedFlood = l.link;
            c.hasFlood = l.flood && !l.cand.bc;
            if (l.cand.bc) {
                c.bcOnly = true;
                c.bcPort = 9000;
                c.fullStream = "snap";
                c.linkedFlood = false;
            }
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).key().equals(c.key())) all.remove(i);
            all.add(c);
        }
        ReolinkCam.save(this, all);
        android.widget.Toast.makeText(this, "Cameras saved", android.widget.Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
