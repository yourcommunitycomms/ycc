package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.FakeGps;
import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.TileMapView;
import org.communitycomms.app.geo.Track;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Path tracking screens: start (speed, steps, live share), the bar over the map, stats, saved tracks, requests. */
final class TrackUi {
    private TrackUi() {
    }

    static final int REQ_LOCATION = 4720, REQ_STEPS = 4721;
    /** A saved track shown on the map (My tracks > Show on map), or null. */
    static volatile Track viewed;

    // ---- starting ----

    /** Tools > Start tracking my path. answerTo: a commander who asked (shared with them), or null. */
    static void start(final Activity a, final String answerTo) {
        if (TrackService.running()) {
            Toast.makeText(a, "Already tracking your path", Toast.LENGTH_SHORT).show();
            return;
        }
        if (FakeGps.enabled(a)) {
            // Never track a made-up position: ask to turn Fake GPS off first.
            new AlertDialog.Builder(a).setTitle("Fake GPS is on")
                    .setMessage("Path tracking needs your real position, so it will not start while Fake GPS is on"
                            + " (your path would be made up). Turn Fake GPS off and start tracking?")
                    .setPositiveButton("Turn Fake GPS off", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            FakeGps.set(a, false, FakeGps.centre(a));
                            if (a instanceof MapActivity) ((MapActivity) a).fakeChanged();
                            start(a, answerTo);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(a, "Allow location, then tap Start tracking again", Toast.LENGTH_LONG).show();
            a.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        final android.content.SharedPreferences sp = a.getSharedPreferences("settings", Activity.MODE_PRIVATE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView h = new TextView(a);
        h.setText("How often to take a position (faster = more detail, more battery):");
        box.addView(h);
        final RadioGroup modes = new RadioGroup(a);
        int last = sp.getInt("trackMode", Track.MODE_WALK);
        for (int i = 0; i < Track.MODE_NAMES.length; i++) {
            RadioButton r = new RadioButton(a);
            r.setId(1000 + i);
            r.setText(Track.MODE_NAMES[i]);
            modes.addView(r);
        }
        modes.check(1000 + Math.max(0, Math.min(Track.MODE_NAMES.length - 1, last)));
        box.addView(modes);
        final CheckBox steps = new CheckBox(a);
        boolean chip = TrackService.hasStepCounter(a);
        steps.setText(chip ? "Count steps" + (Build.VERSION.SDK_INT >= 29 ? " (asks for Physical activity permission)" : "")
                : "Count steps (this phone has no step counter)");
        steps.setEnabled(chip);
        steps.setChecked(chip && TrackService.stepsWanted(a));
        box.addView(steps);
        TextView sh = new TextView(a);
        sh.setText("Share my path live with (private channel only):");
        sh.setPadding(0, pad / 2, 0, 0);
        box.addView(sh);
        final List<String> names = new ArrayList<String>();
        names.add("Nobody");
        for (Contacts.Contact c : Contacts.get(a).all()) names.add(c.name);
        if (answerTo != null && !names.contains(answerTo)) names.add(answerTo);
        final Spinner to = new Spinner(a);
        to.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
        String lastTo = answerTo != null ? answerTo : sp.getString("trackShareTo", "");
        int sel = names.indexOf(lastTo);
        to.setSelection(sel > 0 ? sel : 0);
        box.addView(to);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle(answerTo != null ? "Start tracking (asked by " + answerTo + ")" : "Start tracking my path")
                .setView(sv)
                .setPositiveButton("Start", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int mode = Math.max(0, modes.getCheckedRadioButtonId() - 1000);
                        String who = to.getSelectedItemPosition() > 0 ? names.get(to.getSelectedItemPosition()) : "";
                        sp.edit().putInt("trackMode", mode).putBoolean("trackSteps", steps.isChecked())
                                .putString("trackShareTo", answerTo != null ? sp.getString("trackShareTo", "") : who).commit();
                        Set<String> share = new LinkedHashSet<String>();
                        if (who.length() > 0) share.add(who);
                        if (answerTo != null) share.add(answerTo);
                        if (steps.isChecked() && !TrackService.stepsAllowed(a) && Build.VERSION.SDK_INT >= 29) {
                            a.requestPermissions(new String[]{android.Manifest.permission.ACTIVITY_RECOGNITION}, REQ_STEPS);
                        }
                        TrackService.start(a, mode, share);
                        if (!share.isEmpty() && !TrackShare.canSend(a)) {
                            Toast.makeText(a, "Tracking. Your path goes to " + join(share)
                                    + " while you are on air on a private channel", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(a, "Tracking your path (" + Track.MODE_WORDS[mode] + ")", Toast.LENGTH_SHORT).show();
                        }
                        if (a instanceof MapActivity) ((MapActivity) a).trackChanged();
                    }
                })
                .setNegativeButton(android.R.string.cancel, answerTo == null ? null : new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        TrackShare.reply(a, answerTo, 2);
                    }
                }).show();
    }

    /** The Physical activity answer (from the activity's onRequestPermissionsResult). */
    static void onPermission(int req, int[] results) {
        if (req == REQ_STEPS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            TrackService.stepsPermissionGranted();
        }
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String n : s) b.append(b.length() > 0 ? ", " : "").append(n);
        return b.toString();
    }

    // ---- someone asked for this phone's path ----

    static void answerRequest(final Activity a, final String from) {
        if (TrackService.running()) {
            new AlertDialog.Builder(a).setTitle(from + " asks to see your path")
                    .setMessage("Send your path so far, and keep sending it live while you track?")
                    .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            TrackService.addShare(from);
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            TrackShare.reply(a, from, 1);
                        }
                    }).show();
        } else {
            new AlertDialog.Builder(a).setTitle(from + " asks to see your path")
                    .setMessage("You are not tracking your path. Start tracking now and share it with " + from + "?")
                    .setPositiveButton("Start tracking", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            start(a, from);
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            TrackShare.reply(a, from, 2);
                        }
                    }).show();
        }
    }

    // ---- controls ----

    static void pauseResume(Activity a) {
        Track t = TrackService.current();
        if (t == null) return;
        TrackService.command(a, t.paused() ? TrackService.ACTION_RESUME : TrackService.ACTION_PAUSE);
    }

    static void confirmStop(final Activity a) {
        if (!TrackService.running()) return;
        new AlertDialog.Builder(a).setTitle("Stop tracking?")
                .setMessage("The path is kept on this phone (Tools > My tracks).")
                .setPositiveButton("Stop", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Track t = TrackService.current();
                        TrackService.command(a, TrackService.ACTION_STOP);
                        if (a instanceof MapActivity) ((MapActivity) a).trackChanged();
                        if (t != null) new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                stats(a, t);
                            }
                        }, 300);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    static String statsText(Track t) {
        long now = System.currentTimeMillis();
        Track.Stats s = t.stats(now);
        StringBuilder b = new StringBuilder();
        b.append(new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(t.start)))
                .append(" · ").append(Track.MODE_WORDS[t.mode]).append('\n');
        b.append(t.running() ? (t.paused() ? "Paused" : "Tracking now") : "Stopped").append("\n\n");
        b.append("Total time   ").append(Track.hms(s.total)).append('\n');
        b.append("Moving time  ").append(Track.hms(s.moving)).append('\n');
        b.append("Breaks       ").append(s.breakCount).append(", ").append(Track.hms(s.breaks));
        if (s.breakCount > 0) b.append(" (longest ").append(Track.hms(s.longestBreak)).append(')');
        b.append('\n');
        b.append("Distance     ").append(Geo.distance(s.distanceM)).append('\n');
        b.append("Average      ").append(Track.speed(s.distanceM, s.moving, t.mode)).append(" (moving)\n");
        if (s.steps >= 0) b.append("Steps        ").append(s.steps).append('\n');
        b.append('\n');
        if (s.lapCount == 0) {
            b.append(t.running() ? "Laps: none yet (a lap counts each time you pass your start point again)\n"
                    : "Laps: none (the start point was not passed again)\n");
        } else {
            b.append("Laps done    ").append(s.lapCount).append('\n');
            if (t.running()) b.append("This lap     ").append(Track.hms(s.currentLap)).append(" (lap ").append(s.lapCount + 1).append(")\n");
            b.append("Last lap     ").append(Track.hms(s.lastLap)).append('\n');
            b.append("Fastest lap  ").append(Track.hms(s.fastestLap)).append(" (lap ").append(s.fastestLapNo).append(")\n");
            b.append("Slowest lap  ").append(Track.hms(s.slowestLap)).append('\n');
            long sum = 0;
            for (long l : s.lapTimes) sum += l;
            b.append("Average lap  ").append(Track.hms(sum / s.lapCount)).append("\n\n");
            for (int i = 0; i < s.lapTimes.size(); i++) {
                b.append(String.format(Locale.US, "  Lap %-3d %s", i + 1, Track.hms(s.lapTimes.get(i))));
                if (i + 1 == s.fastestLapNo) b.append("  fastest");
                b.append('\n');
            }
        }
        b.append("\nLap and break times leave out breaks (moving time only).");
        return b.toString();
    }

    /** Stats, kept up to date each second while the track runs. */
    static void stats(Activity a, final Track t) {
        if (t == null) {
            Toast.makeText(a, "No track yet: Tools > Start tracking my path", Toast.LENGTH_SHORT).show();
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        final TextView tv = new TextView(a);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(14);
        tv.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        tv.setText(statsText(t));
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);
        final Handler h = new Handler();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                tv.setText(statsText(t));
                if (t.running()) h.postDelayed(this, 1000);
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Track stats").setView(sv)
                .setPositiveButton(android.R.string.ok, null).create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                h.removeCallbacks(tick);
            }
        });
        dlg.show();
        if (t.running()) h.postDelayed(tick, 1000);
    }

    /** Tools > Share my live path with… (while tracking). */
    static void chooseShare(final Activity a) {
        final List<String> names = new ArrayList<String>();
        for (Contacts.Contact c : Contacts.get(a).all()) names.add(c.name);
        final Set<String> now = TrackService.shareTo();
        for (String n : now) if (!names.contains(n)) names.add(n);
        if (names.isEmpty()) {
            Toast.makeText(a, "Nobody heard yet: join a private channel first", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean[] on = new boolean[names.size()];
        for (int i = 0; i < on.length; i++) on[i] = now.contains(names.get(i));
        new AlertDialog.Builder(a).setTitle("Share my live path with")
                .setMultiChoiceItems(names.toArray(new String[0]), on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i, boolean c) {
                        on[i] = c;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Set<String> s = new LinkedHashSet<String>();
                        for (int i = 0; i < on.length; i++) if (on[i]) s.add(names.get(i));
                        Set<String> added = new LinkedHashSet<String>(s);
                        added.removeAll(now);
                        TrackService.setShare(s);
                        for (String n : added) TrackService.addShare(n);
                        Toast.makeText(a, s.isEmpty() ? "Your path is not shared" : "Sharing your path with " + join(s)
                                + (TrackShare.canSend(a) ? "" : " (when on air on a private channel)"), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- saved tracks ----

    static void history(final Activity a) {
        final List<File> files = Track.saved(a.getFilesDir());
        Track running = TrackService.current();
        final List<Track> tracks = new ArrayList<Track>();
        List<String> rows = new ArrayList<String>();
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (File file : files) {
            if (running != null && running.file.equals(file)) continue;
            try {
                Track t = Track.read(file);
                Track.Stats s = t.stats(t.end);
                tracks.add(t);
                rows.add(f.format(new Date(t.start)) + "  " + Track.MODE_WORDS[t.mode] + "  " + Geo.distance(s.distanceM)
                        + "  " + Track.hms(s.moving) + (s.lapCount > 0 ? "  " + s.lapCount + " laps" : ""));
            } catch (Exception ignored) {
                // unreadable file
            }
            if (tracks.size() >= 200) break;
        }
        if (tracks.isEmpty()) {
            Toast.makeText(a, "No saved tracks yet", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("My tracks (kept on this phone)")
                .setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        trackMenu(a, tracks.get(i));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (viewed != null) b.setNeutralButton("Hide shown track", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                viewed = null;
                if (a instanceof MapActivity) ((MapActivity) a).trackChanged();
            }
        });
        b.show();
    }

    private static void trackMenu(final Activity a, final Track t) {
        new AlertDialog.Builder(a).setTitle(new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(t.start)))
                .setItems(new String[]{"Show on map", "Stats", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            viewed = t;
                            if (a instanceof MapActivity) ((MapActivity) a).showTrack(t);
                        } else if (w == 1) {
                            stats(a, t);
                        } else {
                            new AlertDialog.Builder(a).setTitle("Delete this track?").setMessage("It cannot be brought back.")
                                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d2, int w2) {
                                            t.file.delete();
                                            if (viewed == t) viewed = null;
                                            if (a instanceof MapActivity) ((MapActivity) a).trackChanged();
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null).show();
                        }
                    }
                }).show();
    }

    // ---- what the map draws ----

    private static final int[] COLOURS = {0xff42a5f5, 0xffffca28, 0xffab47bc, 0xff26a69a, 0xffff7043, 0xffec407a, 0xff9ccc65};

    static List<double[]> segments(List<Track.Point> pts) {
        List<double[]> out = new ArrayList<double[]>();
        List<Double> cur = new ArrayList<Double>();
        for (Track.Point p : pts) {
            if (p.newSegment && !cur.isEmpty()) {
                out.add(toArray(cur));
                cur.clear();
            }
            cur.add(p.lat);
            cur.add(p.lon);
        }
        if (!cur.isEmpty()) out.add(toArray(cur));
        return out;
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** My path being recorded (red), a saved one being viewed (purple), and the paths the commander chose to show. */
    static List<TileMapView.TrackLine> lines() {
        List<TileMapView.TrackLine> out = new ArrayList<TileMapView.TrackLine>();
        Track v = viewed;
        if (v != null) {
            List<Track.Point> pts;
            synchronized (v) {
                pts = new ArrayList<Track.Point>(v.points);
            }
            out.add(new TileMapView.TrackLine(segments(pts), 0xffce93d8, "", true));
        }
        Track t = TrackService.current();
        if (t != null) {
            List<Track.Point> pts;
            synchronized (t) {
                pts = new ArrayList<Track.Point>(t.points);
            }
            out.add(new TileMapView.TrackLine(segments(pts), 0xffe53935, "", true));
        }
        int i = 0;
        for (TrackShare.Remote r : TrackShare.shownRemotes()) {
            List<double[]> segs = new ArrayList<double[]>();
            List<Double> cur = new ArrayList<Double>();
            synchronized (TrackShare.class) {
                for (double[] p : r.points) {
                    if (p[3] != 0 && !cur.isEmpty()) {
                        segs.add(toArray(cur));
                        cur.clear();
                    }
                    cur.add(p[0]);
                    cur.add(p[1]);
                }
            }
            if (!cur.isEmpty()) segs.add(toArray(cur));
            out.add(new TileMapView.TrackLine(segs, COLOURS[i++ % COLOURS.length],
                    r.name + (r.stopped ? " (stopped)" : r.paused ? " (paused)" : ""), true));
        }
        return out;
    }

    // ---- the bar over the map while tracking ----

    static String barText(Track t) {
        if (TrackService.fakeBlocked) return "● Tracking held: Fake GPS is on (turn it off to carry on)";
        return (t.paused() ? "❚❚ Paused  " : "● Tracking  ") + TrackService.summary(t)
                + (TrackService.shareTo().isEmpty() ? "" : "  → " + join(TrackService.shareTo()));
    }

    /** Commander: the Track button beside a person. Shows their path, asking them for it (they are asked first). */
    static void toggleRemote(Activity a, String name) {
        if (TrackShare.shown(name)) {
            TrackShare.setShown(name, false);
            Toast.makeText(a, name + "'s path hidden", Toast.LENGTH_SHORT).show();
            return;
        }
        TrackShare.setShown(name, true);
        if (!TrackShare.request(a, name)) {
            Toast.makeText(a, "Go on air on a private channel (not Public) to ask " + name + " for their path",
                    Toast.LENGTH_LONG).show();
            return;
        }
        TrackShare.Remote r = TrackShare.remote(name);
        Toast.makeText(a, r != null && !r.points.isEmpty() ? "Showing " + name + "'s path and asking for the latest"
                : "Asked " + name + " for their path: it shows on the map once they share it", Toast.LENGTH_LONG).show();
    }

    static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        };
    }
}
