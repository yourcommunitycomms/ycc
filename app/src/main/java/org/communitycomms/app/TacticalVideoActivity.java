package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tactical videos: everyone's shared camera in a grid, like a control-room wall. Choose who to
 * show (others are hidden), tap a picture to fill the screen with it, tap again to go back.
 */
public final class TacticalVideoActivity extends Activity {
    private static final int MENU_PEOPLE = 1, MENU_SHARE = 2;
    private Wall wall;
    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            wall.invalidate();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tactical videos");
        sp = getSharedPreferences("video", MODE_PRIVATE);
        wall = new Wall(this);
        wall.hidden = hidden(sp);
        setContentView(wall);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static Set<String> hidden(SharedPreferences sp) {
        Set<String> out = new HashSet<String>();
        for (String s : sp.getString("hidden", "").split("\n")) if (s.length() > 0) out.add(s);
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        VideoHub.ui = new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        wall.invalidate();
                    }
                });
            }
        };
        main.post(tick);
        // Camera audio plays only here: everyone shown, or just the one filling the screen.
        final org.communitycomms.app.audio.Mixer mx = new org.communitycomms.app.audio.Mixer(RadioEngine.SAMPLE_RATE,
                RadioEngine.FRAME_SAMPLES);
        mixer = mx;
        VideoHub.audio = new VideoHub.AudioOut() {
            @Override
            public boolean wants(String name) {
                String f = wall.focus;
                return !wall.hidden.contains(name) && (f == null || f.equals(name));
            }

            @Override
            public void play(long sender, int seq, short[] pcm) {
                mx.push(sender, seq, pcm);
            }
        };
    }

    private org.communitycomms.app.audio.Mixer mixer;

    @Override
    protected void onPause() {
        VideoHub.audio = null;
        if (mixer != null) mixer.close();
        mixer = null;
        VideoHub.ui = null;
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_PEOPLE, 0, "People").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, MENU_SHARE, 0, "My camera").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_PEOPLE) {
            choosePeople();
            return true;
        }
        if (item.getItemId() == MENU_SHARE) {
            startActivity(new Intent(this, VideoShareActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void choosePeople() {
        List<VideoHub.Feed> feeds = VideoHub.feeds();
        if (feeds.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No one is sharing a camera yet.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final String[] names = new String[feeds.size()];
        final boolean[] shown = new boolean[feeds.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = feeds.get(i).name;
            shown[i] = !wall.hidden.contains(names[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Show these cameras")
                .setMultiChoiceItems(names, shown, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean on) {
                        shown[which] = on;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < names.length; i++) {
                            if (shown[i]) wall.hidden.remove(names[i]);
                            else wall.hidden.add(names[i]);
                        }
                        StringBuilder s = new StringBuilder();
                        for (String n : wall.hidden) s.append(n).append('\n');
                        sp.edit().putString("hidden", s.toString()).commit();
                        wall.focus = null;
                        wall.invalidate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The grid of pictures; also used by the TV screen. */
    static final class Wall extends View {
        Set<String> hidden = new HashSet<String>();
        String focus;
        boolean showEmptyHint = true;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint band = new Paint();
        private final List<RectF> cells = new ArrayList<RectF>();
        private final List<String> cellNames = new ArrayList<String>();
        private final float dp;

        Wall(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.BLACK);
            text.setColor(Color.WHITE);
            text.setTextSize(14 * dp);
            band.setColor(0x99000000);
        }

        List<VideoHub.Feed> visible() {
            List<VideoHub.Feed> out = new ArrayList<VideoHub.Feed>();
            for (VideoHub.Feed f : VideoHub.feeds()) {
                if (hidden.contains(f.name)) continue;
                if (focus != null && !focus.equals(f.name)) continue;
                out.add(f);
            }
            if (focus != null && out.isEmpty()) {
                focus = null;
                return visible();
            }
            return out;
        }

        @Override
        protected void onDraw(Canvas c) {
            cells.clear();
            cellNames.clear();
            List<VideoHub.Feed> feeds = visible();
            int n = feeds.size();
            if (n == 0) {
                if (showEmptyHint) {
                    String[] lines = {"No cameras shared yet.", "Share yours from My camera."};
                    for (int i = 0; i < lines.length; i++) {
                        c.drawText(lines[i], Math.max(4 * dp, (getWidth() - text.measureText(lines[i])) / 2),
                                getHeight() / 2f + i * 20 * dp, text);
                    }
                }
                return;
            }
            int cols = (int) Math.ceil(Math.sqrt(n * (float) getWidth() / Math.max(1, getHeight()) * 0.75f));
            cols = Math.max(1, Math.min(n, cols));
            int rows = (n + cols - 1) / cols;
            float cw = getWidth() / (float) cols, ch = getHeight() / (float) rows;
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                VideoHub.Feed f = feeds.get(i);
                RectF cell = new RectF((i % cols) * cw, (i / cols) * ch, (i % cols + 1) * cw, (i / cols + 1) * ch);
                cells.add(cell);
                cellNames.add(f.name);
                Bitmap bm = f.picture;
                if (bm != null && !bm.isRecycled()) {
                    float s = Math.min((cell.width() - 2 * dp) / bm.getWidth(), (cell.height() - 2 * dp) / bm.getHeight());
                    float w = bm.getWidth() * s, h = bm.getHeight() * s;
                    RectF dst = new RectF(cell.centerX() - w / 2, cell.centerY() - h / 2, cell.centerX() + w / 2, cell.centerY() + h / 2);
                    c.drawBitmap(bm, null, dst, paint);
                }
                long age = (now - f.atMs) / 1000;
                String label = f.name + (age >= 3 ? "  (" + age + " s ago)" : "");
                c.drawRect(cell.left, cell.bottom - 20 * dp, cell.right, cell.bottom, band);
                text.setColor(age >= 10 ? 0xFFFFB0B0 : Color.WHITE);
                c.drawText(label, cell.left + 6 * dp, cell.bottom - 6 * dp, text);
                text.setColor(Color.WHITE);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (focus != null) {
                focus = null;
            } else {
                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i).contains(e.getX(), e.getY())) {
                        focus = cellNames.get(i);
                        break;
                    }
                }
            }
            invalidate();
            return true;
        }
    }
}
