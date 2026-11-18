package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import org.json.JSONObject;

/**
 * Setting up from this app's stamp (a site or personal APK from a LAN storage server): the rest of the setup is
 * fetched straight away, then shown to confirm ("Set up as ALPHA1?"); nothing is applied without that tap. If the
 * fetch fails, a warning says why with Retry and Continue without it; Settings > Me can try again later.
 */
final class StampSetup {
    private StampSetup() {
    }

    static boolean available(Activity a) {
        return ApkStamp.read(a) != null;
    }

    /** onDone: set up (the caller refreshes); onSkip: carry on without it (the wizard's QR code step). */
    static void run(final Activity a, final Runnable onDone, final Runnable onSkip) {
        final JSONObject st = ApkStamp.read(a);
        if (st == null) {
            if (onSkip != null) onSkip.run();
            return;
        }
        final String who = ApkStamp.personal(st) ? st.optString("call") : st.optString("site", "this site");
        final AlertDialog wait = new AlertDialog.Builder(a).setTitle(ApkStamp.personal(st) ? "Setting up " + who : "Setting up from " + who)
                .setMessage("This app came with a setup from " + st.optString("site", "a LAN server") + ". Fetching the rest of it from "
                        + st.optString("server") + "…")
                .setCancelable(false).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String got = ApkStamp.fetchSetup(st);
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        wait.dismiss();
                        if (got.startsWith("!")) failed(a, got.substring(1), onDone, onSkip);
                        else confirm(a, st, got, onDone, onSkip);
                    }
                });
            }
        }, "stamp-setup").start();
    }

    private static void failed(final Activity a, String why, final Runnable onDone, final Runnable onSkip) {
        new AlertDialog.Builder(a).setTitle("Setup not fetched")
                .setMessage(why + "\n\nRetry when on that network, or carry on and set up by hand. You can try again later "
                        + "from Settings > Me > Set up from this app's server.")
                .setCancelable(false)
                .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        run(a, onDone, onSkip);
                    }
                })
                .setNegativeButton("Continue without it", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onSkip != null) onSkip.run();
                    }
                }).show();
    }

    private static void confirm(final Activity a, final JSONObject st, final String code, final Runnable onDone, final Runnable onSkip) {
        String what;
        try {
            what = SetupQr.describe(new JSONObject(code.substring(SetupQr.PREFIX.length())));
        } catch (Exception e) {
            failed(a, "The server's setup could not be read.", onDone, onSkip);
            return;
        }
        boolean personal = ApkStamp.personal(st);
        new AlertDialog.Builder(a)
                .setTitle(personal ? "Set up as " + st.optString("call") + "?" : "Set up from " + st.optString("site", "this site") + "?")
                .setMessage("From " + st.optString("site", "the server") + " (" + st.optString("server") + "):\n\n" + what
                        + (st.has("pub") || st.has("cloud") ? "\n\nThe Public key" + (st.has("cloud") ? " and cloud group" : "")
                        + " came inside the app." : ""))
                .setCancelable(false)
                .setPositiveButton("Set up", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ApkStamp.applyStage1(a, st);
                        SetupQr.offer(a, code, onSkip, onDone);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onSkip != null) onSkip.run();
                    }
                }).show();
    }
}
