package org.communitycomms.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.net.UbusApi;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Radio windows (advanced, from the HaLow dialog), phone-controlled: HaLow on the node this phone is connected to is on
 * only during set times each day (e.g. 06:00-06:15 and 18:00-18:15) and off the rest of the time. This phone keeps the
 * schedule (an exact alarm for the next window start or end, by the phone's clock) and sends "on" or "off" to the node
 * then, using the remembered admin login. No timer is left on the node: HaLow comes back at the next window start only
 * if this phone sends it (or when the node restarts). If the phone is not on that node's WiFi or cable at a window start or end, that step is skipped (tried again for 5
 * minutes). Older builds put the windows on the node itself (tagged crontab lines and an rc.local block): saving or
 * removing takes those out.
 */
final class HalowWindows {
    private HalowWindows() {
    }

    static final String TAG = "ycc-halow-win"; // the old on-node windows (cleaned up)
    static final String RC_BLOCK = "halow-win";
    private static final int REQ = 0x4a57;

    static SharedPreferences sp() {
        return HalowToggle.sp();
    }

    /** "06:00-06:15" lines -> {startMin, endMin} pairs; null with a message in err[0] when a line is wrong. */
    static List<int[]> parse(String text, String[] err) {
        List<int[]> out = new ArrayList<int[]>();
        for (String raw : text.split("[\\n,;]+")) {
            String l = raw.trim();
            if (l.length() == 0) continue;
            String[] p = l.split("\\s*(-|–|to)\\s*");
            int a = p.length == 2 ? HalowToggle.parseHhmm(p[0]) : -1, b = p.length == 2 ? HalowToggle.parseHhmm(p[1]) : -1;
            if (a < 0 || b < 0) {
                err[0] = "\"" + l + "\" is not a window: use 24 h times like 06:00-06:15";
                return null;
            }
            if (a == b) {
                err[0] = "\"" + l + "\": start and end are the same";
                return null;
            }
            out.add(new int[]{a, b});
        }
        if (out.isEmpty()) err[0] = "No windows: add at least one, like 06:00-06:15";
        return out.isEmpty() ? null : out;
    }

    static String hhmm(int m) {
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    static String text(List<int[]> w) {
        StringBuilder b = new StringBuilder();
        for (int[] x : w) b.append(b.length() > 0 ? ", " : "").append(hhmm(x[0])).append("-").append(hhmm(x[1]));
        return b.toString();
    }

    static boolean inside(List<int[]> w, int now) {
        for (int[] x : w) {
            if (x[0] < x[1] ? now >= x[0] && now < x[1] : now >= x[0] || now < x[1]) return true; // a window may cross midnight
        }
        return false;
    }

    /** The windows set on this phone ("" = none), as text. */
    static String saved() {
        return sp().getString("halow_windows", "");
    }

    static List<int[]> savedList() {
        String s = saved();
        if (s.length() == 0) return null;
        return parse(s, new String[1]);
    }

    static int nowMin() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** The next window start or end after now (phone clock): {epoch ms, 1 = start (on) / 0 = end (off), minute}. */
    static long[] next(List<int[]> w) {
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int best = Integer.MAX_VALUE, bestMin = 0;
        boolean bestUp = false;
        for (int[] x : w) {
            for (int k = 0; k < 2; k++) {
                int d = (x[k] - now + 1440) % 1440;
                if (d == 0) d = 1440;
                if (d < best || d == best && k == 1) { // an end and a start at the same minute: end first, then the start is due
                    best = d;
                    bestUp = k == 0;
                    bestMin = x[k];
                }
            }
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, best);
        return new long[]{c.getTimeInMillis(), bestUp ? 1 : 0, bestMin};
    }

    /** For reports: "18:00-18:15 (HT02)", or "open now until 14:40, then 18:00-18:15 (HT02)"; "" when no windows are set. */
    public static String nextWindowText() {
        List<int[]> w = savedList();
        if (w == null) return "";
        int now = nowMin();
        String nm = sp().getString("halow_win_name", "");
        int ns = nextStart(w);
        int[] nxt = null;
        for (int[] x : w) if (x[0] == ns) nxt = x;
        String next = nxt == null ? hhmm(ns) : hhmm(nxt[0]) + "-" + hhmm(nxt[1]);
        for (int[] x : w) {
            if (inside(java.util.Collections.singletonList(x), now))
                return "open now until " + hhmm(x[1]) + ", then " + next + (nm.length() > 0 ? " (" + nm + ")" : "");
        }
        return next + (nm.length() > 0 ? " (" + nm + ")" : "");
    }
    /** The start of the next window after now (minutes after midnight): where the node's safety timer points. */
    static int nextStart(List<int[]> w) {
        int now = nowMin(), best = Integer.MAX_VALUE, at = w.get(0)[0];
        for (int[] x : w) {
            int d = (x[0] - now + 1440) % 1440;
            if (d == 0) d = 1440;
            if (d < best) {
                best = d;
                at = x[0];
            }
        }
        return at;
    }

    private static PendingIntent pi(Context c, int attempt, boolean up) {
        Intent i = new Intent(c, Receiver.class).putExtra("attempt", attempt).putExtra("up", up);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, REQ, i, fl);
    }

    private static void alarmAt(Context c, long at, PendingIntent p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 31 ? am.canScheduleExactAlarms() : Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else if (Build.VERSION.SDK_INT >= 19) am.setExact(AlarmManager.RTC_WAKEUP, at, p);
        else am.set(AlarmManager.RTC_WAKEUP, at, p);
    }

    /** Sets the alarm for the next window start or end (or cancels it when no windows are set). App start and phone restart call this too. */
    static void arm(Context c) {
        c = c.getApplicationContext();
        List<int[]> w = savedList();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (w == null) {
            if (am != null) am.cancel(pi(c, 0, false));
            armAnnouncements(c); // cancels them
            sp().edit().remove("halow_win_next").commit();
            return;
        }
        long[] n = next(w);
        alarmAt(c, n[0], pi(c, 0, n[1] == 1));
        armAnnouncements(c);
        sp().edit().putLong("halow_win_next", n[0]).putBoolean("halow_win_next_up", n[1] == 1).commit();
    }

    /** "next: on at 18:00" for the dialog. */
    static String nextText() {
        long at = sp().getLong("halow_win_next", 0);
        if (at <= 0 || saved().length() == 0) return "";
        return "next: " + (sp().getBoolean("halow_win_next_up", false) ? "on" : "off") + " at "
                + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(at));
    }

    /**
     * Sends on or off to the windows' node now (off until the next window start or a node restart; nothing is left on the
     * node). Returns null when done, else why not.
     */
    static String send(Context c, boolean up) {
        String host = sp().getString("halow_win_host", ""), nm = sp().getString("halow_win_name", host);
        List<int[]> w = savedList();
        if (host.length() == 0 || w == null) return "no radio windows set";
        String pass = MeshRadio.adminPass();
        if (pass == null) return "no remembered admin login on this phone (log in with Remember ticked)";
        String[] dn = MeshRadio.detectHost(c);
        String fixed = MeshTest.sp().getString("local_host", "").trim();
        String direct = fixed.length() > 0 ? fixed : dn == null ? null : dn[0];
        if (!host.equals(direct)) return "this phone is not on " + nm + "'s WiFi or cable" + (direct != null ? " (it is on " + direct + ")" : "");
        HalowToggle.Result r = HalowToggle.work(host, MeshRadio.user(), pass, up,
                up ? HalowToggle.ON_UNTIL_OFF : HalowToggle.UNTIL_REBOOT, -1); // off: no timer on the node (the phone turns it back on)
        if (r.error != null) return r.error;
        HalowToggle.set(r.up ? "on" : "off", host, nm);
        if (!up) {
            sp().edit().putInt("halow_mode", HalowToggle.UNTIL_REBOOT).remove("halow_until").commit();
        }
        return null;
    }

    // ---- announcements: "Radio window opens in 5 minutes / 1 minute / open", "closes in 1 minute / closed" ----

    private static final int REQ_ANN = 0x4a58;

    /** Which announcements: opens in 5 min, opens in 1 min, open, closes in 1 min, closed (each can be ticked off). */
    static final String[] ANN_KEYS = {"halow_win_a5", "halow_win_a1", "halow_win_aopen", "halow_win_c1", "halow_win_cclosed"};
    static final String[] ANN_NAMES = {"Radio window opens in 5 minutes", "Radio window opens in 1 minute", "Radio window open",
            "Radio window closes in 1 minute", "Radio window closed"};

    static boolean annOn(int k) {
        return sp().getBoolean(ANN_KEYS[k], true);
    }

    static boolean annLocal() {
        return sp().getBoolean("halow_win_ann_local", true);
    }

    static boolean annChannel() {
        return sp().getBoolean("halow_win_ann_channel", false);
    }

    /** Says it on this phone and / or sends it to the channel as text as voice (each as chosen in the dialog). */
    static void announce(String text) {
        android.util.Log.i("RadioWindow", "announce: " + text + (annLocal() ? " (said here)" : "") + (annChannel() ? " (to the channel)" : ""));
        RadioService rs = RadioService.instance();
        if (rs == null) return;
        if (annLocal()) rs.speakLocal(text);
        if (annChannel() && rs.messages() != null) rs.messages().sendText(RadioService.SAY + text);
    }

    /** The next "opens in 5 / 1 minutes" or "closes in 1 minute" after now: {epoch ms, text}, or null. */
    static Object[] nextAnnouncement(List<int[]> w) {
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int best = Integer.MAX_VALUE;
        String text = null;
        for (int[] x : w) {
            int len = (x[1] - x[0] + 1440) % 1440;
            int[] at = {x[0] - 5, x[0] - 1, x[1] - 1};
            int[] which = {0, 1, 3};
            String[] say = {ANN_NAMES[0], ANN_NAMES[1], ANN_NAMES[3]};
            for (int k = 0; k < 3; k++) {
                if (k == 2 && len <= 1) continue;
                if (!annOn(which[k])) continue;
                int d = ((at[k] % 1440 + 1440) % 1440 - now + 1440) % 1440;
                if (d == 0) d = 1440;
                if (d < best) {
                    best = d;
                    text = say[k];
                }
            }
        }
        if (text == null) return null;
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, best);
        return new Object[]{c.getTimeInMillis(), text};
    }

    private static PendingIntent annPi(Context c, String text) {
        Intent i = new Intent(c, Receiver.class).putExtra("ann", text == null ? "" : text);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, REQ_ANN, i, fl);
    }

    static void armAnnouncements(Context c) {
        List<int[]> w = savedList();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (w == null || !annLocal() && !annChannel()) {
            am.cancel(annPi(c, null));
            return;
        }
        Object[] n = nextAnnouncement(w);
        if (n != null) alarmAt(c, (Long) n[0], annPi(c, (String) n[1]));
    }

    /**
     * The alarms: an announcement, or a window start / end (send on / off, then set the next alarm, or try again in a
     * minute, up to 5 times). The work runs on its own thread under a wake lock and the broadcast is finished at once:
     * a node call can take tens of seconds, and holding the broadcast that long made Android show "not responding".
     */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context c, Intent i) {
            final Context app = c.getApplicationContext();
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
                arm(app);
                return;
            }
            final String ann = i.getStringExtra("ann");
            if (ann != null) {
                if (ann.length() > 0 && savedList() != null) announce(ann);
                armAnnouncements(app);
                return;
            }
            final boolean up = i.getBooleanExtra("up", false);
            final int attempt = i.getIntExtra("attempt", 0);
            final android.os.PowerManager.WakeLock wl = ((android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ycc:radiowindow");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60000L);
            if (!up && attempt == 0 && annOn(4)) announce(ANN_NAMES[4]); // before HaLow goes off, while the mesh can still hear it
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String nm = sp().getString("halow_win_name", "");
                    String what = up ? "start (HaLow on)" : "end (HaLow off)";
                    try {
                        String why;
                        try {
                            why = send(app, up);
                        } catch (Throwable t) { // never let one bad call stop the schedule
                            why = "error: " + t;
                        }
                        if (why == null) {
                            winLog("Window " + what + " at " + nm + ": done" + (attempt > 0 ? " (try " + (attempt + 1) + ")" : ""));
                            if (up) {
                                try {
                                    Thread.sleep(8000); // the mesh needs a moment to rejoin before the channel can hear it
                                } catch (InterruptedException ignored) {
                                }
                                if (annOn(2)) announce(ANN_NAMES[2]);
                            }
                            arm(app);
                        } else if (attempt < RETRIES && !why.startsWith("no ")) {
                            winLog("Window " + what + " at " + nm + ": " + why + " (try " + (attempt + 1) + ", again in 20 s)");
                            alarmAt(app, System.currentTimeMillis() + 20000, pi(app, attempt + 1, up));
                        } else {
                            winLog("Window " + what + " at " + nm + " NOT done: " + why);
                            arm(app);
                        }
                    } finally {
                        wl.release();
                    }
                }
            }, "halow-window").start();
        }
    }

    /** 12 tries 20 s apart: about 4 minutes of trying at each window start or end. */
    static final int RETRIES = 12;

    /** A short log of what each window start / end did (shown in the Radio windows dialog), also in the node log. */
    static void winLog(String line) {
        NodeShare.log(line);
        String at = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
        String old = sp().getString("halow_win_log", "");
        String all = at + " " + line + (old.length() > 0 ? "\n" + old : "");
        String[] ls = all.split("\n");
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < ls.length && k < 15; k++) b.append(k > 0 ? "\n" : "").append(ls[k]);
        sp().edit().putString("halow_win_log", b.toString()).commit();
    }

    /** Is a window open right now (phone clock)? */
    static boolean openNow() {
        List<int[]> w = savedList();
        return w != null && inside(w, nowMin());
    }
    /** Takes the old on-node windows out (tagged crontab lines and the rc.local block), if a node has them. */
    static void cleanNode(UbusApi u) throws Exception {
        String ct = CustomizeOm.read(u, HalowToggle.CRONTAB);
        if (ct != null && ct.contains(TAG)) {
            StringBuilder b = new StringBuilder();
            for (String l : ct.split("\n", -1)) if (!l.contains(TAG) && l.length() > 0) b.append(l).append('\n');
            u.call("file", "write", new JSONObject().put("path", HalowToggle.CRONTAB).put("data", b.toString()));
            CustomizeOm.initAction(u, "cron", "restart");
        }
        String rc = CustomizeOm.read(u, CustomizeOm.RCLOCAL);
        if (rc != null && rc.contains(HdMods.start(RC_BLOCK))) {
            u.call("file", "write", new JSONObject().put("path", CustomizeOm.RCLOCAL).put("data", HdMods.withoutBlock(rc, RC_BLOCK)));
        }
    }

    /** Save (w != null) or remove (w == null) the windows for this node; sets the right state now; returns a line for the user. */
    static String apply(Context c, String host, String nm, String user, String pass, List<int[]> w) {
        UbusApi u = new UbusApi(host, 20000);
        try {
            u.login(user == null || user.length() == 0 ? "root" : user, pass);
            cleanNode(u);
        } catch (Exception e) {
            return "could not reach " + nm + ": " + e.getMessage();
        } finally {
            u.logout();
        }
        if (w == null) {
            sp().edit().remove("halow_windows").remove("halow_win_host").remove("halow_win_name").commit();
            arm(c);
            HalowToggle.Result r = HalowToggle.work(host, user, pass, true, HalowToggle.ON_UNTIL_OFF, -1);
            if (r.error == null) HalowToggle.set(r.up ? "on" : "off", host, nm);
            return "Radio windows removed: HaLow " + (r.error == null ? "left on." : "not confirmed on (" + r.error + ").");
        }
        sp().edit().putString("halow_windows", text(w)).putString("halow_win_host", host).putString("halow_win_name", nm).commit();
        boolean in = inside(w, nowMin());
        String why = send(c, in);
        arm(c);
        return "Radio windows set (" + text(w) + ", every day, this phone's clock); "
                + (why == null ? (in ? "inside a window now: HaLow on" : "outside the windows now: HaLow off until " + hhmm(nextStart(w))) : "could not set HaLow for now: " + why) + ". " + nextText() + ".";
    }

    /** The dialog: list of windows, Save, Remove windows, clock check. */
    static void dialog(final Activity a, final String host, final String nm, final Runnable after) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        TextView t = new TextView(a);
        t.setText("Advanced. HaLow on " + nm + " is on only during these times each day and off the rest of the time "
                + "(radio silence between check-ins). This phone runs the schedule by its own clock and sends on / off to "
                + nm + " at each start and end, so it needs to be on " + nm + "'s WiFi or cable then, with the admin login "
                + "remembered. Nothing is left on " + nm + ": if this phone is gone, flat or asleep at a window start, HaLow stays "
                + "off until the phone sends \"on\" or " + nm + " restarts.\n\nOne window per line, 24 h time, e.g.\n06:00-06:15\n18:00-18:15\n"
                + "(a window can cross midnight: 23:50-00:10)");
        box.addView(t);
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(3);
        e.setHint("06:00-06:15");
        e.setText(saved().replace(", ", "\n"));
        box.addView(e);
        final TextView st = new TextView(a);
        st.setPadding(0, (int) (8 * d), 0, 0);
        String other = sp().getString("halow_win_name", "");
        st.setText(saved().length() > 0 ? "Set for " + other + ": " + saved() + (nextText().length() > 0 ? " (" + nextText() + ")" : "")
                : "No radio windows set.");
        String wl0 = sp().getString("halow_win_log", "");
        if (wl0.length() > 0) {
            String[] ll = wl0.split("\n");
            StringBuilder lb = new StringBuilder("\nLast window actions:");
            for (int k = 0; k < ll.length && k < 6; k++) lb.append("\n").append(ll[k]);
            st.append(lb.toString());
        }
        if (!MeshRadio.adminRemembered()) st.append("\n⚠ The admin login is not remembered on this phone: log in with Remember ticked, or the windows cannot run when the app is closed.");
        box.addView(st);
        TextView warn = new TextView(a);
        warn.setTextSize(12);
        warn.setText("While windows are set, the mesh only works through " + nm + " during them, and a gateway node cuts the "
                + "mesh's internet outside them. Turning HaLow on or off by hand still works until the next window start or end. "
                + "If this phone is somewhere else at a start or end, that step is skipped (and logged).");
        box.addView(warn);
        TextView annH = new TextView(a);
        annH.setText("Announcements (tick the ones you want):");
        annH.setPadding(0, (int) (10 * d), 0, 0);
        box.addView(annH);
        final android.widget.CheckBox annL = new android.widget.CheckBox(a), annC = new android.widget.CheckBox(a);
        annL.setText("Say them on this phone");
        annL.setChecked(annLocal());
        annC.setText("Send them to the channel as text as voice (every phone says them; \"closed\" goes just before HaLow goes off)");
        annC.setChecked(annChannel());
        final android.widget.CheckBox[] which = new android.widget.CheckBox[ANN_KEYS.length];
        for (int k = 0; k < ANN_KEYS.length; k++) {
            final int kk = k;
            which[k] = new android.widget.CheckBox(a);
            which[k].setText("\"" + ANN_NAMES[k] + "\"");
            which[k].setChecked(annOn(k));
            which[k].setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    sp().edit().putBoolean(ANN_KEYS[kk], on).commit();
                    armAnnouncements(a.getApplicationContext());
                }
            });
            box.addView(which[k]);
        }
        TextView annHow = new TextView(a);
        annHow.setText("How:");
        annHow.setPadding(0, (int) (6 * d), 0, 0);
        box.addView(annHow);
        box.addView(annL);
        box.addView(annC);
        android.widget.CompoundButton.OnCheckedChangeListener annChange = new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp().edit().putBoolean("halow_win_ann_local", annL.isChecked()).putBoolean("halow_win_ann_channel", annC.isChecked()).commit();
                armAnnouncements(a.getApplicationContext());
            }
        };
        annL.setOnCheckedChangeListener(annChange);
        annC.setOnCheckedChangeListener(annChange);
        final Button clock = new Button(a);
        clock.setAllCaps(false);
        clock.setText("Check / sync " + nm + "'s clock with this phone");
        box.addView(clock);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncClock(a, host, nm, st, clock, false);
            }
        });        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Radio windows: " + nm).setView(sv)
                .setPositiveButton("Save", null).setNeutralButton("Remove windows", null)
                .setNegativeButton("Close", null).create();
        final Handler ui = new Handler(Looper.getMainLooper());
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                final Button save = dlg.getButton(AlertDialog.BUTTON_POSITIVE), rem = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                View.OnClickListener go = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final boolean remove = v == rem;
                        final List<int[]> w;
                        if (!remove) {
                            String[] err = new String[1];
                            w = parse(e.getText().toString(), err);
                            if (w == null) {
                                e.setError(err[0]);
                                return;
                            }
                            if (!MeshRadio.adminRemembered()) {
                                st.setText("Log in as admin first (with Remember ticked): the phone needs the login at each window start and end.");
                                return;
                            }
                        } else {
                            w = null;
                        }
                        save.setEnabled(false);
                        rem.setEnabled(false);
                        st.setText(remove ? "Removing the windows…" : "Setting " + text(w) + " for " + nm + "…");
                        final String user = MeshRadio.user(), pass = MeshRadio.adminPass();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String r = apply(a, host, nm, user, pass, w);
                                NodeShare.log("HaLow radio windows at " + nm + ": " + r);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        st.setText(nm + ": " + r);
                                        save.setEnabled(true);
                                        rem.setEnabled(true);
                                        if (after != null) after.run();
                                    }
                                });
                            }
                        }, "halow-windows").start();
                    }
                };
                save.setOnClickListener(go);
                rem.setOnClickListener(go);
            }
        });
        dlg.show();
    }
    /** The clock button: check, set when it is out; if another phone set it recently and this one disagrees, offer "Set anyway". */
    static void syncClock(final Activity a, final String host, final String nm, final TextView st, final Button clock, final boolean force) {
        clock.setEnabled(false);
        st.setText("Checking " + nm + "'s clock…");
        final String user = MeshRadio.user(), pass = MeshRadio.adminPass();
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r;
                UbusApi u = new UbusApi(host, 20000);
                try {
                    u.login(user == null || user.length() == 0 ? "root" : user, pass);
                    String cc = HalowToggle.checkClock(u, true, force);
                    r = cc == null ? "the node's clock matches this phone's (within 5 minutes)" : cc;
                } catch (Exception ex) {
                    r = "could not check: " + ex.getMessage();
                } finally {
                    u.logout();
                }
                final String fr = r;
                NodeShare.log("Clock at " + nm + ": " + fr);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        st.setText(nm + ": " + fr);
                        clock.setEnabled(true);
                        if (fr.startsWith(HalowToggle.CLOCK_NTP) && !force) {
                            st.setText(nm + ": " + fr.substring(HalowToggle.CLOCK_NTP.length()));
                            HalowToggle.askWhichClock(a, nm + ": " + fr.substring(HalowToggle.CLOCK_NTP.length()) + ".", new Runnable() {
                                @Override
                                public void run() {
                                    syncClock(a, host, nm, st, clock, true);
                                }
                            });
                        }
                        if (fr.startsWith(HalowToggle.CLOCK_HELD) && !force) {
                            new AlertDialog.Builder(a).setTitle(nm + "'s clock").setMessage(fr.substring(HalowToggle.CLOCK_HELD.length()) + ".")
                                    .setPositiveButton("Set it from this phone anyway", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            syncClock(a, host, nm, st, clock, true);
                                        }
                                    }).setNegativeButton("Leave it", null).show();
                        }
                    }
                });
            }
        }, "node-clock").start();
    }
}
