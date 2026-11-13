package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.camera.ReolinkApi;
import org.communitycomms.app.camera.ReolinkCam;

import java.util.List;

/**
 * Reolink cameras: the one list shared by Tactical videos and the Intercom. Add with the wizard,
 * tap a camera to change its options, test or remove it; Settings holds the default sign-in, the
 * floodlight time, the quiet time and what counts as a trigger. Opened from Tactical videos, and
 * from the Intercom behind the PIN.
 */
public final class ReolinkSetupActivity extends Activity {
    private LinearLayout list;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Reolink cameras");
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        Button add = new Button(this);
        add.setText("Find and add cameras");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ReolinkSetupActivity.this, ReolinkWizardActivity.class));
            }
        });
        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings();
            }
        });
        Button addBc = new Button(this);
        addBc.setText("Add a port 9000 camera");
        addBc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addBcOnly();
            }
        });
        root.addView(FilesActivity.row(add, settings));
        root.addView(addBc);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        List<ReolinkCam> cams = ReolinkCam.load(this);
        if (cams.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No cameras yet. Find and add cameras scans this network for Reolink cameras (no internet needed). "
                    + "They then show on the Tactical videos screen with the shared phone cameras, and on the Intercom.");
            t.setPadding(0, (int) (12 * d), 0, 0);
            list.addView(t);
            return;
        }
        for (int i = 0; i < cams.size(); i++) {
            final int idx = i;
            ReolinkCam c = cams.get(i);
            Button bt = new Button(this);
            bt.setAllCaps(false);
            bt.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            String s = c.bcOnly ? c.feedName() + (c.enabled ? "" : "  (off)") + "\n" + (c.model.length() > 0 && !c.model.equals("port 9000") ? c.model + ", " : "") + "port 9000: pictures and motion"
                    + (c.offlineAlert ? ", offline alerts" : "") : c.feedName() + (c.enabled ? "" : "  (off)") + "\n" + (c.model.length() > 0 ? c.model + ", " : "")
                    + (c.channels > 1 ? "lens " + (c.channel + 1) + " of " + c.channels + ", " : "")
                    + (c.fullStream.equals("snap") ? "pictures only" : c.fullStream + " stream")
                    + (c.showOnTrigger ? ", to front on trigger" : "") + (c.linkedFlood ? ", linked floodlight" : "")
                    + (c.hasFlood ? " (has light)" : "") + (c.sendAlert ? ", alerts the channel" + (c.announce ? " aloud" : "")
                    + (c.attachPicture ? " with a picture" : "") : "") + (c.offlineAlert ? ", offline alerts" : "");
            bt.setText(s);
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    options(idx);
                }
            });
            list.addView(bt);
        }
        TextView tm = new TextView(this);
        tm.setText("Works with Reolink cameras. Reolink is a trademark of its owner; this app is not affiliated with Reolink.");
        tm.setTextSize(12);
        tm.setPadding(0, (int) (12 * d), 0, 0);
        list.addView(tm);
    }

    private void options(final int idx) {
        final List<ReolinkCam> cams = ReolinkCam.load(this);
        if (idx >= cams.size()) return;
        final ReolinkCam c = cams.get(idx);
        new AlertDialog.Builder(this)
                .setTitle(c.feedName())
                .setItems(new String[]{"View", "Change options", "Change user name / password", "Test", "Remove"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                if (w == 0) ReolinkViewActivity.show(ReolinkSetupActivity.this, c.feedName(), false);
                                else if (w == 1) edit(cams, c);
                                else if (w == 2) login(cams, c);
                                else if (w == 3) test(c);
                                else {
                                    cams.remove(idx);
                                    ReolinkCam.save(ReolinkSetupActivity.this, cams);
                                    ReolinkMonitor.refresh();
                                    render();
                                }
                            }
                        })
                .show();
    }

    private CheckBox check(LinearLayout box, String t, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(t);
        cb.setChecked(on);
        box.addView(cb);
        return cb;
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        return box;
    }

    private void edit(final List<ReolinkCam> cams, final ReolinkCam c) {
        LinearLayout box = box();
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name on the tile");
        name.setText(c.feedName());
        box.addView(name);
        final CheckBox on = check(box, "Show this camera (untick to hide it everywhere)", c.enabled);
        final CheckBox show = check(box, "Bring to front when triggered", c.showOnTrigger);
        final CheckBox link = check(box, "Linked floodlight" + (c.hasFlood ? "" : " (no floodlight found on this lens)"), c.linkedFlood);
        TextView at = new TextView(this);
        at.setText("On the phone or tablet that watches the cameras (e.g. one left on mains power):");
        box.addView(at);
        final CheckBox alert = check(box, "Send motion alert to channel (a chat message, also over the internet relay)", c.sendAlert);
        final CheckBox say = check(box, "Announce it (the other phones read it aloud)", c.announce);
        final CheckBox pic = check(box, "Attach one picture (small)", c.attachPicture);
        final CheckBox off = check(box, "Tell the channel if this camera goes offline (after 2 minutes) and when it is back", c.offlineAlert);
        TextView st = new TextView(this);
        st.setText("Full-screen view:");
        box.addView(st);
        final android.widget.RadioGroup g = new android.widget.RadioGroup(this);
        final String[] keys = {"sub", "main", "snap"};
        String[] labels = {"Sub stream (plays on most phones)", "Main stream (best, often H.265)", "Pictures only"};
        for (int i = 0; i < keys.length; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setId(200 + i);
            r.setText(labels[i]);
            g.addView(r);
            if (keys[i].equals(c.fullStream)) g.check(200 + i);
        }
        box.addView(g);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(c.feedName())
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        c.name = name.getText().toString().trim();
                        c.enabled = on.isChecked();
                        c.showOnTrigger = show.isChecked();
                        c.linkedFlood = link.isChecked();
                        c.sendAlert = alert.isChecked();
                        c.announce = say.isChecked();
                        c.attachPicture = pic.isChecked();
                        c.offlineAlert = off.isChecked();
                        int id = g.getCheckedRadioButtonId();
                        if (id >= 200 && id < 203) c.fullStream = keys[id - 200];
                        ReolinkCam.save(ReolinkSetupActivity.this, cams);
                        ReolinkMonitor.refresh();
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** New sign-in for this camera: every lens of it takes the change. */
    private void login(final List<ReolinkCam> cams, final ReolinkCam c) {
        LinearLayout box = box();
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setHint("User name");
        u.setText(c.user);
        box.addView(u);
        final EditText p = new EditText(this);
        p.setSingleLine(true);
        p.setHint("Password");
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p.setText(c.pass);
        box.addView(p);
        new AlertDialog.Builder(this)
                .setTitle("Sign-in for " + c.feedName())
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String user = u.getText().toString().trim();
                        for (ReolinkCam x : cams) {
                            if (!x.host.equals(c.host) || x.httpPort != c.httpPort) continue;
                            x.user = user.length() == 0 ? "admin" : user;
                            x.pass = p.getText().toString();
                        }
                        ReolinkCam.save(ReolinkSetupActivity.this, cams);
                        ReolinkMonitor.refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void test(final ReolinkCam c) {
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Testing " + c.feedName())
                .setMessage("Signing in…").setPositiveButton(android.R.string.ok, null).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                if (c.bcOnly) {
                    msg = bcTest(c);
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (dlg.isShowing()) dlg.setMessage(m);
                        }
                    });
                    return;
                }
                ReolinkApi api = ReolinkApi.of(c);
                try {
                    ReolinkApi.DevInfo i = api.devInfo();
                    msg = "Signed in: " + i.model + (i.firmware.length() > 0 ? ", firmware " + i.firmware : "") + ".";
                    byte[] jpg = api.snap(c.channel, 640, 360);
                    msg += "\nPicture: " + (jpg.length / 1024) + " KB.";
                    int s = api.triggerState(c.channel, true, true);
                    msg += "\nTriggers: " + (s < 0 ? "not answered" : s == 0 ? "quiet now" : "triggered now") + ".";
                    Integer led = api.hasFlood(c.channel) ? api.whiteLed(c.channel) : null;
                    msg += "\nFloodlight: " + (led == null ? "none on this lens" : led == 1 ? "on" : "off") + ".";
                } catch (Exception e) {
                    msg = "Problem: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dlg.isShowing()) dlg.setMessage(m);
                    }
                });
            }
        }, "reolink-test").start();
    }

    /**
     * A camera with only Reolink's own port 9000 (no HTTP or RTSP, e.g. an E1): added by name and address,
     * watched for presence so "Tell the channel if this camera goes offline" works.
     */
    private void addBcOnly() {
        LinearLayout box = box();
        TextView t = new TextView(this);
        t.setText("For cameras that answer only on Reolink's own port 9000 (the scan shows \"ports 9000\", e.g. the E1). "
                + "The app signs in over that port for pictures, motion and offline alerts (no live video or sound).");
        box.addView(t);
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name (e.g. Nursery)");
        box.addView(name);
        final EditText addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setHint("Camera address");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addr);
        final EditText user = new EditText(this);
        user.setSingleLine(true);
        user.setHint("Camera user name");
        user.setText(ReolinkCam.defaultUser(this));
        box.addView(user);
        final EditText pass = new EditText(this);
        pass.setSingleLine(true);
        pass.setHint("Camera password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(ReolinkCam.defaultPass(this));
        box.addView(pass);
        final CheckBox off = check(box, "Tell the channel if this camera goes offline (after 2 minutes) and when it is back", true);
        final CheckBox say = check(box, "Announce it (the other phones read it aloud)", false);
        new AlertDialog.Builder(this)
                .setTitle("Add a port 9000 camera")
                .setView(box)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String a = addr.getText().toString().trim().replaceFirst("^[a-z]+://", "");
                        int port = 9000;
                        int colon = a.lastIndexOf(':');
                        if (colon > 0) {
                            try {
                                port = Integer.parseInt(a.substring(colon + 1).replace("/", ""));
                            } catch (NumberFormatException e) {
                                port = 9000;
                            }
                            a = a.substring(0, colon);
                        }
                        // Letters, digits, dots and dashes only: a stray character (e.g. from the keyboard) would
                        // make the camera look offline for ever.
                        if (!a.matches("[A-Za-z0-9.\\-]+")) {
                            Toast.makeText(ReolinkSetupActivity.this, "That address has a character that cannot be in an address: please check it",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        ReolinkCam c = new ReolinkCam();
                        c.bcOnly = true;
                        c.host = a;
                        c.bcPort = port;
                        c.httpPort = port; // keeps the list key unique per camera
                        c.name = name.getText().toString().trim();
                        if (c.name.length() == 0) c.name = "Camera " + a;
                        c.model = "port 9000";
                        c.fullStream = "snap";
                        c.showOnTrigger = false;
                        c.offlineAlert = off.isChecked();
                        c.announce = say.isChecked();
                        c.user = user.getText().toString().trim().length() == 0 ? "admin" : user.getText().toString().trim();
                        c.pass = pass.getText().toString();
                        List<ReolinkCam> cams = ReolinkCam.load(ReolinkSetupActivity.this);
                        cams.add(c);
                        ReolinkCam.save(ReolinkSetupActivity.this, cams);
                        ReolinkMonitor.refresh();
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Test a port 9000 camera: sign in, device info, one picture. Nothing is switched on. */
    static String bcTest(ReolinkCam c) {
        org.communitycomms.app.camera.BcClient b = new org.communitycomms.app.camera.BcClient();
        try {
            b.connect(c.host, c.bcPort, 5000);
            b.login(c.user, c.pass, 10000);
            String di = b.deviceInfo(8000);
            String model = org.communitycomms.app.camera.BcClient.tag(di, "type");
            String fw = org.communitycomms.app.camera.BcClient.tag(di, "firmwareVersion");
            byte[] jpg = b.snapshot(c.channel, "sub", 15000);
            b.logout();
            return "Signed in (port 9000): " + (model == null ? "camera" : model) + (fw == null ? "" : ", firmware " + fw)
                    + ".\nPicture: " + (jpg.length / 1024) + " KB.";
        } catch (Exception e) {
            return "Problem: " + e.getMessage();
        } finally {
            b.close();
        }
    }

    private void settings() {
        LinearLayout box = box();
        TextView t = new TextView(this);
        t.setText("Sign-in offered for new cameras (each camera keeps its own):");
        box.addView(t);
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setHint("User name (admin)");
        u.setText(ReolinkCam.defaultUser(this));
        box.addView(u);
        final EditText p = new EditText(this);
        p.setSingleLine(true);
        p.setHint("Password");
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p.setText(ReolinkCam.defaultPass(this));
        box.addView(p);
        TextView ft = new TextView(this);
        ft.setText("Linked floodlights stay on for (seconds after the last trigger):");
        box.addView(ft);
        final EditText flood = new EditText(this);
        flood.setInputType(InputType.TYPE_CLASS_NUMBER);
        flood.setText(String.valueOf(ReolinkCam.floodSeconds(this)));
        box.addView(flood);
        TextView qt = new TextView(this);
        qt.setText("Hold time: a triggered camera stays in front this many seconds after its movement ends (also the shortest gap between two channel alerts from one camera):");
        box.addView(qt);
        final EditText quiet = new EditText(this);
        quiet.setInputType(InputType.TYPE_CLASS_NUMBER);
        quiet.setText(String.valueOf(ReolinkCam.quietSeconds(this)));
        box.addView(quiet);
        final CheckBox motion = check(box, "Plain motion counts as a trigger", ReolinkCam.useMotion(this));
        final CheckBox ai = check(box, "People, vehicles and animals (AI detection) count", ReolinkCam.useAi(this));
        final CheckBox bg = check(box, "Keep watching in the background for the linked floodlights and channel alerts (while the radio runs; "
                + "uses a little battery and Wi-Fi)", ReolinkCam.backgroundWatch(this));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Reolink settings")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        ReolinkCam.setDefaults(ReolinkSetupActivity.this, u.getText().toString().trim(), p.getText().toString());
                        ReolinkCam.setInt(ReolinkSetupActivity.this, "floodSecs", num(flood, 60, 5, 3600));
                        ReolinkCam.setInt(ReolinkSetupActivity.this, "holdSecs", num(quiet, 60, 5, 3600));
                        ReolinkCam.setBool(ReolinkSetupActivity.this, "useMotion", motion.isChecked());
                        ReolinkCam.setBool(ReolinkSetupActivity.this, "useAi", ai.isChecked());
                        ReolinkCam.setBool(ReolinkSetupActivity.this, "bgWatch", bg.isChecked());
                        ReolinkMonitor.refresh();
                        Toast.makeText(ReolinkSetupActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int num(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException x) {
            return def;
        }
    }
}
