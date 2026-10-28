package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.audio.Morse;
import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.Pigpen;
import org.communitycomms.app.dryad.PigpenKeyboardView;
import org.communitycomms.app.dryad.PigpenStripView;
import org.communitycomms.app.dryad.SheetStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DRYAD Enigma mode: a rotor-like cipher on a DRYAD sheet. Each letter is swapped using the next
 * row of the sheet (A, B, C… looping back to the first), so the same letter comes out differently
 * every time. Both sides need the sheet's 5-letter identity and the start row, which can be shown
 * (and read back) as classic A–Z pigpen, or sent with the cipher text in a QR code.
 * <p>
 * Swap rule: plain symbol at alphabet position i (A–Z, space, new line) ↔ the symbol at position i
 * of the row. In cipher text '.' stands for the sheet's space symbol and '@' for its new line.
 */
public final class EnigmaActivity extends Activity {
    public static final String EXTRA_ID = "id", EXTRA_TEXT = "text", EXTRA_QR = "qr";
    /** QR: YCCE1|sheet id|start row|cipher text. */
    public static final String QR_PREFIX = "YCCE1|";
    private static final int REQ_QR = 7;
    private static final String[] DIGITS = {"ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE"};

    private EditText idField, startField, plain, cipher;
    private TextView sheetLine;
    private Button action, qr;
    private boolean decode, fixing;
    private List<SheetStore.Entry> sheets;
    private RadioButton enc, dec;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("DRYAD Enigma");
        sheets = new SheetStore(this).loadAll(false);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (8 * d));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView l1 = new TextView(this);
        l1.setText("Sheet ID ");
        top.addView(l1);
        idField = new EditText(this);
        idField.setSingleLine(true);
        idField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        idField.setHint("ABCDE");
        top.addView(idField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView l2 = new TextView(this);
        l2.setText("  Start row ");
        top.addView(l2);
        startField = new EditText(this);
        startField.setSingleLine(true);
        startField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        startField.setText("A");
        top.addView(startField, new LinearLayout.LayoutParams((int) (64 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(top);
        sheetLine = new TextView(this);
        sheetLine.setTextSize(12);
        root.addView(sheetLine);

        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        enc = new RadioButton(this);
        dec = new RadioButton(this);
        enc.setText("Encode");
        dec.setText("Decode");
        enc.setId(0x7e01);
        dec.setId(0x7e02);
        mode.addView(enc);
        mode.addView(dec);
        enc.setChecked(true);
        mode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                decode = id == 0x7e02;
                renderButtons();
            }
        });
        root.addView(mode);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        plain = textBox(box, "Plain text (A–Z, space, new line; numbers become words)");
        action = new Button(this);
        action.setAllCaps(false);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doCipher();
            }
        });
        box.addView(action);
        cipher = textBox(box, "Cipher text ('.' = space, '@' = new line)");
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button pigId = small("Pigpen ID", new Runnable() {
            @Override
            public void run() {
                showHeader();
            }
        });
        qr = small("Create QR", new Runnable() {
            @Override
            public void run() {
                if (decode) startActivityForResult(new Intent(EnigmaActivity.this, QrScanActivity.class), REQ_QR);
                else createQr();
            }
        });
        Button toPig = small("Send to pigpen", new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(EnigmaActivity.this, PigpenActivity.class);
                i.putExtra(EXTRA_TEXT, decode ? plain.getText().toString() : cipherAsText(cipher.getText().toString()));
                startActivity(i);
            }
        });
        Button readPig = small("Enter pigpen ID", new Runnable() {
            @Override
            public void run() {
                readHeader();
            }
        });
        // CW (Morse) row above the pigpen row: the lock enters CW mode for Send and Receive.
        root.addView(cwRow());
        LinearLayout pigRow = FilesActivity.row(pigId, qr, toPig, readPig);
        for (Button x : new Button[]{pigId, qr, toPig, readPig}) {
            x.setTextSize(12);
            x.setPadding((int) (2 * d), 0, (int) (2 * d), 0);
        }
        root.addView(pigRow);
        setContentView(root);

        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) idField.setText(id);
        else if (!sheets.isEmpty()) idField.setText(sheets.get(0).sheet.id());
        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                renderSheet();
            }
        };
        idField.addTextChangedListener(w);
        startField.addTextChangedListener(w);
        plain.addTextChangedListener(cleaner(plain, false));
        cipher.addTextChangedListener(cleaner(cipher, true));
        String text = getIntent().getStringExtra(EXTRA_TEXT);
        if (text != null) plain.setText(text);
        renderSheet();
        renderButtons();
        String q = getIntent().getStringExtra(EXTRA_QR);
        if (q != null) {
            enc.setChecked(false);
            dec.setChecked(true);
            useQr(q);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCw();
        PinLock.gateScreen(this, "Enter your PIN to open DRYAD Enigma.");
    }

    private EditText textBox(LinearLayout box, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final EditText e = new EditText(this);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(iconButton(android.R.drawable.ic_menu_edit, "Paste", new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData c = cm == null ? null : cm.getPrimaryClip();
                if (c == null || c.getItemCount() == 0) {
                    Toast.makeText(EnigmaActivity.this, "Nothing to paste", Toast.LENGTH_SHORT).show();
                    return;
                }
                CharSequence s = c.getItemAt(0).coerceToText(EnigmaActivity.this);
                e.setText(s);
                e.setSelection(e.getText().length());
            }
        }));
        row.addView(iconButton(android.R.drawable.ic_menu_save, "Copy", new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("enigma", e.getText().toString()));
                Toast.makeText(EnigmaActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
        }));
        box.addView(row);
        box.addView(e);
        return e;
    }

    private View iconButton(int icon, final String label, final Runnable r) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        l.addView(b, new LinearLayout.LayoutParams((int) (44 * d), (int) (40 * d)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(10);
        l.addView(t);
        return l;
    }

    private Button small(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    /**
     * Keeps a box clean as it is typed or pasted into: upper case, digits as words (13 → ONE THREE)
     * in plain text, and anything else removed with one alert per change (so one for a paste).
     */
    private TextWatcher cleaner(final EditText e, final boolean isCipher) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int x) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int x) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                if (fixing) return;
                Set<String> bad = new LinkedHashSet<String>();
                String in = ed.toString();
                String out = isCipher ? cleanCipher(in, bad) : cleanPlain(in, bad);
                if (!out.equals(in)) {
                    fixing = true;
                    int sel = Math.max(0, Math.min(out.length(), e.getSelectionEnd() + out.length() - in.length()));
                    ed.replace(0, ed.length(), out);
                    e.setSelection(sel);
                    fixing = false;
                }
                if (!bad.isEmpty()) {
                    StringBuilder s = new StringBuilder();
                    for (String x : bad) s.append(s.length() > 0 ? "  " : "").append(x);
                    new AlertDialog.Builder(EnigmaActivity.this)
                            .setTitle("Characters removed")
                            .setMessage((isCipher ? "Cipher text only uses A–Z, '.' and '@'." : "Only A–Z, space, new line and numbers can be sent.")
                                    + " Removed: " + s)
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            }
        };
    }

    static String cleanPlain(String in, Set<String> bad) {
        String up = in.toUpperCase(Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if (c == ' ') {
                // One space at a time (a number word already brings its own).
                if (out.length() == 0 || out.charAt(out.length() - 1) != ' ') out.append(c);
            } else if ((c >= 'A' && c <= 'Z') || c == '\n') {
                out.append(c);
            } else if (c >= '0' && c <= '9') {
                // Each digit as its word, kept apart by spaces: 13 → ONE THREE (typed one at a time too).
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '\n') out.append(' ');
                out.append(DIGITS[c - '0']).append(' ');
            } else if (c == '\r' || c == '\t') {
                out.append(c == '\t' ? " " : "");
            } else {
                bad.add(String.valueOf(c));
            }
        }
        return out.toString();
    }

    static String cleanCipher(String in, Set<String> bad) {
        String up = in.toUpperCase(Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c == DryadSheet.SPACE || c == DryadSheet.END_OF_LINE) out.append(c);
            else if (Character.isWhitespace(c)) continue; // line breaks from pasting or QR: not part of it
            else bad.add(String.valueOf(c));
        }
        return out.toString();
    }

    private static String cipherAsText(String c) {
        return c.replace(DryadSheet.SPACE, ' ').replace(DryadSheet.END_OF_LINE, '\n');
    }

    private DryadSheet sheet() {
        String id = idField.getText().toString().trim().toUpperCase(Locale.US);
        for (SheetStore.Entry e : sheets) if (e.sheet.id().equals(id)) return e.sheet;
        return null;
    }

    private static int plainIndex(char c) {
        if (c == ' ') return 26;
        if (c == '\n') return 27;
        return c - 'A';
    }

    private static char plainChar(int i) {
        return i < 26 ? (char) ('A' + i) : i == 26 ? ' ' : '\n';
    }

    /** Plain text (already cleaned: A–Z, space, new line) to cipher text from a start row; also used by the CW relay. */
    static String encode(DryadSheet s, int row, String in) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char[] key = s.pigpenKeyAt((row + i) % s.rowCount());
            int p = plainIndex(in.charAt(i));
            out.append(p >= 0 && p < key.length ? key[p] : in.charAt(i));
        }
        return out.toString();
    }

    /** Encode plain → cipher, or decode cipher → plain, using the next row for each symbol. */
    private void doCipher() {
        DryadSheet s = sheet();
        int row = s == null ? -1 : s.rowIndex(startField.getText().toString());
        if (s == null || row < 0) {
            Toast.makeText(this, "Enter a sheet ID held on this phone and a start row", Toast.LENGTH_LONG).show();
            return;
        }
        String in = (decode ? cipher : plain).getText().toString();
        if (!decode) {
            while (in.endsWith(" ")) in = in.substring(0, in.length() - 1); // e.g. the space after a number word
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char[] key = s.pigpenKeyAt((row + i) % s.rowCount()); // canonical '.' = space, '@' = new line
            char c = in.charAt(i);
            if (!decode) {
                int p = plainIndex(c);
                out.append(p >= 0 && p < key.length ? key[p] : c);
            } else {
                int found = -1;
                for (int k = 0; k < key.length; k++) if (key[k] == c) found = k;
                out.append(found < 0 ? '?' : plainChar(found));
            }
        }
        fixing = true;
        (decode ? plain : cipher).setText(out);
        fixing = false;
        int next = (row + in.length()) % s.rowCount();
        if (!decode) Cw.remember(this, s.id(), s.rowLabel(next)); // the CW relay carries on from here
        sheetLine.setText("Sheet " + s.name + " · " + in.length() + " symbols · a follow-on message starts at row " + s.rowLabel(next));
    }

    private void renderSheet() {
        DryadSheet s = sheet();
        String start = startField.getText().toString().trim().toUpperCase(Locale.US);
        int row = s == null ? -1 : s.rowIndex(start);
        if (s == null) {
            sheetLine.setText(idField.getText().length() == 5 ? "No sheet with that ID on this phone" : "Type the sheet's 5-letter ID");
            sheetLine.setTextColor(0xffff8a80);
        } else if (row < 0) {
            sheetLine.setText(s.name + ": start row must be " + (s.rowCount() == DryadSheet.ROWS ? "A–Z" : "AA–ZZ"));
            sheetLine.setTextColor(0xffff8a80);
        } else {
            sheetLine.setText("Sheet " + s.name + (s.colour == null ? "" : " · " + s.colour) + " · each letter uses the next row");
            sheetLine.setTextColor(0xffcfe3d4);
        }
    }

    private void renderButtons() {
        action.setText(decode ? "Decode cipher text ↑" : "Encode plain text ↓");
        qr.setText(decode ? "Read QR" : "Create QR");
        if (cwGen != null) cwGen.setText(decode ? "CW decode" : "Generate CW");
        renderCw();
    }

    // ---- CW (Morse) ----

    private Button cwGen, cwSend, cwRecv;
    private android.widget.ImageButton cwLock;
    private CwReceiver receiver;
    private android.media.MediaPlayer cwPlayer;

    private LinearLayout cwRow() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(this);
        r.setBaselineAligned(false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        cwLock = new android.widget.ImageButton(this);
        cwLock.setContentDescription("CW mode");
        cwLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCwMode();
            }
        });
        r.addView(cwLock, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));
        cwGen = small("Generate CW", new Runnable() {
            @Override
            public void run() {
                if (decode) cwDecodeFile();
                else generateCw();
            }
        });
        cwSend = small("Send CW ▾", new Runnable() {
            @Override
            public void run() {
                sendCw();
            }
        });
        cwRecv = small("Receive CW", new Runnable() {
            @Override
            public void run() {
                receiveCw();
            }
        });
        Button more = small("⋯", new Runnable() {
            @Override
            public void run() {
                cwMenu();
            }
        });
        for (Button x : new Button[]{cwGen, cwSend, cwRecv, more}) {
            x.setTextSize(12);
            x.setPadding((int) (2 * d), 0, (int) (2 * d), 0);
        }
        r.addView(cwGen, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        r.addView(cwSend, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        r.addView(cwRecv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        r.addView(more, new LinearLayout.LayoutParams((int) (44 * d), LinearLayout.LayoutParams.MATCH_PARENT));
        return r;
    }

    private void renderCw() {
        if (cwLock == null) return;
        boolean on = Cw.mode();
        cwLock.setImageResource(on ? android.R.drawable.ic_lock_lock : android.R.drawable.ic_lock_idle_lock);
        cwLock.setBackgroundColor(on ? 0xfff0c75e : 0x00000000);
        cwSend.setAlpha(on ? 1f : 0.45f);
        cwRecv.setAlpha(on ? 1f : 0.45f);
    }

    private void toggleCwMode() {
        if (Cw.mode()) {
            Cw.setMode(this, false);
            stopCw();
            Toast.makeText(this, "CW mode off: PTT and voice are back", Toast.LENGTH_SHORT).show();
            renderCw();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("CW mode")
                .setMessage("For a radio plugged into this phone's audio. The phone goes into stealth: PTT, VOX and voice "
                        + "in and out are off, and the only audio is CW from Send CW (to the radio) and Receive CW (from it). "
                        + "Tap the lock again to end it.")
                .setPositiveButton("CW mode on", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        Cw.setMode(EnigmaActivity.this, true);
                        renderCw();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean needCwMode() {
        if (Cw.mode()) return true;
        Toast.makeText(this, "Tap the lock first: Send and Receive CW need CW mode", Toast.LENGTH_LONG).show();
        return false;
    }

    /** The message in the CW format, made into a tone file (with repeats and clear air) ready to send. */
    private void generateCw() {
        String id = idField.getText().toString().trim().toUpperCase(Locale.US);
        String row = startField.getText().toString().trim().toUpperCase(Locale.US);
        String c = cipher.getText().toString().trim();
        if (c.length() == 0) {
            Toast.makeText(this, "Encode a message first (the cipher text is what is sent)", Toast.LENGTH_LONG).show();
            return;
        }
        String text = Cw.build(this, id, row, c);
        try {
            Morse.Params p = Cw.params(this);
            java.io.File f = Cw.newFile(this, "CW", id + row);
            Morse.writeWav(f, Morse.render(text, p));
            Cw.setNote(f, text);
            new AlertDialog.Builder(this).setTitle("CW made")
                    .setMessage(text + "\n\n" + Math.round(Morse.durationMs(text, p) / 1000.0) + " s at " + p.wpm + " wpm"
                            + (p.repeats > 1 ? ", sent " + p.repeats + " times" : "") + ". Send CW (in CW mode) plays it to the radio.")
                    .setPositiveButton(android.R.string.ok, null).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not make it: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private interface Callback {
        void on(java.io.File f);
    }

    /** Pick a CW file (newest first). */
    private void pickCw(String title, final List<java.io.File> files, final Callback done) {
        if (files.isEmpty()) {
            Toast.makeText(this, "No CW files yet: Generate CW or Receive CW makes them", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = Cw.label(files.get(i));
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        done.on(files.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void sendCw() {
        if (!needCwMode()) return;
        pickCw("Send CW (newest first)", Cw.sendable(this), new Callback() {
            @Override
            public void on(java.io.File f) {
                play(f, "Sending CW…");
            }
        });
    }

    /** Plays a CW file on the media sound (the radio's audio input in CW mode), with Stop. */
    @SuppressWarnings("deprecation")
    private void play(java.io.File f, String title) {
        stopPlayer();
        try {
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setDataSource(f.getPath());
            mp.prepare();
            final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setMessage(Cw.note(f))
                    .setNegativeButton("Stop", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface di, int w) {
                            stopPlayer();
                        }
                    }).setCancelable(false).show();
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(android.media.MediaPlayer m) {
                    stopPlayer();
                    if (dlg.isShowing()) dlg.dismiss();
                }
            });
            mp.start();
            cwPlayer = mp;
        } catch (Exception e) {
            Toast.makeText(this, "Could not play it: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayer() {
        android.media.MediaPlayer mp = cwPlayer;
        cwPlayer = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (RuntimeException ignored) {
            }
            mp.release();
        }
    }

    private void stopCw() {
        stopPlayer();
        if (receiver != null) receiver.stop();
        receiver = null;
    }

    /** Receive CW: listen, record and decode each message as it ends; the newest fills the boxes. */
    private void receiveCw() {
        if (!needCwMode()) return;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final TextView state = new TextView(this), live = new TextView(this), log = new TextView(this);
        live.setTypeface(android.graphics.Typeface.MONOSPACE);
        live.setTextSize(16);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(12);
        box.addView(state);
        box.addView(live);
        box.addView(log);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Receive CW").setView(box)
                .setNegativeButton("Stop", null).setCancelable(false).create();
        receiver = new CwReceiver(this, new CwReceiver.Listener() {
            @Override
            public void onProgress(String s, String text) {
                state.setText(s);
                live.setText(text);
            }

            @Override
            public void onMessage(java.io.File wav, String text) {
                log.setText((text.length() == 0 ? "(no Morse found)" : text) + "\n" + log.getText());
                if (text.length() > 0) applyDecoded(text, false);
            }

            @Override
            public void onError(String why) {
                state.setText(why);
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                if (receiver != null) receiver.stop();
                receiver = null;
            }
        });
        dlg.show();
        receiver.start();
    }

    /** CW decode (not connected to a radio): pick a recording or a made file and decode it. */
    private void cwDecodeFile() {
        pickCw("CW decode (newest first)", Cw.files(this), new Callback() {
            @Override
            public void on(java.io.File f) {
                decodeFile(f);
            }
        });
    }

    private void decodeFile(final java.io.File f) {
        Toast.makeText(this, "Decoding…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String t;
                try {
                    t = Morse.decode(Morse.readWav(f), 0);
                } catch (Exception e) {
                    t = "";
                }
                final String text = t;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (text.length() == 0) {
                            Toast.makeText(EnigmaActivity.this, "No Morse found in that file", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (f.getName().startsWith("RX-")) Cw.setNote(f, text);
                        applyDecoded(text, true);
                    }
                });
            }
        }, "cw-decode").start();
    }

    /** Decoded CW into the boxes: sheet ID, start row and cipher text read using the format, then decoded. */
    private void applyDecoded(String text, boolean tell) {
        String[] p = Cw.parse(this, text);
        String c = p != null ? p[2] : text.replaceAll("<[A-Z]+>", "");
        c = c.replaceAll("[^A-Z.@]", "");
        if (p != null && p[0].length() > 0) idField.setText(p[0]);
        if (p != null && p[1].length() > 0) startField.setText(p[1]);
        dec.setChecked(true);
        fixing = true;
        cipher.setText(c);
        plain.setText("");
        fixing = false;
        if (c.length() > 0 && sheet() != null) doCipher();
        if (!tell) { // received over the radio: optionally into Chat
            String pt = plain.getText().toString().trim();
            boolean ok = p != null && sheet() != null && pt.length() > 0 && !pt.contains("?");
            Cw.toChat(this, ok ? "CW Decoded: " + pt.replace('\n', ' ') : "CW received: " + text);
        }
        if (tell) {
            new AlertDialog.Builder(this).setTitle("CW decoded")
                    .setMessage("Heard: " + text + "\n\n" + (p != null ? "Sheet " + p[0] + ", start row " + p[1]
                            + ": the cipher text is filled in and decoded."
                            : "It did not match the message format (CW settings), so all of it went into the cipher text."))
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void cwMenu() {
        new AlertDialog.Builder(this).setTitle("CW")
                .setItems(new String[]{"CW settings (format, speed, repeats, clear air)", "CW files (play, decode, join, split, delete)"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int which) {
                                if (which == 0) startActivity(new Intent(EnigmaActivity.this, CwSettingsActivity.class));
                                else cwFiles();
                            }
                        }).show();
    }

    private void cwFiles() {
        final List<java.io.File> files = Cw.files(this);
        if (files.isEmpty()) {
            Toast.makeText(this, "No CW files yet: Generate CW or Receive CW makes them", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = Cw.label(files.get(i));
        new AlertDialog.Builder(this).setTitle("CW files (newest first)")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        fileActions(files.get(which));
                    }
                })
                .setNeutralButton("Join…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        joinFiles(files);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void fileActions(final java.io.File f) {
        new AlertDialog.Builder(this).setTitle(Cw.label(f))
                .setItems(new String[]{"Play", "Decode", "Split at the quiet gaps", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        if (which == 0) play(f, "Playing CW…");
                        else if (which == 1) decodeFile(f);
                        else if (which == 2) splitFile(f);
                        else {
                            Cw.delete(f);
                            Toast.makeText(EnigmaActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }

    private void splitFile(final java.io.File f) {
        final int[] gaps = {600, 1000, 2000, 3000, 5000};
        new AlertDialog.Builder(this).setTitle("Split where it is quiet for at least")
                .setItems(new String[]{"0.6 s (between repeats)", "1 s", "2 s", "3 s", "5 s (between messages)"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface di, int which) {
                                try {
                                    List<short[]> parts = Morse.split(Morse.readWav(f), gaps[which]);
                                    if (parts.size() < 2) {
                                        Toast.makeText(EnigmaActivity.this, "No gap that long: nothing split", Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    for (short[] p : parts) {
                                        java.io.File out = Cw.newFile(EnigmaActivity.this, "PART", "");
                                        Morse.writeWav(out, p);
                                        Cw.setNote(out, Morse.decode(p, 0));
                                    }
                                    Toast.makeText(EnigmaActivity.this, "Split into " + parts.size() + " files (the original is kept)",
                                            Toast.LENGTH_LONG).show();
                                } catch (Exception e) {
                                    Toast.makeText(EnigmaActivity.this, "Could not split it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        }).show();
    }

    private void joinFiles(final List<java.io.File> files) {
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = Cw.label(files.get(i));
        final boolean[] pick = new boolean[files.size()];
        new AlertDialog.Builder(this).setTitle("Join (oldest first, 1 s apart)")
                .setMultiChoiceItems(names, pick, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which, boolean on) {
                        pick[which] = on;
                    }
                })
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        List<short[]> parts = new ArrayList<short[]>();
                        StringBuilder note = new StringBuilder();
                        try {
                            for (int i = files.size() - 1; i >= 0; i--) { // the list is newest first
                                if (!pick[i]) continue;
                                parts.add(Morse.readWav(files.get(i)));
                                String n = Cw.note(files.get(i));
                                if (n.length() > 0) note.append(note.length() > 0 ? " / " : "").append(n);
                            }
                            if (parts.size() < 2) {
                                Toast.makeText(EnigmaActivity.this, "Tick two or more", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            java.io.File out = Cw.newFile(EnigmaActivity.this, "JOIN", "");
                            Morse.writeWav(out, Morse.join(parts, 1000));
                            Cw.setNote(out, note.toString());
                            Toast.makeText(EnigmaActivity.this, "Joined " + parts.size() + " into one file (ready for Send CW)",
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(EnigmaActivity.this, "Could not join them: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override
    protected void onDestroy() {
        stopCw();
        super.onDestroy();
    }

    private String header() {
        return idField.getText().toString().trim().toUpperCase(Locale.US) + " "
                + startField.getText().toString().trim().toUpperCase(Locale.US);
    }

    /** The ID and start row as classic A–Z pigpen, to write at the top of the message. */
    private void showHeader() {
        float d = getResources().getDisplayMetrics().density;
        String h = header();
        PigpenStripView strip = new PigpenStripView(this, null);
        int[] slots = Pigpen.encode(Pigpen.CLASSIC, h);
        List<Integer> tokens = new ArrayList<Integer>();
        for (int i = 0; i < slots.length; i++) tokens.add(slots[i] >= 0 ? slots[i] : PigpenStripView.literal(h.charAt(i)));
        strip.setTokens(tokens);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), 0);
        box.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * d)));
        TextView t = new TextView(this);
        t.setText("ID " + h.replace(" ", ", start row ") + " in classic pigpen (not the sheet's own).");
        box.addView(t);
        new AlertDialog.Builder(this).setTitle("Pigpen ID and start row").setView(box)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void createQr() {
        String c = cipher.getText().toString();
        if (sheet() == null || c.length() == 0) {
            Toast.makeText(this, "Encode a message first", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> p = new ArrayList<String>(), t = new ArrayList<String>();
        p.add(QR_PREFIX + idField.getText().toString().trim().toUpperCase(Locale.US) + "|"
                + startField.getText().toString().trim().toUpperCase(Locale.US) + "|" + c);
        t.add("DRYAD Enigma · sheet " + header());
        QrShowActivity.start(this, p, t, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_QR && res == RESULT_OK && data != null) useQr(data.getStringExtra(QrScanActivity.EXTRA_RESULT));
    }

    /** A scanned Enigma QR: offer to switch to its sheet and row, then fill the cipher text. */
    private void useQr(String q) {
        if (q == null || !q.startsWith(QR_PREFIX)) return;
        String[] f = q.substring(QR_PREFIX.length()).split("\\|", 3);
        if (f.length < 3) {
            Toast.makeText(this, "That QR code is damaged", Toast.LENGTH_SHORT).show();
            return;
        }
        final String id = f[0], row = f[1], text = f[2];
        String curId = idField.getText().toString().trim().toUpperCase(Locale.US);
        String curRow = startField.getText().toString().trim().toUpperCase(Locale.US);
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                cipher.setText(text);
                plain.setText("");
                doCipher();
            }
        };
        if (id.equals(curId) && row.equals(curRow)) {
            fill.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Use the QR's sheet and row?")
                .setMessage("Replace sheet " + (curId.length() > 0 ? curId : "(none)") + " with " + id
                        + " and row " + (curRow.length() > 0 ? curRow : "(none)") + " with " + row + "?")
                .setPositiveButton("Replace", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        idField.setText(id);
                        startField.setText(row);
                        fill.run();
                    }
                })
                .setNegativeButton("Keep mine", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        fill.run();
                    }
                })
                .show();
    }

    /** Tap the header's classic pigpen symbols to fill in the ID (5 letters) then the start row. */
    private void readHeader() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView got = new TextView(this);
        got.setTextSize(20);
        got.setGravity(Gravity.CENTER);
        box.addView(got);
        final StringBuilder letters = new StringBuilder();
        PigpenKeyboardView kb = new PigpenKeyboardView(this, null);
        kb.setOnSymbol(new PigpenKeyboardView.OnSymbol() {
            @Override
            public void onSymbol(int slot) {
                char c = Pigpen.CLASSIC[slot];
                if (c >= 'A' && c <= 'Z') letters.append(c);
                got.setText(letters.length() <= 5 ? "ID " + letters : "ID " + letters.substring(0, 5) + "  row " + letters.substring(5));
            }
        });
        box.addView(kb);
        Button back = new Button(this);
        back.setText("Back");
        back.setAllCaps(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (letters.length() > 0) letters.setLength(letters.length() - 1);
                got.setText("ID " + letters);
            }
        });
        box.addView(back);
        box.setPadding((int) (8 * d), 0, (int) (8 * d), 0);
        new AlertDialog.Builder(this)
                .setTitle("Tap the pigpen ID, then the start row")
                .setView(box)
                .setPositiveButton("Use", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (letters.length() < 6) {
                            Toast.makeText(EnigmaActivity.this, "Needs the 5-letter ID and a start row", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        idField.setText(letters.substring(0, 5));
                        startField.setText(letters.substring(5));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
