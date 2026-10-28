package org.communitycomms.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.audio.CallRinger;

/**
 * The incoming Call: a full-screen alert (over the lock screen too) with one big button that stops
 * the ringing and tells the caller it was heard, and a smaller one that only stops the ringing.
 */
public final class CallAlertActivity extends Activity {
    static final String EXTRA_FROM = "from", EXTRA_WHERE = "where";

    private Button ack;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!CallRinger.isRinging()) ack.setText("ACKNOWLEDGE\n(ringing stopped)");
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setTitle("Call");
        final String from = getIntent().getStringExtra(EXTRA_FROM);
        String where = getIntent().getStringExtra(EXTRA_WHERE);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xffb71c1c);
        int p = (int) (16 * d);
        root.setPadding(p, p, p, (int) (40 * d)); // gap above the phone's own buttons

        TextView t = new TextView(this);
        t.setText("CALL\nfrom " + from + (where == null ? "" : where));
        t.setTextSize(34);
        t.setTextColor(0xffffffff);
        t.setGravity(Gravity.CENTER);
        root.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        ack = new Button(this);
        ack.setText("ACKNOWLEDGE");
        ack.setTextSize(28);
        ack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallRinger.stopNow(CallAlertActivity.this);
                RadioService rs = RadioService.instance();
                if (rs != null) rs.acknowledgeCall(from);
                finish();
            }
        });
        root.addView(ack, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (160 * d)));

        Button stop = new Button(this);
        stop.setText("Stop ringing only");
        stop.setTextSize(18);
        stop.setAllCaps(false);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallRinger.stopNow(CallAlertActivity.this);
                finish();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (64 * d));
        lp.topMargin = (int) (16 * d);
        root.addView(stop, lp);
        setContentView(root);
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
