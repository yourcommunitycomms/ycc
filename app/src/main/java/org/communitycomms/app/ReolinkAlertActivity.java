package org.communitycomms.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * Intercom: every triggered Reolink camera at once, sharing the screen (one fills it, two split it,
 * and so on). Each stays until its hold time after its own movement ends; when all have gone quiet
 * this closes by itself. Tap a camera for its full-screen view (live video, Listen, Light).
 */
public final class ReolinkAlertActivity extends Activity {
    /** Open now (so the intercom does not open it twice). */
    static volatile boolean open;
    /** It closed by itself because every camera went quiet (the intercom then returns to Blackout if it was black). */
    static volatile boolean endedQuiet;

    private TacticalVideoActivity.Wall wall;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (VideoHub.alertingAll().isEmpty()) {
                endedQuiet = true;
                finish();
                return;
            }
            wall.invalidate();
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        endedQuiet = false;
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wall = new TacticalVideoActivity.Wall(this);
        wall.onlyLocal = true;
        wall.autoFront = true;
        wall.showEmptyHint = false;
        wall.onOpenLocal = new TacticalVideoActivity.Wall.OpenLocal() {
            @Override
            public void open(String name) {
                ReolinkViewActivity.show(ReolinkAlertActivity.this, name, false);
            }
        };
        root.addView(wall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (48 * d), Gravity.TOP | Gravity.RIGHT);
        cp.setMargins(0, (int) (8 * d), (int) (8 * d), 0);
        root.addView(close, cp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = true;
        ReolinkMonitor.acquire(this, "alerts");
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        ReolinkMonitor.release("alerts");
        if (isFinishing()) open = false; // before the intercom's onResume (onDestroy comes later)
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        open = false;
        super.onDestroy();
    }
}
