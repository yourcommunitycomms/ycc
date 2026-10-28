package org.communitycomms.app.geo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Map controls for screens without easy dragging (a TV remote, a mouse, gloves): up, down, left,
 * right, zoom in and out, and a dot in the middle that centres on me. Sits in a corner over the map;
 * every button is focusable, so a TV remote's arrows and OK work it.
 */
public final class MapPad {
    private MapPad() {
    }

    /** Settings: show the pad on the maps (off by default; the full map has the switch). */
    public static boolean shown(Context c) {
        return c.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean("showMapPad", false);
    }

    public static void setShown(Context c, boolean on) {
        c.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("showMapPad", on).commit();
    }

    /** Adds the pad over the map (holder must be the FrameLayout the map is in). onCentre may be null. */
    public static View attach(FrameLayout holder, final TileMapView map, final Runnable onCentre, int gravity) {
        Context c = holder.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        final int size = (int) (40 * d);
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(LinearLayout.HORIZONTAL);
        pad.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(row(c, size, null, key(c, "▲", size, pan(map, 0, -1)), null));
        grid.addView(row(c, size, key(c, "◀", size, pan(map, -1, 0)), key(c, "●", size, new Runnable() {
            @Override
            public void run() {
                if (onCentre != null) onCentre.run();
            }
        }), key(c, "▶", size, pan(map, 1, 0))));
        grid.addView(row(c, size, null, key(c, "▼", size, pan(map, 0, 1)), null));
        pad.addView(grid);
        LinearLayout zoom = new LinearLayout(c);
        zoom.setOrientation(LinearLayout.VERTICAL);
        zoom.setPadding((int) (6 * d), 0, 0, 0);
        zoom.addView(key(c, "+", size, new Runnable() {
            @Override
            public void run() {
                map.zoomBy(1, map.getWidth() / 2f, map.getHeight() / 2f);
            }
        }));
        View gap = new View(c);
        zoom.addView(gap, new LinearLayout.LayoutParams(size, size / 2));
        zoom.addView(key(c, "−", size, new Runnable() {
            @Override
            public void run() {
                map.zoomBy(-1, map.getWidth() / 2f, map.getHeight() / 2f);
            }
        }));
        pad.addView(zoom);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        int m = (int) (8 * d);
        lp.setMargins(m, m, m, (int) (36 * d)); // clear of the "+ is … from me" line at the bottom
        holder.addView(pad, lp);
        pad.setVisibility(shown(c) ? View.VISIBLE : View.GONE);
        return pad;
    }

    private static Runnable pan(final TileMapView map, final int dx, final int dy) {
        return new Runnable() {
            @Override
            public void run() {
                map.pan(dx * map.getWidth() / 3f, dy * map.getHeight() / 3f);
            }
        };
    }

    private static LinearLayout row(Context c, int size, View a, View b, View cc) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : new View[]{a, b, cc}) {
            r.addView(v != null ? v : new View(c), new LinearLayout.LayoutParams(size, size));
        }
        return r;
    }

    private static Button key(Context c, String label, int size, final Runnable action) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setFocusable(true);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xccF0C75E));
        bg.addState(new int[]{android.R.attr.state_pressed}, round(0xccF0C75E));
        bg.addState(new int[]{}, round(0x991F3D2B));
        b.setBackgroundDrawable(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private static GradientDrawable round(int argb) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(argb);
        g.setCornerRadius(10);
        g.setStroke(1, 0x66CFE3D4);
        return g;
    }
}
