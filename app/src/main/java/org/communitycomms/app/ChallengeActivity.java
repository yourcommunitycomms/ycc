package org.communitycomms.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetStore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * DRYAD challenge and response (authentication). The challenger reads out a row and one letter of
 * that row; the right reply is the letter in the same position of the next row (Z wraps to A).
 * Make a challenge (with its expected reply hidden until tapped), or work out the reply to one.
 */
public final class ChallengeActivity extends Activity {
    private List<SheetStore.Entry> sheets;
    private Spinner picker;
    private TextView challenge, expected, answer;
    private EditText inRow, inLetter;
    private String expectedText = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Challenge and response");
        sheets = new SheetStore(this).loadAll(false);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (14 * d));
        TextView help = new TextView(this);
        help.setText("Challenge: say a row and one letter from it. Reply: the letter in the same place in the next row "
                + "(after the last row, the first). Both sides use the same sheet.");
        help.setTextSize(13);
        box.addView(help);
        picker = new Spinner(this);
        List<String> names = new ArrayList<String>();
        int sel = 0;
        String want = getIntent().getStringExtra(EnigmaActivity.EXTRA_ID);
        long now = System.currentTimeMillis();
        for (int i = 0; i < sheets.size(); i++) {
            DryadSheet s = sheets.get(i).sheet;
            names.add(s.id() + "  " + DryadActivity.label(s, now));
            if (s.id().equals(want)) sel = i;
            else if (want == null && s.status(now) == DryadSheet.ACTIVE && sel == 0) sel = i;
        }
        if (names.isEmpty()) names.add("No DRYAD sheets on this phone");
        picker.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        picker.setSelection(sel);
        box.addView(picker);

        box.addView(heading("Challenge someone"));
        Button make = new Button(this);
        make.setText("New challenge");
        make.setAllCaps(false);
        make.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newChallenge();
            }
        });
        box.addView(make);
        challenge = new TextView(this);
        challenge.setTextSize(30);
        challenge.setGravity(Gravity.CENTER);
        challenge.setTextColor(0xfff0c75e);
        box.addView(challenge);
        expected = new TextView(this);
        expected.setTextSize(18);
        expected.setGravity(Gravity.CENTER);
        expected.setText("");
        expected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (expectedText.length() > 0) expected.setText("Correct reply: " + expectedText);
            }
        });
        box.addView(expected);

        box.addView(heading("Reply to a challenge"));
        LinearLayout in = new LinearLayout(this);
        inRow = field("Row");
        inLetter = field("Letter");
        in.addView(inRow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        in.addView(inLetter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(in);
        answer = new TextView(this);
        answer.setTextSize(30);
        answer.setGravity(Gravity.CENTER);
        answer.setTextColor(0xffa5d6a7);
        box.addView(answer);
        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int e) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                reply();
            }
        };
        inRow.addTextChangedListener(w);
        inLetter.addTextChangedListener(w);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PinLock.gateScreen(this, "Enter your PIN for challenge and response.");
    }

    private TextView heading(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTextColor(0xfff0c75e);
        v.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        return e;
    }

    private DryadSheet sheet() {
        int i = picker.getSelectedItemPosition();
        return i >= 0 && i < sheets.size() ? sheets.get(i).sheet : null;
    }

    /** The reply: same position, next row. */
    static char reply(DryadSheet s, int row, char letter) {
        char[] r = s.rowAt(row);
        for (int i = 0; i < r.length; i++) {
            if (r[i] == letter) return s.rowAt((row + 1) % s.rowCount())[i];
        }
        return '?';
    }

    private void newChallenge() {
        DryadSheet s = sheet();
        if (s == null) return;
        SecureRandom rnd = new SecureRandom();
        int row = rnd.nextInt(s.rowCount());
        char letter = (char) ('A' + rnd.nextInt(26));
        challenge.setText(s.rowLabel(row) + "  " + letter);
        expectedText = String.valueOf(reply(s, row, letter));
        expected.setText("Tap here to show the correct reply");
    }

    private void reply() {
        DryadSheet s = sheet();
        String l = inLetter.getText().toString().trim().toUpperCase(java.util.Locale.US);
        if (s == null || l.length() != 1) {
            answer.setText("");
            return;
        }
        int row = s.rowIndex(inRow.getText().toString());
        answer.setText(row < 0 ? "Row?" : "Reply: " + reply(s, row, l.charAt(0)));
    }

    /** Two heads facing each other, for the home screen shortcut. */
    static Bitmap icon(float d) {
        int w = (int) (28 * d);
        Bitmap b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xfff0c75e);
        for (int side = 0; side < 2; side++) {
            float cx = side == 0 ? w * 0.27f : w * 0.73f;
            float dir = side == 0 ? 1 : -1; // faces the middle
            c.drawCircle(cx, w * 0.36f, w * 0.17f, p);
            Path nose = new Path();
            nose.moveTo(cx + dir * w * 0.15f, w * 0.30f);
            nose.lineTo(cx + dir * w * 0.25f, w * 0.40f);
            nose.lineTo(cx + dir * w * 0.13f, w * 0.43f);
            nose.close();
            c.drawPath(nose, p);
            c.drawRect(cx - w * 0.17f, w * 0.60f, cx + w * 0.17f, w * 0.92f, p);
        }
        return b;
    }
}
