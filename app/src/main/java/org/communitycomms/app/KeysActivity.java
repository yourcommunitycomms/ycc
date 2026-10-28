package org.communitycomms.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The channels page of the simple modes and the intercom: just the group's private key (channel A)
 * and the public key, each shown hidden until "Show", with Make one, QR and Scan. Public can be
 * hidden from the home screen. Saving goes back to the home screen, which re-joins with the new key.
 */
public final class KeysActivity extends Activity {
    private Prefs prefs;
    private EditText priv, pub;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        final boolean intercom = prefs.appMode() == Prefs.MODE_INTERCOM;
        setTitle(intercom ? "Intercom key" : "Keys");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (16 * d));

        box.addView(label(intercom ? "Intercom key" : "Private key (your group's channel)"));
        box.addView(note(intercom
                ? "Every intercom in the house types the same key (or scans its QR). Only phones with it can hear."
                : "Everyone in your group uses the same private key. It is the password: long and hard to guess."));
        priv = keyField(prefs.channel(Prefs.SLOT_A));
        box.addView(priv);
        final CheckBox showPriv = new CheckBox(this);
        showPriv.setText("Show");
        showPriv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reveal(priv, showPriv.isChecked());
            }
        });
        Button make = button("Make one", new Runnable() {
            @Override
            public void run() {
                priv.setText(randomKey());
                showPriv.setChecked(true);
                reveal(priv, true);
            }
        });
        Button qr = button("Show QR", new Runnable() {
            @Override
            public void run() {
                if (!save(false)) return;
                MainActivity.showChannelQr(KeysActivity.this, Prefs.SLOT_A);
            }
        });
        Button scan = button("Scan QR", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(KeysActivity.this, QrScanActivity.class));
            }
        });
        box.addView(FilesActivity.row(showPriv, make, qr, scan));

        if (!intercom) {
            box.addView(label("Public key"));
            box.addView(note("For meeting other groups on the Public channel. Keep the default unless told otherwise."));
            pub = keyField(prefs.publicKey());
            box.addView(pub);
            final CheckBox showPub = new CheckBox(this);
            showPub.setText("Show");
            showPub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    reveal(pub, showPub.isChecked());
                }
            });
            box.addView(showPub);
            box.addView(button("Shareable listen key (read only)…", new Runnable() {
                @Override
                public void run() {
                    if (pub.getText().toString().trim().length() > 0) prefs.setPublicKey(pub.getText().toString());
                    ListenKeyShare.show(KeysActivity.this);
                }
            }));
            final CheckBox onHome = new CheckBox(this);
            onHome.setText("Show Public on the home screen");
            onHome.setChecked(prefs.showPublic());
            onHome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setShowSlots(onHome.isChecked(), false);
                }
            });
            box.addView(onHome);
        }

        Button done = button("Save", new Runnable() {
            @Override
            public void run() {
                if (save(true)) {
                    // Back to the home screen: it re-joins with the new key (and puts the intercom back up).
                    startActivity(new Intent(KeysActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        box.addView(done, lp);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A scanned QR may have just set channel A.
        if (priv != null && priv.getText().length() == 0) priv.setText(prefs.channel(Prefs.SLOT_A));
    }

    private boolean save(boolean toast) {
        String k = priv.getText().toString().trim();
        if (k.length() < 8) {
            Toast.makeText(this, "The private key needs at least 8 characters", Toast.LENGTH_SHORT).show();
            return false;
        }
        prefs.setChannel(Prefs.SLOT_A, k);
        if (pub != null && pub.getText().toString().trim().length() > 0) prefs.setPublicKey(pub.getText().toString());
        if (toast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private EditText keyField(String v) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(v);
        reveal(e, false);
        return e;
    }

    private static void reveal(EditText e, boolean show) {
        e.setInputType(InputType.TYPE_CLASS_TEXT | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        e.setSelection(e.getText().length());
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(18);
        v.setTextColor(0xfff0c75e);
        v.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }

    private TextView note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        return v;
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    static String randomKey() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 5 == 0) sb.append('-');
            sb.append(abc.charAt(r.nextInt(abc.length())));
        }
        return sb.toString();
    }
}
