package org.communitycomms.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.power.BluettiBle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bluetti power (a Specialised menu, off by default): battery, power in and out, time to full or
 * left, AC/DC output switches, over Bluetooth from a phone near the unit; and the latest status other
 * phones shared on the channel. "Works with Bluetti" units; offline only.
 */
public final class BluettiActivity extends Activity {
    private static final int MENU_FIND = 1, MENU_SETTINGS = 2, MENU_PROBE = 3, MENU_LOG = 4, MENU_FORGET = 5;
    private final Handler main = new Handler();
    private TextView unit, big, flows, time, updated, stateLine, writeLine, heardView;
    private LinearLayout switches;
    private Button acOn, acOff, dcOn, dcOff;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Bluetti power");
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * d);
        root.setPadding(pad, pad, pad, pad);
        unit = text(18);
        root.addView(unit);
        big = text(56);
        big.setTextColor(0xfff0c75e);
        root.addView(big);
        flows = text(18);
        root.addView(flows);
        time = text(18);
        root.addView(time);
        updated = text(14);
        root.addView(updated);
        stateLine = text(14);
        stateLine.setTextColor(0xffffab91);
        root.addView(stateLine);
        switches = new LinearLayout(this);
        switches.setOrientation(LinearLayout.VERTICAL);
        acOn = button("AC on", true, true);
        acOff = button("AC off", true, false);
        dcOn = button("DC on", false, true);
        dcOff = button("DC off", false, false);
        switches.addView(row(acOn, acOff));
        switches.addView(row(dcOn, dcOff));
        writeLine = text(14);
        switches.addView(writeLine);
        root.addView(switches);
        TextView h = text(16);
        h.setText("\nOn the channel");
        h.setTextColor(0xffa5d6a7);
        root.addView(h);
        heardView = text(15);
        root.addView(heardView);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        askPermissions();
    }

    private TextView text(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        return t;
    }

    private Button button(String label, final boolean ac, final boolean on) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmSwitch(ac, on);
            }
        });
        return bt;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private void confirmSwitch(final boolean ac, final boolean on) {
        new AlertDialog.Builder(this).setMessage("Turn the " + (ac ? "AC" : "DC") + " output " + (on ? "on" : "off") + "?")
                .setPositiveButton(on ? "Turn on" : "Turn off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        BluettiMonitor.setOutput(ac, on);
                        show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BluettiMonitor.ui = new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        show();
                    }
                });
            }
        };
        if (BluettiMonitor.address(this).length() > 0) BluettiMonitor.acquire(this, "screen");
        show();
        if (BluettiMonitor.address(this).length() == 0 && BluettiBle.supported()) main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && BluettiMonitor.address(BluettiActivity.this).length() == 0) find();
            }
        }, 600);
        main.postDelayed(tick, 15000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BluettiMonitor.ui = null;
        BluettiMonitor.release("screen");
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            main.postDelayed(this, 15000);
        }
    };

    private void show() {
        BluettiMonitor.Status s = BluettiMonitor.last;
        boolean set = BluettiMonitor.address(this).length() > 0;
        unit.setText(set ? BluettiMonitor.label(this) : (BluettiBle.supported() ? "No unit chosen yet: menu > Find my unit"
                : "This phone has no Bluetooth LE (needs Android 4.3 or newer). The status other phones share shows below."));
        if (s != null && s.soc >= 0) {
            big.setText(s.soc + "%");
            flows.setText(String.format(Locale.US, "In: AC %d W, DC/solar %d W\nOut: AC %d W, DC %d W", s.acIn, s.dcIn, s.acOut, s.dcOut));
            String t = BluettiMonitor.timeText(this, s);
            time.setText(t.length() > 0 ? t.substring(0, 1).toUpperCase(Locale.US) + t.substring(1) + " (" + BluettiMonitor.capacityWh(this) + " Wh)" : "");
            updated.setText("Updated " + ago(s.time));
            boolean known = s.acOn >= 0;
            switches.setVisibility(set ? View.VISIBLE : View.GONE);
            acOn.setEnabled(s.acOn != 1);
            acOff.setEnabled(s.acOn != 0);
            dcOn.setEnabled(s.dcOn != 1);
            dcOff.setEnabled(s.dcOn != 0);
            String sw = known ? "AC output " + (s.acOn == 1 ? "on" : "off") + ", DC output " + (s.dcOn == 1 ? "on" : "off") : "";
            String wr = BluettiMonitor.writeResult;
            writeLine.setText(sw + (wr.length() > 0 ? (sw.length() > 0 ? "\n" : "") + "Last change: " + wr : ""));
        } else {
            big.setText(set ? "--" : "");
            flows.setText("");
            time.setText("");
            updated.setText("");
            switches.setVisibility(View.GONE);
        }
        stateLine.setText(BluettiMonitor.state);
        List<MessageLog.Entry> heard = BluettiMonitor.heard(this);
        StringBuilder b = new StringBuilder();
        for (MessageLog.Entry e : heard) {
            String body = e.body.startsWith(RadioService.SAY) ? e.body.substring(RadioService.SAY.length()) : e.body;
            b.append(body.substring(BluettiMonitor.PREFIX.length())).append("\n   from ").append(e.creator).append(", ").append(ago(e.time)).append("\n\n");
        }
        heardView.setText(b.length() > 0 ? b.toString().trim() : "Nothing shared in the last day. The phone near the unit shares its status when "
                + "Watch in the background is ticked (menu > Settings).");
    }

    static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h " + (s % 3600 / 60) + " min ago";
        return (s / 86400) + " days ago";
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_FIND, 0, "Find my unit");
        m.add(0, MENU_SETTINGS, 1, "Settings");
        m.add(0, MENU_PROBE, 2, "Probe (raw log)");
        m.add(0, MENU_LOG, 3, "Show log");
        m.add(0, MENU_FORGET, 4, "Forget this unit");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case MENU_FIND:
                find();
                return true;
            case MENU_SETTINGS:
                settings();
                return true;
            case MENU_PROBE:
                if (BluettiMonitor.address(this).length() == 0) find();
                else {
                    BluettiMonitor.probe();
                    Toast.makeText(this, "Probing for about 30 s; then menu > Show log", Toast.LENGTH_LONG).show();
                }
                return true;
            case MENU_LOG:
                showLog();
                return true;
            case MENU_FORGET:
                BluettiMonitor.sp(this).edit().remove("addr").remove("layout").commit();
                BluettiMonitor.last = null;
                BluettiMonitor.release("screen");
                show();
                return true;
        }
        return super.onOptionsItemSelected(it);
    }

    // ---- permissions ----

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add("android.permission.BLUETOOTH_SCAN");
            need.add("android.permission.BLUETOOTH_CONNECT");
        } else {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> ask = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 7);
    }

    @SuppressWarnings("deprecation")
    private boolean locationOff() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 31) return false;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            return !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- finding the unit ----

    private BluetoothAdapter.LeScanCallback scanCb;

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void find() {
        if (!BluettiBle.supported()) {
            Toast.makeText(this, "Needs Android 4.3 or newer (Bluetooth LE)", Toast.LENGTH_LONG).show();
            return;
        }
        final BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            new AlertDialog.Builder(this).setMessage("Turn on Bluetooth first.").setPositiveButton("OK", null).show();
            return;
        }
        if (locationOff()) {
            new AlertDialog.Builder(this).setMessage("Android needs Location turned on to find Bluetooth devices (nothing is sent anywhere). Turn it on, then try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        BluettiMonitor.release("screen"); // the unit allows one link: let go while looking
        final Map<String, String> found = new LinkedHashMap<String, String>();
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Looking for Bluetti units...")
                .setMessage("Close the Bluetti app on any phone near the unit, and keep this phone within a few metres.")
                .setNegativeButton("Stop", null).create();
        scanCb = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice dev, int rssi, byte[] rec) {
                String n = null;
                try {
                    n = dev.getName();
                } catch (RuntimeException ignored) {
                }
                if (n == null || n.length() == 0) n = nameFromRecord(rec);
                if (n == null || n.length() == 0) return;
                final String addr = dev.getAddress(), label = n;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!found.containsKey(addr)) found.put(addr, label);
                    }
                });
            }
        };
        try {
            a.startLeScan(scanCb);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not look: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                stopScan(a);
            }
        });
        dlg.show();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dlg.isShowing()) return;
                dlg.dismiss();
                pick(found);
            }
        }, 8000);
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void stopScan(BluetoothAdapter a) {
        try {
            if (scanCb != null) a.stopLeScan(scanCb);
        } catch (RuntimeException ignored) {
        }
        scanCb = null;
    }

    /** The complete or short local name (AD types 9 and 8) from an advertisement. */
    static String nameFromRecord(byte[] rec) {
        if (rec == null) return null;
        int i = 0;
        while (i < rec.length - 1) {
            int len = rec[i] & 0xFF;
            if (len == 0 || i + len >= rec.length) break;
            int type = rec[i + 1] & 0xFF;
            if (type == 9 || type == 8) return new String(rec, i + 2, len - 1);
            i += len + 1;
        }
        return null;
    }

    static boolean bluettiName(String n) {
        return n.matches("(?i)^(AC|EB|EP|EL|PR|AP|B\\d|HANDSFREE|BLUETTI).*");
    }

    private void pick(Map<String, String> found) {
        final List<String> addrs = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<String, String> e : found.entrySet()) {
                if (bluettiName(e.getValue()) == (pass == 0)) {
                    addrs.add(e.getKey());
                    labels.add(e.getValue() + (pass == 0 ? "" : "  (not a Bluetti name)"));
                }
            }
        }
        if (addrs.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Nothing found. Is the unit on, with Bluetooth on, and the Bluetti app closed?")
                    .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface di, int w) {
                            find();
                        }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        final List<String> shown = labels;
        new AlertDialog.Builder(this).setTitle("Pick your unit").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                // The advertised name ends in the serial number: the screen and the channel use the model the unit reports.
                BluettiMonitor.sp(BluettiActivity.this).edit().putString("addr", addrs.get(w)).remove("layout").commit();
                BluettiMonitor.last = null;
                BluettiMonitor.acquire(BluettiActivity.this, "screen");
                show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ---- settings ----

    private void settings() {
        final SharedPreferences p = BluettiMonitor.sp(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        l.setPadding(pad, pad / 2, pad, 0);
        final EditText name = field(l, "Name (shown on the channel)", p.getString("name", ""), false);
        final EditText cap = field(l, "Battery size in Wh (worked out from the model where known)", "" + BluettiMonitor.capacityWh(this), true);
        final CheckBox bg = check(l, "Watch in the background (this phone is near the unit): shares the status and sends alerts", p.getBoolean("bgWatch", false));
        final EditText sendAt = field(l, "Send status and alerts to the channel only when the unit is at or below % (100 = always)", "" + BluettiMonitor.sendAtPct(this), true);
        final CheckBox share = check(l, "Share the status on the channel", p.getBoolean("share", true));
        final EditText every = field(l, "...every how many minutes (also at once on a 5% change)", "" + p.getInt("shareMin", 5), true);
        final CheckBox alerts = check(l, "Alerts on the channel", p.getBoolean("alerts", true));
        final CheckBox say = check(l, "Read alerts aloud on the other phones", p.getBoolean("announce", false));
        final EditText low = field(l, "Low battery at %", "" + p.getInt("lowPct", 10), true);
        final EditText crit = field(l, "Critical battery at %", "" + p.getInt("critPct", 5), true);
        final CheckBox charge = check(l, "Charging started / stopped", p.getBoolean("chargeAlert", true));
        final CheckBox mains = check(l, "Mains input lost / back", p.getBoolean("mainsAlert", true));
        final EditText over = field(l, "Overload at watts out (0 = off; AC30 and Elite 30 V2 are rated 300 W)", "" + p.getInt("overloadW", 300), true);
        final EditText lost = field(l, "Out of contact alert after minutes (0 = off)", "" + p.getInt("lostMin", 10), true);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        new AlertDialog.Builder(this).setTitle("Bluetti settings").setView(sv).setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                p.edit().putString("name", name.getText().toString().trim())
                        .putInt("capWh", num(cap, 300, 50, 100000) == BluettiMonitor.autoCapacity() ? 0 : num(cap, 300, 50, 100000))
                        .putBoolean("bgWatch", bg.isChecked())
                        .putInt("sendAtPct", num(sendAt, 10, 0, 100))
                        .putBoolean("share", share.isChecked())
                        .putInt("shareMin", num(every, 5, 1, 1440))
                        .putBoolean("alerts", alerts.isChecked())
                        .putBoolean("announce", say.isChecked())
                        .putInt("lowPct", num(low, 10, 0, 100))
                        .putInt("critPct", num(crit, 5, 0, 100))
                        .putBoolean("chargeAlert", charge.isChecked())
                        .putBoolean("mainsAlert", mains.isChecked())
                        .putInt("overloadW", num(over, 300, 0, 100000))
                        .putInt("lostMin", num(lost, 10, 0, 1440)).commit();
                RadioService rs = RadioService.instance();
                if (rs != null) BluettiMonitor.acquire(rs, "bg");
                BluettiMonitor.refresh();
                show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(LinearLayout l, String label, String value, boolean number) {
        TextView t = new TextView(this);
        t.setText(label);
        l.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        l.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout l, String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        l.addView(c);
        return c;
    }

    private static int num(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException x) {
            return def;
        }
    }

    private void showLog() {
        StringBuilder b = new StringBuilder();
        for (String s : BluettiMonitor.log()) b.append(s).append('\n');
        final String all = b.length() > 0 ? b.toString() : "Nothing yet.";
        TextView t = new TextView(this);
        t.setText(all);
        t.setTextSize(11);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int pad = (int) (8 * d);
        t.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("Bluetti log").setView(sv).setPositiveButton("Close", null)
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    @SuppressWarnings("deprecation")
                    public void onClick(DialogInterface di, int w) {
                        android.text.ClipboardManager cm = (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setText(all);
                        Toast.makeText(BluettiActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
}
