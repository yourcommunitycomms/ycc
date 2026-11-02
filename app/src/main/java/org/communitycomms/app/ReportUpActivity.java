package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Report to leaders: for phones with an approved code. Only leaders can read these. */
public final class ReportUpActivity extends Activity {
    private TextView state;
    private CheckBox withLocation;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long id = ReportUp.lastId;
            if (id != 0) state.setText(ReportUp.delivered(id) ? "Delivered to leaders" : "Sending. NOT yet delivered to leaders. Use your emergency plan if you can't wait.");
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Report to leaders");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * d);
        root.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        t.setText("Only your leaders can read these. Your approved code: " + new Prefs(this).reportCode());
        root.addView(t);
        state = new TextView(this);
        state.setTextSize(18);
        state.setPadding(0, p, 0, p);
        root.addView(state);
        withLocation = new CheckBox(this);
        withLocation.setText("Include my location");
        withLocation.setChecked(true);
        root.addView(withLocation);
        root.addView(key("SOS", 0xffb71c1c, 96, ReportUp.SOS, d));
        root.addView(key("I need help", 0xffe65100, 72, ReportUp.HELP, d));
        root.addView(key("I'm OK", 0xff2e7d32, 72, ReportUp.OK, d));
        root.addView(key("Send my location", 0xff455a64, 56, ReportUp.LOCATION, d));
        root.addView(key("Ask to talk to a leader", 0xff455a64, 56, ReportUp.TALK, d));
        final EditText text = new EditText(this);
        text.setHint("Short message to leaders");
        root.addView(text);
        Button send = new Button(this);
        send.setAllCaps(false);
        send.setText("Send message");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = text.getText().toString().trim();
                if (s.length() > 0) {
                    report(ReportUp.TEXT, s.length() > 300 ? s.substring(0, 300) : s);
                    text.setText("");
                }
            }
        });
        root.addView(send);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private Button key(String word, int colour, int heightDp, final int kind, float d) {
        Button k = new Button(this);
        k.setAllCaps(false);
        k.setText(word);
        k.setTextColor(0xffffffff);
        k.setBackgroundColor(colour);
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                report(kind, "");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (heightDp * d));
        lp.topMargin = (int) (8 * d);
        k.setLayoutParams(lp);
        return k;
    }

    private void report(int kind, String text) {
        android.location.Location at = null;
        if (withLocation.isChecked()) {
            RadioService s = RadioService.instance();
            at = s == null ? null : s.lastFix();
        }
        String why = ReportUp.send(this, kind, text, at);
        if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
