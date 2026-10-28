package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.widget.Toast;

/**
 * Feeds a map its tile sources, and when online tiles are off and the view lands on an area with
 * no offline tiles, asks for 10 s whether to fetch online tiles for 5 minutes. No answer = stay
 * offline. Saying no (or letting it time out) stops it asking again for 2 minutes.
 */
public final class OnlineTilePrompt {
    public static final long ONLINE_FOR_MS = 5 * 60 * 1000L;
    private static final int ASK_SECONDS = 10;
    private static final long SNOOZE_MS = 2 * 60 * 1000L;
    /** Shared by every map screen, so leaving one map for another does not ask again at once. */
    private static long snoozeUntil;

    private final Activity activity;
    private final TileMapView map;
    private final MapPrefs mp;
    private final Runnable onChange;
    private final Handler handler = new Handler();
    private AlertDialog dialog;
    private boolean resumed;

    private final Runnable expire = new Runnable() {
        @Override
        public void run() {
            boolean was = map.hasOnline();
            apply();
            if (was && !map.hasOnline() && resumed) {
                Toast.makeText(activity, "Online map tiles off again (offline mode)", Toast.LENGTH_LONG).show();
            }
        }
    };

    /** onChange runs whenever the sources change (to update a label); may be null. */
    public OnlineTilePrompt(Activity a, TileMapView map, Runnable onChange) {
        activity = a;
        this.map = map;
        this.onChange = onChange;
        mp = new MapPrefs(a);
        map.setOnBlankArea(new TileMapView.OnBlankArea() {
            @Override
            public void onBlankArea() {
                maybeAsk();
            }
        });
    }

    /** Call from onResume. */
    public void resume() {
        resumed = true;
        apply();
    }

    /** Call from onPause. */
    public void pause() {
        resumed = false;
        handler.removeCallbacks(expire);
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /** Minutes left of a temporary online window, or 0. */
    public int minutesLeft() {
        long left = mp.onlineUntil() - System.currentTimeMillis();
        return left <= 0 || mp.onlineEnabled() ? 0 : (int) ((left + 59999) / 60000);
    }

    public void apply() {
        boolean online = mp.onlineActive();
        map.setSources(mp.offlineFolder(), online ? mp.onlineUrl() : null, mp.onlineCache());
        mp.applyOverlay(map);
        map.setLan(new LanTiles(activity), mp.chosenUrl(), mp.overlayUrl());
        handler.removeCallbacks(expire);
        long left = mp.onlineUntil() - System.currentTimeMillis();
        if (online && !mp.onlineEnabled() && left > 0) handler.postDelayed(expire, left + 200);
        if (onChange != null) onChange.run();
    }

    private void maybeAsk() {
        if (!resumed || dialog != null || map.hasOnline()) return;
        if (System.currentTimeMillis() < snoozeUntil) return;
        if (mp.onlineUrl().length() == 0) return; // custom source with no URL: nothing to offer
        if (new org.communitycomms.app.Prefs(activity).radioSilent()) return;
        final int[] left = {ASK_SECONDS};
        final android.widget.TextView msg = new android.widget.TextView(activity);
        android.widget.LinearLayout box = choices(msg, true);
        msg.setText(text(left[0]));
        dialog = new AlertDialog.Builder(activity)
                .setTitle("No offline map here")
                .setView(box)
                .setNegativeButton("Stay offline", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                dialog = null;
                handler.removeCallbacksAndMessages(left);
                if (!map.hasOnline()) snoozeUntil = System.currentTimeMillis() + SNOOZE_MS;
            }
        });
        dialog.show();
        final Runnable count = new Runnable() {
            @Override
            public void run() {
                if (dialog == null) return;
                left[0]--;
                if (left[0] <= 0) {
                    dialog.dismiss();
                    return;
                }
                msg.setText(text(left[0]));
                handler.postAtTime(this, left, android.os.SystemClock.uptimeMillis() + 1000);
            }
        };
        handler.postAtTime(count, left, android.os.SystemClock.uptimeMillis() + 1000);
    }

    private static String text(int s) {
        return "This area has no offline tiles. Fetch map tiles from the internet?\n\nStaying offline in " + s + " s";
    }

    private static final String[] LABELS = {"Online for 5 min", "Online for 1 hour", "Online for 1 day", "Online permanently"};
    private static final long[] TIMES = {ONLINE_FOR_MS, 3600000L, 24 * 3600000L, -1};

    /** The message (if any) above one button per choice: 5 min, 1 hour, 1 day, permanently. */
    private android.widget.LinearLayout choices(android.widget.TextView msg, final boolean fromPrompt) {
        float d = activity.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        if (msg != null) box.addView(msg);
        for (int i = 0; i < LABELS.length; i++) {
            final int k = i;
            android.widget.Button b = new android.widget.Button(activity);
            b.setText(LABELS[i]);
            b.setAllCaps(false);
            b.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    goOnline(TIMES[k]);
                    Toast.makeText(activity, "Map tiles: " + LABELS[k].toLowerCase(java.util.Locale.US), Toast.LENGTH_SHORT).show();
                    if (dialog != null) dialog.dismiss();
                    if (picker != null) picker.dismiss();
                }
            });
            box.addView(b);
        }
        return box;
    }

    private AlertDialog picker;

    private void goOnline(long ms) {
        if (ms < 0) {
            mp.setOnlineEnabled(true);
        } else {
            mp.setOnlineEnabled(false);
            mp.setOnlineFor(ms);
        }
        apply();
        syncWithLan(); // what the LAN already has need not come from the internet
    }

    /** Quietly syncs what is on screen (this zoom and the next two) with the LAN storage location. */
    public void syncWithLan() {
        final LanTiles lan = new LanTiles(activity);
        if (!lan.configured() || mp.onlineUrl().length() == 0) return;
        final double[] b = map.bounds();
        final int z = map.zoom();
        // The source this phone really fetches (Google's road map on Android 4.x), so its cache
        // never lands in another source's LAN folder.
        final String src = LanTiles.sourceId(mp.onlineUrl());
        final java.io.File cache = mp.onlineCache();
        new Thread(new Runnable() {
            @Override
            public void run() {
                lan.syncArea(src, cache, b[0], b[1], b[2], b[3], z, Math.min(19, z + 2), null);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        map.retryMissing();
                    }
                });
            }
        }, "lan-sync").start();
    }

    /** "Online map tiles…" from a map screen: 5 min, 1 hour, 1 day, permanently, or offline now. */
    public void choose() {
        if (mp.onlineUrl().length() == 0) {
            Toast.makeText(activity, "No online map source set (Data / files > Maps)", Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.TextView msg = new android.widget.TextView(activity);
        int mins = minutesLeft();
        msg.setText(mp.onlineEnabled() ? "Online tiles are on permanently."
                : mins > 0 ? "Online tiles are on for another " + (mins >= 120 ? (mins / 60) + " hours" : mins + " min") + "."
                : "Online tiles are off (offline only).");
        picker = new AlertDialog.Builder(activity)
                .setTitle("Online map tiles")
                .setView(choices(msg, false))
                .setNegativeButton("Offline now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        mp.setOnlineEnabled(false);
                        mp.setOnlineFor(0);
                        apply();
                    }
                })
                .show();
    }
}
