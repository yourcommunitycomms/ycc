package org.communitycomms.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import org.communitycomms.app.geo.FakeGps;
import org.communitycomms.app.geo.Geo;

/**
 * "Share my location" for screens other than home (the full map), with the same questions: Fake GPS
 * on (keep it, or share the real position obscured or exact), and when already sharing, stop or
 * keep going for longer. Also answers a location request from someone on a private channel.
 */
final class ShareLocation {
    private ShareLocation() {
    }

    static final int REQ_LOCATION = 77;

    /** after (may be null) runs on the main thread when sharing starts, stops or changes. */
    static void start(final Activity a, final Runnable after) {
        start(a, after, false);
    }

    private static void start(final Activity a, final Runnable after, boolean fakeAsked) {
        final RadioService s = RadioService.instance();
        if (s == null || s.engine() == null) {
            Toast.makeText(a, "Join a channel first (not available on OpenMANET*)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.rapidShareLeftMs() > 0) {
            askMode(a, s, after);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            Toast.makeText(a, "Allow location, then tap Share again", Toast.LENGTH_LONG).show();
            return;
        }
        if (FakeGps.enabled(a) && !fakeAsked) {
            new AlertDialog.Builder(a)
                    .setTitle("Fake GPS is on")
                    .setMessage("Others would see a made-up spot near " + FakeGps.centre(a)
                            + ", not where you are. Turn Fake GPS off and share your real position?")
                    .setPositiveButton("Share real position", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            FakeGps.set(a, false, FakeGps.centre(a));
                            askObscure(a, after);
                        }
                    })
                    .setNeutralButton("Keep fake", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            start(a, after, true);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        s.setRapidShare(true);
        Toast.makeText(a, "Sharing your location every 30 s for 30 minutes", Toast.LENGTH_SHORT).show();
        if (after != null) after.run();
    }

    private static void askObscure(final Activity a, final Runnable after) {
        int m = FakeGps.obscureM(a);
        final int keep = m > 0 ? m : FakeGps.OBSCURE_DEFAULT_M;
        new AlertDialog.Builder(a)
                .setTitle("Obscure your position?")
                .setMessage("Share a spot up to " + Geo.range(keep) + " from you (the same offset each time, shown as "
                        + "approximate), or your exact position? Change the range in Settings > Mission and data > "
                        + "Obscure location.")
                .setPositiveButton("Obscure (" + Geo.range(keep) + ")", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        FakeGps.setObscureM(a, keep);
                        start(a, after, true);
                    }
                })
                .setNeutralButton("Exact", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        FakeGps.setObscureM(a, 0);
                        start(a, after, true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void askMode(final Activity a, final RadioService s, final Runnable after) {
        final String[] names = {"Stop sharing", "Share for 2 hours", "Share until the app restarts", "Always share (every time the app runs)"};
        final int[] modes = {RadioService.SHARE_STOP, RadioService.SHARE_2H, RadioService.SHARE_UNTIL_RESTART, RadioService.SHARE_ALWAYS};
        final String[] said = {"Stopped sharing location", "Sharing your location for 2 hours",
                "Sharing your location until the app restarts", "Always sharing your location (tap Share to change)"};
        new AlertDialog.Builder(a)
                .setTitle("Sharing your location")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        s.setRapidShareMode(modes[which]);
                        Toast.makeText(a, said[which], Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Someone asked everyone on the channel to share: ask this person (automatic accept is off). */
    static void answerRequest(final Activity a, String from, final Runnable share) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("Location request")
                .setMessage(from + " asks everyone on this channel to share their location for 30 minutes. Share yours?\n\n"
                        + "(Settings > Mission and data > Automatically accept location sharing answers yes by itself.)")
                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        share.run();
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }
}
