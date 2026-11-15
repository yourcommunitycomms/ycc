package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The lock button's dialogs: set the password the first time, unlock, change the password, erase all. */
final class ProtectedUi {
    static final String WARNING = "There is no password recovery. If you forget this password, your protected notes "
            + "and calendar entries are lost forever.\n\n" + ProtectedStore.MAX_TRIES + " wrong passwords in a row erase all protected "
            + "entries on this phone.";

    private ProtectedUi() {
    }

    private static volatile int pauses;

    /** A protected screen paused: an unlock still running then ends locked. */
    static void paused() {
        pauses++;
    }

    /** Lock tapped: lock now if unlocked, else unlock (or set the password the first time). */
    static void toggle(final Activity a, final Runnable changed) {
        if (ProtectedStore.unlocked()) {
            ProtectedStore.lock();
            Toast.makeText(a, "Protected entries locked", Toast.LENGTH_SHORT).show();
            changed.run();
            return;
        }
        if (!ProtectedStore.hasPassword(a)) create(a, changed);
        else unlock(a, changed);
    }

    private static EditText pw(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private static LinearLayout box(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        return box;
    }

    private static char[] chars(EditText e) {
        Editable t = e.getText();
        char[] out = new char[t.length()];
        t.getChars(0, out.length, out, 0);
        return out;
    }

    private static void wipe(EditText... es) {
        for (EditText e : es) e.setText("");
    }

    private static void secure(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    static void create(final Activity a, final Runnable changed) {
        LinearLayout box = box(a);
        TextView warn = new TextView(a);
        warn.setText("Protected notes and calendar entries are encrypted with this password and stay on this phone "
                + "only: they are never shared, sent or backed up.\n\n" + WARNING);
        warn.setTextColor(0xffffcc80);
        box.addView(warn);
        final EditText p1 = pw(a, "New password"), p2 = pw(a, "New password again");
        box.addView(p1);
        box.addView(p2);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("Create a protected password").setView(box)
                .setPositiveButton("Create", null).setNegativeButton(android.R.string.cancel, null).create();
        secure(d);
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (p1.length() < 4) {
                    p1.setError("At least 4 characters");
                    return;
                }
                if (!p1.getText().toString().equals(p2.getText().toString())) {
                    p2.setError("The two passwords are different");
                    return;
                }
                final char[] pw = chars(p1);
                wipe(p1, p2);
                d.dismiss();
                work(a, "Setting the password…", new Job() {
                    @Override
                    public boolean run() {
                        return ProtectedStore.create(a, pw);
                    }
                }, new Done() {
                    @Override
                    public void done(boolean ok) {
                        Toast.makeText(a, ok ? "Password set. Protected entries are unlocked." : "Could not save the password",
                                Toast.LENGTH_LONG).show();
                        changed.run();
                    }
                });
            }
        });
    }

    static void unlock(final Activity a, final Runnable changed) {
        LinearLayout box = box(a);
        int left = ProtectedStore.triesLeft(a);
        TextView tries = new TextView(a);
        tries.setText(left <= 1 ? "Last attempt: a wrong password erases all protected entries."
                : left < ProtectedStore.MAX_TRIES ? left + " attempts left. After " + ProtectedStore.MAX_TRIES + " wrong passwords all protected entries are erased."
                : ProtectedStore.MAX_TRIES + " wrong passwords in a row erase all protected entries.");
        tries.setTextColor(left <= 1 ? 0xffff8a80 : left < ProtectedStore.MAX_TRIES ? 0xffffcc80 : 0xffbdbdbd);
        box.addView(tries);
        final EditText p = pw(a, "Password");
        box.addView(p);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("Unlock protected entries").setView(box)
                .setPositiveButton("Unlock", null).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Forgot it…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        erase(a, changed);
                    }
                }).create();
        secure(d);
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final char[] pw = chars(p);
                wipe(p);
                d.dismiss();
                work(a, "Unlocking…", new Job() {
                    @Override
                    public boolean run() {
                        return ProtectedStore.unlock(a, pw);
                    }
                }, new Done() {
                    @Override
                    public void done(boolean ok) {
                        if (!ok) {
                            if (ProtectedStore.erasedByFails) {
                                new AlertDialog.Builder(a).setTitle("Protected entries erased")
                                        .setMessage(ProtectedStore.MAX_TRIES + " wrong passwords: all protected notes and calendar entries and "
                                                + "the protected password were erased from this phone.\n\nTap the lock to set a new password.")
                                        .setPositiveButton(android.R.string.ok, null).show();
                                changed.run();
                                return;
                            }
                            int left = ProtectedStore.triesLeft(a);
                            Toast.makeText(a, "Wrong password. " + (left <= 1 ? "Last attempt left: a wrong one erases all protected entries."
                                    : left + " attempts left."), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (ProtectedStore.badItems > 0) {
                            Toast.makeText(a, ProtectedStore.badItems + " protected item(s) failed their check and are not shown",
                                    Toast.LENGTH_LONG).show();
                        }
                        changed.run();
                    }
                });
            }
        });
    }

    static void changePassword(final Activity a, final Runnable changed) {
        if (!ProtectedStore.hasPassword(a)) {
            Toast.makeText(a, "No protected password has been set yet (tap the lock to set one)", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = box(a);
        TextView warn = new TextView(a);
        warn.setText(WARNING);
        warn.setTextColor(0xffffcc80);
        box.addView(warn);
        final EditText old = pw(a, "Current password"), p1 = pw(a, "New password"), p2 = pw(a, "New password again");
        box.addView(old);
        box.addView(p1);
        box.addView(p2);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("Change protected password").setView(box)
                .setPositiveButton("Change", null).setNegativeButton(android.R.string.cancel, null).create();
        secure(d);
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (p1.length() < 4) {
                    p1.setError("At least 4 characters");
                    return;
                }
                if (!p1.getText().toString().equals(p2.getText().toString())) {
                    p2.setError("The two passwords are different");
                    return;
                }
                final char[] o = chars(old), n = chars(p1);
                wipe(old, p1, p2);
                d.dismiss();
                work(a, "Re-encrypting…", new Job() {
                    @Override
                    public boolean run() {
                        return ProtectedStore.changePassword(a, o, n);
                    }
                }, new Done() {
                    @Override
                    public void done(boolean ok) {
                        Toast.makeText(a, ok ? "Password changed. Protected entries are locked." : ProtectedStore.erasedByFails
                                        ? ProtectedStore.MAX_TRIES + " wrong passwords: all protected entries were erased" : "Wrong current password: nothing changed ("
                                        + ProtectedStore.triesLeft(a) + " attempts left)",
                                Toast.LENGTH_LONG).show();
                        changed.run();
                    }
                });
            }
        });
    }

    static void erase(final Activity a, final Runnable changed) {
        int n = ProtectedStore.count(a);
        new AlertDialog.Builder(a).setTitle("Erase all protected entries?")
                .setMessage("This deletes " + n + " protected note(s) and calendar entries and the protected password on this "
                        + "phone. They cannot be brought back. Your normal notes and calendar are kept.\n\nAfter this you can "
                        + "set a new password.")
                .setPositiveButton("Erase", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        ProtectedStore.eraseAll(a);
                        Toast.makeText(a, "Protected entries erased", Toast.LENGTH_LONG).show();
                        changed.run();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    interface Job {
        boolean run();
    }

    interface Done {
        void done(boolean ok);
    }

    private static void work(final Activity a, String msg, final Job job, final Done done) {
        final int at = pauses;
        final ProgressDialog pd = ProgressDialog.show(a, null, msg, true, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = job.run();
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (!a.isFinishing() && at == pauses) done.done(ok);
                        else ProtectedStore.lock(); // the screen was left meanwhile: stay locked
                    }
                });
            }
        }, "protected").start();
    }
}
