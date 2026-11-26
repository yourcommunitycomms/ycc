package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.Toast;

import org.communitycomms.app.geo.TileMapView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Mesh testing: the Mapping walk dialogs shared by the full map and the Mesh testing page. */
public final class WalkUi {
    private WalkUi() {
    }

    /** Is Mapping walk available (Mesh testing > Map mesh and Mapping walk ticked)? */
    public static boolean available(Activity a) {
        return new Prefs(a).menuOn(MeshTest.K_MAP);
    }

    /** A tapped trail or Map mesh dot: its stats. */
    public static void dotTapped(Activity a, TileMapView.Dot d) {
        String text;
        if (d.tag instanceof MeshWalk.TrailPoint) text = ((MeshWalk.TrailPoint) d.tag).details();
        else if (d.tag instanceof MeshWalk.Flag) {
            new AlertDialog.Builder(a).setTitle("Flag").setMessage(((MeshWalk.Flag) d.tag).details()).setPositiveButton("OK", null).show();
            return;
        } else if (d.tag instanceof MeshMap.Point) {
            MeshMap.Point p = (MeshMap.Point) d.tag;
            text = p.name + "\n" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(p.t)) + "\n" + MeshMap.describe(p);
        } else return;
        new AlertDialog.Builder(a).setTitle("Point").setMessage(text).setPositiveButton("OK", null).show();
    }

    /** From the map menu: start a walk (pick the fixed station), or the running walk's status with Stop. */
    public static void fromMap(final Activity a) {
        if (MeshWalk.running()) {
            new AlertDialog.Builder(a).setTitle("Mapping walk").setMessage(MeshWalk.details())
                    .setPositiveButton("Stop walk", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            MeshWalk.stop();
                            showPendingSummary(a);
                        }
                    })
                    .setNeutralButton("Flag this spot…", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            flagDialog(a);
                        }
                    })
                    .setNegativeButton("Keep walking", null).show();
            return;
        }
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        final List<String> names = new ArrayList<String>();
        if (e != null) {
            for (RadioEngine.Station s : e.stations()) {
                if (s.name.startsWith("via ") || s.name.endsWith("(TAK)") || names.contains(s.name)) continue;
                names.add(s.name);
            }
        }
        String last = MeshWalk.target();
        if (last.length() > 0 && !names.contains(last)) names.add(0, last);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Mapping walk: pick the fixed station");
        if (names.isEmpty()) {
            b.setMessage("Nobody heard on this channel yet. Join the channel the fixed station is on, then try again.");
        } else {
            b.setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, final int w) {
                    MeshTest.fakeGpsGate(a, new Runnable() {
                        @Override
                        public void run() {
                    MeshTest.checkChannel(a, "walk", "Mapping walk", new Runnable() {
                        @Override
                        public void run() {
                            loginThen(a, new Runnable() {
                                @Override
                                public void run() {
                                    String err = MeshWalk.start(names.get(w));
                                    Toast.makeText(a, err != null ? err : "Walk started: probing " + names.get(w) + " every " + MeshWalk.intervalSec() + " s",
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                        }
                    }, null);
                }
            });
        }
        b.setNeutralButton("Past walks", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                history(a);
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** The live row shown while walking: the 30 s graph and a Flag button beside it. */
    public static android.widget.LinearLayout liveRow(final Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xe0102027);
        row.addView(new WalkGraph(a), new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Button f = new android.widget.Button(a);
        f.setAllCaps(false);
        f.setText("⚑ Flag");
        f.setMinWidth((int) (72 * d));
        f.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                flagDialog(a);
            }
        });
        row.addView(f, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setVisibility(android.view.View.GONE);
        return row;
    }

    /** While walking: flag where I am now (taken when this opens, so typing while walking does not move it), with a comment. */
    public static void flagDialog(final Activity a) {
        final double[] here = MeshWalk.hereNow();
        if (here == null) {
            Toast.makeText(a, "No GPS position yet: can't flag this spot", Toast.LENGTH_LONG).show();
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int) (18 * d), (int) (8 * d), (int) (18 * d), 0);
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(String.format(Locale.US, "Flag at %.5f, %.5f (where you are now). A comment is optional.", here[0], here[1]));
        box.addView(t);
        final android.widget.EditText e = new android.widget.EditText(a);
        e.setHint("Comment, e.g. drops behind the shed");
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(e);
        new AlertDialog.Builder(a).setTitle("Flag this spot").setView(box)
                .setPositiveButton("Flag", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MeshWalk.addFlag(here[0], here[1], e.getText().toString());
                        Toast.makeText(a, "Flagged (" + MeshWalk.flags().size() + " on this walk)", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Before a walk: the nodes' login once (to read my own node), unless it is held, not wanted, or OpenMANET is off. */
    static void loginThen(Activity a, Runnable start) {
        if (!MeshRadio.walkLocal() || MeshRadio.hasLogin() || !MeshRadio.available(a)) {
            start.run();
            return;
        }
        MeshRadio.askLogin(a, true, start);
    }

    /** After Stop: the summary sheet, once. */
    public static void showPendingSummary(Activity a) {
        String s = MeshWalk.takeSummary();
        if (s == null) return;
        List<File> w = MeshWalk.walks();
        summaryDialog(a, s, w.isEmpty() ? null : w.get(0));
    }

    static void summaryDialog(final Activity a, final String summary, final File walk) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView t = new android.widget.TextView(a);
        float d = a.getResources().getDisplayMetrics().density;
        box.setPadding((int) (18 * d), (int) (10 * d), (int) (18 * d), 0);
        t.setTextSize(14);
        t.setText(summary);
        t.setTextIsSelectable(true);
        box.addView(t);
        String old = "";
        try {
            if (walk != null) old = MeshWalk.readWalk(walk).optString("notes", "");
        } catch (Exception ignored) {
        }
        android.widget.TextView nh = new android.widget.TextView(a);
        nh.setText("Notes on this walk (saved with it, included when shared):");
        nh.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(nh);
        final android.widget.EditText notes = new android.widget.EditText(a);
        notes.setHint("e.g. antenna on the roof at 6 m, raining, walked the road both ways");
        notes.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(2);
        notes.setText(old);
        notes.setEnabled(walk != null);
        box.addView(notes);
        final android.os.Handler saver = new android.os.Handler();
        final Runnable save = new Runnable() {
            @Override
            public void run() {
                MeshWalk.setNotes(walk, notes.getText().toString().trim());
            }
        };
        notes.addTextChangedListener(new android.text.TextWatcher() { // saved as you type (a second after you stop)
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                saver.removeCallbacks(save);
                saver.postDelayed(save, 1200);
            }
        });
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Walk summary").setView(sv)
                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MeshWalk.setNotes(walk, notes.getText().toString().trim());
                        exportChoice(a, MeshWalk.fullText(summary, null, notes.getText().toString()), walk);
                    }
                })
                .setNeutralButton("On map", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MeshWalk.setNotes(walk, notes.getText().toString().trim());
                        try {
                            if (walk != null) {
                                org.json.JSONObject o = MeshWalk.readWalk(walk);
                                MeshWalk.showOld(MeshWalk.pointsOf(o), MeshWalk.flagsOf(o));
                            }
                        } catch (Exception ignored) {
                        }
                        if (!(a instanceof MapActivity)) a.startActivity(new Intent(a, MapActivity.class));
                    }
                })
                .setNegativeButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MeshWalk.setNotes(walk, notes.getText().toString().trim());
                    }
                }).create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() { // tapped outside or Back: keep what was typed
            @Override
            public void onDismiss(DialogInterface di) {
                MeshWalk.setNotes(walk, notes.getText().toString().trim());
            }
        });
        dlg.show();
    }

    private static void exportChoice(final Activity a, final String summary, final File walk) {
        new AlertDialog.Builder(a).setTitle("Share the walk")
                .setItems(new String[]{"Summary as text", "Walk as CSV (with the summary)", "Walk as GPX (with the summary)"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                if (w == 0) {
                                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary);
                                    a.startActivity(Intent.createChooser(s, "Share the walk summary"));
                                    return;
                                }
                                try {
                                    org.json.JSONObject o = walk == null ? null : MeshWalk.readWalk(walk);
                                    List<MeshWalk.TrailPoint> pts = o == null ? MeshWalk.trail() : MeshWalk.pointsOf(o);
                                    List<MeshWalk.Flag> fl = o == null ? MeshWalk.flags() : MeshWalk.flagsOf(o);
                                    File f = MeshWalk.exportWalk(a, pts, fl, w == 2, summary, o == null ? System.currentTimeMillis() : o.optLong("start"));
                                    Toast.makeText(a, "Saved " + f.getName() + " (Data / files)", Toast.LENGTH_LONG).show();
                                    Intent s = new Intent(Intent.ACTION_SEND).setType(w == 2 ? "application/gpx+xml" : "text/csv")
                                            .putExtra(Intent.EXTRA_STREAM, FilesProvider.received(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    a.startActivity(Intent.createChooser(s, "Share " + f.getName()));
                                } catch (Exception e) {
                                    Toast.makeText(a, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Past walks, newest first: tap one for its summary (and View on map / export). */
    public static void history(final Activity a) {
        final List<File> ws = MeshWalk.walks();
        if (ws.isEmpty()) {
            Toast.makeText(a, "No walks saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<String>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File w : ws) {
            try {
                org.json.JSONObject o = MeshWalk.readWalk(w);
                long s = o.optLong("start"), e = o.optLong("end");
                labels.add(f.format(new Date(s)) + " · " + MeshWalk.hm(e - s) + " · to " + o.optString("target"));
            } catch (Exception ex) {
                labels.add(w.getName());
            }
        }
        new AlertDialog.Builder(a).setTitle("Past walks").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int i) {
                try {
                    summaryDialog(a, MeshWalk.readWalk(ws.get(i)).optString("summary"), ws.get(i));
                } catch (Exception e) {
                    Toast.makeText(a, "Could not open that walk", Toast.LENGTH_SHORT).show();
                }
            }
        }).setNeutralButton("Hide old walk on the map", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int i) {
                MeshWalk.showOld(null);
            }
        }).setNegativeButton("Close", null).show();
    }
}
