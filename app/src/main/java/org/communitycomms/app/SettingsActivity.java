package org.communitycomms.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Everything that is set up once rather than used in the field: callsign, channel names,
 * network, PTT behaviour, VOX, alerts, catch-up sync and security. Kept off the main screen.
 */
public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText callsign, channelA, channelB, openManet, port, peers, every, window, publicKey;
    private RadioGroup netMode;
    private CheckBox showNames, latch, vox, announce, catchUp, wipe;
    private TextView keyLabel;
    private boolean learningKey;
    private int taps;
    private long lastTapMs;
    private final Handler handler = new Handler();
    private final Runnable voxTick = new Runnable() {
        @Override
        public void run() {
            renderVox();
            showBtButton(); // live button status and last press, for testing the button
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.settings);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        showSection(getIntent().getStringExtra(EXTRA_SECTION));

        callsign = (EditText) findViewById(R.id.callsign);
        channelA = (EditText) findViewById(R.id.channel_a);
        channelB = (EditText) findViewById(R.id.channel_b);
        openManet = (EditText) findViewById(R.id.openmanet_group);
        port = (EditText) findViewById(R.id.port);
        peers = (EditText) findViewById(R.id.peers);
        every = (EditText) findViewById(R.id.catch_up_every);
        window = (EditText) findViewById(R.id.catch_up_window);
        netMode = (RadioGroup) findViewById(R.id.net_mode);
        showNames = (CheckBox) findViewById(R.id.show_channel);
        latch = (CheckBox) findViewById(R.id.latch);
        vox = (CheckBox) findViewById(R.id.vox);
        announce = (CheckBox) findViewById(R.id.announce_poi);
        catchUp = (CheckBox) findViewById(R.id.catch_up);
        wipe = (CheckBox) findViewById(R.id.wipe_on_bad_pin);
        keyLabel = (TextView) findViewById(R.id.key_label);

        callsign.setText(prefs.callsign());
        channelA.setText(prefs.channel(Prefs.SLOT_A));
        channelB.setText(prefs.channel(Prefs.SLOT_B));
        openManet.setText(prefs.channel(Prefs.SLOT_OPENMANET));
        port.setText(String.valueOf(prefs.port()));
        peers.setText(prefs.extraPeers());
        netMode.check(prefs.netMode() == Prefs.NET_MULTICAST ? R.id.net_multicast : R.id.net_broadcast);
        latch.setChecked(prefs.latch());
        announce.setChecked(prefs.announcePoi());
        catchUp.setChecked(prefs.catchUp());
        every.setText(String.valueOf(prefs.catchUpEveryMin()));
        window.setText(String.valueOf(prefs.catchUpWindowMin()));
        wipe.setChecked(prefs.wipeOnBadPin() && PinLock.isSet(this));
        // Intercoms are in public places: wrong PINs never wipe them, so the option is hidden.
        if (prefs.appMode() == Prefs.MODE_INTERCOM) wipe.setVisibility(View.GONE);
        publicKey = (EditText) findViewById(R.id.public_key);
        publicKey.setText(prefs.publicKey());
        final CheckBox showPub = (CheckBox) findViewById(R.id.show_public), showOm = (CheckBox) findViewById(R.id.show_openmanet);
        showPub.setChecked(prefs.showPublic());
        showOm.setChecked(prefs.showOpenManet());
        CompoundButton.OnCheckedChangeListener slots = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setShowSlots(showPub.isChecked(), showOm.isChecked());
            }
        };
        showPub.setOnCheckedChangeListener(slots);
        showOm.setOnCheckedChangeListener(slots);
        findViewById(R.id.share_config).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                ConfigShare.offerSend(SettingsActivity.this);
            }
        });
        styleNames(false);
        renderKeyLabel();
        renderIface();

        showNames.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton bt, boolean show) {
                if (!show) {
                    styleNames(false);
                    return;
                }
                PinLock.require(SettingsActivity.this, "Enter your PIN to show the channel names.", new PinLock.Done() {
                    @Override
                    public void onUnlocked() {
                        styleNames(true);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        bt.setChecked(false);
                    }
                });
            }
        });

        latch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setLatch(on);
            }
        });
        announce.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setAnnouncePoi(on);
            }
        });
        RadioGroup home = (RadioGroup) findViewById(R.id.home_style);
        home.check(prefs.homeMap() ? R.id.home_map : R.id.home_radio);
        home.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                prefs.setHomeMap(id == R.id.home_map);
            }
        });
        final CheckBox dw = (CheckBox) findViewById(R.id.dual_watch), dwPlay = (CheckBox) findViewById(R.id.dual_watch_play),
                dwSave = (CheckBox) findViewById(R.id.dual_watch_save);
        dw.setChecked(prefs.dualWatch());
        dwPlay.setChecked(prefs.dualWatchPlay());
        dwSave.setChecked(prefs.dualWatchSave());
        CompoundButton.OnCheckedChangeListener dwChange = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setDualWatch(dw.isChecked(), dwPlay.isChecked(), dwSave.isChecked());
            }
        };
        dw.setOnCheckedChangeListener(dwChange);
        dwPlay.setOnCheckedChangeListener(dwChange);
        dwSave.setOnCheckedChangeListener(dwChange);
        findViewById(R.id.about).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        findViewById(R.id.permissions).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, PermissionsActivity.class));
            }
        });
        bindCheck(R.id.rejoin_on_start, prefs.rejoinOnStart(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setRejoinOnStart(on);
            }
        });
        bindCheck(R.id.auto_stealth, prefs.autoStealth(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setAutoStealth(on);
            }
        });
        bindCheck(R.id.stealth_vibrate, prefs.stealthVibrate(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setStealthVibrate(on);
            }
        });
        bindCheck(R.id.call_reminder, prefs.callReminder(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setCallReminder(on);
            }
        });
        bindCheck(R.id.call_wakes_volume, prefs.callWakesVolume(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setCallWakesVolume(on);
            }
        });
        bindCheck(R.id.gunshot_alert, prefs.gunshotAlert(), new Toggle() {
            @Override
            public void set(boolean on) {
                prefs.setGunshotAlert(on);
                RadioService rs = RadioService.instance();
                if (rs != null) rs.applyGunshot();
            }
        });
        findViewById(R.id.quick_profile).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                MainActivity.chooseProfile(SettingsActivity.this);
            }
        });
        findViewById(R.id.quick_custom).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, QuickProfileActivity.class));
            }
        });
        // Channel QR codes (moved here from the main menu). Save first so the QR matches what is typed.
        findViewById(R.id.qr_a).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                save();
                MainActivity.showChannelQr(SettingsActivity.this, Prefs.SLOT_A);
            }
        });
        findViewById(R.id.qr_b).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                save();
                MainActivity.showChannelQr(SettingsActivity.this, Prefs.SLOT_B);
            }
        });
        findViewById(R.id.qr_scan).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                save();
                startActivity(new android.content.Intent(SettingsActivity.this, QrScanActivity.class));
            }
        });
        findViewById(R.id.update_from_url).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                UpdateFromUrl.run(SettingsActivity.this);
            }
        });
        findViewById(R.id.edit_update_url).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                UpdateFromUrl.edit(SettingsActivity.this, false);
            }
        });
        findViewById(R.id.rerun_wizard).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.rerun_wizard)
                        .setMessage("Goes through the first-start questions again: callsign, quick set from a QR code, "
                                + "mode, keys, permissions, black screen and Record all voice. Settings you do not change "
                                + "there stay as they are.")
                        .setPositiveButton("Run it", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                save();
                                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("setupDone", false)
                                        .putBoolean("rerunWizard", true).putBoolean("permissionsShown", false)
                                        .putBoolean("blackPromptShown", false).putBoolean("recordPromptShown", false).commit();
                                startActivity(new android.content.Intent(SettingsActivity.this, MainActivity.class)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK | android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); // a fresh home screen starts the wizard
                                finish();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        findViewById(R.id.qr_setup).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                save();
                SetupQr.show(SettingsActivity.this);
            }
        });
        CheckBox callOn = (CheckBox) findViewById(R.id.call_enabled);
        callOn.setChecked(prefs.callEnabled());
        callOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setCallEnabled(on);
            }
        });
        CheckBox silent = (CheckBox) findViewById(R.id.radio_silent_setting);
        silent.setChecked(prefs.radioSilent());
        silent.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setRadioSilent(on);
            }
        });
        // Mission and data > Automatically accept location sharing (off by default).
        CheckBox autoLoc = (CheckBox) findViewById(R.id.auto_accept_location);
        autoLoc.setChecked(RadioService.autoAcceptLocation(this));
        autoLoc.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("autoAcceptLocation", on).commit();
            }
        });
        // Units: metric (default) or imperial, used for every distance shown or read aloud.
        android.widget.RadioGroup units = (android.widget.RadioGroup) findViewById(R.id.units);
        units.check(org.communitycomms.app.geo.Geo.imperial ? R.id.units_imperial : R.id.units_metric);
        units.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                org.communitycomms.app.geo.Geo.setImperial(SettingsActivity.this, id == R.id.units_imperial);
                findViewById(R.id.obscure_range).post(new Runnable() {
                    @Override
                    public void run() {
                        SeekBar o = (SeekBar) findViewById(R.id.obscure_range);
                        o.setProgress(o.getProgress() == 0 ? 1 : o.getProgress() - 1); // relabel the slider
                        o.setProgress(org.communitycomms.app.geo.FakeGps.obscureM(SettingsActivity.this) / 100);
                    }
                });
            }
        });
        // Obscure location: 0 to 10 km in 0.1 km steps (slider 0..100).
        final SeekBar obscure = (SeekBar) findViewById(R.id.obscure_range);
        final TextView obscureLabel = (TextView) findViewById(R.id.obscure_label);
        obscure.setProgress(org.communitycomms.app.geo.FakeGps.obscureM(this) / 100);
        final Runnable showObscure = new Runnable() {
            @Override
            public void run() {
                int m = obscure.getProgress() * 100;
                obscureLabel.setText(m == 0 ? "Obscure location: off (exact position)"
                        : "Obscure location: up to " + org.communitycomms.app.geo.Geo.range(m));
            }
        };
        showObscure.run();
        obscure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                showObscure.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                org.communitycomms.app.geo.FakeGps.setObscureM(SettingsActivity.this, s.getProgress() * 100);
            }
        });
        final CheckBox fake = (CheckBox) findViewById(R.id.fake_gps);
        final EditText fakeAt = (EditText) findViewById(R.id.fake_gps_centre);
        fake.setChecked(org.communitycomms.app.geo.FakeGps.enabled(this));
        fakeAt.setText(org.communitycomms.app.geo.FakeGps.centre(this));
        final Runnable saveFake = new Runnable() {
            @Override
            public void run() {
                if (!org.communitycomms.app.geo.FakeGps.set(SettingsActivity.this, fake.isChecked(), fakeAt.getText().toString())) {
                    fakeAt.setError("Type lat, lon (e.g. -33.85680, 151.21530), or use + Fake being here on the full map");
                    fake.setChecked(false);
                } else {
                    fakeAt.setError(null);
                }
            }
        };
        fake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                saveFake.run();
                if (!on) {
                    org.communitycomms.app.geo.ObscureAsk.afterFakeOff(SettingsActivity.this, new Runnable() {
                        @Override
                        public void run() {
                            SeekBar o = (SeekBar) findViewById(R.id.obscure_range);
                            o.setProgress(org.communitycomms.app.geo.FakeGps.obscureM(SettingsActivity.this) / 100);
                        }
                    });
                }
            }
        });
        fakeAt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int x) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int x) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                saveFake.run();
            }
        });
        CheckBox silentL = (CheckBox) findViewById(R.id.silent_listeners);
        silentL.setChecked(prefs.silentListeners());
        silentL.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setSilentListeners(on);
            }
        });
        final Button lanBtn = (Button) findViewById(R.id.lan_storage);
        final Runnable showLan = new Runnable() {
            @Override
            public void run() {
                org.communitycomms.app.geo.LanTiles l = new org.communitycomms.app.geo.LanTiles(SettingsActivity.this);
                lanBtn.setText(l.configured() ? "LAN storage location (maps cache): " + l.base()
                        + (l.shareWithManet() ? " · shared with the MANET" : " · this phone only")
                        : "LAN storage location (maps cache): not set");
            }
        };
        showLan.run();
        lanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LanStorageDialog.show(SettingsActivity.this, showLan);
            }
        });
        // Mission mode (Advanced): LAN sync archives everything under Missions/(name)/.
        final Button missionBtn = (Button) findViewById(R.id.mission);
        missionBtn.setVisibility(prefs.appMode() == Prefs.MODE_ADVANCED ? View.VISIBLE : View.GONE);
        final Runnable showMission = new Runnable() {
            @Override
            public void run() {
                String m = LanSync.mission(SettingsActivity.this);
                missionBtn.setText(m.length() == 0 ? "Mission: none (tap to choose or start one)" : "Mission: " + m
                        + " (LAN sync files go under it)");
            }
        };
        showMission.run();
        missionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final java.util.List<String> list = LanSync.missions(SettingsActivity.this);
                final String[] items = new String[list.size() + 2];
                items[0] = "No mission";
                for (int i = 0; i < list.size(); i++) items[i + 1] = list.get(i);
                items[items.length - 1] = "+ New mission…";
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Mission")
                        .setItems(items, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    LanSync.setMission(SettingsActivity.this, "");
                                    showMission.run();
                                } else if (w < items.length - 1) {
                                    LanSync.setMission(SettingsActivity.this, list.get(w - 1));
                                    showMission.run();
                                } else {
                                    final EditText name = new EditText(SettingsActivity.this);
                                    name.setSingleLine(true);
                                    name.setHint("e.g. 2026-11 Bushfire Cardinia");
                                    new android.app.AlertDialog.Builder(SettingsActivity.this)
                                            .setTitle("New mission")
                                            .setView(name)
                                            .setPositiveButton("Start", new android.content.DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(android.content.DialogInterface d2, int w2) {
                                                    LanSync.setMission(SettingsActivity.this, name.getText().toString());
                                                    showMission.run();
                                                }
                                            })
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .show();
                                }
                            }
                        })
                        .show();
            }
        });
        final CheckBox atak = (CheckBox) findViewById(R.id.atak_on);
        final android.content.SharedPreferences sset = getSharedPreferences("settings", MODE_PRIVATE);
        atak.setChecked(sset.getBoolean("atakOn", false));
        atak.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton bt, boolean on) {
                if (!on) {
                    sset.edit().putBoolean("atakOn", false).commit();
                    return;
                }
                if (sset.getBoolean("atakOn", false)) return;
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("ATAK compatible: not encrypted")
                        .setMessage("This phone will talk the way ATAK does on a mesh, in plain text that anyone on the "
                                + "network can read:\n\n"
                                + "- your callsign and position go out every 30 seconds (239.2.3.1:6969)\n"
                                + "- what you write on the Public channel goes to ATAK's All Chat Rooms (224.10.10.1:17012)\n\n"
                                + "ATAK users then show on your map and their chat in Chat. Your private channels, voice and "
                                + "DRYAD stay encrypted and never go to ATAK.")
                        .setPositiveButton("Turn on", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                sset.edit().putBoolean("atakOn", true).commit();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                bt.setChecked(false);
                            }
                        })
                        .setCancelable(false)
                        .show();
            }
        });
        findViewById(R.id.lan_sync).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LanStorageDialog.showSync(SettingsActivity.this);
            }
        });
        findViewById(R.id.cfg_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(); // what is on screen now goes into the backup too
                new android.app.AlertDialog.Builder(SettingsActivity.this).setTitle("Save my config")
                        .setMessage(ConfigShare.saveMine(SettingsActivity.this)
                                + "\n\nEverything except your PIN. Read my config puts it back.")
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        final Runnable reload = new Runnable() {
            @Override
            public void run() {
                wiped = true; // the screen's old values must not be saved over the loaded ones
                recreate();
            }
        };
        findViewById(R.id.cfg_read).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigShare.readMine(SettingsActivity.this, reload);
            }
        });
        findViewById(R.id.cfg_defaults).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigShare.loadDefaults(SettingsActivity.this, reload);
            }
        });
        findViewById(R.id.tv_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, TvActivity.class));
            }
        });
        findViewById(R.id.dryad_explained).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this, DryadExplainedActivity.class));
            }
        });
        RadioGroup dwMode = (RadioGroup) findViewById(R.id.dual_watch_mode);
        dwMode.check(prefs.dualWatchStereo() ? R.id.dual_watch_stereo : R.id.dual_watch_quiet);
        dwMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                prefs.setDualWatchStereo(id == R.id.dual_watch_stereo);
            }
        });
        CheckBox autoAck = (CheckBox) findViewById(R.id.auto_ack);
        autoAck.setChecked(prefs.autoAck());
        autoAck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setAutoAck(on);
            }
        });
        CheckBox locTx = (CheckBox) findViewById(R.id.loc_on_tx);
        locTx.setChecked(prefs.locationOnTransmit());
        locTx.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setLocationOnTransmit(on);
                if (on && android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION}, 40);
                }
            }
        });
        wipe.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton bt, boolean on) {
                if (!on) {
                    prefs.setWipeOnBadPin(false);
                    return;
                }
                if (!PinLock.isSet(SettingsActivity.this)) {
                    bt.setChecked(false);
                    Toast.makeText(SettingsActivity.this, "Set a PIN first", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.setWipeOnBadPin(true);
                Toast.makeText(SettingsActivity.this, "On: 2 wrong PIN entries in a row erase all app data",
                        Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.learn_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                learningKey = true;
                keyLabel.setText(R.string.press_key);
            }
        });
        CheckBox btHs = (CheckBox) findViewById(R.id.bt_headset);
        btHs.setChecked(prefs.btHeadset());
        btHs.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setBtHeadset(on);
                if (on) askBtPermission();
                RadioService rs = RadioService.instance();
                if (rs != null) rs.restartBt();
            }
        });
        findViewById(R.id.bt_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseBtButton();
            }
        });
        findViewById(R.id.iface_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickInterface();
            }
        });
        findViewById(R.id.pin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PinLock.manage(SettingsActivity.this);
            }
        });

        final Button myIcon = (Button) findViewById(R.id.my_icon);
        final Runnable showIcon = new Runnable() {
            @Override
            public void run() {
                int ic = prefs.myIcon();
                myIcon.setText("My map icon (how others see me): " + (ic < 0 ? "a dot" : org.communitycomms.app.geo.Poi.ICON_NAMES[ic]));
            }
        };
        showIcon.run();
        myIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("My map icon")
                        .setMessage("Others see your position as this icon, e.g. an ambulance or a fire truck.")
                        .setPositiveButton("Choose…", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int w) {
                                IconPicker.pick(SettingsActivity.this, "My map icon", new IconPicker.Picked() {
                                    @Override
                                    public void onPicked(int icon) {
                                        prefs.setMyIcon(icon);
                                        showIcon.run();
                                    }
                                });
                            }
                        })
                        .setNeutralButton("A plain dot", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int w) {
                                prefs.setMyIcon(-1);
                                showIcon.run();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        findViewById(R.id.listen_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(); // the Public key as typed
                ListenKeyShare.show(SettingsActivity.this);
            }
        });
        final Button rot = (Button) findViewById(R.id.key_rotation);
        final String[] rotNames = {"Off", "Hourly", "Daily"};
        rot.setText("Automatic key rotation: " + rotNames[prefs.keyRotation()]);
        rot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Automatic key rotation")
                        .setSingleChoiceItems(new String[]{"Off", "Hourly (key changes on the hour, UTC)",
                                "Daily (key changes at midnight UTC)"}, prefs.keyRotation(), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int which) {
                                prefs.setKeyRotation(which);
                                rot.setText("Automatic key rotation: " + rotNames[which]);
                                di.dismiss();
                                if (which != 0) {
                                    Toast.makeText(SettingsActivity.this, "Every phone needs the same setting (Share settings passes it on) "
                                            + "and roughly the right time. The Public channel never rotates.", Toast.LENGTH_LONG).show();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        final Button mode = (Button) findViewById(R.id.app_mode);
        mode.setText("Mode: " + Prefs.MODE_NAMES[prefs.appMode()] + " (tap to change)");
        mode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("How will you use it?")
                        .setSingleChoiceItems(Prefs.MODE_NAMES, prefs.appMode(), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int which) {
                                MainActivity.applyMode(SettingsActivity.this, which);
                                mode.setText("Mode: " + Prefs.MODE_NAMES[which] + " (tap to change)");
                                di.dismiss();
                                Toast.makeText(SettingsActivity.this, which >= Prefs.MODE_SCOUTS
                                        ? "Simple mode: channel A is your private key. DRYAD and Pigpen are hidden."
                                        : which == Prefs.MODE_INTERCOM ? "Intercom: the intercom screen opens from the home screen."
                                        : "Advanced: everything is shown.", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        CheckBox tone = (CheckBox) findViewById(R.id.start_tone);
        tone.setChecked(prefs.startTone());
        tone.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setStartTone(on);
            }
        });
        CheckBox streams = (CheckBox) findViewById(R.id.streams_enabled);
        streams.setChecked(prefs.streamsEnabled());
        streams.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setStreamsEnabled(on);
                if (!on) StreamsActivity.stopPlayer();
            }
        });
        findViewById(R.id.clear_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PinLock.require(SettingsActivity.this, "Enter your PIN to erase all app data.", new PinLock.Done() {
                    @Override
                    public void onUnlocked() {
                        new android.app.AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("Clear all data and exit?")
                                .setMessage("Everything this app keeps on this phone is erased: channel names and keys, DRYAD "
                                        + "sheets, settings, PIN, chat and reports, map points, files and offline maps. "
                                        + "The app then closes, and starts the setup wizard next time.\n\nThis cannot be undone.")
                                .setPositiveButton("Erase and exit", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface di, int w) {
                                        StreamsActivity.stopPlayer();
                                        AppWipe.wipeAndExit(SettingsActivity.this);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                }, null);
            }
        });
        CheckBox drop = (CheckBox) findViewById(R.id.drop_alert);
        drop.setChecked(prefs.dropAlert());
        drop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setDropAlert(on);
            }
        });
        CheckBox crash = (CheckBox) findViewById(R.id.crash_detect);
        crash.setChecked(prefs.crashDetect());
        crash.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setCrashDetect(on);
                RadioService s = RadioService.instance();
                if (s != null) s.applyCrashDetect();
            }
        });

        CheckBox offerMissed = (CheckBox) findViewById(R.id.offer_missed);
        offerMissed.setChecked(prefs.offerMissed());
        offerMissed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setOfferMissed(on);
            }
        });
        CheckBox recAll = (CheckBox) findViewById(R.id.record_all);
        recAll.setChecked(prefs.recordAllVoice());
        recAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setRecordAllVoice(on);
                RadioService s = RadioService.instance();
                if (s != null) s.applyRecording();
            }
        });
        CheckBox black = (CheckBox) findViewById(R.id.black_screen_button);
        black.setChecked(prefs.blackScreenButton());
        black.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setBlackScreenButton(on);
            }
        });
        CheckBox blackLegacy = (CheckBox) findViewById(R.id.black_legacy);
        blackLegacy.setChecked(prefs.blackoutLegacy());
        blackLegacy.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setBlackoutLegacy(on);
            }
        });
        setUpPower();
        final EditText blackAfter = (EditText) findViewById(R.id.black_after);
        blackAfter.setText(String.valueOf(prefs.blackAfterSec()));
        renderBlackAfter();
        blackAfter.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                int v;
                try {
                    v = Integer.parseInt(s.toString().trim());
                } catch (NumberFormatException e) {
                    v = 0;
                }
                prefs.setBlackAfterSec(v);
                renderBlackAfter();
            }
        });
        findViewById(R.id.clear_mission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PinLock.require(SettingsActivity.this, "Enter your PIN to clear mission data.", new PinLock.Done() {
                    @Override
                    public void onUnlocked() {
                        clearMission();
                    }
                }, null);
            }
        });
        findViewById(R.id.tunnel_client).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TunnelSetup.client(SettingsActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        renderTunnel();
                    }
                });
            }
        });
        findViewById(R.id.tunnel_relay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TunnelSetup.relay(SettingsActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        renderTunnel();
                    }
                });
            }
        });
        renderTunnel();

        // Secret: three quick taps on the callsign erase all app data (same as the lost-device wipe).
        View.OnTouchListener tripleTap = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
                long now = e.getEventTime();
                if (now - lastTapMs > 500) taps = 0;
                lastTapMs = now;
                if (++taps >= 3) {
                    taps = 0;
                    secretWipe();
                    return true;
                }
                return false;
            }
        };
        findViewById(R.id.callsign_label).setOnTouchListener(tripleTap);
        callsign.setOnTouchListener(tripleTap);

        final SeekBar voxBar = (SeekBar) findViewById(R.id.vox_threshold);
        vox.setChecked(prefs.vox());
        voxBar.setProgress(prefs.voxThresholdDb() + 90);
        findViewById(R.id.vox_panel).setVisibility(prefs.vox() ? View.VISIBLE : View.GONE);
        vox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setVox(on, voxBar.getProgress() - 90);
                findViewById(R.id.vox_panel).setVisibility(on ? View.VISIBLE : View.GONE);
                RadioEngine e = engine();
                if (e != null) {
                    e.setVoxThresholdDb(prefs.voxThresholdDb());
                    e.setVox(on);
                }
            }
        });
        voxBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.setVox(vox.isChecked(), p - 90);
                RadioEngine e = engine();
                if (e != null) e.setVoxThresholdDb(p - 90);
                renderVox();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PinLock.gateScreen(this, "Enter your PIN to open the settings.");
        wipe.setChecked(prefs.wipeOnBadPin() && PinLock.isSet(this));
        handler.post(voxTick);
        showProfile();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) showProfile(); // after the profile chooser closes
    }

    private interface Toggle {
        void set(boolean on);
    }

    private void bindCheck(int id, boolean value, final Toggle t) {
        CheckBox c = (CheckBox) findViewById(id);
        c.setChecked(value);
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                t.set(on);
            }
        });
    }

    // ---- Bluetooth PTT button ----

    private static final int REQ_BT = 61;

    /** Android 12+: nearby-devices permission; older: location is needed to scan for BLE. */
    private boolean askBtPermission() {
        java.util.List<String> need = new java.util.ArrayList<String>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            for (String p : new String[]{android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT}) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQ_BT);
        return false;
    }

    private final java.util.List<android.bluetooth.BluetoothDevice> found = new java.util.ArrayList<android.bluetooth.BluetoothDevice>();
    private ArrayAdapter<String> foundNames;

    /** Scans for BLE buttons for 10 s; pick one (the FalconClaw's is called "BLU-PTT"). */
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("MissingPermission")
    private void chooseBtButton() {
        if (android.os.Build.VERSION.SDK_INT < 18) {
            Toast.makeText(this, "Bluetooth buttons need Android 4.3 or newer", Toast.LENGTH_LONG).show();
            return;
        }
        if (!askBtPermission()) return;
        final android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            return;
        }
        found.clear();
        foundNames = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        final android.bluetooth.BluetoothAdapter.LeScanCallback cb = new android.bluetooth.BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(final android.bluetooth.BluetoothDevice d, int rssi, byte[] rec) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (android.bluetooth.BluetoothDevice f : found) if (f.getAddress().equals(d.getAddress())) return;
                        found.add(d);
                        String n = BtPtt.name(d);
                        // Likely PTT buttons first.
                        boolean ptt = n.toUpperCase(java.util.Locale.US).contains("PTT");
                        foundNames.insert(n + "  (" + d.getAddress() + ")" + (ptt ? "  ← PTT button?" : ""), ptt ? 0 : foundNames.getCount());
                        if (ptt) {
                            found.remove(d);
                            found.add(0, d);
                        }
                    }
                });
            }
        };
        try {
            a.startLeScan(cb);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not scan: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Bluetooth PTT button (scanning…)")
                .setAdapter(foundNames, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int which) {
                        android.bluetooth.BluetoothDevice d = found.get(which);
                        prefs.setBtButton(d.getAddress(), BtPtt.name(d));
                        showBtButton();
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.restartBt();
                        Toast.makeText(SettingsActivity.this, "PTT button set. It connects whenever you are on air.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("No button", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        prefs.setBtButton("", "");
                        showBtButton();
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.restartBt();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                try {
                    a.stopLeScan(cb);
                } catch (RuntimeException ignored) {
                }
            }
        });
        dlg.show();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    a.stopLeScan(cb);
                } catch (RuntimeException ignored) {
                }
                if (dlg.isShowing()) dlg.setTitle(found.isEmpty() ? "No Bluetooth buttons found. Is it switched on?"
                        : "Bluetooth PTT button");
            }
        }, 10000);
    }

    private void showBtButton() {
        String n = prefs.btButtonName();
        String state = n.length() == 0 ? "" : "\n" + BtPtt.buttonState
                + (BtPtt.lastEvent.length() == 0 ? "" : "\nlast: " + BtPtt.lastEvent);
        ((TextView) findViewById(R.id.bt_button_label)).setText("Bluetooth PTT button: "
                + (n.length() == 0 ? "none" : n) + state);
    }

    private void showProfile() {
        ((android.widget.Button) findViewById(R.id.quick_profile)).setText("Quick map points: "
                + org.communitycomms.app.geo.QuickProfiles.NAMES[new org.communitycomms.app.geo.QuickProfiles(this).profile()]);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(voxTick);
        if (!wiped) save();
        super.onPause();
    }

    private static RadioEngine engine() {
        RadioService s = RadioService.instance();
        return s == null ? null : s.engine();
    }

    /** Which settings page this is (null = the main page, which links to the others). */
    public static final String EXTRA_SECTION = "section";
    private static final int[] SECTIONS = {R.id.sec_channels, R.id.sec_audio, R.id.sec_home, R.id.sec_power, R.id.sec_network,
            R.id.sec_security, R.id.sec_data};
    private static final int[] LINKS = {R.id.link_channels, R.id.link_audio, R.id.link_home, R.id.link_power, R.id.link_network,
            R.id.link_security, R.id.link_data};
    private static final String[] NAMES = {"channels", "audio", "home", "power", "network", "security", "data"};
    private static final int[] TITLES = {R.string.page_channels, R.string.page_audio, R.string.page_home, R.string.page_power,
            R.string.page_network, R.string.page_security, R.string.page_data};
    private int section = -1;

    /** One page of the settings: the main page (top and bottom items, links), or one section. */
    private void showSection(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) section = i;
        findViewById(R.id.main_top).setVisibility(section < 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.main_bottom).setVisibility(section < 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < SECTIONS.length; i++) {
            findViewById(SECTIONS[i]).setVisibility(section == i ? View.VISIBLE : View.GONE);
            final String n = NAMES[i];
            findViewById(LINKS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    save();
                    startActivity(new android.content.Intent(SettingsActivity.this, SettingsActivity.class).putExtra(EXTRA_SECTION, n));
                }
            });
        }
        if (section >= 0) setTitle(getString(TITLES[section]).replace("…", ""));
    }

    /** Opens one settings page directly (e.g. Mission and data from the Data screen). */
    public static void open(android.content.Context c, String section) {
        c.startActivity(new android.content.Intent(c, SettingsActivity.class).putExtra(EXTRA_SECTION, section));
    }

    /** True when that field is on this page (so a page never saves stale copies of another page's fields). */
    private static boolean here(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Back from another settings page: show what it changed (this page saved its own fields on leaving).
        recreateFresh = true;
        recreate();
    }

    private boolean recreateFresh;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        // Being rebuilt with fresh values: restoring the old tick boxes would write them back over what the other page saved.
        if (!recreateFresh) super.onSaveInstanceState(out);
    }

    private static final int[] POWER_RADIOS = {R.id.power_normal, R.id.power_saver, R.id.power_ultra, R.id.power_offline};

    /** Settings > Power and sleep (the black screen settings there are set up with the others). */
    private void setUpPower() {
        ((RadioGroup) findViewById(R.id.power_mode)).check(POWER_RADIOS[PowerMode.mode()]);
        ((RadioGroup) findViewById(R.id.power_mode)).setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                for (int i = 0; i < POWER_RADIOS.length; i++) if (POWER_RADIOS[i] == id) PowerMode.set(SettingsActivity.this, i);
            }
        });
        ((EditText) findViewById(R.id.power_ask_saver)).setText(String.valueOf(prefs.powerAskSaver()));
        ((EditText) findViewById(R.id.power_ask_ultra)).setText(String.valueOf(prefs.powerAskUltra()));
        CheckBox wake = (CheckBox) findViewById(R.id.black_wake);
        wake.setChecked(prefs.blackWake());
        wake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setBlackWake(on);
            }
        });
        CheckBox red = (CheckBox) findViewById(R.id.red_mode);
        red.setChecked(LightDiscipline.redMode(this));
        red.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                LightDiscipline.setRedMode(SettingsActivity.this, on);
            }
        });
        CheckBox auto = (CheckBox) findViewById(R.id.red_auto);
        auto.setChecked(LightDiscipline.redAuto(this));
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                LightDiscipline.setRedSchedule(SettingsActivity.this, on, LightDiscipline.redFrom(SettingsActivity.this), LightDiscipline.redTo(SettingsActivity.this));
                LightDiscipline.checkRedSchedule(SettingsActivity.this);
            }
        });
        redTime(R.id.red_from, "Red light on at ", true);
        redTime(R.id.red_to, "Red light off at ", false);
    }

    private void redTime(int id, final String word, final boolean from) {
        final android.widget.Button b = (android.widget.Button) findViewById(id);
        int t = from ? LightDiscipline.redFrom(this) : LightDiscipline.redTo(this);
        b.setText(word + String.format(java.util.Locale.US, "%02d:%02d", t / 60, t % 60));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int now = from ? LightDiscipline.redFrom(SettingsActivity.this) : LightDiscipline.redTo(SettingsActivity.this);
                new android.app.TimePickerDialog(SettingsActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                        int at = h * 60 + m;
                        boolean on = LightDiscipline.redAuto(SettingsActivity.this);
                        LightDiscipline.setRedSchedule(SettingsActivity.this, on, from ? at : LightDiscipline.redFrom(SettingsActivity.this),
                                from ? LightDiscipline.redTo(SettingsActivity.this) : at);
                        b.setText(word + String.format(java.util.Locale.US, "%02d:%02d", h, m));
                    }
                }, now / 60, now % 60, true).show();
            }
        });
    }

    private static int percent(EditText e) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException x) {
            return 0;
        }
    }

    private void save() {
        if (recreateFresh) return; // being rebuilt with fresh values: nothing typed here to keep
        EditText askSaver = (EditText) findViewById(R.id.power_ask_saver), askUltra = (EditText) findViewById(R.id.power_ask_ultra);
        if (here(askSaver)) {
            prefs.setPowerAskSaver(percent(askSaver));
            prefs.setPowerAskUltra(percent(askUltra));
        }        if (here(callsign)) prefs.setCallsign(callsign.getText().toString().trim());
        if (here(channelA)) prefs.setChannel(Prefs.SLOT_A, channelA.getText().toString().trim());
        if (here(channelB)) prefs.setChannel(Prefs.SLOT_B, channelB.getText().toString().trim());
        if (here(openManet)) prefs.setChannel(Prefs.SLOT_OPENMANET, openManet.getText().toString().trim());
        if (here(publicKey)) prefs.setPublicKey(publicKey.getText().toString());
        if (!here(port)) {
            if (here(catchUp)) prefs.setCatchUp(catchUp.isChecked(), number(every, 5), number(window, 30));
            return;
        }
        int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            p = org.communitycomms.app.net.UdpLink.DEFAULT_PORT;
        }
        prefs.setNetwork(netMode.getCheckedRadioButtonId() == R.id.net_multicast ? Prefs.NET_MULTICAST : Prefs.NET_BROADCAST,
                p, peers.getText().toString().trim());
        if (here(catchUp)) prefs.setCatchUp(catchUp.isChecked(), number(every, 5), number(window, 30));
    }

    private static int number(EditText e, int dflt) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    private void styleNames(boolean show) {
        int t = InputType.TYPE_CLASS_TEXT | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD);
        channelA.setInputType(t);
        channelB.setInputType(t);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (learningKey && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            learningKey = false;
            prefs.setPttKey(ev.getKeyCode());
            renderKeyLabel();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Warns when the phone's own screen timeout would switch the screen off before the timer. */
    private void renderBlackAfter() {
        TextView t = (TextView) findViewById(R.id.black_after_note);
        int s = prefs.blackAfterSec();
        int phoneMs;
        try {
            phoneMs = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT);
        } catch (Exception e) {
            phoneMs = -1;
        }
        if (s == 0) {
            t.setText("Off. Double-tap the screen to get out of black screen.");
            t.setTextColor(0xffcfe3d4);
        } else if (phoneMs > 0 && s * 1000L >= phoneMs) {
            t.setText("Warning: the phone switches its screen off after " + phoneMs / 1000 + " s (Android display settings), "
                    + "before this timer. Set a shorter time here, or a longer screen timeout on the phone.");
            t.setTextColor(0xffff8a80);
        } else {
            t.setText("After " + s + " s without a touch the home screen goes black (double-tap to wake)."
                    + (phoneMs > 0 ? " Phone screen timeout: " + phoneMs / 1000 + " s." : ""));
            t.setTextColor(0xffcfe3d4);
        }
    }

    private void renderTunnel() {
        ((TextView) findViewById(R.id.tunnel_state)).setText(TunnelSetup.describe(this));
    }

    /**
     * With a mission set: offer a final sync to the LAN storage first, then clear and end the mission
     * (its name is removed). Without one: the plain clear below.
     */
    private void clearMission() {
        final String m = LanSync.mission(this);
        if (m.length() == 0 || !new org.communitycomms.app.geo.LanTiles(this).configured()) {
            clearMissionNow(m);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Mission " + m)
                .setMessage("Do a final sync of this mission to the LAN storage first (logs, files, recordings, photos, "
                        + "videos, DRYAD sheets and settings)? Then the mission data is cleared and the mission ends.")
                .setPositiveButton("Final sync, then clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(SettingsActivity.this,
                                "Final sync", "Sending mission " + m + " to the LAN storage…", true, false);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String r = LanSync.runNow(SettingsActivity.this, true);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        pd.dismiss();
                                        new android.app.AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Final sync done")
                                                .setMessage(r + "\n\nClear the mission data now?")
                                                .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(android.content.DialogInterface d2, int w2) {
                                                        clearMissionNow(m);
                                                    }
                                                })
                                                .setNegativeButton("Not yet", null)
                                                .show();
                                    }
                                });
                            }
                        }, "final-sync").start();
                    }
                })
                .setNeutralButton("Clear without syncing", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        clearMissionNow(m);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void deleteChildren(java.io.File dir) {
        java.io.File[] kids = dir == null ? null : dir.listFiles();
        if (kids != null) for (java.io.File k : kids) k.delete();
    }

    /** Leaving every channel is required first, so nothing new arrives while it is cleared. */
    private void clearMissionNow(final String mission) {
        final RadioService s = RadioService.instance();
        final boolean onAir = s != null && (s.engine() != null || s.compat() != null);
        String msg = "Delete chat and report history, pictures and files, voice recordings, map points and "
                + "contacts' last positions?\n\nDRYAD sheets, channel names, settings and offline maps are kept.";
        if (onAir) msg = "You are on a channel. You must leave every channel first.\n\n" + msg;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear mission data")
                .setMessage(msg)
                .setPositiveButton(onAir ? "Leave and clear" : "Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (onAir) {
                            s.leave();
                            new Prefs(SettingsActivity.this).setWasOnAir(false);
                        }
                        AppWipe.clearMission(SettingsActivity.this);
                        deleteChildren(LanSync.videosDir(SettingsActivity.this));
                        if (mission.length() > 0) LanSync.setMission(SettingsActivity.this, ""); // the mission ends
                        Toast.makeText(SettingsActivity.this, mission.length() > 0 ? "Mission " + mission + " cleared and ended"
                                : "Mission data cleared", Toast.LENGTH_LONG).show();
                        recreate();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderKeyLabel() {
        int k = prefs.pttKey();
        if (k == KeyEvent.KEYCODE_UNKNOWN) keyLabel.setText(R.string.no_key);
        else keyLabel.setText("Hardware PTT key: " + KeyEvent.keyCodeToString(k));
    }

    private void renderVox() {
        if (!vox.isChecked()) return;
        RadioEngine e = engine();
        boolean on = e != null && e.voxOn();
        TextView l = (TextView) findViewById(R.id.vox_label);
        ProgressBar meter = (ProgressBar) findViewById(R.id.vox_meter);
        double level = e == null ? -90 : e.levelDb();
        meter.setProgress((int) Math.max(0, Math.min(90, level + 90)));
        meter.setSecondaryProgress(prefs.voxThresholdDb() + 90);
        l.setText("Trigger level " + prefs.voxThresholdDb() + " dB · mic " + Math.round(level) + " dB"
                + (on ? "" : " · join a channel to start VOX")
                + "\nListeners hear you about 3 s late. A headset stops the speaker triggering it.");
    }

    private void renderIface() {
        String n = prefs.netInterface();
        ((TextView) findViewById(R.id.iface_label)).setText(n.length() == 0
                ? "Using: all networks (automatic)" : "Using: " + MainActivity.kindOf(n) + " (" + n + ")");
    }

    private void pickInterface() {
        final java.util.List<String> names = new java.util.ArrayList<String>();
        java.util.List<String> labels = new java.util.ArrayList<String>();
        names.add("");
        labels.add("Automatic: all networks");
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ip = null;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address) ip = ia.getAddress().getHostAddress();
                }
                if (ip == null) continue;
                names.add(ni.getName());
                labels.add(MainActivity.kindOf(ni.getName()) + "  ·  " + ni.getName() + "  ·  " + ip);
            }
        } catch (java.net.SocketException ignored) {
        }
        String cur = prefs.netInterface();
        if (cur.length() > 0 && !names.contains(cur)) {
            names.add(cur);
            labels.add(MainActivity.kindOf(cur) + "  ·  " + cur + "  ·  not connected");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Network to use")
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, names.indexOf(cur)),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                prefs.setNetInterface(names.get(which));
                                renderIface();
                                d.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean wiped;

    private void secretWipe() {
        wiped = true;
        AppWipe.wipe(this);
        Toast.makeText(this, "All app data erased", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
