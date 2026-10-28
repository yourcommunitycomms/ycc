package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.Poi;
import org.communitycomms.app.geo.PoiStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Map menu > Bulk add / export points. One point per line, comma separated:
 * <pre>Name, Icon, Lat, Lon, Notes</pre>
 * The position may also be one field ("-37.9 145.3") and the notes may hold commas. An icon this app
 * does not know becomes a grey dot with its name in brackets. A header line ("Name, Icon, …") and
 * lines starting with # are skipped. Export writes the same format (plus the layer), so a list can be
 * edited in a spreadsheet and brought back.
 */
final class BulkPois {
    private BulkPois() {
    }

    static final String HEADER = "Name,Icon,Lat,Lon,Notes,Layer";

    static final class Row {
        String name = "", iconText = "", notes = "", layer = "";
        int icon = -1;
        double lat, lon;
    }

    // ---- reading ----

    /** Splits a CSV line (quotes allowed, "" inside quotes is a quote). */
    static List<String> fields(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    q = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == ',' || c == '\t' || c == ';') {
                out.add(f.toString().trim());
                f.setLength(0);
            } else {
                f.append(c);
            }
        }
        out.add(f.toString().trim());
        return out;
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int iconFor(String text) {
        String t = text.trim().toLowerCase(Locale.US);
        if (t.length() == 0) return -1;
        Double n = number(t);
        if (n != null && n >= 0 && n < Poi.ICON_NAMES.length && n == Math.floor(n)) return n.intValue();
        for (int i = 0; i < Poi.ICON_NAMES.length; i++) if (Poi.ICON_NAMES[i].toLowerCase(Locale.US).equals(t)) return i;
        for (int i = 0; i < Poi.ICON_NAMES.length; i++) if (Poi.ICON_NAMES[i].toLowerCase(Locale.US).startsWith(t)) return i;
        for (int i = 0; i < Poi.ICON_NAMES.length; i++) if (Poi.ICON_NAMES[i].toLowerCase(Locale.US).contains(t)) return i;
        return -1;
    }

    /** The rows that make sense; problems (line number and why) go in bad. */
    static List<Row> parse(String text, List<String> bad) {
        List<Row> out = new ArrayList<Row>();
        String[] lines = text.split("\r?\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            List<String> f = fields(line);
            if (n == 0 && f.get(0).equalsIgnoreCase("name")) continue; // header
            if (f.size() < 3) {
                bad.add("Line " + (n + 1) + ": needs at least name, icon and position");
                continue;
            }
            Row r = new Row();
            r.name = f.get(0);
            r.iconText = f.get(1);
            int next;
            Double a = number(f.get(2)), b = f.size() > 3 ? number(f.get(3)) : null;
            if (a != null && b != null) {
                r.lat = a;
                r.lon = b;
                next = 4;
            } else {
                String[] ll = f.get(2).trim().split("[\\s/]+");
                Double x = ll.length == 2 ? number(ll[0]) : null, y = ll.length == 2 ? number(ll[1]) : null;
                if (x == null || y == null) {
                    bad.add("Line " + (n + 1) + ": no position (lat, lon) after the icon");
                    continue;
                }
                r.lat = x;
                r.lon = y;
                next = 3;
            }
            if (Math.abs(r.lat) > 90 || Math.abs(r.lon) > 180) {
                bad.add("Line " + (n + 1) + ": position out of range");
                continue;
            }
            StringBuilder notes = new StringBuilder();
            for (int i = next; i < f.size(); i++) {
                if (notes.length() > 0) notes.append(", ");
                notes.append(f.get(i));
            }
            r.notes = notes.toString().trim();
            r.icon = iconFor(r.iconText);
            out.add(r);
        }
        return out;
    }

    // ---- writing ----

    private static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains(";")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    static String export(Context c, String onlyLayer) {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        long now = System.currentTimeMillis();
        for (Poi p : PoiStore.get(c).all()) {
            if (p.expired(now)) continue;
            String layer = Poi.layer(p.note);
            if (onlyLayer.length() > 0 && !onlyLayer.equalsIgnoreCase(layer)) continue;
            String[] lines = Poi.readable(p.note).split("\n", 2);
            String name = lines[0].trim(), notes = lines.length > 1 ? lines[1].trim().replace("\n", " / ") : "";
            String icon = p.icon >= 0 && p.icon < Poi.ICON_NAMES.length ? Poi.ICON_NAMES[p.icon] : String.valueOf(p.icon);
            b.append(csv(name)).append(',').append(csv(icon)).append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)).append(',')
                    .append(csv(notes)).append(',').append(csv(layer)).append('\n');
        }
        return b.toString();
    }

    // ---- the screens ----

    private static final String[] LIFE_LABELS = {"Never expires", "24 hours", "7 days", "6 hours", "1 hour"};
    private static final long[] LIFE_MS = {0, 24 * 3600000L, 7 * 24 * 3600000L, 6 * 3600000L, 3600000L};

    static final int REQ_QR = 42, REQ_FILE = 43;
    /** QR: "YCCPOI1 part/total" on the first line, then up to about 1200 characters of the list. */
    static final String QR_PREFIX = "YCCPOI1 ";
    private static EditText importBox;

    /** The map's onActivityResult: a scanned QR part or an opened file goes into the bulk add box. */
    static boolean onResult(Activity a, int req, int res, Intent data) {
        if (req != REQ_QR && req != REQ_FILE) return false;
        if (res != Activity.RESULT_OK || data == null || importBox == null) return true;
        String got = null;
        if (req == REQ_QR) {
            String q = data.getStringExtra(QrScanActivity.EXTRA_RESULT);
            if (q == null) return true;
            if (q.startsWith(QR_PREFIX)) {
                int nl = q.indexOf('\n');
                String part = nl < 0 ? "" : q.substring(QR_PREFIX.length(), nl).trim();
                got = nl < 0 ? "" : q.substring(nl + 1);
                String[] pt = part.split("/");
                if (pt.length == 2 && !pt[0].equals(pt[1])) {
                    Toast.makeText(a, "Part " + pt[0] + " of " + pt[1] + " added: Scan QR again for the next", Toast.LENGTH_LONG).show();
                }
            } else {
                got = q; // a plain text QR with the list in it
            }
        } else if (data.getData() != null) {
            try {
                java.io.InputStream in = a.getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 2 * 1024 * 1024) bo.write(buf, 0, n);
                in.close();
                got = new String(bo.toByteArray(), "UTF-8");
                if (got.startsWith("﻿")) got = got.substring(1); // a spreadsheet's byte order mark
            } catch (Exception e) {
                Toast.makeText(a, "Could not read that file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return true;
            }
        }
        if (got != null && got.length() > 0) {
            String now = importBox.getText().toString();
            importBox.setText(now.trim().length() == 0 ? got : now + (now.endsWith("\n") ? "" : "\n") + got);
        }
        return true;
    }

    /** Bulk add: type, paste, scan a QR or open a file into the box, then Next asks who gets them and the layer. */
    static void showImport(final Activity a, String preset, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        TextView help = new TextView(a);
        help.setText("One point per line: Name, Icon, Lat, Lon, Notes. For example:\n"
                + "Alpha Coy HQ, Friendly HQ, -37.9451, 145.2984, Main gate\n"
                + "Water tank, Water point, -37.9502 145.3011\n"
                + "An icon the app does not have becomes a grey dot with its name in brackets. "
                + "Lines starting with # and a Name,Icon,… header are skipped.");
        help.setTextSize(12);
        box.addView(help);
        final EditText text = new EditText(a);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setMinLines(6);
        text.setTextSize(13);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setGravity(android.view.Gravity.TOP);
        if (preset != null) text.setText(preset);
        box.addView(text);
        importBox = text;
        // The list can also come from a QR code (another phone's Export > Show QR) or a file (.csv / .txt).
        LinearLayout from = new LinearLayout(a);
        android.widget.Button scan = new android.widget.Button(a), file = new android.widget.Button(a);
        scan.setText("Scan QR");
        file.setText("Open file");
        scan.setAllCaps(false);
        file.setAllCaps(false);
        scan.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                a.startActivityForResult(new Intent(a, QrScanActivity.class), REQ_QR);
            }
        });
        file.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    a.startActivityForResult(Intent.createChooser(i, "A points list (.csv or .txt)"), REQ_FILE);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(a, "No file picker on this phone", Toast.LENGTH_LONG).show();
                }
            }
        });
        from.addView(scan, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        from.addView(file, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(from);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Bulk add points").setView(sv)
                .setPositiveButton("Next", null)
                .setNeutralButton("Paste", null)
                .setNegativeButton(android.R.string.cancel, null).show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData c = cm == null ? null : cm.getPrimaryClip();
                if (c != null && c.getItemCount() > 0) text.setText(c.getItemAt(0).coerceToText(a));
            }
        });
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                List<String> bad = new ArrayList<String>();
                List<Row> rows = parse(text.getText().toString(), bad);
                if (rows.isEmpty()) {
                    Toast.makeText(a, bad.isEmpty() ? "Nothing to add" : bad.get(0), Toast.LENGTH_LONG).show();
                    return;
                }
                dlg.dismiss();
                askHow(a, rows, bad, done);
            }
        });
    }

    /** Who gets them (everyone on the channel, or just this phone), the layer, expiry, repeats. */
    private static void askHow(final Activity a, final List<Row> rows, List<String> bad, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        int unknown = 0;
        for (Row r : rows) if (r.icon < 0) unknown++;
        TextView sum = new TextView(a);
        sum.setText(rows.size() + " point(s) read" + (unknown > 0 ? ", " + unknown + " with an unknown icon (grey dot)" : "")
                + (bad.isEmpty() ? "." : ". Skipped:\n" + join(bad)));
        box.addView(sum);
        final android.widget.RadioGroup who = new android.widget.RadioGroup(a);
        android.widget.RadioButton all = new android.widget.RadioButton(a), me = new android.widget.RadioButton(a);
        all.setText("Send to everyone on the channel on air");
        me.setText("Just this phone (Sync POIs can share them later)");
        all.setId(1);
        me.setId(2);
        who.addView(all);
        who.addView(me);
        who.check(2);
        box.addView(who);
        TextView ll = new TextView(a);
        ll.setText("Layer (Map > Points can show just this layer)");
        box.addView(ll);
        final EditText layer = new EditText(a);
        layer.setSingleLine(true);
        layer.setText("Import " + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date()));
        layer.setSelectAllOnFocus(true);
        box.addView(layer);
        final Spinner life = new Spinner(a);
        life.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, LIFE_LABELS));
        box.addView(life);
        final CheckBox skip = new CheckBox(a);
        skip.setText("Skip repeats: a point with the same name within 25 m of one already on the map (or earlier in the list)");
        skip.setChecked(true);
        box.addView(skip);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("Add " + rows.size() + " point(s)").setView(sv)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        add(a, rows, who.getCheckedRadioButtonId() == 1, layer.getText().toString().trim(),
                                LIFE_MS[life.getSelectedItemPosition()], skip.isChecked());
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < 8; i++) b.append(l.get(i)).append('\n');
        if (l.size() > 8) b.append("…and ").append(l.size() - 8).append(" more");
        return b.toString().trim();
    }

    private static void add(Activity a, List<Row> rows, boolean send, String layer, long lifeMs, boolean skipRepeats) {
        String me = new Prefs(a).callsign();
        RadioService rs = RadioService.instance();
        if (send && (rs == null || rs.engine() == null)) {
            Toast.makeText(a, "Not on a channel: kept on this phone only", Toast.LENGTH_LONG).show();
            send = false;
        }
        List<Poi> have = new ArrayList<Poi>(PoiStore.get(a).all());
        int added = 0, repeats = 0;
        for (Row r : rows) {
            String name = r.icon < 0 && r.iconText.length() > 0 ? "(" + r.iconText + ") " + r.name : r.name;
            if (skipRepeats && repeat(have, name, r.lat, r.lon)) {
                repeats++;
                continue;
            }
            String note = name + (r.notes.length() > 0 ? "\n" + r.notes : "");
            note = Poi.withLayer(note, layer);
            note = Poi.withQuiet(note, true); // no spoken announcement for each of a bulk add
            Poi p = Poi.create(r.lat, r.lon, r.icon < 0 ? Poi.ICON_DOT : r.icon, note, lifeMs, me);
            boolean sent = send && rs.sendPoi(p);
            if (!sent) PoiStore.get(a).apply(p);
            have.add(p);
            added++;
        }
        Toast.makeText(a, "Added " + added + " point(s)" + (send ? " and sent them" : " on this phone")
                + (repeats > 0 ? "; skipped " + repeats + " repeat(s)" : "")
                + (layer.length() > 0 ? " (layer " + layer + ")" : ""), Toast.LENGTH_LONG).show();
    }

    private static boolean repeat(List<Poi> have, String name, double lat, double lon) {
        for (Poi p : have) {
            String n = Poi.readable(p.note).split("\n", 2)[0].trim();
            if (n.equalsIgnoreCase(name.trim()) && Geo.distanceM(p.lat, p.lon, lat, lon) < 25) return true;
        }
        return false;
    }

    /** Export: every point (or one layer) as text to copy, share or save. */
    static void showExport(final Activity a) {
        final List<String> layers = new ArrayList<String>();
        for (Poi p : PoiStore.get(a).all()) {
            String l = Poi.layer(p.note);
            if (l.length() > 0 && !layers.contains(l)) layers.add(l);
        }
        if (layers.isEmpty()) {
            exportText(a, "");
            return;
        }
        final String[] names = new String[layers.size() + 1];
        names[0] = "All points";
        for (int i = 0; i < layers.size(); i++) names[i + 1] = "Layer: " + layers.get(i);
        new AlertDialog.Builder(a).setTitle("Export which points?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        exportText(a, which == 0 ? "" : layers.get(which - 1));
                    }
                }).show();
    }

    private static void exportText(final Activity a, String layer) {
        final String csv = export(a, layer);
        int n = csv.split("\n").length - 1;
        float d = a.getResources().getDisplayMetrics().density;
        TextView t = new TextView(a);
        t.setText(csv);
        t.setTextIsSelectable(true);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        ScrollView sv = new ScrollView(a);
        sv.addView(t);
        new AlertDialog.Builder(a).setTitle(n + " point(s)" + (layer.length() > 0 ? " · " + layer : "")).setView(sv)
                .setPositiveButton("Share / save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        try {
                            java.io.File f = new java.io.File(FilesProvider.exportDir(a), "points-"
                                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".csv");
                            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                            o.write(csv.getBytes("UTF-8"));
                            o.close();
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("text/csv");
                            s.putExtra(Intent.EXTRA_STREAM, FilesProvider.export(f));
                            s.putExtra(Intent.EXTRA_TEXT, csv);
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            a.startActivity(Intent.createChooser(s, "Share or save the points"));
                        } catch (Exception e) {
                            Toast.makeText(a, "Could not share it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("Show QR", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        showQr(a, csv);
                    }
                })
                .setNegativeButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("points", csv));
                        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    /** The list as QR codes: one if it fits, otherwise numbered parts to scan one after another. */
    private static void showQr(Activity a, String csv) {
        final int per = 1200;
        List<String> lines = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String line : csv.split("\n")) {
            if (cur.length() + line.length() + 1 > per && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) lines.add(cur.toString());
        ArrayList<String> payloads = new ArrayList<String>(), titles = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            payloads.add(QR_PREFIX + (i + 1) + "/" + lines.size() + "\n" + lines.get(i));
            titles.add("Map points · part " + (i + 1) + " of " + lines.size() + " (Bulk add points > Scan QR)");
        }
        QrShowActivity.start(a, payloads, titles, null);
    }
}
