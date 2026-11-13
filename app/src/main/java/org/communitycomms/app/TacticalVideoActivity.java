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
    private static final int MENU_PEOPLE = 1, MENU_SHARE = 2, MENU_BLACKOUT = 3, MENU_CAMS = 4, MENU_AUTOBLACK = 5, MENU_ABTOGGLE = 6;
    private Wall wall;
    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            wall.invalidate();
            autoBlackTick();
            main.postDelayed(this, 1000);
        }
    };

    // ---- auto blackout: after a while with no touch and no camera trigger; a trigger wakes it ----

    private long lastTouch = System.currentTimeMillis();
    /** The screen was woken from Blackout by a camera trigger (it goes back once they are all quiet). */
    private boolean wokeByTrigger;

    private boolean autoBlack() {
        return sp.getBoolean("autoBlack", true);
    }

    private int autoBlackSecs() {
        return sp.getInt("autoBlackSecs", 60);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastTouch = System.currentTimeMillis();
        wokeByTrigger = false; // someone is using it now
    }

    private void autoBlackTick() {
        long now = System.currentTimeMillis();
        boolean black = LightDiscipline.blackedOut(this);
        boolean triggered = wall.front() != null;
        if (triggered) {
            lastTouch = now; // not idle while a camera is in front
            if (black) {
                LightDiscipline.endBlackout(this);
                wokeByTrigger = true;
            }
            return;
        }
        if (wokeByTrigger && !black) { // the triggered cameras have gone quiet (after their hold time)
            wokeByTrigger = false;
            if (autoBlack() && hasWindowFocus()) LightDiscipline.blackout(this);
            return;
        }
        if (autoBlack() && !black && hasWindowFocus() && now - lastTouch >= autoBlackSecs() * 1000L) {
            LightDiscipline.blackout(this);
        }
    }

    private void autoBlackSettings() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        final android.widget.CheckBox on = new android.widget.CheckBox(this);
        on.setText("Go to Blackout when nobody has touched the screen and no camera has been triggered for:");
        on.setChecked(autoBlack());
        box.addView(on);
        final android.widget.EditText secs = new android.widget.EditText(this);
        secs.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        secs.setText(String.valueOf(autoBlackSecs()));
        box.addView(secs);
        android.widget.TextView note = new android.widget.TextView(this);
        note.setText("seconds. A triggered camera wakes the screen and shows it; it goes back to Blackout once the cameras have "
                + "been quiet for the hold time. Double-tap to wake it yourself.");
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Auto blackout")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int s = 60;
                        try {
                            s = Math.max(10, Math.min(3600, Integer.parseInt(secs.getText().toString().trim())));
                        } catch (NumberFormatException ignored) {
                        }
                        sp.edit().putBoolean("autoBlack", on.isChecked()).putInt("autoBlackSecs", s).commit();
                        lastTouch = System.currentTimeMillis();
                        invalidateOptionsMenu();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tactical videos");
        sp = getSharedPreferences("video", MODE_PRIVATE);
        wall = new Wall(this);
        wall.hidden = hidden(sp);
        wall.autoFront = true;
        wall.onOpenLocal = new Wall.OpenLocal() {
            @Override
            public void open(String name) {
                ReolinkViewActivity.show(TacticalVideoActivity.this, name, false);
            }
        };
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
        invalidateOptionsMenu(); // the red dot on My camera while it is shared
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
        lastTouch = System.currentTimeMillis();
        ReolinkMonitor.acquire(this, "tactical");
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
        ReolinkMonitor.release("tactical");
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, MENU_PEOPLE, 0, "People").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, MENU_SHARE, 0, CameraShareService.running() ? "\u25CF My camera" : "My camera").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        // Timer beside Blackout: auto blackout on (timer) or off (timer crossed out); one tap switches it.
        m.add(0, MENU_ABTOGGLE, 0, autoBlack() ? "Auto blackout is on (tap to turn off)" : "Auto blackout is off (tap to turn on)")
                .setIcon(autoBlack() ? R.drawable.ic_timer : R.drawable.ic_timer_off)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, MENU_BLACKOUT, 0, "Blackout").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, MENU_CAMS, 0, "Reolink cameras").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, MENU_AUTOBLACK, 0, "Auto blackout\u2026").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
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
        if (item.getItemId() == MENU_ABTOGGLE) {
            boolean on = !autoBlack();
            sp.edit().putBoolean("autoBlack", on).commit();
            lastTouch = System.currentTimeMillis();
            invalidateOptionsMenu();
            android.widget.Toast.makeText(this, on ? "Auto blackout on (after " + autoBlackSecs() + " s)" : "Auto blackout off",
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        if (item.getItemId() == MENU_AUTOBLACK) {
            autoBlackSettings();
            return true;
        }
        if (item.getItemId() == MENU_CAMS) {
            startActivity(new Intent(this, ReolinkSetupActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_BLACKOUT) { // the screen goes black; my camera (if shared) keeps going
            if (LightDiscipline.legacyBlackout(this)) android.widget.Toast.makeText(this, "Double-tap to wake", android.widget.Toast.LENGTH_SHORT).show();
            LightDiscipline.blackout(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void choosePeople() {
        List<VideoHub.Feed> feeds = VideoHub.feeds();
        if (feeds.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No one is sharing a camera yet (Reolink cameras are added from the menu).")
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
        /** A triggered LAN camera fills the wall until it goes quiet (or the wall is tapped). */
        boolean autoFront;
        /** Only LAN (Reolink) cameras: the strip on the Intercom. */
        boolean onlyLocal;
        /** Triggered cameras sent back to the grid by a tap (until each goes quiet). */
        private final Set<String> dismissed = new HashSet<String>();

        interface OpenLocal {
            void open(String name);
        }

        /** Set: a tap on a LAN camera opens it full screen (live video) instead of filling the wall. */
        OpenLocal onOpenLocal;

        /** The triggered cameras shown in front now (sharing the screen), or null for the normal grid. */
        Set<String> front() {
            if (!autoFront || focus != null) return null;
            List<String> all = VideoHub.alertingAll();
            dismissed.retainAll(all);
            Set<String> out = new HashSet<String>();
            for (String a : all) {
                if (dismissed.contains(a) || hidden.contains(a) || VideoHub.feed(a) == null) continue;
                out.add(a);
            }
            return out.isEmpty() ? null : out;
        }
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
            Set<String> front = front();
            for (VideoHub.Feed f : VideoHub.feeds()) {
                if (hidden.contains(f.name)) continue;
                if (onlyLocal && !f.local) continue;
                if (focus != null && !focus.equals(f.name)) continue;
                if (front != null && !front.contains(f.name)) continue;
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
                    String[] lines = onlyLocal ? new String[]{"Cameras starting\u2026"} : new String[]{"No cameras shared yet.", "Share yours from My camera."};
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
                String label = f.name + (f.alert != null ? "  \u25CF " + f.alert : "") + (f.note != null ? "  " + f.note
                        : age >= 3 ? "  (" + age + " s ago)" : "");
                c.drawRect(cell.left, cell.bottom - 20 * dp, cell.right, cell.bottom, band);
                text.setColor(f.alert != null ? 0xFFFF8A80 : age >= 10 || f.note != null ? 0xFFFFB0B0 : Color.WHITE);
                c.drawText(label, cell.left + 6 * dp, cell.bottom - 6 * dp, text);
                text.setColor(Color.WHITE);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            Set<String> front = front();
            if (front != null && onOpenLocal == null) {
                dismissed.addAll(front); // back to the grid; a new trigger after they go quiet brings them forward again
            } else if (focus != null) {
                focus = null;
            } else {
                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i).contains(e.getX(), e.getY())) {
                        String n = cellNames.get(i);
                        VideoHub.Feed f = VideoHub.feed(n);
                        if (front != null) dismissed.addAll(front);
                        if (f != null && f.local && onOpenLocal != null) onOpenLocal.open(n);
                        else if (front == null) focus = n;
                        break;
                    }
                }
            }
            invalidate();
            return true;
        }
    }
}
