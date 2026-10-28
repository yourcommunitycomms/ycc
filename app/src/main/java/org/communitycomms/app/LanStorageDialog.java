package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.communitycomms.app.geo.LanTiles;

/**
 * Settings > LAN storage location (also from the full map's Sync tiles when none is set): the
 * address, user and password of a shared map tile store on the LAN. The password is never shown
 * again; leaving it empty keeps the saved one. Saving asks whether to share the details with the
 * MANET (they then go out with Share config).
 */
public final class LanStorageDialog {
    private LanStorageDialog() {
    }

    public static void show(final Activity a, final Runnable saved) {
        final LanTiles lan = new LanTiles(a);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, p / 2, p, 0);
        TextView intro = new TextView(a);
        intro.setText("A shared store for map tiles on the LAN (a NAS, an OpenMANET node or a PC) so phones get maps "
                + "from it with no internet, and tiles fetched online are copied to it (folder \"" + LanTiles.FOLDER + "\").\n"
                + "It needs WebDAV (web folder) access, e.g. http://10.41.0.1/webdav/ - for a Windows or Samba share, "
                + "turn on its WebDAV / web access, or run a small WebDAV server pointed at the share.");
        intro.setTextSize(13);
        box.addView(intro);
        final EditText url = field(a, box, "Address", lan.base().length() > 0 ? lan.base() : "http://", InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = field(a, box, "User (optional)", lan.user(), InputType.TYPE_CLASS_TEXT);
        final EditText pass = field(a, box, "Password", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint(lan.hasPassword() ? "(saved: leave empty to keep it)" : "(optional)");
        final TextView result = new TextView(a);
        Button test = new Button(a);
        test.setText("Test");
        test.setAllCaps(false);
        box.addView(test);
        box.addView(result);
        if (lan.configured() && lan.shareWithManet()) {
            Button again = new Button(a);
            again.setText("Send it to the group again");
            again.setAllCaps(false);
            again.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigShare.sendLan(a);
                }
            });
            box.addView(again);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("LAN storage location (maps cache)")
                .setView(sv)
                .setPositiveButton("Save", null)
                .setNeutralButton("Clear", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Test with what is typed, without saving yet.
                final LanTiles probe = new LanTiles(a, url.getText().toString(), user.getText().toString(),
                        pass.getText().length() > 0 ? pass.getText().toString() : null);
                result.setText("Testing…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = probe.test();
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                result.setText(r);
                            }
                        });
                    }
                }).start();
            }
        });
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final String u = url.getText().toString().trim();
                        if (!u.startsWith("http://") && !u.startsWith("https://")) {
                            url.setError("Starts with http:// or https://");
                            return;
                        }
                        dlg.dismiss();
                        final String pw = pass.getText().length() > 0 ? pass.getText().toString() : null;
                        new AlertDialog.Builder(a)
                                .setTitle("Share with the MANET?")
                                .setMessage("Share this LAN storage location (address, user and password) with the MANET? "
                                        + "It is sent to everyone on the channel now (and with Share config), and other phones are asked to "
                                        + "\"Update LAN storage location\".")
                                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w) {
                                        lan.save(u, user.getText().toString(), pw, true);
                                        ConfigShare.sendLan(a); // to everyone on the channel now, and with Share config later
                                        if (saved != null) saved.run();
                                    }
                                })
                                .setNegativeButton("This phone only", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w) {
                                        lan.save(u, user.getText().toString(), pw, false);
                                        if (saved != null) saved.run();
                                    }
                                })
                                .setCancelable(false)
                                .show();
                    }
                });
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lan.save("", "", "", false);
                        dlg.dismiss();
                        if (saved != null) saved.run();
                    }
                });
            }
        });
        dlg.show();
    }

    /** Settings > LAN sync and updates: what to keep in step through the LAN storage, and how often. */
    public static void showSync(final Activity a) {
        final android.content.SharedPreferences sp = a.getSharedPreferences("settings", Activity.MODE_PRIVATE);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        box.setPadding(p, p / 2, p, 0);
        LanTiles lan = new LanTiles(a);
        TextView where = new TextView(a);
        where.setText(lan.configured() ? "Using " + lan.base() : "Set the LAN storage location first.");
        box.addView(where);
        final String[] keys = {"lanAutoUpdate", "lanSyncLogs", "lanSyncData", "lanSyncDryad", "lanSyncConfig"};
        String[] labels = {"Enable auto updates (new versions from the LAN's Updates folder; Android asks before installing)",
                "Logs: send this app's log for debugging",
                "Data files: send received files, voice recordings and photos",
                "DRYAD sheets: share and merge every phone's sheets (anyone with the LAN login gets them)",
                "Config backup: keep a copy of this phone's settings on the LAN (not the PIN)"};
        final android.widget.CheckBox[] boxes = new android.widget.CheckBox[keys.length];
        for (int i = 0; i < keys.length; i++) {
            boxes[i] = new android.widget.CheckBox(a);
            boxes[i].setText(labels[i]);
            boxes[i].setChecked(sp.getBoolean(keys[i], false));
            box.addView(boxes[i]);
        }
        final android.widget.CheckBox home = new android.widget.CheckBox(a);
        home.setText("Enable home network *");
        home.setChecked(LanSync.homeEnabled(a));
        box.addView(home);
        final EditText ssid = new EditText(a);
        ssid.setSingleLine(true);
        ssid.setHint("Home WiFi name (SSID)");
        ssid.setText(LanSync.homeSsid(a));
        box.addView(ssid);
        Button thisWifi = new Button(a);
        String cur = LanSync.currentSsid(a);
        thisWifi.setText(cur.length() > 0 ? "Use this WiFi: " + cur : "(WiFi name not readable: type it)");
        thisWifi.setAllCaps(false);
        thisWifi.setEnabled(cur.length() > 0);
        final String curF = cur;
        thisWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ssid.setText(curF);
            }
        });
        box.addView(thisWifi);
        TextView star = new TextView(a);
        star.setTextSize(12);
        star.setText("* Allowing home network will sync everything but logs to the server (data files, DRYAD sheets, "
                + "config backup and update checks), at least hourly, whenever this phone is on that WiFi.");
        box.addView(star);
        TextView el = new TextView(a);
        el.setText("Check and sync:");
        box.addView(el);
        final android.widget.Spinner every = new android.widget.Spinner(a);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(a,
                android.R.layout.simple_spinner_item, LanSync.EVERY_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        every.setAdapter(ad);
        every.setSelection(LanSync.every(a));
        box.addView(every);
        final TextView result = new TextView(a);
        String last = LanSync.lastResult(a);
        result.setText("Installed version " + LanSync.installedVersion(a) + (last.length() > 0 ? "\nLast sync " + last : ""));
        box.addView(result);
        Button now = new Button(a);
        now.setText("Sync now");
        now.setAllCaps(false);
        box.addView(now);
        final Button install = new Button(a);
        install.setText("Install the downloaded update");
        install.setAllCaps(false);
        install.setVisibility(LanSync.downloaded(a) != null ? View.VISIBLE : View.GONE);
        box.addView(install);
        final Runnable save = new Runnable() {
            @Override
            public void run() {
                android.content.SharedPreferences.Editor e = sp.edit();
                for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], boxes[i].isChecked());
                e.putInt("lanSyncEvery", every.getSelectedItemPosition())
                        .putBoolean("homeNetOn", home.isChecked()).putString("homeNetSsid", ssid.getText().toString().trim()).commit();
            }
        };
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save.run();
                result.setText("Syncing…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = LanSync.runNow(a);
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                result.setText(r);
                                install.setVisibility(LanSync.downloaded(a) != null ? View.VISIBLE : View.GONE);
                            }
                        });
                    }
                }, "lan-sync").start();
            }
        });
        install.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent i = LanSync.installIntent(a);
                if (i == null) return;
                try {
                    a.startActivity(i);
                } catch (Exception e) {
                    result.setText("Could not open the installer: " + e.getMessage()
                            + "\nAllow \"Install unknown apps\" for this app in Android settings.");
                }
            }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("LAN sync and updates")
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        save.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static EditText field(Activity a, LinearLayout box, String label, String value, int type) {
        TextView t = new TextView(a);
        t.setText(label);
        box.addView(t);
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        box.addView(e);
        return e;
    }
}
