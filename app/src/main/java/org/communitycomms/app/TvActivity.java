package org.communitycomms.app;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.TileMapView;
import org.communitycomms.app.msg.MessageCenter;
import org.communitycomms.app.msg.MessageLog;

import java.util.ArrayList;
import java.util.List;

/**
 * TV mode: the channel's map on a big screen with chat popping up like notifications down the
 * right-hand side (pinned announcements stay at the top) and any shared cameras in a corner. Opens
 * by itself on an Android TV with no private key (on the Public channel); with a private key the
 * TV shows the commander view instead. Remote: arrows move the map, + / − (or channel up / down, fast
 * forward / rewind) zoom, OK fits everyone, back leaves.
 */
public final class TvActivity extends Activity {
    private static final long SHOW_MS = 90 * 1000L;
    private TileMapView map;
    private LinearLayout chat;
    private TextView header;
    private TacticalVideoActivity.Wall wall;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int lastMarkCount = -1;
    private boolean moved;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 3000);
        }
    };

    /** An Android TV (or a box on a TV): UI mode television, or the leanback feature. */
    public static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return c.getPackageManager().hasSystemFeature("android.software.leanback");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        map = new TileMapView(this, null);
        root.addView(map);

        header = new TextView(this);
        header.setTextColor(Color.WHITE);
        header.setTextSize(16);
        header.setBackgroundColor(0xaa000000);
        header.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        root.addView(header, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) (320 * d), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        cl.setMargins(0, (int) (8 * d), (int) (8 * d), 0);
        root.addView(chat, cl);

        wall = new TacticalVideoActivity.Wall(this);
        wall.showEmptyHint = false;
        wall.hidden = TacticalVideoActivity.hidden(getSharedPreferences("video", MODE_PRIVATE));
        wall.setVisibility(View.GONE);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams((int) (320 * d), (int) (200 * d), Gravity.BOTTOM | Gravity.LEFT);
        wl.setMargins((int) (8 * d), 0, 0, (int) (8 * d));
        root.addView(wall, wl);
        setContentView(root);

        org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
        map.center(mp.centerLat(), mp.centerLon(), mp.zoom());
        tiles = new org.communitycomms.app.geo.OnlineTilePrompt(this, map, null);
        adsb = new org.communitycomms.app.geo.AdsbFeed(this);
        ships = new org.communitycomms.app.geo.ShipFeed(this);
        alerts = new org.communitycomms.app.geo.CapFeed(this);
    }

    private org.communitycomms.app.geo.OnlineTilePrompt tiles;
    private org.communitycomms.app.geo.AdsbFeed adsb;
    private org.communitycomms.app.geo.ShipFeed ships;
    private org.communitycomms.app.geo.CapFeed alerts;

    @Override
    protected void onResume() {
        super.onResume();
        tiles.resume();
        RadioService.poiUi = poiRefresh;
        ships.setCentre(map.centerLat(), map.centerLon());
        ships.start(poiRefresh);
        alerts.setCentre(map.centerLat(), map.centerLon());
        alerts.start(poiRefresh);
        adsb.setCentre(map.centerLat(), map.centerLon());
        adsb.start(new Runnable() {
            @Override
            public void run() {
                adsb.setCentre(map.centerLat(), map.centerLon());
                poiRefresh.run();
            }
        });
        VideoHub.ui = new Runnable() {
            @Override
            public void run() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        wall.setVisibility(wall.visible().isEmpty() ? View.GONE : View.VISIBLE);
                        wall.invalidate();
                    }
                });
            }
        };
        main.post(tick);
    }

    @Override
    protected void onPause() {
        tiles.pause();
        adsb.stop();
        ships.stop();
        alerts.stop();
        main.removeCallbacks(tick);
        if (RadioService.poiUi == poiRefresh) RadioService.poiUi = null;
        VideoHub.ui = null;
        super.onPause();
    }

    private final Runnable poiRefresh = new Runnable() {
        @Override
        public void run() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    };

    private void refresh() {
        List<TileMapView.Marker> marks = MapActivity.markers(this, null, thumbs);
        int ours = marks.size();
        MapActivity.addAircraft(marks, adsb);
        MapActivity.addShips(marks, ships);
        MapActivity.addAlerts(marks, alerts);
        map.setMarkers(marks);
        marks = marks.subList(0, ours); // fit to our people and points, not every aircraft
        // Follow everyone until someone moves the map with the remote.
        if (!moved && marks.size() != lastMarkCount && map.getWidth() > 0) {
            lastMarkCount = marks.size();
            fit(marks);
        }
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        int people = 0;
        if (e != null) for (RadioEngine.Station s : e.stations()) if (!Double.isNaN(s.lat)) people++;
        header.setText(e == null ? "Your Community Communications · not on a channel"
                : "Your Community Communications · " + people + " on the map · OK = fit everyone");
        renderChat();
        wall.setVisibility(wall.visible().isEmpty() ? View.GONE : View.VISIBLE);
        wall.invalidate();
    }

    private final java.util.Map<String, android.graphics.Bitmap> thumbs = new java.util.HashMap<String, android.graphics.Bitmap>();

    private void fit(List<TileMapView.Marker> marks) {
        if (marks.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (TileMapView.Marker m : marks) {
            minLat = Math.min(minLat, m.lat);
            maxLat = Math.max(maxLat, m.lat);
            minLon = Math.min(minLon, m.lon);
            maxLon = Math.max(maxLon, m.lon);
        }
        int zoom = 17;
        while (zoom > 3) {
            double w = (Geo.lonToX(maxLon, zoom) - Geo.lonToX(minLon, zoom)) * 256;
            double h = (Geo.latToY(minLat, zoom) - Geo.latToY(maxLat, zoom)) * 256;
            if (w < map.getWidth() * 0.55 && h < map.getHeight() * 0.7) break;
            zoom--;
        }
        map.center((minLat + maxLat) / 2, (minLon + maxLon) / 2, zoom);
    }

    /** Pinned announcements, then the latest messages of the last minute and a half. */
    private void renderChat() {
        chat.removeAllViews();
        int tag = ChatActivity.channelOnAir();
        if (tag == 0) return;
        List<MessageLog.Entry> all = MessageLog.get(this).all(tag);
        java.util.Set<Long> down = MessageCenter.unpinned(all);
        List<MessageLog.Entry> recent = new ArrayList<MessageLog.Entry>();
        long now = System.currentTimeMillis();
        for (MessageLog.Entry m : all) {
            if (m.type != MessageLog.TYPE_TEXT || MessageCenter.isUnpin(m) || m.privateTo.length() > 0) continue;
            if (MessageCenter.isPin(m)) {
                if (!down.contains(m.id)) card("PINNED · " + who(m) + ": " + m.body.substring(MessageCenter.PIN.length()), 0xfff0c75e);
            } else if (now - m.time < SHOW_MS && !m.ack) {
                recent.add(m);
            }
        }
        for (int i = Math.max(0, recent.size() - 6); i < recent.size(); i++) {
            MessageLog.Entry m = recent.get(i);
            card(who(m) + ": " + m.body, Color.WHITE);
        }
    }

    private static String who(MessageLog.Entry m) {
        return m.mine ? "Me" : m.creator;
    }

    private void card(String text, int colour) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(colour);
        t.setTextSize(17);
        t.setBackgroundColor(0xcc202020);
        t.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int) (6 * d));
        chat.addView(t, lp);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        float cx = map.getWidth() / 2f, cy = map.getHeight() / 2f;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moved = true;
                map.pan(code == KeyEvent.KEYCODE_DPAD_LEFT ? -cx * 2 / 3 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? cx * 2 / 3 : 0,
                        code == KeyEvent.KEYCODE_DPAD_UP ? -cy * 2 / 3 : code == KeyEvent.KEYCODE_DPAD_DOWN ? cy * 2 / 3 : 0);
                return true;
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_ZOOM_IN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                moved = true;
                map.zoomBy(1, cx, cy);
                return true;
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_ZOOM_OUT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                moved = true;
                map.zoomBy(-1, cx, cy);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                moved = false;
                lastMarkCount = -1;
                refresh();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_MOVE) moved = true; // touch TVs / tablets
        return super.dispatchTouchEvent(ev);
    }
}
