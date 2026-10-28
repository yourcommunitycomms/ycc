package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.communitycomms.app.crypto.ChannelCrypto;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Optional PIN that guards the sensitive parts of the app: Modern DRYAD and pigpen, revealing
 * channel names / channel QR, and joining a channel you have not joined before. Once entered it
 * stays unlocked for a few minutes so it does not nag. Stored as a salted PBKDF2 hash.
 */
public final class PinLock {
    public interface Done {
        void onUnlocked();
    }

    private static final long UNLOCK_MS = 5 * 60000;
    private static volatile long unlockedUntil;

    private PinLock() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context c) {
        return sp(c).getString("pinHash", "").length() > 0;
    }

    private static String hash(String pin, String salt) {
        try {
            byte[] h = ChannelCrypto.pbkdf2Sha256(pin.getBytes(Charset.forName("UTF-8")),
                    Base64.decode(salt, Base64.NO_WRAP), 5000, 32);
            return Base64.encodeToString(h, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean check(Context c, String pin) {
        String salt = sp(c).getString("pinSalt", "");
        String want = sp(c).getString("pinHash", "");
        return want.length() > 0 && MessageDigest.isEqual(want.getBytes(), hash(pin, salt).getBytes());
    }

    static void set(Context c, String pin) {
        if (pin == null || pin.length() == 0) {
            sp(c).edit().remove("pinHash").remove("pinSalt").remove("lastChannelHash").remove("pinFails").commit();
            new Prefs(c).setWipeOnBadPin(false); // nothing to guess without a PIN
            return;
        }
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        String salt = Base64.encodeToString(s, Base64.NO_WRAP);
        sp(c).edit().putString("pinSalt", salt).putString("pinHash", hash(pin, salt)).commit();
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS;
    }

    /** Runs done right away if no PIN is set or it was entered recently; otherwise asks. */
    public static void require(final Activity a, String why, final Done done, final Runnable cancelled) {
        if (!isSet(a) || System.currentTimeMillis() < unlockedUntil) {
            done.onUnlocked();
            return;
        }
        final EditText pin = pinField(a);
        new AlertDialog.Builder(a)
                .setTitle("PIN required")
                .setMessage(why)
                .setView(wrap(a, pin))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (check(a, pin.getText().toString())) {
                            sp(a).edit().putInt("pinFails", 0).commit();
                            unlockedUntil = System.currentTimeMillis() + UNLOCK_MS;
                            done.onUnlocked();
                        } else {
                            if (wrongPin(a)) return;
                            Toast.makeText(a, "Wrong PIN", Toast.LENGTH_SHORT).show();
                            if (cancelled != null) cancelled.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (cancelled != null) cancelled.run();
                    }
                })
                .show();
    }

    /**
     * For a protected screen: call from onResume. Hides the screen until the PIN is entered;
     * cancelling closes it.
     */
    public static void gateScreen(final Activity a, String why) {
        if (!isSet(a) || System.currentTimeMillis() < unlockedUntil) return;
        final android.view.View content = a.findViewById(android.R.id.content);
        if (content.getTag(TAG_PROMPTING) != null) return;
        content.setTag(TAG_PROMPTING, Boolean.TRUE);
        content.setVisibility(android.view.View.INVISIBLE);
        require(a, why, new Done() {
            @Override
            public void onUnlocked() {
                content.setTag(TAG_PROMPTING, null);
                content.setVisibility(android.view.View.VISIBLE);
            }
        }, new Runnable() {
            @Override
            public void run() {
                a.finish();
            }
        });
    }

    private static final int TAG_PROMPTING = 0x7f0f1234;
    /** Wrong entries in a row that trigger the lost-device wipe (when it is switched on). */
    public static final int WIPE_AFTER = 2;

    /**
     * Counts a wrong entry (kept across restarts). With lost-device protection on, the second
     * wrong entry in a row erases all app data and closes the app; returns true if that happened.
     */
    static boolean wrongPin(Activity a) {
        int fails = sp(a).getInt("pinFails", 0) + 1;
        sp(a).edit().putInt("pinFails", fails).commit();
        Prefs p = new Prefs(a);
        // Intercom units sit in public places: strangers poking at the PIN must never wipe them.
        if (fails >= WIPE_AFTER && p.wipeOnBadPin() && p.appMode() != Prefs.MODE_INTERCOM) {
            AppWipe.wipeAndExit(a);
            return true;
        }
        return false;
    }

    public static void lockNow() {
        unlockedUntil = 0;
    }

    /** Set, change or remove the PIN (asks for the current one first). */
    public static void manage(final Activity a) {
        require(a, "Enter your current PIN to change it.", new Done() {
            @Override
            public void onUnlocked() {
                final EditText p1 = pinField(a), p2 = pinField(a);
                p1.setHint("New PIN (4–12 digits)");
                p2.setHint("New PIN again");
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(LinearLayout.VERTICAL);
                box.addView(p1);
                box.addView(p2);
                AlertDialog.Builder b = new AlertDialog.Builder(a)
                        .setTitle(isSet(a) ? "Change PIN" : "Set a PIN")
                        .setMessage("Protects Modern DRYAD, pigpen, settings, showing channel names and joining new channels.")
                        .setView(wrap(a, box))
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String s1 = p1.getText().toString(), s2 = p2.getText().toString();
                                if (s1.length() < 4 || !s1.equals(s2)) {
                                    Toast.makeText(a, "PINs must match and be at least 4 digits", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                set(a, s1);
                                Toast.makeText(a, "PIN set", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null);
                if (isSet(a)) {
                    b.setNeutralButton("Remove PIN", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            set(a, null);
                            Toast.makeText(a, "PIN removed", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                b.show();
            }
        }, null);
    }

    /** A channel never joined before (since the PIN was set) needs the PIN. */
    public static boolean isNewChannel(Context c, String channel) {
        return !sp(c).getString("lastChannelHash", "").equals(channelHash(channel));
    }

    public static void rememberChannel(Context c, String channel) {
        sp(c).edit().putString("lastChannelHash", channelHash(channel)).commit();
    }

    private static String channelHash(String ch) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("ch|" + ch).getBytes(Charset.forName("UTF-8")));
            return Base64.encodeToString(d, 0, 12, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static EditText pinField(Context c) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setSingleLine(true);
        return e;
    }

    private static LinearLayout wrap(Context c, android.view.View v) {
        LinearLayout l = new LinearLayout(c);
        int pad = (int) (16 * c.getResources().getDisplayMetrics().density);
        l.setPadding(pad, 0, pad, 0);
        l.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }
}
