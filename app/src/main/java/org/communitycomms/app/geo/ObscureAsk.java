package org.communitycomms.app.geo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/** Fake GPS just turned off with Obscure location above 0: keep obscuring the real position, or exact? */
public final class ObscureAsk {
    private ObscureAsk() {
    }

    /** done (may be null) runs after the answer; nothing is asked when the range is already 0. */
    public static void afterFakeOff(final Activity a, final Runnable done) {
        final int m = FakeGps.obscureM(a);
        if (m == 0) {
            if (done != null) done.run();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Obscure your real position?")
                .setMessage("Fake GPS is off, so others now get your real position. Obscure it by up to " + Geo.range(m)
                        + " (the same offset each time, shown as approximate)? No sets Obscure location to 0 (exact).")
                .setPositiveButton("Yes, obscure", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton("No, exact", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        FakeGps.setObscureM(a, 0);
                        if (done != null) done.run();
                    }
                })
                .setCancelable(false)
                .show();
    }
}
