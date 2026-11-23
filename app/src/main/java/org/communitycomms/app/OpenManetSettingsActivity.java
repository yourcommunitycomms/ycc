package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The OpenMANET / TAK talk group's own settings (Channels > OpenMANET ⋮): talk group and channel presets,
 * broadcast my callsign (on by default), TAK compatible (callsign and chat), share my location with TAK (off by
 * default), OpenMANET nodes in TAK, the channel link to a YCC channel. Same settings as Settings > Channels and
 * Network (one set of preferences, so they stay in step). Changes apply the next time the talk group is joined.
 */
public final class OpenManetSettingsActivity extends Activity {
    private Prefs prefs;
    private SharedPreferences sp;
    private LinearLayout box;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("OM/TAK channel (OpenMANET / TAK)");
        prefs = new Prefs(this);
        sp = getSharedPreferences("settings", MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (14 * d);
        box.setPadding(p, p, p, p);
        sv.addView(box);
        setContentView(sv);
        render();
    }

    private void render() {
        box.removeAllViews();
        note("NOT encrypted · half duplex (one talker at a time). Voice with OpenMANET Comms and TAK Vx; chat and positions with TAK "
                + "(TAK compatible, below). Anyone on the mesh on the same talk group hears it. Changes apply the next time you join the OM/TAK channel.", 0xffffb74d);

        heading("Talk group");
        final TextView now = text("Now: " + CompatVoice.channelLabel(prefs.openManetGroup()));
        LinearLayout r = new LinearLayout(this);
        r.addView(button("Change channel…", new Runnable() {
            @Override
            public void run() {
                pickChannel(now);
            }
        }));
        r.addView(button("Type address:port…", new Runnable() {
            @Override
            public void run() {
                typeGroup(now);
            }
        }));
        box.addView(r);

        heading("What this phone shares");
        check("Broadcast my callsign (TAK Vx beacon and talk group report, so others see your name)", "vxDataOn", true,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!prefs.omCallsign()) VxData.stop();
                    }
                });
        check("TAK compatible: callsign in TAK's contacts, and Public chat with TAK's All Chat Rooms", "atakOn", false, new Runnable() {
            @Override
            public void run() {
                if (sp.getBoolean("atakOn", false)) prefs.burstOffFor("TAK compatibility");
                AtakBridge.apply(OpenManetSettingsActivity.this);
            }
        });
        check("Share my location with TAK (needs TAK compatible; off: TAK lists you without a position)", "atakPosition", false, null);
        check("Show OpenMANET nodes in TAK (the Mesh controller's node list; one phone is enough)", "atakNodes", false, null);
        check("TAK markers on the YCC map (markers and drawings shared in TAK become map points on layer TAK; "
                + "with a channel link towards YCC they go on the linked channel too)", "takMarkers", true, null);
        check("YCC map points in TAK (NOT encrypted: every map point on this phone, from any channel, goes to everyone on the mesh "
                + "with TAK as a marker named \"name (YCC A)\"; edits and removals follow)", "yccPoisToTak", false, new Runnable() {
            @Override
            public void run() {
                AtakBridge.poisChanged();
            }
        });

        heading("Channel link (Advanced)");
        int slot = prefs.linkSlot();
        String[] dirs = {"OpenMANET → channel", "channel → OpenMANET", "both ways"};
        text(slot < 0 ? "Off. Relays voice between the OpenMANET talk group and one of your YCC channels, on this phone."
                : "Linked to " + Prefs.letter(slot) + ": " + dirs[Math.max(0, Math.min(2, prefs.linkDir()))].replace("channel", Prefs.letter(slot))
                + ". Relayed voice is named \"via OpenMANET: callsign\".");
        box.addView(button(slot < 0 ? "Set up a channel link…" : "Change the channel link…", new Runnable() {
            @Override
            public void run() {
                setUpLink();
            }
        }));
        RadioService rs = RadioService.instance();
        String ln = rs == null ? "" : rs.linkNote();
        if (ln.length() > 0) text("Now: " + ln);
    }

    private void pickChannel(final TextView now) {
        final String[] items = new String[CompatVoice.CHANNELS + 1];
        items[0] = "TAK Vx default  (" + CompatVoice.ATAK_VX_DEFAULT + ")";
        for (int n = 1; n <= CompatVoice.CHANNELS; n++) {
            items[n] = "OpenMANET channel " + n + "  (port " + CompatVoice.portFor(n) + ")" + (n <= 5 ? "  · on OpenMANET nodes" : "");
        }
        new AlertDialog.Builder(this).setTitle("Talk group").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int which) {
                String g = which == 0 ? CompatVoice.ATAK_VX_DEFAULT : CompatVoice.ADDRESS + ":" + CompatVoice.portFor(which);
                prefs.setChannel(Prefs.SLOT_OPENMANET, g);
                now.setText("Now: " + CompatVoice.channelLabel(g));
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void typeGroup(final TextView now) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(prefs.openManetGroup());
        new AlertDialog.Builder(this).setTitle("Talk group address:port").setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        String g = e.getText().toString().trim();
                        if (g.length() > 0 && !g.contains(":")) {
                            Toast.makeText(OpenManetSettingsActivity.this, "Give it as address:port, e.g. 239.192.41.1:38801", Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setChannel(Prefs.SLOT_OPENMANET, g);
                        now.setText("Now: " + CompatVoice.channelLabel(prefs.openManetGroup()));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void setUpLink() {
        final int[] slots = {-1, Prefs.SLOT_A, Prefs.SLOT_B, Prefs.SLOT_PUBLIC};
        String[] names = {"Off", "Channel A", "Channel B", "Public"};
        new AlertDialog.Builder(this).setTitle("Link the OpenMANET talk group to").setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dlg, int which) {
                final int slot = slots[which];
                if (slot < 0) {
                    prefs.setLink(-1, 0);
                    render();
                    return;
                }
                if (prefs.channel(slot).length() == 0) {
                    Toast.makeText(OpenManetSettingsActivity.this, "Set that channel's name first", Toast.LENGTH_LONG).show();
                    return;
                }
                final String l = Prefs.letter(slot);
                String[] dirs = {"OpenMANET → " + l + " (TAK and OpenMANET voice heard on " + l + ")",
                        l + " → OpenMANET (" + l + "'s voice goes out NOT encrypted)", "Both ways"};
                new AlertDialog.Builder(OpenManetSettingsActivity.this).setTitle("Which way?").setItems(dirs, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg2, final int dir) {
                        if (dir == RadioService.LINK_TO_YCC) {
                            prefs.setLink(slot, dir);
                            done(l);
                            return;
                        }
                        new AlertDialog.Builder(OpenManetSettingsActivity.this).setTitle("Not encrypted")
                                .setMessage("Voice on " + l + " will be relayed onto the OpenMANET talk group WITHOUT encryption: anyone "
                                        + "on the mesh with OpenMANET Comms, TAK Vx or YCC on that talk group hears it. Only do this "
                                        + "for a channel with nothing private on it.")
                                .setPositiveButton("Link anyway", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d3, int w) {
                                        prefs.setLink(slot, dir);
                                        done(l);
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                }).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void done(String letter) {
        Toast.makeText(this, "Linked to " + letter + ". It starts the next time you join the OM/TAK channel on this phone "
                + "(keep only one phone linking a channel).", Toast.LENGTH_LONG).show();
        render();
    }

    // ---- small helpers ----

    private void heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xfff0c75e);
        t.setTextSize(16);
        t.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        box.addView(t);
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        box.addView(t);
        return t;
    }

    private void note(String s, int colour) {
        TextView t = text(s);
        t.setTextColor(colour);
    }

    private Button button(String s, final Runnable r) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return bt;
    }

    private void check(String label, final String key, boolean def, final Runnable after) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(sp.getBoolean(key, def));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                sp.edit().putBoolean(key, on).commit();
                if (after != null) after.run();
            }
        });
        box.addView(c);
    }
}
