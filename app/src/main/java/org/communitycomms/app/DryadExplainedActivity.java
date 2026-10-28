package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** "How Modern DRYAD works": a short illustrated walk-through, readable offline. */
public final class DryadExplainedActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Modern DRYAD explained");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (20 * d));
        h(box, "1. The sheet");
        p(box, "A DRYAD sheet is a grid. Each row (A to Z, or AA to ZZ on a huge sheet) is the alphabet in a random "
                + "order, plus two symbols of your choice for a space and a new line. Everyone in the group holds the "
                + "same sheet, valid for a set time, and destroys (marks used) it afterwards. Each sheet has a 5-letter ID.");
        table(box);
        p(box, "('.' is the space symbol and '@' new line on this sheet.)");
        h(box, "2. Channels");
        p(box, "A row can be a radio channel name: → A puts row A on channel A. Everyone who picks the same row of the same "
                + "sheet is on the same encrypted channel; change sheet or row to move everyone at once.");
        h(box, "3. Numbers");
        p(box, "Each row is split into columns 0–9. To send a digit, say any letter under that column in the agreed row "
                + "(e.g. row A, 3 → \"A\" or \"J\" or \"W\", the cells under 3 in the table above).");
        h(box, "4. Challenge and response");
        p(box, "To check someone is genuine, give a row and a letter in it (\"K, M\"). The reply is the letter in the same "
                + "place in the next row. Only someone holding the sheet can answer. (Two heads button on the home screen.)\n"
                + "Example from the table: challenge \"A, M\". M is the 5th symbol of row A, so the reply is the 5th "
                + "symbol of row B: \"Z\".");
        h(box, "5. Pigpen from a row");
        p(box, "Pigpen draws each letter as a shape. Keyed with a DRYAD row, the shapes are reshuffled, so only someone "
                + "with the sheet can read it. Space and new line have their own shapes too.");
        h(box, "6. Enigma mode");
        p(box, "Like a rotor machine: the first letter is swapped using the start row, the next letter the next row, and so "
                + "on, looping back to the first row. The same letter comes out differently each time. Write the sheet ID "
                + "and start row at the top (also as plain pigpen), or send it all as a QR code; the receiver enters them, "
                + "pastes or types the cipher text and taps Decode.");
        h(box, "Good habits");
        p(box, "Never send the sheet itself on Public. Mark sheets used when finished. Use a new start row for each "
                + "message in Enigma mode. Keep a PIN on the app.");
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    private void h(LinearLayout box, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(18);
        v.setTextColor(0xfff0c75e);
        v.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(v);
    }

    private void p(LinearLayout box, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        box.addView(v);
    }

    /** Example rows A and B in their number columns 0–9; M and its reply Z picked out. */
    private void table(LinearLayout box) {
        String[][] rows = {
                {"", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"},
                {"A", "QXTR", "MBE", "KLZ", "AJW", "FY", ".CU", "NO", "PDG", "H@V", "IS"},
                {"B", "LDKA", "ZRO", "TVH", "EQC", "IN", "@WS", "GB", "UYJ", "X.M", "FP"}};
        float d = getResources().getDisplayMetrics().density;
        android.widget.TableLayout t = new android.widget.TableLayout(this);
        t.setStretchAllColumns(true);
        t.setShrinkAllColumns(true);
        for (int r = 0; r < rows.length; r++) {
            android.widget.TableRow tr = new android.widget.TableRow(this);
            for (int c = 0; c < rows[r].length; c++) {
                TextView v = new TextView(this);
                String s = rows[r][c];
                android.text.SpannableString sp = new android.text.SpannableString(s);
                // Challenge example: M in row A (5th symbol) and Z in row B (5th symbol).
                if (r == 1 && c == 2) sp.setSpan(new android.text.style.ForegroundColorSpan(0xfff0c75e), 0, 1, 0);
                if (r == 2 && c == 2) sp.setSpan(new android.text.style.ForegroundColorSpan(0xfff0c75e), 0, 1, 0);
                v.setText(sp);
                v.setTypeface(android.graphics.Typeface.MONOSPACE);
                v.setTextSize(13);
                v.setGravity(android.view.Gravity.CENTER);
                v.setTextColor(r == 0 || c == 0 ? 0xfff0c75e : 0xffcfe3d4);
                v.setBackgroundColor(r == 0 || c == 0 ? 0xff1c2a22 : (c % 2 == 0 ? 0xff22332a : 0xff1a261f));
                v.setPadding((int) (1 * d), (int) (4 * d), (int) (1 * d), (int) (4 * d));
                tr.addView(v);
            }
            t.addView(tr);
        }
        box.addView(t);
    }

    private void mono(LinearLayout box, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextColor(0xffcfe3d4);
        box.addView(v);
    }
}
