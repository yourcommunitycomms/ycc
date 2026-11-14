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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.power.BatteryReader;
import org.communitycomms.app.power.BatteryStatus;
import org.communitycomms.app.power.BatteryUnit;
import org.communitycomms.app.power.BluettiReader;
import org.communitycomms.app.power.EcoFlowReader;
import org.communitycomms.app.power.PowerwallReader;
import org.communitycomms.app.power.SolixReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Battery power (a Specialised menu, off by default): every watched battery with its %, power in and
 * out, time to full or left; the scanner that adds Bluetti, Anker SOLIX* and EcoFlow* units over
 * Bluetooth and a Tesla Powerwall 2 by its gateway address; and the latest status other phones
 * shared on the channel. READ-ONLY: no switches; people use the maker's own app for those.
 */
public final class BatteryActivity extends Activity {
    private static final int MENU_ADD = 1, MENU_SETTINGS = 2, MENU_LOG = 3;
    private final Handler main = new Handler();
    private LinearLayout cards;
    private TextView heardView, empty;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Battery power");
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * d);
        root.setPadding(pad, pad, pad, pad);
        TextView ro = text(13);
        ro.setText("Read-only: this app shows each battery and never switches outputs or changes settings. Use the maker's app for those.");
        ro.setTextColor(0xffa5d6a7);
        root.addView(ro);
        empty = text(16);
        root.addView(empty);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        root.addView(cards);
        TextView h = text(16);
        h.setText("\nOn the channel");
        h.setTextColor(0xffa5d6a7);
        root.addView(h);
        heardView = text(15);
        root.addView(heardView);
        TextView note = text(12);
        note.setText("\n* untested: written from public descriptions of the protocol and not yet tried on a real unit.");
        note.setTextColor(0xff9e9e9e);
        root.addView(note);
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

    @Override
    protected void onResume() {
        super.onResume();
        BatteryMonitor.ui = new Runnable() {
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
        BatteryMonitor.acquire(this, "screen");
        show();
        if (BatteryMonitor.units(this).isEmpty()) main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && BatteryMonitor.units(BatteryActivity.this).isEmpty()) add();
            }
        }, 600);
        main.postDelayed(tick, 15000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BatteryMonitor.ui = null;
        BatteryMonitor.release("screen");
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
        List<BatteryUnit> units = BatteryMonitor.units(this);
        empty.setText(units.isEmpty() ? "\nNo batteries yet: menu > Add a battery." : "");
        cards.removeAllViews();
        for (final BatteryUnit u : units) cards.addView(card(u));
        List<MessageLog.Entry> heard = BatteryMonitor.heard(this);
        StringBuilder b = new StringBuilder();
        for (MessageLog.Entry e : heard) {
            String body = e.body.startsWith(RadioService.SAY) ? e.body.substring(RadioService.SAY.length()) : e.body;
            b.append(body.substring(BatteryMonitor.PREFIX.length())).append("\n   from ").append(e.creator).append(", ").append(ago(e.time)).append("\n\n");
        }
        heardView.setText(b.length() > 0 ? b.toString().trim() : "Nothing shared in the last day. The phone watching a battery shares its status when "
                + "Watch in the background is ticked (menu > Settings) and the battery is at or below the send-at %.");
    }

    private View card(final BatteryUnit u) {
        BatteryMonitor.Watch w = BatteryMonitor.watch(u);
        BatteryStatus s = w.last;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * d);
        c.setPadding(pad, pad, pad, pad);
        c.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        c.setLayoutParams(lp);
        TextView name = text(18);
        String type = BatteryReader.typeName(u.type);
        String label = BatteryMonitor.label(u);
        name.setText(label + (label.equals(type.replace("*", "")) ? (BatteryReader.untested(u.type) ? "*" : "") : "   (" + type + ")"));
        c.addView(name);
        TextView big = text(48);
        big.setTextColor(0xfff0c75e);
        c.addView(big);
        TextView body = text(16);
        c.addView(body);
        TextView st = text(14);
        st.setTextColor(0xffffab91);
        c.addView(st);
        if (s != null && s.soc >= 0) {
            big.setText(s.soc + "%");
            StringBuilder b = new StringBuilder();
            if (s.detail.length() > 0 && BatteryReader.POWERWALL.equals(u.type)) b.append(s.detail);
            else {
                b.append(String.format(Locale.US, "In: AC %d W, DC/solar %d W\nOut: AC %d W, DC/USB %d W", s.acIn, s.dcIn, s.acOut, s.dcOut));
                if (s.acOn >= 0 || s.dcOn >= 0) {
                    b.append("\n");
                    if (s.acOn >= 0) b.append("AC output ").append(s.acOn == 1 ? "on" : "off");
                    if (s.dcOn >= 0) b.append(s.acOn >= 0 ? ", " : "").append("DC output ").append(s.dcOn == 1 ? "on" : "off");
                }
                if (s.detail.length() > 0) b.append("\n").append(s.detail);
            }
            String t = BatteryMonitor.timeText(u, s);
            if (t.length() > 0) b.append("\n").append(t.substring(0, 1).toUpperCase(Locale.US)).append(t.substring(1)).append(" (").append(BatteryMonitor.capacityWh(u, s)).append(" Wh)");
            b.append("\nUpdated ").append(ago(s.time));
            body.setText(b.toString());
        } else {
            big.setText("--");
            body.setText("");
        }
        String state = w.state;
        if (BatteryReader.POWERWALL.equals(u.type) && state.startsWith("can't reach")) state += "\n\n" + PowerwallReader.SETUP_HINT;
        st.setText(state);
        st.setVisibility(state.length() > 0 ? View.VISIBLE : View.GONE);
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unitMenu(u);
            }
        });
        return c;
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
        m.add(0, MENU_ADD, 0, "Add a battery");
        m.add(0, MENU_SETTINGS, 1, "Settings");
        m.add(0, MENU_LOG, 2, "Show log");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case MENU_ADD:
                add();
                return true;
            case MENU_SETTINGS:
                settings();
                return true;
            case MENU_LOG:
                showLog();
                return true;
        }
        return super.onOptionsItemSelected(it);
    }

    private void unitMenu(final BatteryUnit u) {
        String[] items = {"Settings for this battery", "Probe (raw log of what it sends)", "Remove"};
        new AlertDialog.Builder(this).setTitle(BatteryMonitor.label(u)).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int which) {
                if (which == 0) unitSettings(u, false);
                else if (which == 1) {
                    BatteryMonitor.probe(BatteryActivity.this, u);
                    Toast.makeText(BatteryActivity.this, "Probing for up to a minute; then menu > Show log", Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(BatteryActivity.this).setMessage("Stop watching " + BatteryMonitor.label(u) + "?")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d2, int w) {
                                    List<BatteryUnit> l = BatteryMonitor.units(BatteryActivity.this);
                                    for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(u.id)) l.remove(i);
                                    BatteryMonitor.saveUnits(BatteryActivity.this, l);
                                    show();
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            }
        }).show();
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

    // ---- adding a battery ----

    private void add() {
        String[] items = {"Look for Bluetooth batteries nearby (Bluetti, Anker SOLIX*, EcoFlow*)", "Tesla Powerwall 2 (type the gateway's address)"};
        new AlertDialog.Builder(this).setTitle("Add a battery").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int which) {
                if (which == 0) scan();
                else {
                    BatteryUnit u = new BatteryUnit();
                    u.type = BatteryReader.POWERWALL;
                    unitSettings(u, true);
                }
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private static final class Found {
        String addr, name, type, serial = "";
    }

    private BluetoothAdapter.LeScanCallback scanCb;

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void scan() {
        if (Build.VERSION.SDK_INT < 18) {
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
        BatteryMonitor.release("screen"); // these units allow one link: let go while looking
        final Map<String, Found> found = new LinkedHashMap<String, Found>();
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Looking for batteries...")
                .setMessage("Close the maker's app (Bluetti, Anker, EcoFlow) on any phone near the unit, and keep this phone within a few metres.")
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
                final Found f = new Found();
                f.addr = dev.getAddress();
                f.name = n == null ? "" : n.trim();
                f.serial = EcoFlowReader.serialFromRecord(rec);
                f.type = detect(f.name, rec, f.serial);
                if (f.name.length() == 0 && f.type == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Found old = found.get(f.addr);
                        if (old == null || (old.type == null && f.type != null)) found.put(f.addr, f);
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
                BatteryMonitor.acquire(BatteryActivity.this, "screen");
            }
        });
        dlg.show();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dlg.isShowing()) return;
                dlg.dismiss();
                pick(new ArrayList<Found>(found.values()));
            }
        }, 9000);
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

    /** Which reader fits this advertisement, or null. */
    static String detect(String name, byte[] rec, String efSerial) {
        if (efSerial.length() > 0 || name.toUpperCase(Locale.US).startsWith("EF-")) return BatteryReader.ECOFLOW;
        if (hasUuid16(rec, 0xff09) || name.toLowerCase(Locale.US).contains("solix") || name.toLowerCase(Locale.US).startsWith("anker")) return BatteryReader.SOLIX;
        if (name.length() > 0 && BluettiReader.bluettiName(name)) return BatteryReader.BLUETTI;
        return null;
    }

    /** A 16-bit service UUID in the advertisement (AD types 2 and 3). */
    static boolean hasUuid16(byte[] rec, int uuid) {
        if (rec == null) return false;
        int i = 0;
        while (i < rec.length - 1) {
            int len = rec[i] & 0xFF;
            if (len == 0 || i + len >= rec.length) break;
            int type = rec[i + 1] & 0xFF;
            if (type == 2 || type == 3) {
                for (int j = i + 2; j + 1 <= i + len; j += 2) if (((rec[j] & 0xFF) | ((rec[j + 1] & 0xFF) << 8)) == uuid) return true;
            }
            i += len + 1;
        }
        return false;
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

    private void pick(List<Found> all) {
        final List<Found> list = new ArrayList<Found>();
        for (int pass = 0; pass < 2; pass++) for (Found f : all) if ((f.type != null) == (pass == 0)) list.add(f);
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Nothing found. Is the unit on, with Bluetooth on, and the maker's app closed?")
                    .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface di, int w) {
                            scan();
                        }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Found f = list.get(i);
            labels[i] = (f.name.length() > 0 ? f.name : "(no name)") + "  -  " + (f.type != null ? BatteryReader.typeName(f.type) : "not a known battery");
        }
        new AlertDialog.Builder(this).setTitle("Pick your battery").setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                final Found f = list.get(w);
                if (f.type != null) addFound(f, f.type);
                else {
                    final String[] types = {BatteryReader.BLUETTI, BatteryReader.SOLIX, BatteryReader.ECOFLOW};
                    String[] names = {"Bluetti", "Anker SOLIX*", "EcoFlow*"};
                    new AlertDialog.Builder(BatteryActivity.this).setTitle("Which kind is it?").setItems(names, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d2, int t) {
                            addFound(f, types[t]);
                        }
                    }).show();
                }
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void addFound(Found f, String type) {
        for (BatteryUnit u : BatteryMonitor.units(this)) {
            if (u.addr.equalsIgnoreCase(f.addr)) {
                Toast.makeText(this, "Already watching that one (" + BatteryMonitor.label(u) + ")", Toast.LENGTH_LONG).show();
                return;
            }
        }
        BatteryUnit u = new BatteryUnit();
        u.type = type;
        u.addr = f.addr;
        u.serial = f.serial;
        if (BatteryReader.ECOFLOW.equals(type) && f.name.toUpperCase(Locale.US).startsWith("EF-")) u.model = f.name.substring(3).replaceAll("[^A-Za-z0-9].*$", "");
        if (BatteryReader.BLUETTI.equals(type)) saveNew(u);
        else unitSettings(u, true); // Anker: which model; EcoFlow: user ID and serial
    }

    private void saveNew(BatteryUnit u) {
        List<BatteryUnit> l = BatteryMonitor.units(this);
        u.id = BatteryMonitor.newId(l);
        l.add(u);
        BatteryMonitor.saveUnits(this, l);
        BatteryMonitor.acquire(this, "screen");
        show();
    }

    // ---- per-battery settings ----

    private void unitSettings(final BatteryUnit u, final boolean isNew) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        l.setPadding(pad, pad / 2, pad, 0);
        if (BatteryReader.untested(u.type)) {
            TextView t = new TextView(this);
            t.setText("* Untested: written from public protocol notes, not yet tried on a real " + BatteryReader.typeName(u.type).replace("*", "")
                    + ". If it does not read, menu > Show log after a Probe helps work out why.");
            t.setTextColor(0xffffcc80);
            l.addView(t);
        }
        final EditText name = field(l, "Name (shown on the channel)", u.name, false);
        EditText addr = null, uid = null, serial = null;
        final int[] model = {0};
        if (BatteryReader.POWERWALL.equals(u.type)) {
            addr = field(l, "Gateway address (as on your router, e.g. its LAN address or name)", u.addr, false);
            addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            TextView hint = new TextView(this);
            hint.setText(PowerwallReader.SETUP_HINT);
            hint.setTextSize(12);
            l.addView(hint);
        }
        if (BatteryReader.SOLIX.equals(u.type)) {
            TextView t = new TextView(this);
            t.setText("Model");
            l.addView(t);
            android.widget.RadioGroup g = new android.widget.RadioGroup(this);
            for (int i = 0; i < SolixReader.MODELS.length; i++) {
                android.widget.RadioButton rb = new android.widget.RadioButton(this);
                rb.setText(SolixReader.MODEL_NAMES[i]);
                rb.setId(1000 + i);
                g.addView(rb);
                if (SolixReader.MODELS[i].equals(u.model)) model[0] = i;
            }
            g.check(1000 + model[0]);
            g.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.RadioGroup group, int id) {
                    model[0] = id - 1000;
                }
            });
            l.addView(g);
        }
        if (BatteryReader.ECOFLOW.equals(u.type)) {
            uid = field(l, "Your EcoFlow user ID (a number from your EcoFlow account; the unit checks it. This app never signs in to EcoFlow.)", u.userId, false);
            serial = field(l, "The unit's serial number (filled in from Bluetooth when seen; also on the unit's label)", u.serial, false);
        }
        final EditText cap = field(l, "Battery size in Wh (blank = what the unit says, else 300)", u.capWh > 0 ? "" + u.capWh : "", true);
        final EditText over = field(l, "Overload alert at watts out (0 = off)", "" + u.overload(), true);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        final EditText fAddr = addr, fUid = uid, fSerial = serial;
        new AlertDialog.Builder(this).setTitle(BatteryReader.typeName(u.type)).setView(sv).setPositiveButton(isNew ? "Add" : "Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                u.name = name.getText().toString().trim();
                if (fAddr != null) u.addr = fAddr.getText().toString().trim();
                if (BatteryReader.SOLIX.equals(u.type)) u.model = SolixReader.MODELS[model[0]];
                if (fUid != null) u.userId = fUid.getText().toString().trim();
                if (fSerial != null) u.serial = fSerial.getText().toString().trim();
                u.capWh = num(cap, 0, 0, 1000000);
                int o = num(over, u.overload(), 0, 1000000);
                u.overloadW = o == defaultOverload(u.type) ? -1 : o;
                if (isNew) saveNew(u);
                else {
                    List<BatteryUnit> l2 = BatteryMonitor.units(BatteryActivity.this);
                    for (int i = 0; i < l2.size(); i++) if (l2.get(i).id.equals(u.id)) l2.set(i, u);
                    BatteryMonitor.saveUnits(BatteryActivity.this, l2);
                    show();
                }
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private static int defaultOverload(String type) {
        BatteryUnit t = new BatteryUnit();
        t.type = type;
        return t.overload();
    }

    // ---- shared settings ----

    private void settings() {
        final SharedPreferences p = BatteryMonitor.sp(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        l.setPadding(pad, pad / 2, pad, 0);
        final CheckBox bg = check(l, "Watch in the background (this phone is near the batteries / on the home network): shares the status and sends alerts", p.getBoolean("bgWatch", false));
        final EditText sendAt = field(l, "Send status and alerts to the channel only when a battery is at or below % (100 = always)", "" + BatteryMonitor.sendAtPct(this), true);
        final CheckBox share = check(l, "Share the status on the channel", p.getBoolean("share", true));
        final EditText every = field(l, "...every how many minutes (also at once on a 5% change)", "" + p.getInt("shareMin", 5), true);
        final CheckBox alerts = check(l, "Alerts on the channel", p.getBoolean("alerts", true));
        final CheckBox say = check(l, "Read alerts aloud on the other phones", p.getBoolean("announce", false));
        final EditText low = field(l, "Low battery at %", "" + p.getInt("lowPct", 10), true);
        final EditText crit = field(l, "Critical battery at %", "" + p.getInt("critPct", 5), true);
        final CheckBox charge = check(l, "Charging started / stopped (portable units)", p.getBoolean("chargeAlert", true));
        final CheckBox mains = check(l, "Mains / grid power lost and back", p.getBoolean("mainsAlert", true));
        final EditText lost = field(l, "Out of contact alert after minutes (0 = off)", "" + p.getInt("lostMin", 10), true);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        new AlertDialog.Builder(this).setTitle("Battery settings").setView(sv).setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                p.edit().putBoolean("bgWatch", bg.isChecked())
                        .putInt("sendAtPct", num(sendAt, 10, 0, 100))
                        .putBoolean("share", share.isChecked())
                        .putInt("shareMin", num(every, 5, 1, 1440))
                        .putBoolean("alerts", alerts.isChecked())
                        .putBoolean("announce", say.isChecked())
                        .putInt("lowPct", num(low, 10, 0, 100))
                        .putInt("critPct", num(crit, 5, 0, 100))
                        .putBoolean("chargeAlert", charge.isChecked())
                        .putBoolean("mainsAlert", mains.isChecked())
                        .putInt("lostMin", num(lost, 10, 0, 1440)).commit();
                RadioService rs = RadioService.instance();
                if (rs != null) BatteryMonitor.acquire(rs, "bg");
                BatteryMonitor.refresh();
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
        for (String s : BatteryMonitor.log()) b.append(s).append('\n');
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
        new AlertDialog.Builder(this).setTitle("Battery log").setView(sv).setPositiveButton("Close", null)
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    @SuppressWarnings("deprecation")
                    public void onClick(DialogInterface di, int w) {
                        android.text.ClipboardManager cm = (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setText(all);
                        Toast.makeText(BatteryActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
}
