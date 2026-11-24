package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.TileMapView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Mesh testing: Play audio to channel, Echo Test, Map mesh and Mapping walk (each shown when its feature is ticked). */
public final class MeshTestingActivity extends Activity {
    private static final int REQ_FILE = 71;
    private final Handler ui = new Handler();
    private float d;
    private Prefs prefs;
    private TextView channelLine;
    // play audio
    private EditText urlEdit, autoEdit;
    private TextView filesText, audioStatus;
    private Button audioBtn;
    // echo
    private EditText echoName, echoMax, echoCool;
    private TextView echoStatus;
    private Button echoBtn;
    // map mesh
    private TileMapView map;
    private org.communitycomms.app.geo.OnlineTilePrompt tiles;
    private TextView fixedText, countText, nodeText;
    private Spinner nameFilter, timeFilter;
    private CheckBox hDirect, hTwo, hThree, hUnknown, bySignal;
    private EditText hostEdit;
    private Button nodeBtn;
    private int shownVersion = -1;
    private String pendingNodePass;
    // walk
    private Spinner walkTarget, walkNode;
    private EditText walkEvery;
    private TextView walkBig, walkDetails;
    private Button walkBtn;
    private CheckBox simBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Mesh testing");
        d = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * d);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
        channelLine = text(root, "", 14);
        TextView quiet = text(root, "Testing tools for the mesh. Play audio works on private channels only; the others can be allowed on public ones (with a warning). "
                + "Nothing plays out loud on this phone unless you ask for it.", 12);
        quiet.setTextColor(0xffb0bec5);
        boolean any = false;
        if (prefs.menuOn(MeshTest.K_AUDIO)) {
            buildAudio(root);
            any = true;
        }
        if (prefs.menuOn(MeshTest.K_ECHO)) {
            buildEcho(root);
            any = true;
        }
        if (prefs.menuOn(MeshTest.K_MAP)) {
            buildMap(root);
            buildWalk(root);
            any = true;
        }
        if (!any) text(root, "\nNo mesh testing tool is ticked. Turn them on in Settings > Advanced and Region features > Mesh testing.", 15);
    }

    // ---- small builders ----

    private TextView text(LinearLayout into, String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        into.addView(t);
        return t;
    }

    private void heading(LinearLayout into, String s) {
        TextView h = text(into, "\n" + s, 19);
        h.setTextColor(0xfff0c75e);
    }

    private Button button(LinearLayout into, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setOnClickListener(l);
        into.addView(b);
        return b;
    }

    private LinearLayout row(LinearLayout into) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        into.addView(r);
        return r;
    }

    private Button rowButton(LinearLayout r, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setOnClickListener(l);
        r.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private CheckBox check(LinearLayout into, String s, final String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(MeshTest.sp().getBoolean(key, def));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                MeshTest.sp().edit().putBoolean(key, on).commit();
                shownVersion = -1;
            }
        });
        into.addView(c);
        return c;
    }

    private EditText number(LinearLayout into, String label, int value) {
        LinearLayout r = row(into);
        TextView t = new TextView(this);
        t.setText(label);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        r.addView(e, new LinearLayout.LayoutParams((int) (90 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** "Allow on public channels": ticking it shows the warning and needs a yes. */
    private void publicTick(LinearLayout into, final String tool, final String what) {
        final CheckBox c = new CheckBox(this);
        c.setText("Allow on public channels (Public, cloud group, OM/TAK)");
        c.setChecked(MeshTest.allowPublic(tool));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!on) {
                    MeshTest.setAllowPublic(tool, false);
                    return;
                }
                new AlertDialog.Builder(MeshTestingActivity.this).setTitle("Allow on public channels?")
                        .setMessage(what + " on a public channel: EVERYONE on that channel will hear it, and it uses airtime for "
                                + "all users of the network. Only allow this when the channel's users expect a test.")
                        .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                MeshTest.setAllowPublic(tool, true);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                c.setChecked(false);
                            }
                        }).setCancelable(false).show();
            }
        });
        into.addView(c);
    }

    // ---- Play audio to channel ----

    private void buildAudio(LinearLayout root) {
        heading(root, "Play audio to channel");
        text(root, "Sends a radio stream, audio files or what another app plays as live voice from this phone, as if PTT were held, on the channel on air "
                + "(normal encrypted voice; standard Opus on the OM/TAK talk group).", 13);
        RadioGroup g = new RadioGroup(this);
        final RadioButton rStream = new RadioButton(this), rFiles = new RadioButton(this);
        rStream.setText("Internet radio stream (MP3 / AAC, Icecast, Shoutcast, .m3u, .pls)");
        rFiles.setText("Audio files on this phone (a playlist)");
        final RadioButton rSpeaker = new RadioButton(this);
        rSpeaker.setText(CaptureService.supported() ? "Phone speaker output (whatever another app plays: music, radio app, browser)"
                : "Phone speaker output (needs Android 10 or newer)");
        rSpeaker.setEnabled(CaptureService.supported());
        rStream.setId(0x7f0a7101); // fixed ids: View.generateViewId needs Android 4.2
        rFiles.setId(0x7f0a7102);
        rSpeaker.setId(0x7f0a7103);
        g.addView(rStream);
        g.addView(rFiles);
        g.addView(rSpeaker);
        (TestAudio.useSpeaker() ? rSpeaker : TestAudio.useFiles() ? rFiles : rStream).setChecked(true);
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int id) {
                MeshTest.sp().edit().putString("ta_src", rSpeaker.isChecked() ? "speaker" : rFiles.isChecked() ? "files" : "url").commit();
                applySource();
            }
        });
        root.addView(g);
        // One section per source: only the chosen one shows.
        secStream = section(root);
        secFiles = section(root);
        secSpeaker = section(root);
        TextView sp = text(secSpeaker, "Start the other app playing (music, a radio app, a browser), then tap Start below and "
                + "allow Android's \"start recording or casting\" prompt. A notification shows while it runs.\n"
                + "• This phone still plays it out loud: turn its media volume down if you do not want to hear it.\n"
                + "• Some apps block sharing (protected music, some streaming apps): then only silence goes out, and this "
                + "page says \"No audio captured\".\n• YCC's own sounds are never sent.", 13);
        sp.setTextColor(0xffcfd8dc);
        urlEdit = new EditText(this);
        urlEdit.setHint("http://… stream address");
        urlEdit.setSingleLine(true);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setText(TestAudio.url());
        secStream.addView(urlEdit);
        recentBox = section(secStream);
        showRecent();
        filesList = section(secFiles);
        LinearLayout fr = row(secFiles);
        rowButton(fr, "Add file…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("audio/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Audio file"), REQ_FILE);
                } catch (RuntimeException e) {
                    Toast.makeText(MeshTestingActivity.this, "No file picker on this phone", Toast.LENGTH_SHORT).show();
                }
            }
        });
        rowButton(fr, "Clear list", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (String f : TestAudio.files()) if (f.startsWith(getFilesDir().getPath())) new File(f).delete();
                TestAudio.setFiles(new ArrayList<String>());
                showFiles();
            }
        });
        showFiles();
        check(secFiles, "Loop the playlist", "ta_loop", false);
        TextView common = text(root, "\nFor every source", 14);
        common.setTextColor(0xffa5d6a7);
        final TextView gl = text(root, "", 13);
        SeekBar gain = new SeekBar(this);
        gain.setMax(190);
        gain.setProgress(TestAudio.gainPct() - 10);
        gl.setText("Volume / gain: " + TestAudio.gainPct() + "%");
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                gl.setText("Volume / gain: " + (v + 10) + "%");
                MeshTest.sp().edit().putInt("ta_gain", v + 10).commit();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(gain);
        autoEdit = number(root, "Auto-stop after (minutes, 0 = never)", TestAudio.autoStopMin());
        monitorBox = check(root, "Also play it on this phone (monitor)", "ta_monitor", false);
        // Never on public channels (Public, cloud group, OM/TAK): no tick to allow it.
        audioPublicNote = text(root, "Play audio works on private channels only.", 13);
        audioPublicNote.setTextColor(0xffffab91);
        audioBtn = button(root, "Start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TestAudio.running()) {
                    TestAudio.stop("stopped by you");
                    refresh();
                    return;
                }
                MeshTest.sp().edit().putString("ta_url", urlEdit.getText().toString().trim())
                        .putInt("ta_auto", Math.max(0, num(autoEdit, 10))).commit();
                if (!TestAudio.useFiles() && !TestAudio.useSpeaker()) {
                    rememberUrl(urlEdit.getText().toString().trim());
                    showRecent();
                }
                if (TestAudio.useSpeaker()) {
                    String pre = TestAudio.precheck();
                    if (pre != null) {
                        Toast.makeText(MeshTestingActivity.this, pre, Toast.LENGTH_LONG).show();
                        return;
                    }
                    runNow(new Runnable() {
                        @Override
                        public void run() {
                            askCapture();
                        }
                    });
                    return;
                }
                runNow(new Runnable() {
                    @Override
                    public void run() {
                        String err = TestAudio.start(MeshTestingActivity.this);
                        if (err != null) Toast.makeText(MeshTestingActivity.this, err, Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        });
        audioStatus = text(root, "", 13);
        applySource();
    }

    private TextView audioPublicNote;

    /** Play audio has no public-channel option: TestAudio refuses public channels itself. */
    private static void runNow(Runnable r) {
        r.run();
    }
    private LinearLayout secStream, secFiles, secSpeaker, recentBox, filesList;
    private CheckBox monitorBox;

    private LinearLayout section(LinearLayout into) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        into.addView(l);
        return l;
    }

    /** Only the chosen source's controls show; the monitor makes no sense for phone audio (it already plays). */
    private void applySource() {
        if (secStream == null) return;
        boolean speaker = TestAudio.useSpeaker(), files = !speaker && TestAudio.useFiles();
        secStream.setVisibility(!speaker && !files ? View.VISIBLE : View.GONE);
        secFiles.setVisibility(files ? View.VISIBLE : View.GONE);
        secSpeaker.setVisibility(speaker ? View.VISIBLE : View.GONE);
        monitorBox.setVisibility(speaker ? View.GONE : View.VISIBLE);
        refresh();
    }

    // ---- stream: recent addresses ----

    private List<String> recentUrls() {
        List<String> out = new ArrayList<String>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(MeshTest.sp().getString("ta_recent", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void rememberUrl(String u) {
        if (u.length() == 0) return;
        List<String> l = recentUrls();
        l.remove(u);
        l.add(0, u);
        while (l.size() > 5) l.remove(l.size() - 1);
        MeshTest.sp().edit().putString("ta_recent", new org.json.JSONArray(l).toString()).commit();
    }

    private void showRecent() {
        recentBox.removeAllViews();
        List<String> l = recentUrls();
        if (l.isEmpty()) return;
        TextView h = text(recentBox, "Recent:", 12);
        h.setTextColor(0xffb0bec5);
        for (final String u : l) {
            TextView t = text(recentBox, "  " + TestAudio.shortUrl(u), 14);
            t.setTextColor(0xff90caf9);
            t.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    urlEdit.setText(u);
                }
            });
        }
    }

    // ---- audio files: the playlist, each with up / down / remove ----

    private void showFiles() {
        filesList.removeAllViews();
        final List<String> f = TestAudio.files();
        if (f.isEmpty()) {
            text(filesList, "No files yet.", 13);
            return;
        }
        for (int i = 0; i < f.size(); i++) {
            final int idx = i;
            LinearLayout r = row(filesList);
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText((i + 1) + ". " + new File(f.get(i)).getName());
            t.setTextSize(14);
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            smallButton(r, "↑", idx > 0, new Runnable() {
                @Override
                public void run() {
                    f.add(idx - 1, f.remove(idx));
                    TestAudio.setFiles(f);
                    showFiles();
                }
            });
            smallButton(r, "↓", idx < f.size() - 1, new Runnable() {
                @Override
                public void run() {
                    f.add(idx + 1, f.remove(idx));
                    TestAudio.setFiles(f);
                    showFiles();
                }
            });
            smallButton(r, "✕", true, new Runnable() {
                @Override
                public void run() {
                    String gone = f.remove(idx);
                    if (gone.startsWith(getFilesDir().getPath())) new File(gone).delete();
                    TestAudio.setFiles(f);
                    showFiles();
                }
            });
        }
    }

    private void smallButton(LinearLayout r, String s, boolean on, final Runnable go) {
        Button b = new Button(this);
        b.setText(s);
        b.setEnabled(on);
        b.setMinWidth(0);
        b.setMinimumWidth((int) (44 * d));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go.run();
            }
        });
        r.addView(b, new LinearLayout.LayoutParams((int) (48 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private static final int REQ_CAPTURE = 72;

    /** Android's own consent prompt for capturing other apps' audio (Android 10+). */
    @android.annotation.TargetApi(29)
    private void askCapture() {
        try {
            android.media.projection.MediaProjectionManager m =
                    (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "This phone cannot share its audio", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res != RESULT_OK || data == null) {
                Toast.makeText(this, "Not allowed: nothing is sent", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent s = new Intent(this, CaptureService.class).putExtra(CaptureService.EXTRA_CODE, res).putExtra(CaptureService.EXTRA_DATA, data);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(s);
            else startService(s);
            return;
        }
        if (req != REQ_FILE || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        Toast.makeText(this, "Copying the file…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // A copy in the app's own folder keeps working after a restart (old Android cannot keep access to picked files).
                String name = u.getLastPathSegment() == null ? "audio" : u.getLastPathSegment().replaceAll("[^A-Za-z0-9._-]", "_");
                if (name.length() > 60) name = name.substring(name.length() - 60);
                File dir = new File(getFilesDir(), "testaudio");
                dir.mkdirs();
                final File out = new File(dir, System.currentTimeMillis() % 100000 + "-" + name);
                try {
                    InputStream in = getContentResolver().openInputStream(u);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    in.close();
                    List<String> l = TestAudio.files();
                    l.add(out.getPath());
                    TestAudio.setFiles(l);
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MeshTestingActivity.this, "Could not copy the file", Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showFiles();
                    }
                });
            }
        }).start();
    }

    // ---- Echo Test ----

    private void buildEcho(LinearLayout root) {
        heading(root, "Echo Test");
        text(root, "This phone also shows as a contact. When someone on the channel talks to it, it posts a link report (read aloud "
                + "on their phone, saying how the test arrived: direct, via mesh hops or via a relay) and plays their test back "
                + "exactly as it arrived, gaps and all. Replies go back the same way.", 13);
        LinearLayout r = row(root);
        TextView l = new TextView(this);
        l.setText("Callsign ");
        r.addView(l);
        echoName = new EditText(this);
        echoName.setSingleLine(true);
        echoName.setText(EchoTest.name());
        r.addView(echoName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        check(root, "Answer relayed and remote tests (relays, internet relay, cloud group, channel link). Untick for direct-only mesh tests",
                "echo_relayed", true);
        check(root, "Also answer chat messages saying \"echo\" or \"@" + EchoTest.name() + "\"", "echo_chat", true);
        echoMax = number(root, "Longest recording (seconds)", EchoTest.maxSec());
        echoCool = number(root, "Wait between echoes (seconds)", EchoTest.cooldownSec());
        check(root, "Show the Echo Test station on the map (at the fixed station point, else this phone)", "echo_map", false);
        publicTick(root, "echo", "Echo Test");
        echoBtn = button(root, "Start Echo Test", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (EchoTest.running()) {
                    EchoTest.stop();
                    refresh();
                    return;
                }
                saveEcho();
                MeshTest.checkChannel(MeshTestingActivity.this, "echo", "Echo Test", new Runnable() {
                    @Override
                    public void run() {
                        String err = EchoTest.start();
                        if (err != null) Toast.makeText(MeshTestingActivity.this, err, Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        });
        echoStatus = text(root, "", 13);
    }

    private void saveEcho() {
        if (echoName == null) return;
        String n = echoName.getText().toString().trim().toUpperCase(Locale.US);
        MeshTest.sp().edit().putString("echo_name", n.length() == 0 ? "ECHO TEST" : n)
                .putInt("echo_max", Math.max(3, Math.min(120, num(echoMax, 30))))
                .putInt("echo_cool", Math.max(0, Math.min(120, num(echoCool, 5)))).commit();
    }

    // ---- Map mesh ----

    private void buildMap(LinearLayout root) {
        heading(root, "Map mesh (coverage)");
        text(root, "On the FIXED phone: records every position it hears from other phones straight off the network (direct or "
                + "counted mesh hops, never through a relay or the internet), coloured by link quality. Works offline.", 13);
        check(root, "Map mesh on (record positions heard)", "map_on", false);
        final CheckBox fixed = check(root, "This phone is fixed here", "fixed_on", false);
        fixedText = text(root, "", 13);
        LinearLayout r = row(root);
        rowButton(r, "Use my GPS", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RadioService rs = RadioService.instance();
                android.location.Location l = rs == null ? null : MeshWalk.myLocation(rs);
                if (l == null) {
                    Toast.makeText(MeshTestingActivity.this, "No GPS fix yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                MeshMap.setFixedPoint(l.getLatitude(), l.getLongitude());
                fixed.setChecked(true);
                map.center(l.getLatitude(), l.getLongitude(), Math.max(15, map.zoom()));
                shownVersion = -1;
            }
        });
        TextView tip = new TextView(this);
        tip.setText("or long-press the map");
        tip.setTextSize(12);
        r.addView(tip);
        // The fixed node (read-only polling, fixed phone only).
        text(root, "\nThe fixed station's mesh node (OpenMANET, read-only, every 5 s or slower): its neighbours, signal and hops "
                + "for the walkers. Only this phone asks, and only this one node.", 13);
        hostEdit = new EditText(this);
        hostEdit.setSingleLine(true);
        hostEdit.setHint("Node address");
        hostEdit.setText(MeshNode.defaultHost(this));
        root.addView(hostEdit);
        check(root, "Remember the node login on this phone", "node_remember", false);
        nodeBtn = button(root, "Poll my node…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MeshNode.polling()) {
                    MeshNode.stop();
                    refresh();
                    return;
                }
                MeshTest.sp().edit().putString("node_host", hostEdit.getText().toString().trim()).commit();
                askNodeLogin();
            }
        });
        nodeText = text(root, "", 12);
        // The map and its filters.
        map = new TileMapView(this, null);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.addView(map);
        root.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (330 * d)));
        map.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(e.getAction() != MotionEvent.ACTION_UP && e.getAction() != MotionEvent.ACTION_CANCEL);
                return false;
            }
        });
        map.setOnDotTap(new TileMapView.OnDotTap() {
            @Override
            public void onDotTap(TileMapView.Dot dd) {
                WalkUi.dotTapped(MeshTestingActivity.this, dd);
            }
        });
        map.setOnLongPress(new TileMapView.OnLongPress() {
            @Override
            public void onLongPress(final double lat, final double lon) {
                new AlertDialog.Builder(MeshTestingActivity.this).setTitle("Fixed station here?")
                        .setMessage(org.communitycomms.app.geo.Geo.latLon(lat, lon))
                        .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                MeshMap.setFixedPoint(lat, lon);
                                fixed.setChecked(true);
                                shownVersion = -1;
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
        final double[] f = MeshMap.fixedPoint();
        if (f != null) {
            map.center(f[0], f[1], 17); // street level round the fixed station
            map.post(new Runnable() { // again once laid out (a zoom change before layout lands off centre)
                @Override
                public void run() {
                    map.center(f[0], f[1], 17);
                }
            });
        } else map.center(mp.centerLat(), mp.centerLon(), mp.zoom());
        tiles = new org.communitycomms.app.geo.OnlineTilePrompt(this, map, null);
        TextView legend = text(root, "● filled = direct (green good, amber fair, red poor)   ○ blue ring = 2 hops   ○ purple ring = 3+ hops   "
                + "■ square = hops unknown   ⊕ = fixed station. Walk trail: green direct, blue via relay, amber weak, red no link.", 12);
        legend.setTextColor(0xffcfd8dc);
        LinearLayout hr = row(root);
        hDirect = filterBox(hr, "Direct");
        hTwo = filterBox(hr, "2 hops");
        hThree = filterBox(hr, "3+");
        hUnknown = filterBox(hr, "Unknown");
        bySignal = check(root, "Colour by signal strength (needs the fixed node polled) instead of packet loss", "map_by_signal", false);
        LinearLayout fr2 = row(root);
        nameFilter = new Spinner(this);
        fr2.addView(nameFilter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        timeFilter = new Spinner(this);
        ArrayAdapter<String> ta = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"All time", "Last hour", "Last 6 hours", "Last 24 hours"});
        ta.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        timeFilter.setAdapter(ta);
        fr2.addView(timeFilter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        AdapterView.OnItemSelectedListener redraw = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                shownVersion = -1;
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
            }
        };
        nameFilter.setOnItemSelectedListener(redraw);
        timeFilter.setOnItemSelectedListener(redraw);
        countText = text(root, "", 13);
        LinearLayout er = row(root);
        rowButton(er, "Export CSV", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false);
            }
        });
        rowButton(er, "Export GPX", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true);
            }
        });
        rowButton(er, "Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MeshTestingActivity.this).setTitle("Clear all Map mesh points?")
                        .setMessage(MeshMap.all().size() + " points are deleted from this phone. Export them first if you need them.")
                        .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                MeshMap.clear();
                                shownVersion = -1;
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        check(root, "Show the Map mesh layer on the full map", "map_main", true);
        TextView hint = text(root, "Tip: ask the people walking the area to turn on location posts on a timer (or by distance), or to run a "
                + "Mapping walk below, so their positions keep coming in.", 12);
        hint.setTextColor(0xffa5d6a7);
        publicTick(root, "map", "Map mesh recording");
        check(root, "Lab testing only: count packets through Extra peers (e.g. emulators linked by a relay) as straight off the network",
                "labPeers", false);
    }

    private CheckBox filterBox(LinearLayout r, String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(true);
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                shownVersion = -1;
            }
        });
        r.addView(c);
        return c;
    }

    private void askNodeLogin() {
        final String host = hostEdit.getText().toString().trim();
        String remembered = MeshNode.rememberedPass();
        final android.content.SharedPreferences mc = getSharedPreferences("meshctl", MODE_PRIVATE);
        final String user0 = MeshTest.sp().getString("node_user", mc.getString("user", "root"));
        if (remembered == null && mc.contains("savedPass")) remembered = mc.getString("savedPass", null); // Mesh controller's "Remember"
        if (remembered != null) {
            MeshNode.start(host, user0, remembered);
            refresh();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        TextView n = new TextView(this);
        n.setText("The node's login (as on its web page). Only read: nothing is changed on the node. Not saved unless Remember is ticked.");
        box.addView(n);
        final EditText u = new EditText(this);
        u.setSingleLine(true);
        u.setText(user0);
        box.addView(u);
        final EditText pw = new EditText(this);
        pw.setSingleLine(true);
        pw.setHint("Password");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pw);
        new AlertDialog.Builder(this).setTitle("Log in to " + host).setView(box)
                .setPositiveButton("Poll", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String user = u.getText().toString().trim();
                        String pass = pw.getText().toString();
                        android.content.SharedPreferences.Editor ed = MeshTest.sp().edit().putString("node_user", user);
                        if (MeshTest.sp().getBoolean("node_remember", false)) ed.putString("node_pass", pass);
                        ed.commit();
                        MeshNode.start(host, user, pass);
                        refresh();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void export(boolean gpx) {
        try {
            List<MeshMap.Point> l = filtered();
            if (l.isEmpty()) {
                Toast.makeText(this, "No points to export", Toast.LENGTH_SHORT).show();
                return;
            }
            share(MeshMap.export(this, l, gpx, "meshmap"), gpx);
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void share(File f, boolean gpx) {
        Toast.makeText(this, "Saved " + f.getName() + " (Data / files)", Toast.LENGTH_LONG).show();
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(gpx ? "application/gpx+xml" : "text/csv");
        s.putExtra(Intent.EXTRA_STREAM, FilesProvider.received(f));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(s, "Share " + f.getName()));
        } catch (RuntimeException ignored) {
        }
    }

    private int hopsMask() {
        return (hDirect.isChecked() ? MeshMap.H_DIRECT : 0) | (hTwo.isChecked() ? MeshMap.H_TWO : 0)
                | (hThree.isChecked() ? MeshMap.H_THREE : 0) | (hUnknown.isChecked() ? MeshMap.H_UNKNOWN : 0);
    }

    private long since() {
        int i = timeFilter.getSelectedItemPosition();
        long now = System.currentTimeMillis();
        return i == 1 ? now - 3600000L : i == 2 ? now - 6 * 3600000L : i == 3 ? now - 24 * 3600000L : 0;
    }

    private String nameSel() {
        Object o = nameFilter.getSelectedItem();
        return o == null || nameFilter.getSelectedItemPosition() == 0 ? null : o.toString();
    }

    private List<MeshMap.Point> filtered() {
        List<MeshMap.Point> out = new ArrayList<MeshMap.Point>();
        String n = nameSel();
        long s = since();
        int m = hopsMask();
        for (MeshMap.Point p : MeshMap.all()) {
            if (s > 0 && p.t < s) continue;
            if (n != null && !n.equals(p.name)) continue;
            if ((MeshMap.hopsBit(p.hops) & m) == 0) continue;
            out.add(p);
        }
        return out;
    }

    private List<String> knownNames = new ArrayList<String>();

    private void refreshMap() {
        if (map == null) return;
        int v = MeshMap.version() * 31 + MeshWalk.trailVersion();
        if (v == shownVersion) return;
        shownVersion = v;
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (MeshMap.Point p : MeshMap.all()) names.add(p.name);
        List<String> nl = new ArrayList<String>(names);
        if (!nl.equals(knownNames)) {
            knownNames = nl;
            String keep = nameSel();
            List<String> items = new ArrayList<String>();
            items.add("Everyone");
            items.addAll(nl);
            ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            nameFilter.setAdapter(a);
            if (keep != null && items.contains(keep)) nameFilter.setSelection(items.indexOf(keep));
        }
        List<TileMapView.Dot> dots = MeshMap.dots(nameSel(), since(), hopsMask(), bySignal.isChecked());
        dots.addAll(MeshWalk.trailDots());
        map.setDots(dots);
        List<MeshMap.Point> f = filtered();
        int direct = 0, two = 0, three = 0, unk = 0;
        for (MeshMap.Point p : f) {
            int b = MeshMap.hopsBit(p.hops);
            if (b == MeshMap.H_DIRECT) direct++;
            else if (b == MeshMap.H_TWO) two++;
            else if (b == MeshMap.H_THREE) three++;
            else unk++;
        }
        countText.setText(f.size() + " points shown (" + direct + " direct, " + two + " 2 hops, " + three + " 3+, " + unk + " unknown) of "
                + MeshMap.all().size() + " kept (up to " + MeshMap.CAP + ").");
    }

    // ---- Mapping walk ----

    private void buildWalk(LinearLayout root) {
        heading(root, "Mapping walk");
        text(root, "On the MOVING phone: probes the fixed station every few seconds; it answers with what it measured. Shows the link "
                + "live and leaves a coloured trail of the walk.", 13);
        LinearLayout r = row(root);
        TextView l = new TextView(this);
        l.setText("Fixed station ");
        r.addView(l);
        walkTarget = new Spinner(this);
        r.addView(walkTarget, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button rf = new Button(this);
        rf.setAllCaps(false);
        rf.setText("↻");
        rf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillTargets();
                fillNodes();
            }
        });
        r.addView(rf);
        fillTargets();
        walkEvery = number(root, "Probe every (seconds)", MeshWalk.intervalSec());
        LinearLayout nr = row(root);
        TextView nl = new TextView(this);
        nl.setText("My mesh node ");
        nr.addView(nl);
        walkNode = new Spinner(this);
        nr.addView(walkNode, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        walkNode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                MeshTest.sp().edit().putString("walk_node", pos == 0 ? "" : a.getItemAtPosition(pos).toString()).commit();
            }

            @Override
            public void onNothingSelected(AdapterView<?> a) {
            }
        });
        fillNodes();
        TextView nh = text(root, "The node your phone's WiFi is on (the list comes from the fixed station once a walk runs). "
                + "It lets the fixed station tell direct from relayed and report your node's signal.", 12);
        nh.setTextColor(0xffb0bec5);
        check(root, "Vibrate gently when the status changes (no sound)", "walk_vibe", false);
        check(root, "Post the walk summary to the channel chat when the walk stops", "walk_post", false);
        simBox = check(root, "Fake GPS is on: walk a test route (a 150 m circle at walking pace)", "walk_sim", false);
        publicTick(root, "walk", "Mapping walk");
        walkBtn = button(root, "Start walk", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MeshWalk.running()) {
                    MeshWalk.stop();
                    refresh();
                    return;
                }
                MeshTest.sp().edit().putInt("walk_every", Math.max(2, Math.min(60, num(walkEvery, 5)))).commit();
                final Object t = walkTarget.getSelectedItem();
                MeshTest.checkChannel(MeshTestingActivity.this, "walk", "Mapping walk", new Runnable() {
                    @Override
                    public void run() {
                        String err = MeshWalk.start(t == null ? null : t.toString());
                        if (err != null) Toast.makeText(MeshTestingActivity.this, err, Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        });
        walkBig = new TextView(this);
        walkBig.setTextSize(24);
        walkBig.setTextColor(0xffffffff);
        walkBig.setGravity(android.view.Gravity.CENTER);
        walkBig.setPadding((int) (8 * d), (int) (14 * d), (int) (8 * d), (int) (14 * d));
        root.addView(walkBig);
        walkDetails = text(root, "", 14);
        LinearLayout er = row(root);
        rowButton(er, "Walk CSV", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportWalk(false);
            }
        });
        rowButton(er, "Walk GPX", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportWalk(true);
            }
        });
        rowButton(er, "Past walks", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WalkUi.history(MeshTestingActivity.this);
            }
        });
        TextView mh = text(root, "Tip: the walk can also be started, watched and stopped from the full map (menu > Mapping walk); it keeps "
                + "running when you leave either page. Tap a trail dot for its figures.", 12);
        mh.setTextColor(0xffa5d6a7);
    }

    private void exportWalk(boolean gpx) {
        if (MeshWalk.trail().isEmpty()) {
            Toast.makeText(this, "No walk yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            share(MeshWalk.exportTrail(this, gpx), gpx);
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void fillTargets() {
        List<String> names = new ArrayList<String>();
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        if (e != null) {
            for (RadioEngine.Station s : e.stations()) {
                if (s.name.startsWith("via ") || s.name.endsWith("(TAK)")) continue;
                if (!names.contains(s.name)) names.add(s.name);
            }
        }
        String last = MeshWalk.target();
        if (last.length() > 0 && !names.contains(last)) names.add(0, last);
        if (names.isEmpty()) names.add("(nobody heard yet: tap ↻)");
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        walkTarget.setAdapter(a);
        if (last.length() > 0) walkTarget.setSelection(names.indexOf(last));
    }

    private List<String> shownNodes;

    private void fillNodes() {
        List<String> items = new ArrayList<String>();
        items.add("(not set)");
        String cur = MeshWalk.myNode();
        for (String n : MeshWalk.nodeList()) if (!items.contains(n)) items.add(n);
        if (cur.length() > 0 && !items.contains(cur)) items.add(cur);
        if (items.equals(shownNodes)) return;
        shownNodes = items;
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        walkNode.setAdapter(a);
        walkNode.setSelection(cur.length() > 0 ? items.indexOf(cur) : 0);
    }

    // ---- refresh ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        String ch = MeshTest.channelNow();
        channelLine.setText(ch == null ? "Not on a channel." : "On " + ch + (MeshTest.onPublicChannel() ? " (PUBLIC: anyone can hear)" : " (private)"));
        channelLine.setTextColor(ch != null && MeshTest.onPublicChannel() ? 0xffffab91 : 0xffa5d6a7);
        if (audioBtn != null) {
            boolean r = TestAudio.running();
            boolean pub = MeshTest.onPublicChannel();
            String what = TestAudio.useSpeaker() ? "sharing phone audio" : TestAudio.useFiles() ? "playlist" : "stream";
            audioBtn.setText(r ? "Stop " + what : "Start " + what);
            audioBtn.setEnabled(r || !pub);
            audioPublicNote.setVisibility(pub && !r ? View.VISIBLE : View.GONE);
            String np = TestAudio.nowPlaying();
            audioStatus.setText((r ? "Transmitting" + (np.length() > 0 ? ": " + np : "") + "\n" : "") + TestAudio.lastStatus());
        }
        if (echoBtn != null) {
            echoBtn.setText(EchoTest.running() ? "Stop Echo Test" : "Start Echo Test");
            String rep = EchoTest.lastReport();
            echoStatus.setText((EchoTest.running() ? EchoTest.status() : "Off") + (rep.length() > 0 ? "\nLast report: " + rep : ""));
        }
        if (map != null) {
            double[] f = MeshMap.fixedPoint();
            fixedText.setText(f == null ? "No fixed point yet." : "Fixed at " + org.communitycomms.app.geo.Geo.latLon(f[0], f[1])
                    + (MeshMap.recording() ? " · recording" : " · not recording (tick Map mesh on)"));
            nodeBtn.setText(MeshNode.polling() ? "Stop polling " + MeshNode.host() : "Poll my node…");
            StringBuilder nb = new StringBuilder();
            if (MeshNode.polling() || MeshNode.lastError().length() > 0) {
                if (MeshNode.lastError().length() > 0) nb.append(MeshNode.lastError()).append("\n");
                if (MeshNode.lastOkMs() > 0) {
                    nb.append("Node ").append(MeshNode.selfName()).append(", read ").append((System.currentTimeMillis() - MeshNode.lastOkMs()) / 1000).append(" s ago:");
                    for (MeshNode.Info i : MeshNode.all()) {
                        if (i.hops == 0) continue;
                        nb.append("\n  ").append(i.name).append(": ").append(i.neighbour ? "neighbour" : i.hops < 0 ? "hops ?" : i.hops + " hops")
                                .append(i.signalDbm != Integer.MIN_VALUE ? ", " + i.signalDbm + " dBm" : "").append(i.kbps >= 0 ? ", " + i.kbps + " kbit/s" : "");
                    }
                }
            }
            nodeText.setText(nb.toString());
            refreshMap();
        }
        if (walkBtn != null) {
            boolean w = MeshWalk.running();
            walkBtn.setText(w ? "Stop walk" : "Start walk");
            walkBig.setVisibility(w || !MeshWalk.trail().isEmpty() ? View.VISIBLE : View.GONE);
            walkBig.setText(w ? MeshWalk.statusWord() : "Walk stopped");
            walkBig.setBackgroundColor(w ? MeshWalk.statusColour() : 0xff546e7a);
            walkDetails.setText(w ? MeshWalk.details() : "");
            simBox.setVisibility(org.communitycomms.app.geo.FakeGps.enabled(this) ? View.VISIBLE : View.GONE);
            WalkUi.showPendingSummary(this);
            fillNodes();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tiles != null) tiles.resume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        if (tiles != null) tiles.pause();
        saveEcho();
        if (urlEdit != null) MeshTest.sp().edit().putString("ta_url", urlEdit.getText().toString().trim())
                .putInt("ta_auto", Math.max(0, num(autoEdit, 10))).commit();
        if (walkEvery != null) MeshTest.sp().edit().putInt("walk_every", Math.max(2, Math.min(60, num(walkEvery, 5)))).commit();
    }
}
