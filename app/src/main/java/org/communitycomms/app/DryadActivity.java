package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.dryad.ColourAdapter;
import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetColour;
import org.communitycomms.app.dryad.SheetStore;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The "sheet in use" page: pick a sheet from the drop-down (the currently valid one is chosen for
 * you), read it, mark it used. Links to the generate, share and pigpen pages. Also receives shared
 * text so sheets sent from another phone import in one tap.
 */
public final class DryadActivity extends Activity {
    private SheetStore store;
    private Spinner picker, rowPicker;
    private TextView table, info;
    private List<SheetStore.Entry> entries = new ArrayList<SheetStore.Entry>();
    private SheetStore.Entry current;
    private int defaultTextColour;

    static String statusLabel(DryadSheet s, long now) {
        switch (s.status(now)) {
            case DryadSheet.UPCOMING:
                return "upcoming";
            case DryadSheet.EXPIRED:
                return "expired";
            case DryadSheet.USED:
                return "used";
            default:
                return "ACTIVE";
        }
    }

    static String label(DryadSheet s, long now) {
        return (s.colour == null ? "" : "■ ") + s.name + (s.colour == null ? "" : " · " + s.colour)
                + "  [" + statusLabel(s, now) + "]";
    }

    static String window(DryadSheet s) {
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        String from = s.validFrom == 0 ? "any time" : f.format(new Date(s.validFrom));
        String to = s.validTo == 0 ? "no expiry" : f.format(new Date(s.validTo));
        return from + "  →  " + to;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_dryad);
        setTitle(R.string.dryad);
        store = new SheetStore(this);
        picker = (Spinner) findViewById(R.id.sheet);
        table = (TextView) findViewById(R.id.table);
        info = (TextView) findViewById(R.id.info);
        defaultTextColour = table.getCurrentTextColor();

        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                show(entries.get(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        click(R.id.generate, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(DryadActivity.this, DryadGenerateActivity.class));
            }
        });
        click(R.id.share, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(DryadActivity.this, DryadShareActivity.class));
            }
        });
        click(R.id.import_sheet, new Runnable() {
            @Override
            public void run() {
                askImport("");
            }
        });
        click(R.id.pigpen, new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(DryadActivity.this, PigpenActivity.class);
                if (current != null) i.putExtra(PigpenActivity.EXTRA_SHEET, current.id);
                startActivity(i);
            }
        });
        click(R.id.enigma, new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(DryadActivity.this, EnigmaActivity.class);
                if (current != null) i.putExtra(EnigmaActivity.EXTRA_ID, current.sheet.id());
                startActivity(i);
            }
        });
        click(R.id.challenge, new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(DryadActivity.this, ChallengeActivity.class);
                if (current != null) i.putExtra(EnigmaActivity.EXTRA_ID, current.sheet.id());
                startActivity(i);
            }
        });
        click(R.id.mark_used, new Runnable() {
            @Override
            public void run() {
                confirmUsed();
            }
        });
        click(R.id.settings, new Runnable() {
            @Override
            public void run() {
                editAgeRules();
            }
        });
        rowPicker = (Spinner) findViewById(R.id.row);
        setRowPicker(DryadSheet.ROWS);
        click(R.id.row_to_a, new Runnable() {
            @Override
            public void run() {
                rowToChannel(Prefs.SLOT_A);
            }
        });
        click(R.id.row_to_b, new Runnable() {
            @Override
            public void run() {
                rowToChannel(Prefs.SLOT_B);
            }
        });
        click(R.id.row_to_x, new Runnable() {
            @Override
            public void run() {
                pickChannelForRow();
            }
        });
        click(R.id.send_air, new Runnable() {
            @Override
            public void run() {
                DryadShareActivity.pickAndSend(DryadActivity.this, entries);
            }
        });
        click(R.id.show_qr, new Runnable() {
            @Override
            public void run() {
                if (current != null) DryadShareActivity.showQr(DryadActivity.this, Collections.singletonList(current.sheet));
            }
        });
        click(R.id.scan_qr, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(DryadActivity.this, QrScanActivity.class));
            }
        });
        click(R.id.delete, new Runnable() {
            @Override
            public void run() {
                confirmDelete();
            }
        });
        handleShareIntent(getIntent());
    }

    /**
     * Uses a sheet row as a radio channel name: the row's 26 letters. Everyone holding the same
     * sheet who picks the same row lands on the same encrypted channel.
     */
    private int rowPickerCount;

    /** Row choices: A–Z, or AA–ZZ on a huge sheet. */
    private void setRowPicker(int count) {
        if (count == rowPickerCount) return;
        rowPickerCount = count;
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            rows.add("Row " + (count == DryadSheet.ROWS ? String.valueOf((char) ('A' + i))
                    : "" + (char) ('A' + i / 26) + (char) ('A' + i % 26)));
        }
        rowPicker.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, rows));
    }

    private void rowToChannel(int slot) {
        if (current == null) return;
        int idx = Math.max(0, Math.min(current.sheet.rowCount() - 1, rowPicker.getSelectedItemPosition()));
        String row = current.sheet.rowLabel(idx);
        Prefs p = new Prefs(this);
        String name = new String(current.sheet.rowAt(idx));
        if (slot == Prefs.SLOT_PUBLIC) p.setPublicKey(name); // a group's own "public" channel
        else p.setChannel(slot, name);
        if (slot >= Prefs.SLOT_C) p.setExtrasOn(true); // so C–H shows on the Channels page
        Toast.makeText(this, MainActivity.label(slot) + " set from " + current.sheet.name
                + " row " + row + ". Join or listen to it on the Channels page.", Toast.LENGTH_LONG).show();
    }

    /** "→ X": put the row on any channel: A–H, or Public (replacing the Public key). */
    private void pickChannelForRow() {
        if (current == null) return;
        final List<Integer> slots = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        Prefs p = new Prefs(this);
        for (int s : Prefs.order()) {
            if (s == Prefs.SLOT_OPENMANET) continue;
            slots.add(s);
            names.add(MainActivity.label(s) + (s == Prefs.SLOT_PUBLIC ? " (replaces the Public key)"
                    : p.channel(s).length() > 0 ? " (replaces its name)" : ""));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Put this row on which channel?")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        rowToChannel(slots.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PinLock.gateScreen(this, "Enter your PIN to open Modern DRYAD.");
        refresh(current == null ? null : current.id);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) askImport(text);
        }
    }

    /** Reloads, applying age rules. Selects keepId, else the active sheet, else the first. */
    private void refresh(String keepId) {
        int purged = store.purge();
        if (purged > 0) Toast.makeText(this, purged + " old sheet(s) deleted by age rules", Toast.LENGTH_SHORT).show();
        entries = store.loadAll(false);
        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<String>();
        int select = -1;
        List<Integer> colours = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            DryadSheet s = entries.get(i).sheet;
            labels.add(label(s, now));
            colours.add(s.colour == null ? null : SheetColour.argb(s.colour));
            if (entries.get(i).id.equals(keepId)) select = i;
        }
        if (select < 0) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).sheet.status(now) == DryadSheet.ACTIVE) {
                    select = i;
                    break;
                }
            }
        }
        picker.setAdapter(new ColourAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels, colours));
        if (entries.isEmpty()) {
            current = null;
            info.setText("");
            table.setText(R.string.dryad_none);
            return;
        }
        if (select < 0) select = 0;
        picker.setSelection(select);
        show(entries.get(select));
    }

    private void show(SheetStore.Entry e) {
        current = e;
        DryadSheet s = e.sheet;
        info.setText("ID " + s.id() + "   " + (s.colour == null ? "" : s.colour.toUpperCase() + "   ")
                + statusLabel(s, System.currentTimeMillis()) + "   " + window(s) + "\n" + s.formatName()
                + (s.rowCount() == DryadSheet.BIG_ROWS ? " · 676 rows AA–ZZ" : ""));
        setRowPicker(s.rowCount());
        // The whole table takes the sheet's colour so the right sheet is obvious at a glance.
        int c = s.colour == null ? defaultTextColour : SheetColour.argb(s.colour);
        info.setTextColor(c);
        table.setTextColor(c);
        StringBuilder sb = new StringBuilder();
        boolean big = s.rowCount() == DryadSheet.BIG_ROWS;
        sb.append(big ? "    " : "   ").append(s.formatHeader()).append('\n');
        for (int r : s.printOrder()) {
            sb.append(s.rowLabel(r)).append("  ").append(s.formatRow(r)).append('\n');
        }
        table.setText(sb);
    }

    private void askImport(String initial) {
        final EditText text = new EditText(this);
        text.setHint("Paste one or more shared sheets");
        text.setMinLines(6);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setText(initial);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dryad_import)
                .setView(text)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            List<DryadSheet> sheets = DryadSheet.parseAll(text.getText().toString());
                            int added = store.importAll(sheets);
                            int dup = sheets.size() - added;
                            Toast.makeText(DryadActivity.this, "Imported " + added + " sheet(s)"
                                    + (dup > 0 ? ", " + dup + " already held" : ""), Toast.LENGTH_SHORT).show();
                            refresh(null);
                        } catch (Exception e) {
                            Toast.makeText(DryadActivity.this, "Not a valid sheet: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmUsed() {
        if (current == null) return;
        String msg = store.deleteWhenUsed()
                ? "Mark \"" + current.sheet.name + "\" used? Your age rules delete used sheets straight away."
                : "Mark \"" + current.sheet.name + "\" used?";
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.dryad_used, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        current.sheet.used = true;
                        try {
                            store.update(current.id, current.sheet);
                        } catch (Exception e) {
                            Toast.makeText(DryadActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        refresh(null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editAgeRules() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final CheckBox used = new CheckBox(this);
        used.setText("Delete a sheet as soon as it is marked used");
        used.setChecked(store.deleteWhenUsed());
        final CheckBox expired = new CheckBox(this);
        expired.setText("Delete sheets after they expire");
        expired.setChecked(store.deleteWhenExpired());
        TextView l = new TextView(this);
        l.setText("Keep expired sheets for (hours):");
        final EditText hours = new EditText(this);
        hours.setInputType(InputType.TYPE_CLASS_NUMBER);
        hours.setText(String.valueOf(store.keepExpiredHours()));
        box.addView(used);
        box.addView(expired);
        box.addView(l);
        box.addView(hours);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dryad_age)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int h;
                        try {
                            h = Math.max(0, Integer.parseInt(hours.getText().toString().trim()));
                        } catch (NumberFormatException e) {
                            h = 24;
                        }
                        store.setAgeRules(used.isChecked(), expired.isChecked(), h);
                        refresh(current == null ? null : current.id);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Same tick list as Send on air (All at the top, all ticked), then a final "are you sure". */
    private void confirmDelete() {
        if (entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        final String[] items = new String[entries.size() + 1];
        final boolean[] checked = new boolean[items.length];
        items[0] = "All (" + entries.size() + ")";
        for (int i = 0; i < entries.size(); i++) items[i + 1] = label(entries.get(i).sheet, now);
        java.util.Arrays.fill(checked, true);
        new AlertDialog.Builder(this)
                .setTitle("Delete sheets")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean on) {
                        android.widget.ListView lv = ((AlertDialog) d).getListView();
                        if (which == 0) {
                            for (int i = 1; i < checked.length; i++) {
                                checked[i] = on;
                                lv.setItemChecked(i, on);
                            }
                        } else {
                            checked[which] = on;
                            boolean all = true;
                            for (int i = 1; i < checked.length; i++) all &= checked[i];
                            checked[0] = all;
                            lv.setItemChecked(0, all);
                        }
                    }
                })
                .setPositiveButton(R.string.dryad_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final List<SheetStore.Entry> doomed = new ArrayList<SheetStore.Entry>();
                        for (int i = 0; i < entries.size(); i++) if (checked[i + 1]) doomed.add(entries.get(i));
                        if (doomed.isEmpty()) return;
                        new AlertDialog.Builder(DryadActivity.this)
                                .setMessage("Delete " + (doomed.size() == entries.size() ? "all " : "") + doomed.size()
                                        + " sheet(s) from this phone? This cannot be undone.")
                                .setPositiveButton(R.string.dryad_delete, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        for (SheetStore.Entry e : doomed) store.delete(e.id);
                                        current = null;
                                        refresh(null);
                                        Toast.makeText(DryadActivity.this, "Deleted " + doomed.size() + " sheet(s)",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
