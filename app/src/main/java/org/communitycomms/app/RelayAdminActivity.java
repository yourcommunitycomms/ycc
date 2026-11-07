package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.net.RelayAdmin;

import java.util.ArrayList;

/**
 * Relay admin (a Specialised menu, off by default): manages the internet relay's users from this
 * phone with the relay's admin key. Lists sites and remotes with online / last seen, adds them (the
 * relay makes each one's key), renames, removes (cut off at once) and shows each one's setup QR
 * code, which sets a new phone up in one scan. The admin key is kept only on this phone, in its own
 * settings file: never in backups, network defaults or setup codes.
 */
public final class RelayAdminActivity extends Activity {
    private static final int REQ_SCAN = 1;
    private SharedPreferences sp;
    private TextView relayLine, status;
    private LinearLayout list;
    private float d;

    static final class User {
        String id, kind, name;
        boolean online;
        long agoSec;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Relay admin");
        sp = getSharedPreferences("relayadmin", Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        root.setPadding(pad, pad, pad, pad);
        relayLine = new TextView(this);
        relayLine.setTextSize(16);
        root.addView(relayLine);
        status = new TextView(this);
        status.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        root.addView(status);
        root.addView(row(button("Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }), button("Add site", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add("site");
            }
        }), button("Add remote", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add("remote");
            }
        })));
        root.addView(row(button("Scan admin QR", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(RelayAdminActivity.this, QrScanActivity.class), REQ_SCAN);
            }
        }), button("Paste key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteKey();
            }
        }), button("More…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                more();
            }
        })));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        showRelay();
        String scanned = getIntent().getStringExtra(QrScanActivity.EXTRA_RESULT);
        if (scanned != null) offerKey(scanned);
        else if (configured()) refresh();
        else status.setText("Scan the relay's admin QR code (or paste its admin key) to start. The relay's owner makes it "
                + "on the server; it is kept only on this phone.");
    }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setOnClickListener(l);
        return bt;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private String addr() {
        return sp.getString("addr", "");
    }

    private String key() {
        return sp.getString("key", "");
    }

    private boolean configured() {
        return addr().length() > 0 && key().length() > 0;
    }

    private void showRelay() {
        relayLine.setText(configured() ? "Relay " + addr() : "No relay admin key on this phone");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SCAN && res == RESULT_OK && data != null) offerKey(data.getStringExtra(QrScanActivity.EXTRA_RESULT));
    }

    /** A scanned or pasted admin code: ask, then keep it on this phone. */
    private void offerKey(String text) {
        final String[] p = RelayAdmin.parse(text);
        if (p == null || p[0].length() == 0) {
            Toast.makeText(this, "That is not a relay admin code", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Relay admin key")
                .setMessage("Keep the admin key for the relay at " + p[0] + " on this phone?\n\nAnyone with this phone unlocked "
                        + "can then add and remove the relay's users. It is never put in backups or setup codes.")
                .setPositiveButton("Keep it", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        save(p[0], p[1]);
                        refresh();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void save(String addr, String key) {
        sp.edit().putString("addr", addr.trim()).putString("key", key).commit();
        showRelay();
    }

    private void pasteKey() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final EditText a = new EditText(this);
        a.setHint("Relay address host:port");
        a.setSingleLine(true);
        a.setText(addr().length() > 0 ? addr() : new Prefs(this).vpsServer());
        final EditText k = new EditText(this);
        k.setHint("Admin key (or the whole admin code)");
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(a);
        box.addView(k);
        new AlertDialog.Builder(this).setTitle("Relay admin key").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String[] p = RelayAdmin.parse(k.getText().toString());
                        if (p == null) {
                            Toast.makeText(RelayAdminActivity.this, "That admin key is too short", Toast.LENGTH_LONG).show();
                            return;
                        }
                        String ad = p[0].length() > 0 ? p[0] : a.getText().toString().trim();
                        if (ad.lastIndexOf(':') <= 0) {
                            Toast.makeText(RelayAdminActivity.this, "Give the relay address as host:port", Toast.LENGTH_LONG).show();
                            return;
                        }
                        save(ad, p[1]);
                        refresh();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void more() {
        final String[] items = {"Show admin QR (for a second admin phone)", "Change the admin key", "Forget the admin key on this phone"};
        new AlertDialog.Builder(this).setTitle("Relay admin").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                if (!configured()) {
                    Toast.makeText(RelayAdminActivity.this, "No admin key on this phone yet", Toast.LENGTH_LONG).show();
                    return;
                }
                if (w == 0) showAdminQr();
                else if (w == 1) rotate();
                else forget();
            }
        }).show();
    }

    private void showAdminQr() {
        new AlertDialog.Builder(this).setTitle("Show the admin QR?")
                .setMessage("Whoever scans it can add and remove the relay's users. Only show it to another admin.")
                .setPositiveButton("Show", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        qr(RelayAdmin.QR_PREFIX + addr() + "|" + key(), "Relay admin " + addr());
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void forget() {
        new AlertDialog.Builder(this).setTitle("Forget the admin key?")
                .setMessage("This phone can no longer manage the relay until the admin QR is scanned again. The relay itself is not changed.")
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        sp.edit().clear().commit();
                        list.removeAllViews();
                        showRelay();
                        status.setText("Admin key forgotten");
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void rotate() {
        new AlertDialog.Builder(this).setTitle("Change the admin key?")
                .setMessage("The relay makes a new admin key and this phone keeps it. Old admin QR codes and other admin phones "
                        + "stop working (show them the new admin QR). Sites and remotes are not affected.")
                .setPositiveButton("Change", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        call("rotate", new Done() {
                            @Override
                            public void ok(String[] r) {
                                if (r.length < 2) return;
                                save(addr(), r[1]);
                                // The first command with the new key makes the relay drop the old one.
                                call("ping", new Done() {
                                    @Override
                                    public void ok(String[] r2) {
                                        status.setText("Admin key changed. Old admin codes no longer work.");
                                        showAdminQr();
                                    }

                                    @Override
                                    public void failed(String why) {
                                        // The relay keeps the new key waiting until it is first used, so keeping it here is safe.
                                        status.setText("The new admin key is kept; the relay switches to it on the next command that gets through. (" + why + ")");
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- users ----

    private void refresh() {
        if (!configured()) return;
        status.setText("Asking the relay…");
        call("list", new Done() {
            @Override
            public void ok(String[] r) {
                ArrayList<User> us = new ArrayList<User>();
                for (int i = 1; i < r.length; i++) {
                    String[] f = r[i].split("\\|", -1);
                    if (f.length < 5) continue;
                    User u = new User();
                    u.id = f[0];
                    u.kind = f[1];
                    u.name = f[2];
                    u.online = "1".equals(f[3]);
                    try {
                        u.agoSec = Long.parseLong(f[4]);
                    } catch (NumberFormatException e) {
                        u.agoSec = -1;
                    }
                    us.add(u);
                }
                show(us);
            }
        });
    }

    private void show(ArrayList<User> us) {
        list.removeAllViews();
        int on = 0;
        for (final User u : us) {
            if (u.online) on++;
            TextView t = new TextView(this);
            t.setTextSize(17);
            t.setPadding((int) (4 * d), (int) (10 * d), (int) (4 * d), (int) (10 * d));
            String seen = u.online ? "online" : u.agoSec < 0 ? "never connected" : "offline, last seen " + ago(u.agoSec) + " ago";
            t.setText((u.online ? "● " : "○ ") + u.name + "   (" + u.kind + ")\n    " + seen);
            t.setTextColor(u.online ? 0xFF6FCF7F : 0xFFBBBBBB);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    userMenu(u);
                }
            });
            list.addView(t);
        }
        status.setText(us.size() + " user(s), " + on + " online. Tap one for its setup QR, rename or remove.");
    }

    static String ago(long s) {
        if (s < 120) return s + " s";
        if (s < 7200) return (s / 60) + " min";
        if (s < 172800) return (s / 3600) + " h";
        return (s / 86400) + " days";
    }

    private void userMenu(final User u) {
        String[] items = {"Show setup QR", "Rename", "Remove"};
        new AlertDialog.Builder(this).setTitle(u.name + " (" + u.kind + ")").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int w) {
                if (w == 0) setupQr(u.id, u.name, u.kind);
                else if (w == 1) rename(u);
                else remove(u);
            }
        }).show();
    }

    private void setupQr(String id, final String name, final String kind) {
        call("key\n" + id, new Done() {
            @Override
            public void ok(String[] r) {
                if (r.length < 2) return;
                // A site's key goes on the MANET's gateway phones; a remote's on one phone away from it.
                String code = SetupQr.makeRelayUser(name, addr(), r[1], "site".equals(kind) ? "on" : "off");
                qr(code, "Setup for " + name + ("site".equals(kind) ? " (site gateway)" : " (remote)"));
            }
        });
    }

    private void qr(String payload, String title) {
        ArrayList<String> p = new ArrayList<String>();
        ArrayList<String> t = new ArrayList<String>();
        p.add(payload);
        t.add(title);
        QrShowActivity.start(this, p, t, null);
    }

    private void add(final String kind) {
        if (!configured()) {
            Toast.makeText(this, "Scan the relay's admin QR first", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("site".equals(kind) ? "Site name, e.g. HQ" : "Name, e.g. ALPHA1");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("site".equals(kind) ? "Add a site (a MANET's gateway)" : "Add a remote (one phone off the MANET)")
                .setView(name)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        final String n = name.getText().toString().trim().replace("|", " ");
                        if (n.length() == 0) return;
                        call("add\n" + kind + "\n" + n, new Done() {
                            @Override
                            public void ok(String[] r) {
                                refresh();
                                if (r.length < 3) return;
                                String code = SetupQr.makeRelayUser(n, addr(), r[2], "site".equals(kind) ? "on" : "off");
                                qr(code, "Setup for " + n + ("site".equals(kind) ? " (site gateway)" : " (remote)"));
                            }
                        });
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void rename(final User u) {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(u.name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("Rename " + u.name).setView(name)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String n = name.getText().toString().trim().replace("|", " ");
                        if (n.length() == 0) return;
                        call("rename\n" + u.id + "\n" + n, new Done() {
                            @Override
                            public void ok(String[] r) {
                                refresh();
                            }
                        });
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void remove(final User u) {
        new AlertDialog.Builder(this).setTitle("Remove " + u.name + "?")
                .setMessage("Its key stops working at once and it is cut off within seconds. To let it back, add it again "
                        + "and scan the new setup QR on its phone.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        call("remove\n" + u.id, new Done() {
                            @Override
                            public void ok(String[] r) {
                                Toast.makeText(RelayAdminActivity.this, u.name + " removed", Toast.LENGTH_LONG).show();
                                refresh();
                            }
                        });
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- talking to the relay (off the main thread) ----

    abstract static class Done {
        abstract void ok(String[] lines);

        void failed(String why) {
        }
    }

    private void call(final String command, final Done done) {
        final String a = addr(), k = key();
        status.setText("Asking the relay…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String ans;
                try {
                    ans = new RelayAdmin(k).call(a, command);
                } catch (Exception e) {
                    ans = "fail\n" + e.getMessage();
                }
                final String[] lines = ans.split("\n", -1);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if ("ok".equals(lines[0])) {
                            status.setText("");
                            done.ok(lines);
                            return;
                        }
                        String why = lines.length > 1 ? lines[1] : lines[0];
                        status.setText(("fail".equals(lines[0]) ? "No answer: " : "The relay said: ") + why);
                        done.failed(why);
                    }
                });
            }
        }, "relay-admin").start();
    }
}
