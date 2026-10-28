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

/**
 * UNTESTED (*): crash detection. A hard jolt opens this over everything (screen on, over the lock
 * screen) with a 30 s countdown and a big "I'm OK". With no answer, the phone sends a rescue map
 * point, a message and a Call to everyone, and shares its location until stopped.
 */
public final class CrashActivity extends Activity {
    private static final int SECONDS = 30;
    private final Handler handler = new Handler();
    private int left = SECONDS;
    private TextView count;
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Are you OK?");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xff7f0000);
        root.setPadding((int) (20 * d), (int) (20 * d), (int) (20 * d), (int) (40 * d));
        TextView t = new TextView(this);
        t.setText("Possible crash detected");
        t.setTextSize(28);
        t.setTextColor(0xffffffff);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        count = new TextView(this);
        count.setTextSize(20);
        count.setTextColor(0xffffffff);
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, (int) (16 * d), 0, (int) (24 * d));
        root.addView(count);
        Button ok = new Button(this);
        ok.setText("I'm OK");
        ok.setTextSize(30);
        ok.setAllCaps(false);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                done = true;
                finish();
            }
        });
        root.addView(ok, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (140 * d)));
        Button now = new Button(this);
        now.setText("Send help now");
        now.setAllCaps(false);
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (64 * d));
        lp.topMargin = (int) (20 * d);
        root.addView(now, lp);
        setContentView(root);
        tick.run();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (done) return;
            if (left <= 0) {
                send();
                return;
            }
            count.setText("Sending a rescue alert with your position in " + left + " s unless you tap I'm OK");
            left--;
            handler.postDelayed(this, 1000);
        }
    };

    private void send() {
        if (done) return;
        done = true;
        RadioService s = RadioService.instance();
        if (s != null) s.sendCrashAlert();
        count.setText("Rescue alert sent. Your location is being shared (tap Share location to stop it).");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 5000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
