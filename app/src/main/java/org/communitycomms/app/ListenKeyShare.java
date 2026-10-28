package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.crypto.ListenKey;

import java.util.ArrayList;

/**
 * "Shareable listen key": for a Public channel with a changed key, the read-only key that lets
 * someone hear and read it but never send. Shown as text and a QR code to share publicly.
 */
final class ListenKeyShare {
    private ListenKeyShare() {
    }

    static void show(final Activity a) {
        final String key = new Prefs(a).publicKey();
        if (!ListenKey.signedPublic(key)) {
            new AlertDialog.Builder(a)
                    .setTitle("Shareable listen key")
                    .setMessage("The Public key is still the default, which everyone knows. Change it to your own Public key "
                            + "first; then this gives a read-only key you can share widely.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (ListenKey.isListenKey(key)) {
            new AlertDialog.Builder(a)
                    .setTitle("Listen-only")
                    .setMessage("This phone has the read-only listen key for Public: it can hear and read, but cannot send. "
                            + "You can pass this same key on:\n\n" + key)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Working out the listen key…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    return ListenKey.fromFull(key).shareable();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final String lk) {
                pd.dismiss();
                if (lk == null) {
                    Toast.makeText(a, "Could not work out the listen key on this phone", Toast.LENGTH_LONG).show();
                    return;
                }
                TextView t = new TextView(a);
                int p = (int) (16 * a.getResources().getDisplayMetrics().density);
                t.setPadding(p, p / 2, p, 0);
                t.setTextIsSelectable(true);
                t.setText("Anyone with this key can listen to and read the Public channel, but anything they send is "
                        + "ignored. Your full Public key stays with the people who may talk.\n\n" + lk);
                new AlertDialog.Builder(a)
                        .setTitle("Shareable listen key (read only)")
                        .setView(t)
                        .setPositiveButton("Show QR", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                ArrayList<String> payloads = new ArrayList<String>();
                                payloads.add(lk);
                                ArrayList<String> titles = new ArrayList<String>();
                                titles.add("Public listen key (read only)");
                                QrShowActivity.start(a, payloads, titles, null);
                            }
                        })
                        .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                            @Override
                            @SuppressWarnings("deprecation")
                            public void onClick(DialogInterface d, int w) {
                                android.text.ClipboardManager cm = (android.text.ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
                                if (cm != null) cm.setText(lk);
                                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }
        }.execute();
    }
}
