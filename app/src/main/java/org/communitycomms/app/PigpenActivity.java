package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.Pigpen;
import org.communitycomms.app.dryad.PigpenKeyboardView;
import org.communitycomms.app.dryad.PigpenStripView;
import org.communitycomms.app.dryad.SheetStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Pigpen keyed by a DRYAD row. Encode: type text, see symbols. Decode: tap symbols on the pad,
 * see letters. Changing sheet or row re-keys what is already on screen.
 */
public final class PigpenActivity extends Activity {
    public static final String EXTRA_SHEET = "sheet";

    private List<SheetStore.Entry> entries;
    private Spinner sheetPicker, rowPicker;
    private TextView keyLine, decoded;
    private EditText plain;
    private PigpenStripView strip;
    private View pad;
    private boolean decodeMode;
    /** Decode mode input: slots, or PigpenStripView.literal(' ') for spaces. */
    private final List<Integer> tapped = new ArrayList<Integer>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pigpen);
        setTitle(R.string.pigpen);
        entries = new SheetStore(this).loadAll(true);
        sheetPicker = (Spinner) findViewById(R.id.sheet);
        rowPicker = (Spinner) findViewById(R.id.row);
        keyLine = (TextView) findViewById(R.id.key);
        decoded = (TextView) findViewById(R.id.decoded);
        plain = (EditText) findViewById(R.id.plain);
        strip = (PigpenStripView) findViewById(R.id.strip);
        pad = findViewById(R.id.pad);

        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<String>();
        List<Integer> colours = new ArrayList<Integer>();
        int select = 0;
        String want = getIntent().getStringExtra(EXTRA_SHEET);
        for (int i = 0; i < entries.size(); i++) {
            SheetStore.Entry e = entries.get(i);
            labels.add(DryadActivity.label(e.sheet, now));
            colours.add(e.sheet.colour == null ? null : org.communitycomms.app.dryad.SheetColour.argb(e.sheet.colour));
            if (e.id.equals(want)) select = i;
        }
        labels.add(getString(R.string.pig_classic));
        colours.add(null);
        sheetPicker.setAdapter(new org.communitycomms.app.dryad.ColourAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, labels, colours));
        sheetPicker.setSelection(select);

        setRows(DryadSheet.ROWS);

        AdapterView.OnItemSelectedListener rekey = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                int s = sheetPicker.getSelectedItemPosition();
                setRows(s >= 0 && s < entries.size() ? entries.get(s).sheet.rowCount() : DryadSheet.ROWS);
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        sheetPicker.setOnItemSelectedListener(rekey);
        rowPicker.setOnItemSelectedListener(rekey);

        ((RadioGroup) findViewById(R.id.mode)).setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                decodeMode = id == R.id.mode_decode;
                plain.setVisibility(decodeMode ? View.GONE : View.VISIBLE);
                pad.setVisibility(decodeMode ? View.VISIBLE : View.GONE);
                render();
            }
        });
        plain.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        ((PigpenKeyboardView) findViewById(R.id.keyboard)).setOnSymbol(new PigpenKeyboardView.OnSymbol() {
            @Override
            public void onSymbol(int slot) {
                tapped.add(slot);
                render();
            }
        });
        findViewById(R.id.space).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapped.add(PigpenStripView.literal(' '));
                render();
            }
        });
        findViewById(R.id.backspace).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tapped.isEmpty()) tapped.remove(tapped.size() - 1);
                render();
            }
        });
        findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapped.clear();
                render();
            }
        });
        // Text from the Enigma page, and a way back there with this text.
        String text = getIntent().getStringExtra(EnigmaActivity.EXTRA_TEXT);
        if (text != null) plain.setText(text);
        android.widget.Button toEnigma = new android.widget.Button(this);
        toEnigma.setText("Send to Enigma");
        toEnigma.setAllCaps(false);
        toEnigma.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(PigpenActivity.this, EnigmaActivity.class);
                i.putExtra(EnigmaActivity.EXTRA_TEXT, decodeMode ? decoded.getText().toString() : plain.getText().toString());
                int s = sheetPicker.getSelectedItemPosition();
                if (s >= 0 && s < entries.size()) i.putExtra(EnigmaActivity.EXTRA_ID, entries.get(s).sheet.id());
                startActivity(i);
            }
        });
        ((android.view.ViewGroup) findViewById(R.id.mode).getParent()).addView(toEnigma);
    }

    private int rowsShown;

    private void setRows(int count) {
        if (count == rowsShown) return;
        rowsShown = count;
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            rows.add("Row " + (count == DryadSheet.ROWS ? String.valueOf((char) ('A' + i))
                    : "" + (char) ('A' + i / 26) + (char) ('A' + i % 26)));
        }
        rowPicker.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, rows));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PinLock.gateScreen(this, "Enter your PIN to open pigpen.");
    }

    private char[] key() {
        int s = sheetPicker.getSelectedItemPosition();
        if (s < 0 || s >= entries.size()) return Pigpen.CLASSIC;
        DryadSheet sheet = entries.get(s).sheet;
        return sheet.pigpenKeyAt(Math.max(0, Math.min(sheet.rowCount() - 1, rowPicker.getSelectedItemPosition())));
    }

    private void render() {
        char[] key = key();
        boolean classic = key == Pigpen.CLASSIC;
        rowPicker.setEnabled(!classic);
        int si = sheetPicker.getSelectedItemPosition();
        if (classic || si < 0 || si >= entries.size()) {
            keyLine.setText("Key: " + new String(key) + "   (. = space, @ = new line)");
        } else {
            DryadSheet sh = entries.get(si).sheet;
            int ri = Math.max(0, Math.min(sh.rowCount() - 1, rowPicker.getSelectedItemPosition()));
            keyLine.setText("ID " + sh.id() + " row " + sh.rowLabel(ri) + "  Key: " + new String(sh.rowAt(ri)) + (sh.isModern()
                    ? "   (" + sh.spaceSymbol + " = space, " + sh.eolSymbol + " = new line)" : ""));
        }
        if (decodeMode) {
            StringBuilder sb = new StringBuilder();
            for (int t : tapped) sb.append(t >= 0 ? Pigpen.decode(key, t) : (char) (-t - 1));
            strip.setTokens(tapped);
            decoded.setText(sb);
        } else {
            String text = plain.getText().toString();
            int[] slots = Pigpen.encode(key, text);
            List<Integer> tokens = new ArrayList<Integer>();
            for (int i = 0; i < slots.length; i++) {
                tokens.add(slots[i] >= 0 ? slots[i] : PigpenStripView.literal(text.charAt(i)));
            }
            strip.setTokens(tokens);
            decoded.setText("");
        }
    }
}
