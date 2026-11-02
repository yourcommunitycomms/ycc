package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.crypto.ListenKey;
import org.communitycomms.app.dryad.SheetStore;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Channel array: leaders' table of teams, with import, export, setup QR codes and printed cards. */
public final class ChannelArrayActivity extends Activity {
    private static final int REQ_CSV = 31;
    private List<ChannelArray.Row> rows;
    private LinearLayout list;
    private CheckBox readOnly;
    private ListenKey publicKey;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Channel array");
        rows = ChannelArray.load(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (10 * d);
        root.setPadding(p, p, p, p);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(button("Add team", new Runnable() {
            @Override
            public void run() {
                edit(null);
            }
        }), weight());
        r1.addView(button("Fill AA-ZZ…", new Runnable() {
            @Override
            public void run() {
                fill();
            }
        }), weight());
        r1.addView(button("Print all", new Runnable() {
            @Override
            public void run() {
                print(rows);
            }
        }), weight());
        r2.addView(button("Import CSV", new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "A team list (.csv)"), REQ_CSV);
            }
        }), weight());
        r2.addView(button("Export CSV", new Runnable() {
            @Override
            public void run() {
                export();
            }
        }), weight());
        root.addView(r1);
        root.addView(r2);
        readOnly = new CheckBox(this);
        readOnly.setText("Set all Public to the read-only key in setup QR codes");
        readOnly.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("arrayReadOnly", true));
        readOnly.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("arrayReadOnly", on).commit();
            }
        });
        root.addView(readOnly);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private void changed() {
        ChannelArray.save(this, rows);
        render();
    }

    private void render() {
        list.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        TextView n = new TextView(this);
        n.setText(rows.size() + " teams. The list holds their private keys: keep the phone locked.");
        list.addView(n);
        for (final ChannelArray.Row r : rows) {
            TextView t = new TextView(this);
            t.setTextSize(16);
            t.setPadding(0, (int) (8 * d), 0, 0);
            boolean blocked = r.member.length() > 0 && ReportUp.blocked(this).contains(r.member);
            t.setText(r.label() + (r.listen ? (r.hear ? " · heard live" : " · listening, sound off") : " · not listening") + (blocked ? " · BLOCKED" : ""));
            list.addView(t);
            LinearLayout row = new LinearLayout(this);
            row.addView(button("Talk", new Runnable() {
                @Override
                public void run() {
                    PrivateContacts.talk(ChannelArrayActivity.this, r.priv, null);
                }
            }), weight());
            row.addView(button(r.hear ? "Sound off" : "Sound on", new Runnable() {
                @Override
                public void run() {
                    r.hear = !r.hear;
                    r.listen |= r.hear;
                    changed();
                }
            }), weight());
            row.addView(button("Setup QR", new Runnable() {
                @Override
                public void run() {
                    showQr(r);
                }
            }), weight());
            row.addView(button("Edit", new Runnable() {
                @Override
                public void run() {
                    edit(r);
                }
            }), weight());
            list.addView(row);
        }
    }

    private void edit(final ChannelArray.Row old) {
        float d = getResources().getDisplayMetrics().density;
        final ChannelArray.Row r = old != null ? old : new ChannelArray.Row();
        if (old == null) {
            r.code = ChannelArray.code(rows.size());
            r.priv = ChannelArray.newKey();
            fillDefaults(r);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText[] f = new EditText[ChannelArray.COLUMNS.length];
        for (int i = 0; i < f.length; i++) {
            f[i] = new EditText(this);
            f[i].setSingleLine(true);
            f[i].setHint(ChannelArray.COLUMNS[i] + (i == 8 ? " (lat, lon, km)" : ""));
            f[i].setText(ChannelArray.get(r, i));
            box.addView(f[i]);
        }
        final CheckBox listen = new CheckBox(this);
        listen.setText("Listen to this team");
        listen.setChecked(r.listen);
        box.addView(listen);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(old == null ? "New team" : r.label()).setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        for (int i = 0; i < f.length; i++) ChannelArray.set(r, i, f[i].getText().toString().trim());
                        r.listen = listen.isChecked();
                        if (old == null) rows.add(r);
                        if (r.member.length() == 0) r.member = member(r.code);
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (old != null) {
            b.setNeutralButton("More…", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    more(old);
                }
            });
        }
        b.show();
    }

    private void more(final ChannelArray.Row r) {
        final boolean isBlocked = ReportUp.blocked(this).contains(r.member);
        new AlertDialog.Builder(this).setTitle(r.label())
                .setItems(new String[]{"Print this card", "New key (the old QR stops working)", isBlocked ? "Unblock reports" : "Block reports from this code", "Delete team"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int w) {
                                if (w == 0) {
                                    List<ChannelArray.Row> one = new ArrayList<ChannelArray.Row>();
                                    one.add(r);
                                    print(one);
                                } else if (w == 1) {
                                    r.priv = ChannelArray.newKey();
                                    changed();
                                } else if (w == 2) {
                                    ReportUp.setBlocked(ChannelArrayActivity.this, r.member, !isBlocked);
                                    render();
                                } else {
                                    rows.remove(r);
                                    changed();
                                }
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** New rows take this phone's Public key, tunnel, cache and profile. */
    private void fillDefaults(ChannelArray.Row r) {
        Prefs p = new Prefs(this);
        r.pub = p.publicKey();
        r.remote = p.tunnelServer();
        r.tunnelKey = p.tunnelKey();
        r.lan = new org.communitycomms.app.geo.LanTiles(this).base();
        r.profile = org.communitycomms.app.geo.QuickProfiles.NAMES[new org.communitycomms.app.geo.QuickProfiles(this).profile()];
        r.member = member(r.code);
    }

    private ListenKey fullKey() {
        if (publicKey != null) return publicKey;
        String k = new Prefs(this).publicKey();
        if (!ListenKey.signedPublic(k) || ListenKey.isListenKey(k)) return null;
        try {
            publicKey = ListenKey.fromFull(k);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
        return publicKey;
    }

    /** Needs a changed Public key on this phone, because only full key holders can issue codes. */
    private String member(String code) {
        ListenKey lk = fullKey();
        return lk == null ? "" : lk.memberCode(code);
    }

    private void fill() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText count = new EditText(this);
        count.setHint("How many teams (up to 676)");
        count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(count);
        final List<SheetStore.Entry> sheets = new SheetStore(this).loadAll(false);
        List<String> names = new ArrayList<String>();
        names.add("Random keys");
        for (SheetStore.Entry e : sheets) names.add(e.id);
        final Spinner from = new Spinner(this);
        from.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        box.addView(from);
        new AlertDialog.Builder(this).setTitle("Fill AA-ZZ").setView(box)
                .setPositiveButton("Fill", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int n = 0;
                        try {
                            n = Math.min(676, Integer.parseInt(count.getText().toString().trim()));
                        } catch (NumberFormatException ignored) {
                        }
                        String seed = from.getSelectedItemPosition() == 0 ? null : sheets.get(from.getSelectedItemPosition() - 1).id;
                        int start = rows.size();
                        for (int i = start; i < n; i++) {
                            ChannelArray.Row r = new ChannelArray.Row();
                            r.code = ChannelArray.code(i);
                            r.priv = seed == null ? ChannelArray.newKey() : ChannelArray.keyFrom(seed, r.code);
                            fillDefaults(r);
                            rows.add(r);
                        }
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- QR and printing ----

    private String setupCode(ChannelArray.Row r) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", r.label()).put("team", r.code + (r.name.length() > 0 ? " " + r.name : ""));
            o.put("chA", r.priv);
            String pub = r.pub;
            if (readOnly.isChecked() && ListenKey.signedPublic(pub) && !ListenKey.isListenKey(pub)) pub = ListenKey.fromFull(pub).shareable();
            if (pub.length() > 0) o.put("pub", pub);
            if (r.remote.length() > 0) o.put("tunnel", r.remote);
            if (r.tunnelKey.length() > 0) o.put("tkey", r.tunnelKey);
            if (r.lan.length() > 0) o.put("url", r.lan);
            if (r.profile.length() > 0) o.put("prof", r.profile);
            if (r.area.length() > 0) o.put("area", r.area);
            if (r.member.length() > 0) o.put("code", r.member);
        } catch (JSONException | java.security.GeneralSecurityException e) {
            return null;
        }
        return SetupQr.PREFIX + o;
    }

    private void showQr(ChannelArray.Row r) {
        if (r.profile.length() > 0 && !java.util.Arrays.asList(org.communitycomms.app.geo.QuickProfiles.NAMES).contains(r.profile)) {
            Toast.makeText(this, "Profile \"" + r.profile + "\" is not one the app knows: it will be ignored", Toast.LENGTH_LONG).show();
        }
        ArrayList<String> p = new ArrayList<String>(), t = new ArrayList<String>();
        p.add(setupCode(r));
        t.add("Setup for " + r.label());
        QrShowActivity.start(this, p, t, null);
    }

    private static String img(String payload) throws com.google.zxing.WriterException {
        Bitmap b = QrShowActivity.render(payload);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return "<img style='width:6cm;height:6cm' src='data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) + "'/>";
    }

    /** One page a team: download QR, setup QR, instructions and the map area. */
    private void print(List<ChannelArray.Row> which) {
        if (which.isEmpty()) return;
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif'>");
        String download = UpdateFromUrl.url(this);
        try {
            for (ChannelArray.Row r : which) {
                h.append("<div style='page-break-after:always'><h1>Team ").append(esc(r.label())).append("</h1>");
                h.append("<table><tr><td>").append(img(download)).append("<br/><b>1. Install the app</b></td><td>")
                        .append(img(setupCode(r))).append("<br/><b>2. Scan in the app to set up</b></td></tr></table>");
                h.append("<ol><li>Scan the first code and install the app (allow installs when asked).</li>")
                        .append("<li>Open it and choose Scan QR in the setup wizard, then scan the second code.</li>")
                        .append("<li>Check each item it offers, then Set up.</li>")
                        .append("<li>Use Report to leaders for OK, Need help or SOS.</li></ol>");
                double[] a = ChannelArray.area(r.area);
                if (a != null) h.append("<p>Map area: ").append(a[0]).append(", ").append(a[1]).append(", ").append(a[2]).append(" km. Download it before you leave coverage.</p>");
                h.append("<p>Keep this page private: it holds your team's key.</p></div>");
            }
        } catch (com.google.zxing.WriterException e) {
            Toast.makeText(this, "A code is too long to print", Toast.LENGTH_LONG).show();
            return;
        }
        h.append("</body></html>");
        if (Build.VERSION.SDK_INT < 19) {
            Toast.makeText(this, "Printing needs Android 4.4 or later", Toast.LENGTH_LONG).show();
            return;
        }
        final WebView w = new WebView(this);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                printPage(w);
            }
        });
        w.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    @android.annotation.TargetApi(21)
    private void printPage(WebView w) {
        android.print.PrintManager pm = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
        if (pm != null) pm.print("Team cards", w.createPrintDocumentAdapter(), null);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    // ---- CSV ----

    private void export() {
        new AlertDialog.Builder(this).setTitle("Export the team list")
                .setMessage("The file holds every team's private key. Anyone who gets it can listen to all teams, so keep it safe.")
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < ChannelArray.COLUMNS.length; i++) sb.append(i == 0 ? "" : ",").append(ChannelArray.COLUMNS[i]);
                        sb.append('\n');
                        for (ChannelArray.Row r : rows) {
                            for (int i = 0; i < ChannelArray.COLUMNS.length; i++) sb.append(i == 0 ? "" : ",").append(quote(ChannelArray.get(r, i)));
                            sb.append('\n');
                        }
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Team list").putExtra(Intent.EXTRA_TEXT, sb.toString()), "Export the team list"));
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String quote(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_CSV || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri u = data.getData();
            InputStream in = getContentResolver().openInputStream(u);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            mapColumns(new String(o.toByteArray(), "UTF-8"));
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show();
        }
    }

    /** Asks what each column in the file is, and whether the first row is headings. */
    private void mapColumns(String text) {
        final String[] lines = text.replace("\r", "").split("\n");
        if (lines.length == 0) return;
        final List<String> first = BulkPois.fields(lines[0]);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final CheckBox headings = new CheckBox(this);
        headings.setText("The first row is headings, not a team");
        headings.setChecked(true);
        box.addView(headings);
        List<String> choices = new ArrayList<String>(java.util.Arrays.asList(ChannelArray.COLUMNS));
        choices.add("Ignore");
        final Spinner[] pick = new Spinner[first.size()];
        for (int i = 0; i < pick.length; i++) {
            TextView t = new TextView(this);
            t.setText("Column " + (i + 1) + ": " + first.get(i));
            box.addView(t);
            pick[i] = new Spinner(this);
            pick[i].setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, choices));
            int guess = choices.size() - 1;
            for (int k = 0; k < ChannelArray.COLUMNS.length; k++) if (ChannelArray.COLUMNS[k].equalsIgnoreCase(first.get(i).trim())) guess = k;
            pick[i].setSelection(guess);
            box.addView(pick[i]);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Which column is which?").setView(sv)
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        int added = 0;
                        for (int l = headings.isChecked() ? 1 : 0; l < lines.length; l++) {
                            if (lines[l].trim().length() == 0) continue;
                            List<String> cells = BulkPois.fields(lines[l]);
                            ChannelArray.Row r = new ChannelArray.Row();
                            r.code = ChannelArray.code(rows.size());
                            fillDefaults(r);
                            r.priv = ChannelArray.newKey();
                            for (int i = 0; i < pick.length && i < cells.size(); i++) {
                                int c = pick[i].getSelectedItemPosition();
                                if (c < ChannelArray.COLUMNS.length && cells.get(i).trim().length() > 0) ChannelArray.set(r, c, cells.get(i).trim());
                            }
                            rows.add(r);
                            added++;
                        }
                        changed();
                        Toast.makeText(ChannelArrayActivity.this, added + " teams added", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
