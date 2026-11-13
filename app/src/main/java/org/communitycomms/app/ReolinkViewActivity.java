package org.communitycomms.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.communitycomms.app.camera.ReolinkCam;

/**
 * One Reolink lens full screen. Shows the snapshot pictures (work on every phone), or live video
 * from the camera's RTSP stream where this phone can play it (sub stream: H.264, small; main is
 * often H.265, which older phones cannot decode), falling back to pictures if it will not play.
 * Listen plays the stream's sound; it starts muted. Opened by a tap on a camera tile, or by
 * itself when a camera is triggered (then it closes again after the quiet time if not touched).
 */
public final class ReolinkViewActivity extends Activity {
    static final String EXTRA_FEED = "feed", EXTRA_AUTO = "auto";
    /** The lens open now (so a trigger does not open it twice). */
    static volatile String open;

    private ReolinkCam cam;
    private String feed;
    private boolean auto, touched, live, listen, newStyle, flood;
    private ImageView picture;
    private VideoView video;
    private MediaPlayer player;
    private TextView label;
    private Button liveBtn, listenBtn, lightBtn;
    private long liveStartedAt;
    private boolean prepared;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            h.postDelayed(this, 400);
        }
    };

    static void show(Context c, String feed, boolean auto) {
        Intent i = new Intent(c, ReolinkViewActivity.class).putExtra(EXTRA_FEED, feed).putExtra(EXTRA_AUTO, auto);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        feed = getIntent().getStringExtra(EXTRA_FEED);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        cam = feed == null ? null : ReolinkMonitor.camFor(this, feed);
        if (cam == null) {
            finish();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        root.addView(video, vp);
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setBackgroundColor(0x99000000);
        label.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
        root.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0x99000000);
        liveBtn = button(cam.fullStream.equals("snap") || cam.bcOnly ? null : "Live video", new Runnable() {
            @Override
            public void run() {
                if (live) stopLive("");
                else startLive();
            }
        });
        listenBtn = button(cam.bcOnly ? null : "Listen", new Runnable() { // port 9000 cameras: pictures only
            @Override
            public void run() {
                listen = !listen;
                if (listen && !live) startLive(); // sound comes with the stream
                applyVolume();
                renderButtons();
            }
        });
        lightBtn = button(cam.hasFlood && !cam.bcOnly ? "Light on" : null, new Runnable() {
            @Override
            public void run() {
                flood = !flood;
                ReolinkMonitor.manualFlood(cam, flood);
                renderButtons();
            }
        });
        Button close = button("Close", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        for (Button bt : new Button[]{liveBtn, listenBtn, lightBtn, close}) {
            if (bt.getText().length() > 0) bar.addView(bt, new LinearLayout.LayoutParams(0, (int) (56 * d), 1));
        }
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        renderButtons();

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                player = mp;
                prepared = true;
                applyVolume();
                picture.setVisibility(View.GONE);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                // 100 = Android's media server died (its RTSP player crashed on the stream): do not retry.
                if (!newStyle && !prepared && what != MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
                    newStyle = true; // older/newer firmware name the stream differently
                    // Not from inside the error callback: setVideoURI there throws IllegalStateException.
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (live) play();
                        }
                    }, 300);
                } else {
                    stopLive("Live video does not play on this phone: showing pictures instead");
                }
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stopLive("The live stream ended: showing pictures");
            }
        });
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t == null ? "" : t);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                touched = true;
                r.run();
            }
        });
        return b;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        touched = true;
    }

    private void renderButtons() {
        liveBtn.setText(cam.fullStream.equals("snap") || cam.bcOnly ? "" : live ? "Pictures" : "Live video");
        listenBtn.setText(cam.bcOnly ? "" : listen ? "Mute" : "Listen");
        listenBtn.setTextColor(listen ? 0xffffd54f : Color.WHITE);
        if (cam.hasFlood) lightBtn.setText(flood ? "Light off" : "Light on");
    }

    private void startLive() {
        live = true;
        newStyle = false;
        play();
        renderButtons();
    }

    private void play() {
        prepared = false;
        liveStartedAt = System.currentTimeMillis();
        video.setVisibility(View.VISIBLE);
        // The sound needs the stream, so Listen on a "pictures only" camera uses the sub stream.
        boolean main = cam.fullStream.equals("main");
        try {
            video.setVideoURI(Uri.parse(cam.rtspUrl(main, newStyle)));
            video.start();
        } catch (RuntimeException e) {
            stopLive("Live video does not play on this phone: showing pictures instead");
        }
    }

    private void stopLive(String why) {
        live = false;
        listen = false;
        prepared = false;
        player = null;
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
        }
        video.setVisibility(View.GONE);
        picture.setVisibility(View.VISIBLE);
        if (why.length() > 0) Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        renderButtons();
    }

    /** Live video starts muted; Listen turns the camera's sound on. */
    private void applyVolume() {
        MediaPlayer p = player;
        if (p == null) return;
        try {
            float v = listen ? 1f : 0f;
            p.setVolume(v, v);
        } catch (RuntimeException ignored) {
        }
    }

    private void update() {
        VideoHub.Feed f = VideoHub.feed(feed);
        if (f != null) {
            Bitmap bm = f.picture;
            if (bm != null && !bm.isRecycled()) picture.setImageBitmap(bm);
        }
        long age = f == null || f.picture == null ? -1 : (System.currentTimeMillis() - f.atMs) / 1000;
        String s = feed + (live ? (prepared ? "  (live)" : "  (starting live video…)") : "");
        if (f != null && f.alert != null) s += "   ● " + f.alert;
        if (f != null && f.note != null) s += "   " + f.note;
        else if (!live && age >= 3) s += "  (" + age + " s ago)";
        label.setText(s);
        label.setTextColor(f != null && f.alert != null ? 0xffff8a80 : Color.WHITE);
        if (live && !prepared && System.currentTimeMillis() - liveStartedAt > 15000) {
            stopLive("Live video did not start: showing pictures instead");
        }
        // Opened by a trigger and left alone: go back once the camera has been quiet for a while.
        if (auto && !touched && !VideoHub.isAlerting(feed)) finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = feed;
        ReolinkMonitor.focused = feed;
        ReolinkMonitor.acquire(this, "view");
        h.post(tick);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        if (live) stopLive("");
        if (feed != null && feed.equals(ReolinkMonitor.focused)) ReolinkMonitor.focused = null;
        if (feed != null && feed.equals(open)) open = null;
        ReolinkMonitor.release("view");
        super.onPause();
    }
}
