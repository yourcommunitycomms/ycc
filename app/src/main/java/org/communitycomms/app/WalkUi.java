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
        else if (d.tag instanceof MeshMap.Point) {
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
                    .setNeutralButton("Past walks", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            history(a);
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
        android.widget.TextView t = new android.widget.TextView(a);
        float d = a.getResources().getDisplayMetrics().density;
        t.setPadding((int) (18 * d), (int) (10 * d), (int) (18 * d), 0);
        t.setTextSize(14);
        t.setText(summary);
        t.setTextIsSelectable(true);
        sv.addView(t);
        new AlertDialog.Builder(a).setTitle("Walk summary").setView(sv)
                .setPositiveButton("Share / export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        exportChoice(a, summary, walk);
                    }
                })
                .setNeutralButton("View on map", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        try {
                            if (walk != null) MeshWalk.showOld(MeshWalk.pointsOf(MeshWalk.readWalk(walk)));
                        } catch (Exception ignored) {
                        }
                        if (!(a instanceof MapActivity)) a.startActivity(new Intent(a, MapActivity.class));
                    }
                })
                .setNegativeButton("Close", null).show();
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
                                    File f = MeshWalk.exportWalk(a, pts, w == 2, summary, o == null ? System.currentTimeMillis() : o.optLong("start"));
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
