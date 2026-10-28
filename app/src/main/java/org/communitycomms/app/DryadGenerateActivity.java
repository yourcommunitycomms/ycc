package org.communitycomms.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetColour;
import org.communitycomms.app.dryad.SheetStore;

import java.util.Calendar;
import java.util.TimeZone;

/** Bulk-generates a run of sheets with back-to-back validity windows. */
public final class DryadGenerateActivity extends Activity {
    private static final String[] PERIOD_LABELS = {"6 hours", "12 hours", "1 day", "2 days", "1 week", "No expiry"};
    private static final long H = 3600000L;
    private static final long[] PERIODS = {6 * H, 12 * H, 24 * H, 48 * H, 168 * H, 0};
    private static final String[] START_LABELS = {"Now", "Today 00:00 UTC", "Tomorrow 00:00 UTC"};
    private static final int MAX_SHEETS = 100;

    /** Labels for DryadSheet.SPECIAL_CHOICES, same order. */
    private static final String[] SPECIAL_LABELS = {
            ".  full stop", "@  at", ",  comma", ";  semicolon", ":  colon", "(  open bracket", ")  close bracket",
            "[  open square", "]  close square", "/  slash", "-  dash", "?  question", "!  exclamation", "#  hash",
            "*  star", "+  plus", "=  equals", "_  underscore", "&  ampersand", "%  percent"};

    private EditText name, count, phrase;
    private Spinner period, start, spaceSymbol, eolSymbol;
    private RadioGroup source;
    private TextView result;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_dryad_generate);
        setTitle(R.string.gen_title);
        name = (EditText) findViewById(R.id.name);
        count = (EditText) findViewById(R.id.count);
        phrase = (EditText) findViewById(R.id.phrase);
        period = (Spinner) findViewById(R.id.period);
        start = (Spinner) findViewById(R.id.start);
        source = (RadioGroup) findViewById(R.id.source);
        result = (TextView) findViewById(R.id.result);

        name.setText("Team DRYAD");
        period.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, PERIOD_LABELS));
        period.setSelection(2);
        start.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, START_LABELS));
        start.setSelection(1);
        spaceSymbol = (Spinner) findViewById(R.id.space_symbol);
        eolSymbol = (Spinner) findViewById(R.id.eol_symbol);
        spaceSymbol.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, SPECIAL_LABELS));
        eolSymbol.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, SPECIAL_LABELS));
        spaceSymbol.setSelection(DryadSheet.SPECIAL_CHOICES.indexOf(DryadSheet.SPACE));
        eolSymbol.setSelection(DryadSheet.SPECIAL_CHOICES.indexOf(DryadSheet.END_OF_LINE));
        source.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                phrase.setVisibility(id == R.id.src_phrase ? View.VISIBLE : View.GONE);
                if (id == R.id.src_phrase) {
                    new android.app.AlertDialog.Builder(DryadGenerateActivity.this)
                            .setTitle(R.string.gen_phrase)
                            .setMessage(R.string.gen_phrase_warn)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNegativeButton(android.R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    source.check(R.id.src_random);
                                }
                            })
                            .show();
                }
            }
        });
        findViewById(R.id.go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generate();
            }
        });
    }

    private long startTime() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        if (start.getSelectedItemPosition() == 0) {
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (start.getSelectedItemPosition() == 2) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private void generate() {
        final String base = name.getText().toString().trim().length() == 0 ? "DRYAD" : name.getText().toString().trim();
        int n;
        try {
            n = Integer.parseInt(count.getText().toString().trim());
        } catch (NumberFormatException e) {
            n = 1;
        }
        final int total = Math.max(1, Math.min(MAX_SHEETS, n));
        final long len = PERIODS[period.getSelectedItemPosition()];
        final long t0 = startTime();
        final boolean usePhrase = source.getCheckedRadioButtonId() == R.id.src_phrase;
        final String ph = phrase.getText().toString().trim();
        if (usePhrase && ph.length() == 0) {
            Toast.makeText(this, "Enter the shared phrase", Toast.LENGTH_SHORT).show();
            return;
        }
        final char sp = DryadSheet.SPECIAL_CHOICES.charAt(spaceSymbol.getSelectedItemPosition());
        final char eol = DryadSheet.SPECIAL_CHOICES.charAt(eolSymbol.getSelectedItemPosition());
        if (sp == eol) {
            Toast.makeText(this, "Space and new line need different symbols", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean big = ((android.widget.CheckBox) findViewById(R.id.big_sheet)).isChecked();
        final boolean shuffle = ((android.widget.CheckBox) findViewById(R.id.shuffle_order)).isChecked();
        if (usePhrase && (big || shuffle)) {
            Toast.makeText(this, "Phrase sheets are always the normal A-Z kind", Toast.LENGTH_LONG).show();
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Generating " + total + " sheet(s)…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                SheetStore store = new SheetStore(DryadGenerateActivity.this);
                java.security.SecureRandom rnd = new java.security.SecureRandom();
                String prevColour = null;
                try {
                    for (int i = 0; i < total; i++) {
                        String nm = (total == 1 ? base : base + " #" + (i + 1)) + (usePhrase ? " (EMERGENCY)" : "");
                        // Phrase sheets: sheet #i always gets the same letters for the same phrase,
                        // so two phones only need to agree on the phrase (dates are separate).
                        // Random sheets get a 5-letter identity at the start of the name (Enigma mode).
                        DryadSheet s = usePhrase
                                ? DryadSheet.fromSeed(nm, ph + "|" + i, sp, eol)
                                : DryadSheet.random(DryadSheet.newId() + " " + nm, sp, eol,
                                big ? DryadSheet.BIG_ROWS : DryadSheet.ROWS, shuffle);
                        if (usePhrase) {
                            // Derived from the letters so every phone rebuilding the batch agrees.
                            int k = (s.fingerprint().hashCode() & 0x7fffffff) % SheetColour.NAMES.length;
                            if (SheetColour.NAMES[k].equals(prevColour)) k = (k + 1) % SheetColour.NAMES.length;
                            s.colour = SheetColour.NAMES[k];
                        } else {
                            s.colour = SheetColour.random(rnd, prevColour);
                        }
                        prevColour = s.colour;
                        if (len != 0) {
                            s.validFrom = t0 + i * len;
                            s.validTo = s.validFrom + len;
                        } else {
                            s.validFrom = t0;
                        }
                        store.add(s);
                    }
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String err) {
                pd.dismiss();
                if (err != null) {
                    result.setText("Failed: " + err);
                } else {
                    Toast.makeText(DryadGenerateActivity.this, "Made " + total + " sheet(s)", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        }.execute();
    }
}
