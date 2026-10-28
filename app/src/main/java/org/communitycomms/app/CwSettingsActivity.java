package org.communitycomms.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.audio.Morse;

/** DRYAD Enigma > CW settings: message format, speed, tone, repeats and the clear air round a message. */
public final class CwSettingsActivity extends Activity {
    private SharedPreferences sp;
    private EditText format;
    private TextView example;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("CW settings");
        sp = Cw.sp(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (12 * d));

        box.addView(label("Message format"));
        format = new EditText(this);
        format.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        format.setTypeface(android.graphics.Typeface.MONOSPACE);
        format.setText(Cw.format(this));
        box.addView(format);
        box.addView(note("Variables: %R% a random letter, %SheetID%, %StartRow%, %CipherText%, %Call% (your callsign). "
                + "Anything else is sent as written; prosigns go in angle brackets, e.g. <AR> (end of message), <BT>, <SK>. "
                + "Receiving reads the sheet ID, start row and cipher text back out using the same format. "
                + "Example for a net: VVV %R%%R% %SheetID% %StartRow% %CipherText% <AR>"));
        example = note("");
        box.addView(example);
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("Default format");
        reset.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                format.setText(Cw.DEFAULT_FORMAT);
            }
        });
        box.addView(reset);

        slider(box, "wpm", 15, 5, 40, 1, "Speed: %d words a minute");
        slider(box, "tone", 700, 400, 1200, 10, "Tone: %d Hz");
        slider(box, "repeats", 1, 1, 5, 1, "Send each message %d time(s)");
        slider(box, "preMs", 500, 0, 3000, 100, "Clear air before: %d ms (time for the radio's VOX to key up)");
        slider(box, "postMs", 1000, 0, 3000, 100, "Clear air after: %d ms");
        slider(box, "clearAir", 3, 1, 15, 1, "Receiving: a message is done after %d s with no tone");
        tick(box, "autoChat", "Put received CW in Chat: \"CW Decoded: …\" (the plain text) when it decodes, "
                + "\"CW received: …\" (what was heard) when it does not. Sent to the channel on air.");
        tick(box, "autoRelay", "In CW mode, send private texts by CW: each text received on a private channel (or just to "
                + "me) is encoded on the sheet last used on the Enigma page, from its next unused row, and sent to the radio "
                + "when the air is clear. Chat notes what went.");
        box.addView(note("CW files are kept on this phone (Enigma > ⋯ > CW files), where they can be played, decoded, "
                + "joined or split."));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
        format.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                showExample();
            }
        });
        showExample();
    }

    @Override
    protected void onPause() {
        String f = format.getText().toString().trim();
        if (!f.toUpperCase(java.util.Locale.US).contains("%CIPHER")) {
            Toast.makeText(this, "The format has no %CipherText%: the message would be missing", Toast.LENGTH_LONG).show();
        }
        sp.edit().putString("format", f.length() == 0 ? Cw.DEFAULT_FORMAT : f).commit();
        super.onPause();
    }

    private void showExample() {
        String f = format.getText().toString();
        sp.edit().putString("format", f).apply();
        String msg = Cw.build(this, "ABCDE", "F", "QWERTYXZ");
        example.setText("Sounds like: " + msg + "  (about " + Math.round(Morse.durationMs(msg, Cw.params(this)) / 1000.0) + " s)");
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    private TextView note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        return v;
    }

    private void tick(LinearLayout box, final String key, String text) {
        android.widget.CheckBox c = new android.widget.CheckBox(this);
        c.setText(text);
        c.setChecked(sp.getBoolean(key, false));
        c.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp.edit().putBoolean(key, on).commit();
            }
        });
        box.addView(c);
    }

    private void slider(LinearLayout box, final String key, int dflt, final int min, int max, final int step, final String text) {
        final TextView l = label("");
        box.addView(l);
        SeekBar s = new SeekBar(this);
        s.setMax((max - min) / step);
        s.setProgress((sp.getInt(key, dflt) - min) / step);
        l.setText(String.format(java.util.Locale.US, text, sp.getInt(key, dflt)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                int v = min + p * step;
                l.setText(String.format(java.util.Locale.US, text, v));
                if (user) {
                    sp.edit().putInt(key, v).apply();
                    showExample();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        box.addView(s);
    }
}
