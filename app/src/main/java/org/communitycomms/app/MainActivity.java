package org.communitycomms.app;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The field screen: channel select, push to talk, quick map points, chat / reports / map, and a
 * lock that stops accidental changes. Setup (callsign, channel names, network...) is in Settings.
 */
public final class MainActivity extends Activity implements RadioService.Ui {
    private static final int REQ_PERMS = 1, REQ_LOCATION = 2, REQ_SETUP_QR = 3;

    private Prefs prefs;
    private Button join, ptt, lock;
    private TextView status;
    private Button contactsButton, statusButton;
    /** Rejoin-on-start is tried once per app run, not every time this screen comes back. */
    private static boolean startupRejoinDone;
    private RadioService service;
    private boolean joined;
    private boolean joinAfterPermission;
    /** Settings the radio joined with; if they change in Settings, the radio re-joins. */
    private String joinedWith;
    private final Handler handler = new Handler();

    private final Runnable rosterTick = new Runnable() {
        @Override
        public void run() {
            renderRoster();
            renderShare();
            renderSilent();
            renderChat();
            checkPendingConfig();
            if (mapHome && ++mapTicks % 7 == 0) refreshHomeMap();
            if (prefs.vox()) renderVox();
            if (++relayTicks % 10 == 0) {
                checkMobileRelay();
                checkRelayPrompt();
                checkRemoteNetwork();
                checkBatteryAsk();
                checkBlackWake();
                LightDiscipline.checkRedSchedule(MainActivity.this);
            }
            handler.postDelayed(this, 300);
        }
    };

    private int relayTicks;
    private long nobodySince;
    private android.app.AlertDialog relayDialog;

    /**
     * Remote (relay server) set up, no WiFi or Ethernet, but mobile data is up: offer to talk through
     * the relay over mobile data (asked at most every 15 minutes). When WiFi comes back after that,
     * offer to stop using mobile data.
     */
    private void checkMobileRelay() {
        if (PowerMode.offline() || !joined || relayDialog != null || prefs.tunnelServer().length() == 0 || prefs.tunnelKey().length() == 0) return;
        final android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        boolean wifi = NetCheck.hasLocalNetwork(this);
        if (wifi && prefs.tunnelClientOn() && sp.getBoolean("relayViaMobile", false)) {
            sp.edit().putBoolean("relayViaMobile", false).commit();
            relayDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("WiFi is back")
                    .setMessage("You switched to the relay over mobile data. Stop using the relay and talk on this WiFi again?")
                    .setPositiveButton("Use WiFi", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            prefs.setTunnelClientOn(false);
                            rejoinIfSettingsChanged();
                        }
                    })
                    .setNegativeButton("Keep the relay", null)
                    .create();
        } else if (!wifi && !prefs.tunnelClientOn() && NetCheck.mobileData(this)
                && now - sp.getLong("mobileRelayPromptAt", 0) >= 15 * 60000L) {
            sp.edit().putLong("mobileRelayPromptAt", now).commit();
            relayDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("No WiFi: use mobile data?")
                    .setMessage("This phone is not on WiFi, so nobody nearby can hear it directly. Talk through the relay server ("
                            + prefs.tunnelServer() + ") over mobile data instead? PTT, chat and positions then go over the "
                            + "internet, encrypted as usual (voice uses roughly 1 MB for every 10 minutes of talking).")
                    .setPositiveButton("Use mobile data", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            sp.edit().putBoolean("relayViaMobile", true).commit();
                            prefs.setTunnelClientOn(true);
                            rejoinIfSettingsChanged();
                            Toast.makeText(MainActivity.this, "Connecting to the relay over mobile data…", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .create();
        } else {
            return;
        }
        relayDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                relayDialog = null;
            }
        });
        relayDialog.show();
    }

    /**
     * Nobody heard for a minute while a relay server is set up but not in use: offer to test it and
     * switch to it. Asked at most every 15 minutes.
     */
    private void checkRelayPrompt() {
        long now = System.currentTimeMillis();
        if (PowerMode.offline() || !joined || shownOnline != 0) {
            nobodySince = 0;
            return;
        }
        if (nobodySince == 0) nobodySince = now;
        if (now - nobodySince < PowerMode.quietMs() || relayDialog != null) return;
        if (prefs.tunnelServer().length() == 0 || prefs.tunnelKey().length() == 0 || prefs.tunnelClientOn()) return;
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (now - sp.getLong("relayPromptAt", 0) < 15 * 60000L) return;
        sp.edit().putLong("relayPromptAt", now).commit();
        relayDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Nobody heard")
                .setMessage("No one has been heard for " + (PowerMode.quietMs() <= 60000 ? "a minute" : PowerMode.quietMs() / 60000 + " minutes") + ". A relay server is set up (" + prefs.tunnelServer()
                        + ") but not in use.\n\nTest and enter relay server mode?")
                .setPositiveButton("Test and use it", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setTunnelClientOn(true);
                        rejoinIfSettingsChanged();
                        Toast.makeText(MainActivity.this, "Connecting through the relay…", Toast.LENGTH_LONG).show();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                org.communitycomms.app.net.Tunnel.Client t = org.communitycomms.app.net.Tunnel.client;
                                if (t != null && t.connected()) {
                                    Toast.makeText(MainActivity.this, "Relay connected: " + TunnelSetup.describe(MainActivity.this),
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                new android.app.AlertDialog.Builder(MainActivity.this)
                                        .setTitle("Relay not answering")
                                        .setMessage("The relay at " + prefs.tunnelServer() + " has not answered. Keep trying, or turn relay mode off?")
                                        .setPositiveButton("Keep trying", null)
                                        .setNegativeButton("Turn it off", new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface d2, int w2) {
                                                prefs.setTunnelClientOn(false);
                                                rejoinIfSettingsChanged();
                                            }
                                        }).show();
                            }
                        }, 25000);
                    }
                })
                .setNegativeButton("Not now", null)
                .create();
        relayDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                relayDialog = null;
            }
        });
        relayDialog.show();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((RadioService.LocalBinder) b).service();
            service.setUi(MainActivity.this);
            if (joinAfterPermission) {
                joinAfterPermission = false;
                doJoin();
            } else if (!startupRejoinDone) {
                startupRejoinDone = true;
                if (TvActivity.isTv(MainActivity.this) && prefs.channel(Prefs.SLOT_A).length() == 0) {
                    // A TV with no private key: the Public map and chat on the big screen.
                    if (slot != Prefs.SLOT_PUBLIC) {
                        slot = Prefs.SLOT_PUBLIC;
                        prefs.setActiveSlot(slot);
                    }
                    if (service.engine() == null) requestJoin();
                    startActivity(new Intent(MainActivity.this, TvActivity.class));
                    return;
                }
                // Left with Exit while on air: ask first.
                if (service.engine() == null && service.compat() == null && prefs.exitSlots().length() > 0) {
                    askReconnect();
                    return;
                }
                // App start: back on the channel it was on (the same one stays main with dual watch).
                if (service.engine() == null && service.compat() == null && prefs.rejoinOnStart() && prefs.wasOnAir()
                        && prefs.channel(slot).length() > 0) {
                    Toast.makeText(MainActivity.this, "Rejoining " + label(slot) + " (turn this off in Settings)",
                            Toast.LENGTH_SHORT).show();
                    requestJoin();
                }
            } else {
                rejoinIfSettingsChanged();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        mapHome = wantMapHome();
        setContentView(mapHome ? R.layout.activity_main_map : R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Phones and portrait: the short name fits; the tablet commander view keeps the full one.
        setTitle(commander() ? getString(R.string.app_name) : "Comms");
        setUpTitleToggle();
        if (mapHome) setUpHomeMap();
        if (findViewById(R.id.side_holder) != null) setUpCommander();
        if (savedInstanceState != null) joinedWith = savedInstanceState.getString("joinedWith");

        join = (Button) findViewById(R.id.join);
        ptt = (Button) findViewById(R.id.ptt);
        if (!mapHome && getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Buttons home (portrait): PTT about a quarter of the screen, not all the spare space.
            int quarter = getResources().getDisplayMetrics().heightPixels / 4;
            ptt.getLayoutParams().height = Math.max((int) (120 * getResources().getDisplayMetrics().density), quarter);
        }
        lock = (Button) findViewById(R.id.lock);
        View black = findViewById(R.id.black_screen);
        if (black != null) black.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (LightDiscipline.legacyBlackout(MainActivity.this)) Toast.makeText(MainActivity.this, "Double-tap to wake", Toast.LENGTH_SHORT).show();
                LightDiscipline.blackout(MainActivity.this);
            }
        });
        status = (TextView) findViewById(R.id.status);
        contactsButton = (Button) findViewById(R.id.contacts);
        statusButton = (Button) findViewById(R.id.my_status);
        contactsButton.setOnClickListener(open(ContactsActivity.class));
        statusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseStatus();
            }
        });
        findViewById(R.id.camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FilesActivity.class).putExtra(FilesActivity.EXTRA_PHOTO, true));
            }
        });
        android.widget.ImageButton chal = (android.widget.ImageButton) findViewById(R.id.challenge_btn);
        if (chal != null) {
            chal.setImageBitmap(ChallengeActivity.icon(getResources().getDisplayMetrics().density));
            chal.setOnClickListener(open(ChallengeActivity.class));
        }

        slotGroup = (RadioGroup) findViewById(R.id.slot);
        slot = prefs.activeSlot();
        slotGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                if (quietCheck) return;
                for (int i = 0; i < homeSlots.length; i++) if (SLOT_IDS[i] == id) switchSlot(homeSlots[i]);
            }
        });

        // Join / leave live on the Channels page, so a stray tap here cannot drop you off air.
        join.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Simple modes: just the private and public keys, not the full channels page.
                startActivity(new Intent(MainActivity.this, prefs.simple() ? KeysActivity.class : ChannelsActivity.class));
            }
        });

        findViewById(R.id.chat).setOnClickListener(open(ChatActivity.class));
        findViewById(R.id.reports).setOnClickListener(open(ReportsActivity.class));
        findViewById(R.id.map).setOnClickListener(open(MapActivity.class));
        lock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (prefs.locked()) {
                    Toast.makeText(MainActivity.this, "Hold the lock button to unlock", Toast.LENGTH_SHORT).show();
                } else {
                    prefs.setLocked(true);
                    renderLock();
                }
            }
        });
        lock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (prefs.locked()) {
                    prefs.setLocked(false);
                    renderLock();
                    Toast.makeText(MainActivity.this, "Unlocked", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        renderQuick();

        findViewById(R.id.share_loc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRapidShare();
            }
        });

        findViewById(R.id.call).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callEveryone();
            }
        });
        findViewById(R.id.radio_silent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRadioSilent();
            }
        });

        findViewById(R.id.resend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null && service.resendLast()) {
                    ((TextView) findViewById(R.id.ack_status)).setText("Resending…");
                    findViewById(R.id.resend).setVisibility(View.GONE);
                    findViewById(R.id.retry_later).setVisibility(View.GONE);
                    findViewById(R.id.dismiss).setVisibility(View.GONE);
                }
            }
        });
        findViewById(R.id.retry_later).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askRetryLater();
            }
        });
        findViewById(R.id.dismiss).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.dismissResend();
                hideAckBar();
            }
        });

        ptt.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pttPressed();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pttReleased();
                        return true;
                }
                return false;
            }
        });

        onJoinState("Not on a channel", false);
        renderSlots();
        firstStartWizard();
        if (prefs.setupDone()) showPermissionsOnce(); // phones set up before the permissions screen existed
    }

    /** Join or leave asked for on the Channels page (done here: permissions, PIN, re-join logic). */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String action = i.getStringExtra(ChannelsActivity.EXTRA_ACTION);
        if (action != null) handleChannelAction(action, i.getIntExtra(ChannelsActivity.EXTRA_SLOT, slot));
    }

    /** Join (switch to) or leave a channel, asked for on the Channels page or the commander panel. */
    private void handleChannelAction(String action, int s) {
        if (ChannelsActivity.LEAVE.equals(action)) {
            leaveChannel();
        } else if (ChannelsActivity.JOIN.equals(action)) {
            if (s != slot) {
                // Switching while on air re-joins on the new channel (the old one is left, or watched).
                boolean was = joined;
                selectSlot(s);
                if (!was) requestJoin();
            } else if (!joined) {
                requestJoin();
            }
        }
    }

    private void leaveChannel() {
        joinedWith = null;
        prefs.setWasOnAir(false); // left on purpose: do not rejoin at the next start
        if (service != null) {
            service.setRapidShare(false);
            service.leave();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("joinedWith", joinedWith);
    }

    private View.OnClickListener open(final Class<?> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, screen));
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Back after more than a minute away (re-opened, not just back from another screen): centre on me again.
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60000) centredOnMe = false;
        bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        handler.post(rosterTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.locationAsk = homeLocationAsk;
        if (homePad != null) homePad.setVisibility(org.communitycomms.app.geo.MapPad.shown(this) ? View.VISIBLE : View.GONE);
        askBlackScreenOnce();
        RadioService.missedListener = commander() ? missedOffer : null;
        if (wantMapHome() != mapHome) {
            recreate(); // home screen style changed in Settings
            return;
        }
        if (PowerMode.letScreenSleep()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View black = findViewById(R.id.black_screen);
        if (black != null) black.setVisibility(prefs.blackScreenButton() ? View.VISIBLE : View.GONE);
        View reports = findViewById(R.id.reports);
        if (reports != null) reports.setVisibility(prefs.simple() ? View.GONE : View.VISIBLE); // simple modes: no reports
        View chal = findViewById(R.id.challenge_btn);
        if (chal != null) chal.setVisibility(prefs.appMode() == Prefs.MODE_ADVANCED ? View.VISIBLE : View.GONE);
        restartBlackTimer();
        // Intercom mode: the intercom screen sits on top; this screen keeps running the radio.
        if (prefs.appMode() == Prefs.MODE_INTERCOM && !IntercomActivity.showFullRadio && prefs.setupDone()) {
            // Always on the private (intercom) channel, joined whatever "rejoin on start" says.
            if (slot != Prefs.SLOT_A) selectSlot(Prefs.SLOT_A);
            if ((service == null || (service.engine() == null && service.compat() == null))
                    && prefs.channel(Prefs.SLOT_A).length() > 0) requestJoin();
            startActivity(new Intent(this, IntercomActivity.class));
        }
        if (contactsPanel != null) {
            contactsPanel.start();
            channelsPanel.start();
        }
        if (homeMap != null) {
            tilePrompt.resume();
            myPos.start();
            refreshHomeMap();
        }
        renderLock();
        renderQuick(); // the profile may have changed in Settings
        renderCommanderPoints();
        renderSlots();
        rejoinIfSettingsChanged();
        if (joinWhenBack) {
            joinWhenBack = false; // first start chose Public: join once the permissions screen is done
            requestJoin();
        }
    }

    // ---- commander: every map point of the profile, and broadcast to several teams ----

    /** Two per row: the profile's map points plus extra ones of the same kind (QuickProfiles.commanderItems). */
    private void renderCommanderPoints() {
        LinearLayout box = (LinearLayout) findViewById(R.id.commander_points);
        if (box == null) return;
        box.removeAllViews();
        final List<org.communitycomms.app.geo.QuickProfiles.Item> items =
                new org.communitycomms.app.geo.QuickProfiles(this).commanderItems();
        for (int i = 0; i < items.size(); i += 2) {
            List<Button> row = new ArrayList<Button>();
            for (int j = i; j < Math.min(i + 2, items.size()); j++) {
                final org.communitycomms.app.geo.QuickProfiles.Item it = items.get(j);
                Button b = new Button(this);
                b.setText(it.label + (it.atMapCentre ? " (at +)" : ""));
                b.setTextSize(13);
                b.setAllCaps(false);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        markWith(it);
                    }
                });
                row.add(b);
            }
            box.addView(FilesActivity.row(row.toArray(new Button[0])));
        }
    }

    /** Same as a quick map point button, for any item (at my position, or the map's +). */
    private void markWith(org.communitycomms.app.geo.QuickProfiles.Item it) {
        if (it.atMapCentre && homeMap != null) {
            sendQuick(it, homeMap.centerLat(), homeMap.centerLon());
            return;
        }
        for (int i = 0; i < quickItems.size(); i++) {
            if (quickItems.get(i).label.equals(it.label)) {
                quickPoi(i);
                return;
            }
        }
        quickItems.add(it); // an extra commander item: reuse the same GPS path
        quickPoi(quickItems.size() - 1);
    }

    /** Broadcast: talking (and a message) also goes to the ticked listened channels (teams). */
    private void chooseBroadcast() {
        if (service == null || service.engine() == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Integer> slots = service.watchingSlots();
        if (slots.isEmpty()) {
            Toast.makeText(this, "Listen to other channels first (Channels > Dual watch > Listen)", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Set<Integer> on = service.broadcastSlots();
        final String[] names = new String[slots.size()];
        final boolean[] ticked = new boolean[slots.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = "Team " + Prefs.letter(slots.get(i));
            ticked[i] = on.contains(slots.get(i));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Broadcast: also send to (plus " + label(slot) + ")")
                .setMultiChoiceItems(names, ticked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                        ticked[which] = isChecked;
                    }
                })
                .setPositiveButton("Talk to these", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        java.util.Set<Integer> chosen = new java.util.TreeSet<Integer>();
                        for (int i = 0; i < ticked.length; i++) if (ticked[i]) chosen.add(slots.get(i));
                        service.setBroadcast(chosen);
                        renderBroadcast();
                    }
                })
                .setNeutralButton("Message these…", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        List<Integer> to = new ArrayList<Integer>();
                        to.add(slot);
                        for (int i = 0; i < ticked.length; i++) if (ticked[i]) to.add(slots.get(i));
                        messageTeams(MainActivity.this, to);
                    }
                })
                .setNegativeButton("Stop broadcast", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        service.setBroadcast(new java.util.TreeSet<Integer>());
                        renderBroadcast();
                    }
                })
                .show();
    }

    private void renderBroadcast() {
        Button b = (Button) findViewById(R.id.broadcast);
        if (b == null) return;
        java.util.Set<Integer> on = service == null ? new java.util.TreeSet<Integer>() : service.broadcastSlots();
        if (on.isEmpty()) {
            b.setText(R.string.broadcast_button);
            b.getBackground().mutate().clearColorFilter();
        } else {
            StringBuilder sb = new StringBuilder("To ").append(Prefs.letter(slot));
            for (int s : on) sb.append('+').append(Prefs.letter(s));
            b.setText(sb);
            b.getBackground().mutate().setColorFilter(0xffc62828, android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
    }

    /** One text to everyone on each of these channels (teams). Also used from Contacts. */
    static void messageTeams(final Activity a, final List<Integer> slots) {
        final EditText e = new EditText(a);
        e.setMinLines(2);
        StringBuilder to = new StringBuilder();
        for (int s : slots) to.append(to.length() == 0 ? "" : ", ").append(Prefs.letter(s));
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(p, p / 2, p, 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(a)
                .setTitle("Message team " + to)
                .setView(box)
                .setPositiveButton("Send", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String text = e.getText().toString().trim();
                        RadioService rs = RadioService.instance();
                        if (text.length() == 0 || rs == null) return;
                        int sent = 0;
                        for (int s : slots) if (rs.sendTextTo(s, text)) sent++;
                        Toast.makeText(a, sent == slots.size() ? "Sent" : "Sent to " + sent + " of " + slots.size()
                                + " (only channels you talk or listen on)", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- hide / show the title bar (more room for the map and buttons) ----

    /** ▲ in the title bar hides it; a small ▼ in the top corner brings it back (and the menu with it). */
    private void setUpTitleToggle() {
        final android.app.ActionBar ab = getActionBar();
        if (ab == null) return;
        float d = getResources().getDisplayMetrics().density;
        Button up = new Button(this);
        up.setText("▲");
        up.setTextSize(20);
        up.setBackgroundColor(0);
        up.setTextColor(0xfff0c75e);
        up.setContentDescription("Hide the title bar");
        ab.setCustomView(up, new android.app.ActionBar.LayoutParams((int) (48 * d),
                android.app.ActionBar.LayoutParams.MATCH_PARENT, android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL));
        ab.setDisplayShowCustomEnabled(true);
        final Button down = new Button(this);
        down.setText("▼");
        down.setTextColor(0xfff0c75e);
        down.setContentDescription("Show the title bar");
        down.setMinHeight(0);
        down.setMinWidth(0);
        down.setPadding(0, 0, 0, 0);
        down.setTextSize(16);
        down.setBackgroundColor(0xff1f3d2b);
        // A thin strip above the screen's own content, so it covers no button.
        final int strip = (int) (22 * d);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams((int) (96 * d), strip,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        final android.widget.FrameLayout content = (android.widget.FrameLayout) findViewById(android.R.id.content);
        final int origTop = content.getChildAt(0).getPaddingTop();
        content.addView(down, lp);
        final android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                // On a TV the title bar (with its menu and TV mode) always shows: a remote cannot reach ▼.
                boolean hidden = sp.getBoolean("titleHidden", false) && !TvActivity.isTv(MainActivity.this);
                if (hidden) ab.hide();
                else ab.show();
                down.setVisibility(hidden ? View.VISIBLE : View.GONE);
                View screen = content.getChildAt(0); // the layout set by setContentView
                if (screen != down) screen.setPadding(screen.getPaddingLeft(), hidden ? strip : origTop,
                        screen.getPaddingRight(), screen.getPaddingBottom());
            }
        };
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("titleHidden", true).commit();
                apply.run();
            }
        });
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("titleHidden", false).commit();
                apply.run();
            }
        });
        apply.run();
    }

    // ---- tablet commander view: map plus a Contacts / Channels side panel ----

    private ContactsPanel contactsPanel;
    private ChannelsPanel channelsPanel;

    /** A tablet in landscape is always the commander view (map home with the side panel). */
    private boolean commander() {
        android.content.res.Configuration c = getResources().getConfiguration();
        // A TV with a private key entered is the commander view too (layout-land-television).
        if (TvActivity.isTv(this) && prefs.channel(Prefs.SLOT_A).length() > 0) return true;
        return Build.VERSION.SDK_INT >= 13 && c.smallestScreenWidthDp >= 600
                && c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean wantMapHome() {
        return prefs.homeMap() || commander();
    }

    private void setUpCommander() {
        final android.widget.FrameLayout holder = (android.widget.FrameLayout) findViewById(R.id.side_holder);
        contactsPanel = new ContactsPanel(this);
        channelsPanel = new ChannelsPanel(this, new ChannelsPanel.Host() {
            @Override
            public void channelAction(String action, int s) {
                handleChannelAction(action, s);
            }

            @Override
            public void channelsChanged() {
                renderSlots(); // eyes, names
                rejoinIfSettingsChanged(); // listening, swaps: applied straight away
            }
        });
        final android.widget.ScrollView channelsScroll = new android.widget.ScrollView(this);
        channelsScroll.addView(channelsPanel.view());
        final Button tabContacts = (Button) findViewById(R.id.tab_contacts), tabChannels = (Button) findViewById(R.id.tab_channels);
        View.OnClickListener tabs = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean channels = v == tabChannels;
                holder.removeAllViews();
                holder.addView(channels ? channelsScroll : contactsPanel.view());
                tabChannels.setTextColor(channels ? 0xfff0c75e : 0xffffffff);
                tabContacts.setTextColor(channels ? 0xffffffff : 0xfff0c75e);
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("commanderChannels", channels).commit();
            }
        };
        tabContacts.setOnClickListener(tabs);
        tabChannels.setOnClickListener(tabs);
        findViewById(R.id.broadcast).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseBroadcast();
            }
        });
        renderCommanderPoints();
        final View side = findViewById(R.id.side_panel);
        final Button toggle = (Button) findViewById(R.id.side_toggle);
        final android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        final Runnable showSide = new Runnable() {
            @Override
            public void run() {
                boolean hidden = sp.getBoolean("commanderSideHidden", false);
                side.setVisibility(hidden ? View.GONE : View.VISIBLE);
                toggle.setText(hidden ? "▶" : "◀");
            }
        };
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("commanderSideHidden", !sp.getBoolean("commanderSideHidden", false)).commit();
                showSide.run();
            }
        });
        showSide.run();
        setUpRightFold(sp);
        tabs.onClick(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("commanderChannels", false) ? tabChannels : tabContacts);
    }

    private Button miniPtt;

    /**
     * Commander view: ▶ slides the right-hand buttons away for the biggest map; a small PTT and a
     * "+ POI" (at the map's +) then float on the map. ◀ brings the buttons back.
     */
    private void setUpRightFold(final android.content.SharedPreferences sp) {
        final View right = findViewById(R.id.right_panel);
        final Button toggle = (Button) findViewById(R.id.right_toggle);
        android.widget.FrameLayout holder = (android.widget.FrameLayout) findViewById(R.id.map_holder);
        if (right == null || toggle == null || holder == null) return;
        float d = getResources().getDisplayMetrics().density;
        final LinearLayout mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        miniPtt = new Button(this);
        miniPtt.setText("PTT");
        miniPtt.setTextColor(0xffffffff);
        miniPtt.setTextSize(18);
        miniPtt.setTypeface(null, android.graphics.Typeface.BOLD);
        miniPtt.setBackgroundResource(R.drawable.ptt_background);
        miniPtt.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pttPressed();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pttReleased();
                        return true;
                }
                return false;
            }
        });
        Button addPoi = new Button(this);
        addPoi.setText("+ POI");
        addPoi.setAllCaps(false);
        addPoi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (homeMap != null) poiHere(homeMap.centerLat(), homeMap.centerLon());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (96 * d), (int) (72 * d));
        mini.addView(addPoi, new LinearLayout.LayoutParams((int) (96 * d), (int) (48 * d)));
        lp.topMargin = (int) (6 * d);
        mini.addView(miniPtt, lp);
        android.widget.FrameLayout.LayoutParams fl = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
        fl.setMargins(0, 0, (int) (8 * d), (int) (8 * d));
        holder.addView(mini, fl);
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                boolean hidden = sp.getBoolean("commanderRightHidden", false);
                right.setVisibility(hidden ? View.GONE : View.VISIBLE);
                mini.setVisibility(hidden ? View.VISIBLE : View.GONE);
                toggle.setText(hidden ? "◀" : "▶");
            }
        };
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("commanderRightHidden", !sp.getBoolean("commanderRightHidden", false)).commit();
                apply.run();
            }
        });
        apply.run();
    }

    @Override
    protected void onPause() {
        if (RadioService.locationAsk == homeLocationAsk) RadioService.locationAsk = null;
        if (RadioService.missedListener == missedOffer) RadioService.missedListener = null;
        blackTimer.removeCallbacks(goBlack);
        if (contactsPanel != null) {
            contactsPanel.stop();
            channelsPanel.stop();
        }
        if (homeMap != null) {
            tilePrompt.pause();
            myPos.stop();
            new org.communitycomms.app.geo.MapPrefs(this).saveView(homeMap.centerLat(), homeMap.centerLon(), homeMap.zoom());
        }
        super.onPause();
    }

    // ---- map home screen ----

    private boolean mapHome;
    /** Centred on my position since the app was opened (reset after a long time in the background). */
    private boolean centredOnMe;
    private long stoppedAt;
    private org.communitycomms.app.geo.TileMapView homeMap;
    /** Map controls over the home map (shown when switched on in the full map). */
    private View homePad;
    private org.communitycomms.app.geo.OnlineTilePrompt tilePrompt;
    private org.communitycomms.app.geo.MyPosition myPos;
    private int mapTicks;
    private final java.util.Map<String, android.graphics.Bitmap> thumbs = new java.util.HashMap<String, android.graphics.Bitmap>();

    private void setUpHomeMap() {
        homeMap = new org.communitycomms.app.geo.TileMapView(this, null);
        android.widget.FrameLayout mapHolder = (android.widget.FrameLayout) findViewById(R.id.map_holder);
        mapHolder.addView(homeMap, 0); // under the buttons already over the map
        // Map controls (arrows, zoom, centre on me) for a TV remote, a mouse or gloves.
        homePad = org.communitycomms.app.geo.MapPad.attach(mapHolder, homeMap, new Runnable() {
            @Override
            public void run() {
                android.location.Location me = myPos == null ? null : myPos.location();
                if (me != null) homeMap.center(me.getLatitude(), me.getLongitude(), Math.max(homeMap.zoom(), 14));
                else Toast.makeText(MainActivity.this, "No position yet", Toast.LENGTH_SHORT).show();
            }
        }, android.view.Gravity.BOTTOM | android.view.Gravity.LEFT);
        org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
        homeMap.center(mp.centerLat(), mp.centerLon(), mp.zoom());
        tilePrompt = new org.communitycomms.app.geo.OnlineTilePrompt(this, homeMap, null);
        myPos = new org.communitycomms.app.geo.MyPosition(this, new org.communitycomms.app.geo.MyPosition.Listener() {
            @Override
            public void onMyPosition(android.location.Location loc, float heading) {
                homeMap.setMe(loc, heading);
                if (loc != null && !centredOnMe) {
                    // App (re)opened: start on where I am, not where the map was left.
                    centredOnMe = true;
                    homeMap.center(loc.getLatitude(), loc.getLongitude(), Math.max(homeMap.zoom(), 14));
                }
            }
        });
        homeMap.setOnMarkerTap(new org.communitycomms.app.geo.TileMapView.OnMarkerTap() {
            @Override
            public void onMarkerTap(org.communitycomms.app.geo.TileMapView.Marker m) {
                Intent i = new Intent(MainActivity.this, MapActivity.class); // details, edit, remove
                if (m.tag instanceof org.communitycomms.app.geo.Poi) {
                    i.putExtra(MapActivity.EXTRA_POI, ((org.communitycomms.app.geo.Poi) m.tag).id);
                }
                startActivity(i);
            }
        });
        homeMap.setOnLongPress(new org.communitycomms.app.geo.TileMapView.OnLongPress() {
            @Override
            public void onLongPress(final double lat, final double lon) {
                poiHere(lat, lon);
            }
        });
    }

    /** Long-press on the home map: pick what it is, and it goes out at that spot for an hour. */
    private void poiHere(final double lat, final double lon) {
        final String[] names = org.communitycomms.app.geo.Poi.ICON_NAMES;
        IconPicker.pick(this, "Map point at " + org.communitycomms.app.geo.Geo.latLon(lat, lon), new IconPicker.Picked() {
                    @Override
                    public void onPicked(int which) {
                        String me = prefs.callsign().length() == 0 ? "?" : prefs.callsign();
                        org.communitycomms.app.geo.Poi p = org.communitycomms.app.geo.Poi.create(lat, lon, which,
                                names[which].replace("(", "").replace(")", ""), 3600000L, me);
                        boolean sent = service != null && service.sendPoi(p);
                        Toast.makeText(MainActivity.this, sent ? "Sent map point: " + names[which]
                                : "Saved on this phone. Join a channel to share it.", Toast.LENGTH_SHORT).show();
                        refreshHomeMap();
                    }
                });
    }

    private void refreshHomeMap() {
        if (homeMap == null) return;
        homeMap.setMarkers(MapActivity.markers(this, myPos == null ? null : myPos.location(), thumbs));
        if (myPos != null) homeMap.setMeProblem(myPos.problem());
    }

    @Override
    protected void onStop() {
        stoppedAt = System.currentTimeMillis();
        handler.removeCallbacks(rosterTick);
        if (service != null) service.clearUi(this); // a new copy of this screen may already be showing
        unbindService(conn);
        service = null;
        super.onStop();
    }

    // ---- lock ----

    private void renderLock() {
        boolean l = prefs.locked();
        lock.setText(l ? (mapHome ? R.string.locked_short : R.string.locked) : R.string.lock);
        lock.setTextColor(l ? 0xffffb74d : 0xffffffff);
        for (int id : SLOT_IDS) findViewById(id).setEnabled(!l);
        join.setEnabled(!l);
        invalidateOptionsMenu();
    }

    private boolean blockedByLock() {
        if (!prefs.locked()) return false;
        Toast.makeText(this, "Locked: hold the lock button to unlock", Toast.LENGTH_SHORT).show();
        return true;
    }

    // ---- quick map points ----

    private static final int[] QUICK_IDS = {R.id.quick_1, R.id.quick_2, R.id.quick_3, R.id.quick_4};
    private static final int[] QUICK_IDS_8 = {R.id.quick_1, R.id.quick_2, R.id.quick_3, R.id.quick_4,
            R.id.quick_5, R.id.quick_6, R.id.quick_7, R.id.quick_8};
    private static final int QUICK_MORE = -2;
    private int pendingQuick = -1;
    /** Items of the quick map point profile in use (Settings > Quick map points). */
    private List<org.communitycomms.app.geo.QuickProfiles.Item> quickItems = new ArrayList<org.communitycomms.app.geo.QuickProfiles.Item>();

    /**
     * Radio home: up to 4 buttons (3 + "More…" when the profile has more). Map home has room for
     * one: "Mark…", which lists the whole profile.
     */
    private void renderQuick() {
        quickItems = new org.communitycomms.app.geo.QuickProfiles(this).items();
        // Simple modes get a second row: up to 8 buttons on the radio home.
        View row2 = findViewById(R.id.quick_row2);
        boolean two = !mapHome && row2 != null && prefs.simple();
        if (row2 != null) row2.setVisibility(two ? View.VISIBLE : View.GONE);
        TextView ql = (TextView) findViewById(R.id.quick_label);
        if (ql != null) ql.setText(prefs.simple() ? "Quick buttons" : getString(R.string.quick_poi));
        int[] ids = two ? QUICK_IDS_8 : QUICK_IDS;
        boolean more = mapHome || quickItems.size() > ids.length;
        int direct = mapHome ? 0 : (more ? ids.length - 1 : quickItems.size());
        for (int i = 0; i < ids.length; i++) {
            Button b = (Button) findViewById(ids[i]);
            final int q = i < direct ? i : (i == direct && more ? QUICK_MORE : -1);
            b.setVisibility(q == -1 ? (mapHome ? View.GONE : View.INVISIBLE) : View.VISIBLE);
            boolean sharing = q >= 0 && quickItems.get(q).kind == org.communitycomms.app.geo.QuickProfiles.KIND_SHARE
                    && service != null && service.rapidShareLeftMs() > 0;
            b.setText(q == QUICK_MORE ? (mapHome ? "Mark…" : "More…") : sharing ? "Stop sharing" : q >= 0 ? quickItems.get(q).label : "");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (q == QUICK_MORE) pickQuick();
                    else if (q >= 0) quickPoi(q);
                }
            });
        }
    }

    /** The whole profile as a list with icons; the button underneath switches profile. */
    private void pickQuick() {
        final org.communitycomms.app.geo.QuickProfiles qp = new org.communitycomms.app.geo.QuickProfiles(this);
        final float d = getResources().getDisplayMetrics().density;
        android.widget.BaseAdapter a = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return quickItems.size();
            }

            @Override
            public Object getItem(int i) {
                return quickItems.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int pos, View convert, android.view.ViewGroup parent) {
                final org.communitycomms.app.geo.QuickProfiles.Item it = quickItems.get(pos);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding((int) (12 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
                View pic = new View(MainActivity.this) {
                    @Override
                    protected void onDraw(android.graphics.Canvas c) {
                        if (it.kind != org.communitycomms.app.geo.QuickProfiles.KIND_POINT) return; // not a map point
                        org.communitycomms.app.geo.PoiIcons.draw(c, it.icon, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.9f);
                    }
                };
                row.addView(pic, new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d)));
                TextView t = new TextView(MainActivity.this);
                t.setText("  " + it.label + (it.atMapCentre ? "  (at map +)" : "")
                        + (it.kind == org.communitycomms.app.geo.QuickProfiles.KIND_MESSAGE ? "  (message)" : ""));
                t.setTextSize(17);
                row.addView(t);
                return row;
            }
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("Map point: " + org.communitycomms.app.geo.QuickProfiles.NAMES[qp.profile()])
                .setAdapter(a, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int which) {
                        if (homeMap != null && !quickItems.get(which).atMapCentre
                                && quickItems.get(which).kind == org.communitycomms.app.geo.QuickProfiles.KIND_POINT) askWhere(which);
                        else quickPoi(which);
                    }
                })
                .setNeutralButton("Change profile", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        chooseProfile(MainActivity.this);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Map home: a mark mostly goes on the map's X, so ask X (the default) or my own position. */
    private void askWhere(final int q) {
        final org.communitycomms.app.geo.QuickProfiles.Item it = quickItems.get(q);
        final String[] where = {"At the X (map centre)", "At me (my GPS position)"};
        final int[] pick = {0};
        new android.app.AlertDialog.Builder(this)
                .setTitle(it.label + ": where?")
                .setSingleChoiceItems(where, 0, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        pick[0] = w;
                    }
                })
                .setPositiveButton("Mark", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        if (pick[0] == 1 || homeMap == null) quickPoi(q);
                        else sendQuick(it, homeMap.centerLat(), homeMap.centerLon());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Profile chooser; Custom opens its editor. Also used from Settings. */
    static void chooseProfile(final Activity a) {
        final org.communitycomms.app.geo.QuickProfiles qp = new org.communitycomms.app.geo.QuickProfiles(a);
        new android.app.AlertDialog.Builder(a)
                .setTitle("Quick map point profile")
                .setSingleChoiceItems(org.communitycomms.app.geo.QuickProfiles.NAMES, qp.profile(),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int which) {
                                qp.setProfile(which);
                                di.dismiss();
                                if (which == org.communitycomms.app.geo.QuickProfiles.CUSTOM) {
                                    a.startActivity(new Intent(a, QuickProfileActivity.class));
                                } else if (a instanceof MainActivity) {
                                    ((MainActivity) a).renderQuick();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One tap: a map point at my GPS position (or the map's centre for "meet me there"). */
    private void quickPoi(final int q) {
        if (q < 0 || q >= quickItems.size()) return;
        final org.communitycomms.app.geo.QuickProfiles.Item it = quickItems.get(q);
        if (it.kind == org.communitycomms.app.geo.QuickProfiles.KIND_MESSAGE) {
            String text = it.noteFor(prefs.callsign().length() == 0 ? "?" : prefs.callsign());
            boolean sent = service != null && service.messages() != null && service.engine() != null
                    && service.messages().sendText(text);
            Toast.makeText(this, sent ? "Sent: " + text : "Join a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (it.kind == org.communitycomms.app.geo.QuickProfiles.KIND_SHARE) {
            toggleShareUntilStopped();
            return;
        }
        if (it.atMapCentre) {
            double lat, lon;
            if (homeMap != null) {
                lat = homeMap.centerLat();
                lon = homeMap.centerLon();
            } else {
                org.communitycomms.app.geo.MapPrefs mp = new org.communitycomms.app.geo.MapPrefs(this);
                lat = mp.centerLat();
                lon = mp.centerLon();
            }
            sendQuick(it, lat, lon);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingQuick = q;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        Toast.makeText(this, "Getting a GPS fix…", Toast.LENGTH_SHORT).show();
        org.communitycomms.app.geo.LocationHelper.getFix(this, 15000, new org.communitycomms.app.geo.LocationHelper.Callback() {
            @Override
            public void onFix(android.location.Location l, String problem) {
                if (l == null) {
                    Toast.makeText(MainActivity.this, problem, Toast.LENGTH_LONG).show();
                    return;
                }
                sendQuick(it, l.getLatitude(), l.getLongitude());
            }
        });
    }

    private void sendQuick(org.communitycomms.app.geo.QuickProfiles.Item it, double lat, double lon) {
        String me = prefs.callsign().length() == 0 ? "?" : prefs.callsign();
        String note = it.noteFor(me);
        org.communitycomms.app.geo.Poi p = org.communitycomms.app.geo.Poi.create(lat, lon, it.icon, note, it.lifeMs, me);
        boolean sent = service != null && service.sendPoi(p);
        Toast.makeText(MainActivity.this, sent ? "Sent map point: " + note
                : "Saved on this phone. Join a channel to share it.", Toast.LENGTH_SHORT).show();
        refreshHomeMap();
    }

    // ---- rapid location share ----

    /** Someone on A or B asked everyone to share (automatic accept off): ask here, then share as usual. */
    private final RadioService.LocationAsk homeLocationAsk = new RadioService.LocationAsk() {
        @Override
        public void ask(String from) {
            ShareLocation.answerRequest(MainActivity.this, from, new Runnable() {
                @Override
                public void run() {
                    if (service != null && service.rapidShareLeftMs() > 0) return;
                    toggleRapidShare();
                }
            });
        }
    };

    private void toggleRapidShare() {
        if (service == null || service.engine() == null) {
            Toast.makeText(this, "Join a channel first (not available on OpenMANET*)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (service.rapidShareLeftMs() > 0) {
            askShareMode();
            return;
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingQuick = SHARE_AFTER_PERMISSION;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        } else if (org.communitycomms.app.geo.FakeGps.enabled(this) && !fakeAsked) {
            askFakeBeforeShare();
            return;
        } else {
            fakeAsked = false;
            service.setRapidShare(true);
            Toast.makeText(this, "Sharing your location every 30 s for 30 minutes", Toast.LENGTH_SHORT).show();
        }
        renderShare();
    }

    private boolean fakeAsked;

    /** Share location while Fake GPS is on: keep the made-up spot, or share the real one (obscured or exact). */
    private void askFakeBeforeShare() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Fake GPS is on")
                .setMessage("Others would see a made-up spot near " + org.communitycomms.app.geo.FakeGps.centre(this)
                        + ", not where you are. Turn Fake GPS off and share your real position?")
                .setPositiveButton("Share real position", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        org.communitycomms.app.geo.FakeGps.set(MainActivity.this, false,
                                org.communitycomms.app.geo.FakeGps.centre(MainActivity.this));
                        askObscureBeforeShare();
                    }
                })
                .setNeutralButton("Keep fake", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        fakeAsked = true;
                        toggleRapidShare();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askObscureBeforeShare() {
        final int m = org.communitycomms.app.geo.FakeGps.obscureM(this);
        final int keep = m > 0 ? m : org.communitycomms.app.geo.FakeGps.OBSCURE_DEFAULT_M;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Obscure your position?")
                .setMessage("Share a spot up to " + org.communitycomms.app.geo.Geo.range(keep) + " from you (the same offset "
                        + "each time, shown as approximate), or your exact position? Change the range in Settings > "
                        + "Mission and data > Obscure location.")
                .setPositiveButton("Obscure (" + org.communitycomms.app.geo.Geo.range(keep) + ")",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                org.communitycomms.app.geo.FakeGps.setObscureM(MainActivity.this, keep);
                                fakeAsked = true;
                                toggleRapidShare();
                            }
                        })
                .setNeutralButton("Exact", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        org.communitycomms.app.geo.FakeGps.setObscureM(MainActivity.this, 0);
                        fakeAsked = true;
                        toggleRapidShare();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Already sharing: stop, or keep sharing for 2 hours, until the app restarts, or always. */
    private void askShareMode() {
        final String[] names = {"Stop sharing", "Share for 2 hours", "Share until the app restarts", "Always share (every time the app runs)"};
        final int[] modes = {RadioService.SHARE_STOP, RadioService.SHARE_2H, RadioService.SHARE_UNTIL_RESTART, RadioService.SHARE_ALWAYS};
        final String[] said = {"Stopped sharing location", "Sharing your location for 2 hours",
                "Sharing your location until the app restarts", "Always sharing your location (tap Share location to change)"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Sharing your location")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (service == null) return;
                        service.setRapidShareMode(modes[which]);
                        Toast.makeText(MainActivity.this, said[which], Toast.LENGTH_SHORT).show();
                        renderShare();
                        renderQuick();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Share location until stop" (4WD, convoy): like the 30-minute share, but until tapped again. */
    private void toggleShareUntilStopped() {
        if (service == null || service.engine() == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (service.rapidShareLeftMs() > 0) {
            service.setRapidShare(false);
            Toast.makeText(this, "Stopped sharing location", Toast.LENGTH_SHORT).show();
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        } else {
            service.setRapidShareUntilStopped();
            Toast.makeText(this, "Sharing your location every 30 s until you tap it again", Toast.LENGTH_LONG).show();
        }
        renderShare();
        renderQuick();
    }

    private static final int SHARE_AFTER_PERMISSION = 100;

    private void renderShare() {
        Button b = (Button) findViewById(R.id.share_loc);
        long left = service == null ? 0 : service.rapidShareLeftMs();
        if (left > 24 * 3600000L) { // "until stop" (4WD, convoy), until restart, or always
            String how = service.rapidShareAlways() ? "always" : service.rapidShareUntilRestart() ? "until restart" : "on";
            b.setText(mapHome ? "Sharing" : "Sharing location: " + how + " (tap for options)");
            b.setTextColor(0xffa5d6a7);
        } else if (left > 0) {
            long min = (left + 59999) / 60000;
            b.setText(mapHome ? "Sharing " + min + "m" : "Sharing: " + (min > 90 ? (min + 30) / 60 + " h" : min + " min") + " left (tap for options)");
            b.setTextColor(0xffa5d6a7);
        } else {
            b.setText(mapHome ? R.string.share_loc_short : R.string.share_loc);
            b.setTextColor(0xffffffff);
        }
    }

    /** The four channel buttons; which channel each shows is set by the eyes on the Channels page. */
    private static final int[] SLOT_IDS = {R.id.slot_a, R.id.slot_b, R.id.slot_public, R.id.slot_openmanet};
    /** Channel shown on each of the buttons above (homeSlots[i] on SLOT_IDS[i]). */
    private int[] homeSlots = new int[0];
    private boolean quietCheck;
    private RadioGroup slotGroup;
    private int slot;
    private String lastStatus = "";

    /** "Channel A", "Channel D", "Public", "OpenMANET (experimental)". */
    static String label(int slot) {
        return slot == Prefs.SLOT_PUBLIC ? "Public" : slot == Prefs.SLOT_OPENMANET ? "OpenMANET (experimental)"
                : "Channel " + Prefs.letter(slot);
    }

    /** Ticks the button of the channel on air, if it is on the home screen, without switching anything. */
    private void showChecked() {
        quietCheck = true;
        int id = -1;
        for (int i = 0; i < homeSlots.length; i++) if (homeSlots[i] == slot) id = SLOT_IDS[i];
        if (id == -1) slotGroup.clearCheck();
        else slotGroup.check(id);
        quietCheck = false;
    }

    /** Channel switch from code (wizard, Channels page): same as tapping its button. */
    private void selectSlot(int s) {
        switchSlot(s);
        showChecked();
    }

    /** A/B/Public switch. While on air it re-joins straight away, like flipping a radio's A/B. */
    private void switchSlot(int newSlot) {
        if (newSlot == slot) return;
        slot = newSlot;
        prefs.setActiveSlot(slot);
        status.setText(label(slot) + " · " + lastStatus);
        if (prefs.channel(slot).length() == 0) {
            Toast.makeText(this, label(slot) + " has no channel name yet: set it on the Channels page", Toast.LENGTH_LONG).show();
            if (joined && service != null) service.leave();
            return;
        }
        if (joined && service != null) {
            setTransmit(false);
            doJoin();
        }
    }

    /**
     * Opens the OpenMANET node's own web UI (its Comms page talks to OpenMANET/ATAK Vx voice).
     * The node is usually the WiFi gateway; the address can be edited.
     */
    @SuppressWarnings("deprecation")
    private void openOpenManet() {
        String guess = "";
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wm.getDhcpInfo() != null && wm.getDhcpInfo().gateway != 0) {
            int g = wm.getDhcpInfo().gateway;
            guess = (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        }
        final EditText host = new EditText(this);
        host.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("openmanetHost", guess));
        host.setHint("Node IP, e.g. 10.41.0.1");
        new android.app.AlertDialog.Builder(this)
                .setTitle("OpenMANET node")
                .setMessage("Comms (push-to-talk) needs HTTPS on port 8081 and a modern browser. "
                        + "Node management is on port 8080.")
                .setView(host)
                .setPositiveButton("Comms", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        browse("https://" + host.getText().toString().trim() + ":8081/");
                    }
                })
                .setNeutralButton("Manage", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        browse("http://" + host.getText().toString().trim() + ":8080/");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void browse(String url) {
        String h = android.net.Uri.parse(url).getHost();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("openmanetHost", h == null ? "" : h).commit();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No web browser on this phone", Toast.LENGTH_LONG).show();
        }
    }

    /** Channel QR payload: UBC1|<slot letter>|<channel name>. */
    public static final String CHANNEL_QR_PREFIX = "UBC1|";

    private static final int MENU_DRYAD = 1, MENU_PIGPEN = 2, MENU_CHANNEL_QR = 3, MENU_SCAN = 4, MENU_FILES = 5,
            MENU_OPENMANET = 6, MENU_BLACKOUT = 7, MENU_RED = 8, MENU_SETTINGS = 9, MENU_CHAT = 10, MENU_AUDIO = 11, MENU_ABOUT = 12, MENU_EXIT = 13,
            MENU_STREAMS = 14, MENU_INTERCOM = 15, MENU_VIDEOS = 16, MENU_TV = 17, MENU_GATEWAY = 18,
            MENU_HOME_TOGGLE = 19, MENU_TOP_CHAT = 20, MENU_TOP_BLACK = 21, MENU_NOMAD = 22, MENU_TV_MENU = 23;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Channel QR and Scan QR live in Settings (under the channel names) to keep this menu short.
        menu.add(0, MENU_CHAT, 0, R.string.chat);
        menu.add(0, MENU_AUDIO, 1, "Audio");
        menu.add(0, MENU_SETTINGS, 9, R.string.settings); // just above Exit
        menu.add(0, MENU_FILES, 2, "Data / files");
        menu.add(0, MENU_DRYAD, 3, R.string.dryad); // Pigpen is a button on the DRYAD screen
        menu.add(0, MENU_OPENMANET, 6, "OpenMANET node (browser)");
        // About is reached from Settings.
        menu.add(0, MENU_BLACKOUT, 7, "Blackout screen");
        menu.add(0, MENU_RED, 8, "Red light mode").setCheckable(true).setChecked(LightDiscipline.redMode(this));
        menu.add(0, MENU_STREAMS, 6, "Radio streams (internet)");
        menu.add(0, MENU_INTERCOM, 0, "Intercom screen");
        // Advanced home: quick icons in the top bar (beside ▲ and the dots).
        menu.add(0, MENU_HOME_TOGGLE, 0, "Map / buttons home").setIcon(mapHome
                ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_menu_mapmode)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_TOP_CHAT, 0, "Chat").setIcon(android.R.drawable.sym_action_chat)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_TOP_BLACK, 0, "Blackout (double-tap to wake)").setIcon(android.R.drawable.ic_lock_power_off)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_VIDEOS, 5, "Tactical videos"); // TV mode is in Settings (and in the title bar on a TV)
        if (TvActivity.isTv(this)) {
            menu.add(0, MENU_TV, 0, "TV mode").setIcon(android.R.drawable.ic_menu_slideshow)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            // TVs often report a menu key and so get no overflow dots: a visible Menu button opens the list.
            menu.add(0, MENU_TV_MENU, 0, "Menu").setIcon(android.R.drawable.ic_menu_more)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        }
        menu.add(0, MENU_NOMAD, 5, "Project NOMAD (local AI, library)");
        menu.add(0, MENU_GATEWAY, 6, "OpenMANET internet gateway");
        menu.add(0, MENU_EXIT, 10, "Exit (go off air and close)");
        return true;
    }

    private Menu lastMenu;

    /** The whole menu as a list (TVs, where the overflow dots may not show). */
    private void showMenuList() {
        final Menu m = lastMenu;
        if (m == null) return;
        onPrepareOptionsMenu(m);
        final java.util.List<MenuItem> items = new java.util.ArrayList<MenuItem>();
        for (int i = 0; i < m.size(); i++) {
            MenuItem it = m.getItem(i);
            if (it.isVisible() && it.isEnabled() && it.getItemId() != MENU_TV_MENU) items.add(it);
        }
        java.util.Collections.sort(items, new java.util.Comparator<MenuItem>() {
            @Override
            public int compare(MenuItem a, MenuItem b) {
                return a.getOrder() - b.getOrder();
            }
        });
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = String.valueOf(items.get(i).getTitle());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        onOptionsItemSelected(items.get(which));
                    }
                })
                .show();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        lastMenu = menu;
        boolean l = prefs.locked();
        // Locked: nothing that changes setup (settings, channels, PIN) is reachable.
        for (int id : new int[]{MENU_SETTINGS, MENU_DRYAD, MENU_CHANNEL_QR, MENU_SCAN, MENU_OPENMANET, MENU_GATEWAY}) {
            MenuItem it = menu.findItem(id);
            if (it != null) it.setEnabled(!l);
        }
        // Simple modes (Scouts, Staff, 4WD…): no DRYAD, Pigpen or OpenMANET.
        for (int id : new int[]{MENU_DRYAD, MENU_PIGPEN, MENU_OPENMANET, MENU_GATEWAY, MENU_NOMAD}) {
            MenuItem it = menu.findItem(id);
            if (it != null) it.setVisible(!prefs.simple());
        }
        // OpenMANET menus only once OpenMANET support is switched on (Settings).
        for (int id : new int[]{MENU_OPENMANET, MENU_GATEWAY}) {
            MenuItem it = menu.findItem(id);
            if (it != null && !prefs.showOpenManet()) it.setVisible(false);
        }
        // Top bar icons: Advanced mode only; the tablet commander view is always the map.
        boolean adv = prefs.appMode() == Prefs.MODE_ADVANCED;
        MenuItem ht = menu.findItem(MENU_HOME_TOGGLE);
        if (ht != null) ht.setVisible(adv && !commander());
        for (int id : new int[]{MENU_TOP_CHAT, MENU_TOP_BLACK}) {
            MenuItem it = menu.findItem(id);
            if (it != null) it.setVisible(adv);
        }
        MenuItem st = menu.findItem(MENU_STREAMS);
        if (st != null) st.setVisible(prefs.streamsEnabled()); // Settings: Internet audio
        MenuItem ic = menu.findItem(MENU_INTERCOM);
        if (ic != null) ic.setVisible(prefs.appMode() == Prefs.MODE_INTERCOM);
        return super.onPrepareOptionsMenu(menu);
    }

    // ---- black screen after a time without a touch (Settings) ----

    private final android.os.Handler blackTimer = new android.os.Handler();
    private final Runnable goBlack = new Runnable() {
        @Override
        public void run() {
            if (hasWindowFocus()) LightDiscipline.blackout(MainActivity.this); // never under a dialog, it would block the double tap
            else restartBlackTimer();
        }
    };

    private void restartBlackTimer() {
        blackTimer.removeCallbacks(goBlack);
        int s = prefs.blackAfterSec();
        if (s > 0) blackTimer.postDelayed(goBlack, s * 1000L);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        wokeForVoice = false;
        restartBlackTimer();
    }

    /** Exit: off air, background service stopped, every screen closed. Rejoin on start still applies next time. */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("Exit")
                .setMessage("Go off air and close the app? Nothing will be heard or received until it is opened again.")
                .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        RadioService s = RadioService.instance();
                        boolean onAir = s != null && (s.engine() != null || s.compat() != null);
                        prefs.setExitSlots(onAir ? s.slotsOnAir() : "");
                        startupRejoinDone = false; // the next open asks, even if Android keeps this process
                        if (s != null) s.stopEverything();
                        finishAffinity();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opened again after Exit: which channels it was on, and their remote state, then reconnect or stay off air. */
    private void askReconnect() {
        String[] slots = prefs.exitSlots().split(",");
        prefs.setExitSlots("");
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            int s;
            try {
                s = Integer.parseInt(slots[i].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            list.append(label(s)).append(i == 0 ? " (talking)" : " (listening)");
            if (prefs.tunnelSetUp() && s != Prefs.SLOT_OPENMANET) {
                list.append(": remote ").append(prefs.tunnelClientOn() || prefs.tunnelSlot(s) ? "on" : "off");
            }
            list.append('\n');
        }
        new AlertDialog.Builder(this).setTitle("Reconnect last channels?").setMessage(list.toString().trim())
                .setCancelable(false)
                .setPositiveButton("Reconnect", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        requestJoin();
                    }
                })
                .setNegativeButton("Stay off air", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.setWasOnAir(false);
                    }
                }).show();
    }

    /**
     * Home network set up (Settings > LAN storage) and the tunnel for every channel: on reaching home with it
     * on, or another WiFi with it off, asks once whether to change it. Never over a dialog or the black screen.
     */
    private void checkRemoteNetwork() {
        if (PowerMode.offline() || !prefs.tunnelSetUp() || !LanSync.homeEnabled(this) || LanSync.homeSsid(this).length() == 0) return;
        if (!hasWindowFocus() || LightDiscipline.blackedOut(this)) return;
        String now = LanSync.currentSsid(this);
        if (now.length() == 0) return;
        final boolean home = LanSync.atHome(this);
        String where = home ? "home" : now;
        if (where.equals(prefs.remoteNetSeen())) return;
        prefs.setRemoteNetSeen(where);
        if (home != prefs.tunnelClientOn()) return; // already right for this network
        new AlertDialog.Builder(this)
                .setMessage(home ? "You are on your home network, do you want to disable remote?"
                        : "You appear away from your home network, enable remote?")
                .setPositiveButton(home ? "Disable remote" : "Enable remote", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.setTunnelClientOn(!home);
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.applyTunnelNow();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Asks once as the battery passes each level set in Power and sleep. Never in Off line or on charge. */
    private void checkBatteryAsk() {
        int pct = RadioEngine.myBattery, saver = prefs.powerAskSaver(), ultra = prefs.powerAskUltra();
        if (pct < 0 || PowerMode.charging || pct > Math.max(saver, ultra) + 5) {
            if (prefs.powerAsked() != 0) prefs.setPowerAsked(0);
            return;
        }
        if (PowerMode.offline() || !hasWindowFocus() || LightDiscipline.blackedOut(this)) return;
        final int want = ultra > 0 && pct <= ultra ? PowerMode.ULTRA : saver > 0 && pct <= saver ? PowerMode.SAVER : PowerMode.NORMAL;
        if (want <= PowerMode.mode() || want <= prefs.powerAsked()) return;
        prefs.setPowerAsked(want);
        new AlertDialog.Builder(this).setTitle("Battery " + pct + "%")
                .setMessage(want == PowerMode.ULTRA ? "Switch to Ultra low? With the screen off the radio sleeps and checks in every 4 minutes, "
                        + "so live voice is missed until you use it, and a sound plays for missed messages."
                        : "Switch to Power saver? Slower check-ins and GPS and no background data. Live voice still works.")
                .setPositiveButton("Switch", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        PowerMode.set(MainActivity.this, want);
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                })
                .setNegativeButton("Not now", null).show();
    }

    private boolean wokeForVoice;

    /** Power and sleep: the black screen comes back while someone is heard, and goes black again after. */
    private void checkBlackWake() {
        RadioService s = RadioService.instance();
        if (s == null || !s.blackWakes()) return;
        if (shownTalker != null && LightDiscipline.blackedOut(this)) {
            LightDiscipline.endBlackout(this);
            wokeForVoice = true;
        } else if (shownTalker == null && wokeForVoice) {
            wokeForVoice = false;
            LightDiscipline.blackout(this);
        }
    }

    private void showChannelQr() {
        showChannelQr(this, slot);
    }
    /** Shows the QR that carries a channel's name (the passcode). Also used from Settings. */
    static void showChannelQr(Activity a, int slot) {
        String name = new Prefs(a).channel(slot);
        if (name.length() == 0) {
            Toast.makeText(a, "Set a channel name in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        String letter = slot == Prefs.SLOT_B ? "B" : slot == Prefs.SLOT_PUBLIC ? "P" : "A";
        ArrayList<String> p = new ArrayList<String>();
        p.add(CHANNEL_QR_PREFIX + letter + "|" + name);
        ArrayList<String> t = new ArrayList<String>();
        t.add(label(slot) + " (this QR is the passcode)");
        QrShowActivity.start(a, p, t, null);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if ((id == MENU_SETTINGS || id == MENU_DRYAD || id == MENU_CHANNEL_QR || id == MENU_SCAN || id == MENU_OPENMANET)
                && blockedByLock()) {
            return true;
        }
        if (id == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == MENU_ABOUT) {
            startActivity(new Intent(this, AboutActivity.class));
        } else if (id == MENU_EXIT) {
            confirmExit();
        } else if (id == MENU_STREAMS) {
            startActivity(new Intent(this, StreamsActivity.class));
        } else if (id == MENU_NOMAD) {
            startActivity(new Intent(this, NomadActivity.class));
        } else if (id == MENU_HOME_TOGGLE) {
            prefs.setHomeMap(!mapHome);
            recreate();
        } else if (id == MENU_TOP_CHAT) {
            startActivity(new Intent(this, ChatActivity.class));
        } else if (id == MENU_TOP_BLACK) {
            if (LightDiscipline.legacyBlackout(this)) Toast.makeText(this, "Double-tap to wake", Toast.LENGTH_SHORT).show();
            LightDiscipline.blackout(this);
        } else if (id == MENU_VIDEOS) {
            startActivity(new Intent(this, TacticalVideoActivity.class));
        } else if (id == MENU_TV_MENU) {
            showMenuList();
        } else if (id == MENU_TV) {
            startActivity(new Intent(this, TvActivity.class));
        } else if (id == MENU_GATEWAY) {
            startActivity(new Intent(this, GatewayActivity.class));
        } else if (id == MENU_INTERCOM) {
            IntercomActivity.showFullRadio = false;
            startActivity(new Intent(this, IntercomActivity.class));
        } else if (id == MENU_AUDIO) {
            startActivity(new Intent(this, AudioActivity.class));
        } else if (id == MENU_CHAT) {
            startActivity(new Intent(this, ChatActivity.class));
        } else if (id == MENU_CHANNEL_QR) {
            PinLock.require(this, "Enter your PIN to show the channel QR code.", new PinLock.Done() {
                @Override
                public void onUnlocked() {
                    showChannelQr();
                }
            }, null);
        } else if (id == MENU_BLACKOUT) {
            LightDiscipline.blackout(this);
        } else if (id == MENU_RED) {
            boolean on = !LightDiscipline.redMode(this);
            LightDiscipline.setRedMode(this, on);
            item.setChecked(on);
        } else if (id == MENU_OPENMANET) {
            openOpenManet();
        } else if (id == MENU_FILES) {
            startActivity(new Intent(this, FilesActivity.class));
        } else if (id == MENU_SCAN) {
            startActivity(new Intent(this, QrScanActivity.class));
        } else if (id == MENU_DRYAD) {
            startActivity(new Intent(this, DryadActivity.class));
        } else if (id == MENU_PIGPEN) {
            startActivity(new Intent(this, PigpenActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ---- PTT ----

    // ---- call and radio silent ----

    /** Call asks first (so it is never rung by accident): everyone, one person on the channel, or cancel. */
    private void callEveryone() {
        if (prefs.radioSilent()) {
            Toast.makeText(this, "Radio silent: call not sent", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioEngine e = engine();
        if (e == null) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> who = new ArrayList<String>();
        long now = System.currentTimeMillis();
        for (RadioEngine.Station s : e.stations()) {
            if (now - s.lastSeenMs < Contacts.ONLINE_MS && !who.contains(s.name)) who.add(s.name);
        }
        Collections.sort(who, String.CASE_INSENSITIVE_ORDER);
        final List<String> items = new ArrayList<String>();
        items.add("Everyone on " + label(slot));
        for (String n : who) items.add(n);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Call (rings their phone)")
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String target = which == 0 ? null : who.get(which - 1);
                        if (service == null || !service.sendCall(target)) {
                            Toast.makeText(MainActivity.this, "Not sent: join a channel first", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, target == null ? "Calling everyone on " + label(slot)
                                    : "Calling " + target, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleRadioSilent() {
        if (!prefs.radioSilent()) {
            setTransmit(false);
            prefs.setRadioSilent(true);
            renderSilent();
            Toast.makeText(this, "Radio silent: receive only, nothing is sent", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("End radio silence?")
                .setMessage("The phone will transmit again: voice, messages, positions, acks and catch-up sync.")
                .setPositiveButton("Transmit again", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setRadioSilent(false);
                        renderSilent();
                        if (ptt.isEnabled()) onTransmitChanged(false); // restore the PTT label
                    }
                })
                .setNegativeButton("Stay silent", null)
                .show();
    }

    // ---- settings from another phone ----

    private void checkPendingConfig() {
        java.io.File f = ConfigShare.pending;
        if (f == null) return;
        ConfigShare.pending = null;
        ConfigShare.offerApply(this, f, ConfigShare.pendingFrom, new Runnable() {
            @Override
            public void run() {
                // New channels / options: rebuild the screen; onResume re-joins with the new settings.
                slot = prefs.activeSlot();
                recreate();
            }
        });
    }

    // ---- which channels have a button on the home screen (the eyes on the Channels page) ----

    private void renderSlots() {
        // Simple modes: just the private channel (A) and Public.
        homeSlots = !prefs.simple() ? prefs.homeSlots()
                : prefs.showPublic() ? new int[]{Prefs.SLOT_A, Prefs.SLOT_PUBLIC} : new int[]{Prefs.SLOT_A};
        for (int i = 0; i < SLOT_IDS.length; i++) {
            android.widget.RadioButton rb = (android.widget.RadioButton) findViewById(SLOT_IDS[i]);
            if (i >= homeSlots.length) {
                rb.setVisibility(View.GONE);
                continue;
            }
            int s = homeSlots[i];
            rb.setVisibility(View.VISIBLE);
            rb.setText(s == Prefs.SLOT_PUBLIC ? (mapHome ? "Pub" : "Public")
                    : s == Prefs.SLOT_OPENMANET ? (mapHome ? "OM" : "OpenMANET*")
                    : s == Prefs.SLOT_A && prefs.simple() ? "Private" : Prefs.letter(s));
        }
        showChecked(); // the channel on air may have no button now: then none is ticked
        shownWatch = "";
    }

    // ---- first start: name, public key, first DRYAD sheet ----

    private void firstStartWizard() {
        if (prefs.setupDone()) return;
        android.content.SharedPreferences wz = getSharedPreferences("settings", MODE_PRIVATE);
        boolean rerun = wz.getBoolean("rerunWizard", false); // Settings > Run the setup wizard again
        if (rerun) wz.edit().putBoolean("rerunWizard", false).commit();
        if (!rerun && prefs.channel(Prefs.SLOT_A).length() > 0) { // already set up before the wizard existed
            prefs.setSetupDone();
            return;
        }
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(prefs.callsign());
        name.setSelectAllOnFocus(true);
        // Units below the callsign: metric by default.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name);
        final android.widget.RadioGroup units = new android.widget.RadioGroup(this);
        units.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.RadioButton metric = new android.widget.RadioButton(this), imperial = new android.widget.RadioButton(this);
        metric.setText("Metric (m, km)");
        imperial.setText("Imperial (ft, mi)");
        units.addView(metric);
        units.addView(imperial);
        metric.setId(1);
        imperial.setId(2);
        units.check(org.communitycomms.app.geo.Geo.imperial ? 2 : 1);
        box.addView(units);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Welcome: your callsign")
                .setMessage("The name others see when you talk or send, and the units for distances. You can change "
                        + "both later in Settings.")
                .setView(pad(box))
                .setCancelable(false)
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.length() > 0) prefs.setCallsign(n);
                        org.communitycomms.app.geo.Geo.setImperial(MainActivity.this, units.getCheckedRadioButtonId() == 2);
                        wizardQuickSet();
                    }
                })
                .show();
    }

    /** A phone setup QR code (LAN storage admin page, or another phone) sets everything at once. */
    private void wizardQuickSet() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Quick set from a QR code")
                .setMessage("Has your group given you a phone setup QR code? It is on the LAN storage's admin page, or "
                        + "another phone shows it (Settings > Phone setup QR code). Scanning it sets the LAN storage, "
                        + "channels, tunnel, OpenMANET, home WiFi and your group's defaults in one go.")
                .setCancelable(false)
                .setPositiveButton("Scan QR code", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        startActivityForResult(new android.content.Intent(MainActivity.this, QrScanActivity.class)
                                .putExtra(QrScanActivity.EXTRA_SETUP_ONLY, true), REQ_SETUP_QR);
                    }
                })
                .setNegativeButton("Skip quick set", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        wizardAboutModes();
                    }
                })
                .show();
    }

    /** Before the mode choice: what DRYAD sheets are for, and what Simple modes give up. */
    private void wizardAboutModes() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Advanced or simple")
                .setMessage("Advanced modes revolve around what is known as a DRYAD sheet, used for encryption, for "
                        + "securing digital and physical messages, and for channel names. Once a sheet is made, the app "
                        + "shares it simply between trusted devices.\n\nSimple modes have fixed keys, which you set next, "
                        + "and are for those not wanting all the extra complexity that security needs.")
                .setCancelable(false)
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        wizardMode();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SETUP_QR) return;
        if (res == RESULT_OK && prefs.setupDone()) {
            recreate(); // the new channels and settings show at once
        } else {
            wizardQuickSet(); // came back without a code: ask again (or skip)
        }
    }

    /**
     * End of setup (once, after the wizard and the Permissions screen): offer the black screen, which
     * keeps the app in front, because a closed or backgrounded app may miss live messages.
     */
    /**
     * Commander view: "X has been offline for Y min and missed N messages on B. Send?" for 10 s.
     * Send puts everything since then on the channel again; others drop what they already have.
     */
    private final RadioService.MissedListener missedOffer = new RadioService.MissedListener() {
        @Override
        public void onMissedWhileOffline(final String name, final int minutes, final int count, final String channel,
                                         final long since) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || (missedAsk != null && missedAsk.isShowing())) return;
                    missedAsk = new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(name + " is back")
                            .setMessage(name + " has been offline for " + Math.max(1, minutes) + " min and missed " + count
                                    + " message" + (count == 1 ? "" : "s") + " on " + channel + ". Send?")
                            .setPositiveButton("Send", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    RadioService rs = service;
                                    if (rs != null) rs.sendMissedSince(since);
                                    Toast.makeText(MainActivity.this, "Sending what " + name + " missed", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNeutralButton("Don't ask again", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    prefs.setOfferMissed(false);
                                    Toast.makeText(MainActivity.this, "Turned off (Settings, below Record all voice)",
                                            Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("Not now", null)
                            .show();
                    final android.app.AlertDialog shown = missedAsk;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (shown.isShowing()) shown.dismiss(); // gone by itself after 10 s
                        }
                    }, 10000);
                }
            });
        }
    };
    private android.app.AlertDialog missedAsk;

    /** The setup question on screen (one at a time). */
    private android.app.AlertDialog setupAsk;

    private void askBlackScreenOnce() {
        if (setupAsk != null && setupAsk.isShowing()) return;
        final android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (!prefs.setupDone() || !sp.getBoolean("permissionsShown", false) || prefs.appMode() == Prefs.MODE_INTERCOM) {
            return;
        }
        if (sp.getBoolean("blackPromptShown", false)) {
            askRecordAllOnce();
            return;
        }
        sp.edit().putBoolean("blackPromptShown", true).commit();
        setupAsk = new android.app.AlertDialog.Builder(this)
                .setTitle("Keep the app on screen (black screen)")
                .setMessage("For live voice and messages, leave this app open on screen. The black screen turns the "
                        + "display black with the backlight off while the app stays in front and on air; double-tap "
                        + "to wake it.\n\nIf the app is closed, or another app is in front, Android may pause it and "
                        + "live voice and messages might not arrive reliably. When you come back, the app asks the "
                        + "others for what was sent meanwhile (messages, reports, map points, files) and shows it "
                        + "unread in Chat; live voice that was missed cannot be fetched.\n\nUse the black screen?")
                .setPositiveButton("After 1 min without a touch", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setBlackAfterSec(60);
                        prefs.setBlackScreenButton(true);
                        Toast.makeText(MainActivity.this, "Black screen after 1 min; double-tap to wake. Change it in Settings.",
                                Toast.LENGTH_LONG).show();
                        recreate();
                    }
                })
                .setNeutralButton("Just a button", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setBlackScreenButton(true);
                        recreate();
                    }
                })
                .setNegativeButton("Not now", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        askRecordAllOnce();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** Setup, after the black screen: Record all voice (on unless unticked), the same as the Settings option. */
    private void askRecordAllOnce() {
        final android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (sp.getBoolean("recordPromptShown", false) || (setupAsk != null && setupAsk.isShowing())) return;
        sp.edit().putBoolean("recordPromptShown", true).commit();
        final android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText(R.string.record_all);
        box.setChecked(true);
        // With Record all voice: also catch up on missed messages when coming back (Settings, below it).
        final android.widget.CheckBox missed = new android.widget.CheckBox(this);
        missed.setText("Request catch-ups (missed messages and voice) on my channels, not Public. Each channel has this tick on the Channels page.");
        missed.setChecked(true);
        box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                missed.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });
        android.widget.LinearLayout boxes = new android.widget.LinearLayout(this);
        boxes.setOrientation(android.widget.LinearLayout.VERTICAL);
        boxes.addView(box);
        boxes.addView(missed);
        setupAsk = new android.app.AlertDialog.Builder(this)
                .setTitle("Record all voice")
                .setMessage("Saves every transmission on your channels (heard and sent) to Chat as a recording, so "
                        + "you can play back anything you missed, for example while the app was in the background. "
                        + "About 1 MB a minute; Clear mission data removes them.\n\nThis sets the option "
                        + "Settings > \"Record all voice\", where you can change it at any time.")
                .setView(pad(boxes))
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setRecordAllVoice(box.isChecked());
                        if (box.isChecked()) {
                            for (int s : Prefs.order()) {
                                if (s != Prefs.SLOT_PUBLIC && s != Prefs.SLOT_OPENMANET) prefs.setChannelCatchUp(s, missed.isChecked());
                            }
                        }
                        Toast.makeText(MainActivity.this, box.isChecked() ? "Recording all voice (Settings to change)"
                                : "Not recording all voice (Settings to change)", Toast.LENGTH_SHORT).show();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** How the app will be used: the full radio, the wall intercom, or a simple activity mode. */
    private void wizardMode() {
        final int[] pick = {prefs.appMode()};
        new android.app.AlertDialog.Builder(this)
                .setTitle("How will you use it?")
                .setCancelable(false)
                .setSingleChoiceItems(Prefs.MODE_NAMES, pick[0], new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        pick[0] = which;
                    }
                })
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        applyMode(MainActivity.this, pick[0]);
                        if (pick[0] == Prefs.MODE_ADVANCED) wizardPublicKey();
                        else wizardPrivateKey();
                    }
                })
                .show();
    }

    /** Sets the mode and what goes with it (quick buttons, recording for the intercom). Also from Settings. */
    static void applyMode(Activity a, int mode) {
        Prefs p = new Prefs(a);
        p.setAppMode(mode);
        org.communitycomms.app.geo.QuickProfiles qp = new org.communitycomms.app.geo.QuickProfiles(a);
        if (mode >= Prefs.MODE_SCOUTS) qp.setProfile(org.communitycomms.app.geo.QuickProfiles.SCOUTS + mode - Prefs.MODE_SCOUTS);
        else if (mode == Prefs.MODE_INTERCOM) qp.setProfile(org.communitycomms.app.geo.QuickProfiles.FRIENDS);
        if (mode == Prefs.MODE_INTERCOM) p.setRecordAllVoice(true); // tap History to hear any call again
        if (mode >= Prefs.MODE_SCOUTS) p.setShowSlots(p.showPublic(), false); // simple modes: OpenMANET off
        IntercomActivity.showFullRadio = false;
    }

    /** Simple modes and the intercom: one private key (channel A) everyone in the group types or scans. */
    private void wizardPrivateKey() {
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("e.g. three or four random words");
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Your group's private key")
                .setMessage("Everyone in your group types the same private key (or scans its QR code: Settings > QR of A). "
                        + "It is the password for your private channel: make it long and hard to guess. "
                        + "Make one picks a strong random key.")
                .setView(pad(key))
                .setCancelable(false)
                .setPositiveButton("Next", null)
                .setNeutralButton("Make one", null)
                .create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        key.setText(randomKey());
                    }
                });
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String k = key.getText().toString().trim();
                        if (k.length() < 8) {
                            Toast.makeText(MainActivity.this, "At least 8 characters (longer is safer)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        prefs.setChannel(Prefs.SLOT_A, k);
                        dlg.dismiss();
                        if (prefs.appMode() == Prefs.MODE_INTERCOM) wizardIntercomPin();
                        else wizardSimplePublic();
                    }
                });
            }
        });
        dlg.show();
    }

    /** Intercom units sit in public places: a settings PIN keeps passers-by out (never wipes on wrong guesses). */
    private void wizardIntercomPin() {
        final EditText p1 = new EditText(this), p2 = new EditText(this);
        for (EditText e : new EditText[]{p1, p2}) {
            e.setSingleLine(true);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }
        p1.setHint("PIN (4 or more digits)");
        p2.setHint("PIN again");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(p1);
        box.addView(p2);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Settings PIN")
                .setMessage("Set a PIN so only you can change settings or leave the intercom screen. "
                        + "Wrong guesses never erase anything on an intercom.")
                .setView(pad(box))
                .setCancelable(false)
                .setPositiveButton("Next", null)
                .setNeutralButton("No PIN", null)
                .create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dlg.dismiss();
                        wizardSimplePublic();
                    }
                });
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String a = p1.getText().toString(), b = p2.getText().toString();
                        if (a.length() < 4) {
                            Toast.makeText(MainActivity.this, "At least 4 digits", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!a.equals(b)) {
                            Toast.makeText(MainActivity.this, "The two PINs differ", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        PinLock.set(MainActivity.this, a);
                        prefs.setWipeOnBadPin(false);
                        dlg.dismiss();
                        wizardSimplePublic();
                    }
                });
            }
        });
        dlg.show();
    }

    private static String randomKey() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 5 == 0) sb.append('-');
            sb.append(abc.charAt(r.nextInt(abc.length())));
        }
        return sb.toString();
    }

    /** Simple modes: the public key, then straight onto the private channel. */
    private void wizardSimplePublic() {
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setText(prefs.publicKey());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Public key")
                .setMessage("For meeting other groups on the Public channel. Keep the default unless told otherwise.")
                .setView(pad(key))
                .setCancelable(false)
                .setPositiveButton("Done", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setPublicKey(key.getText().toString());
                        prefs.setSetupDone();
                        renderQuick();
                        selectSlot(Prefs.SLOT_A);
                        boolean perms = showPermissionsOnce();
                        if (!perms) requestJoin();
                        else joinWhenBack = true; // after the permissions screen
                        // Intercom mode: up now, or by onResume once the permissions screen is closed.
                        if (prefs.appMode() == Prefs.MODE_INTERCOM && !perms) {
                            startActivity(new Intent(MainActivity.this, IntercomActivity.class));
                        }
                    }
                })
                .show();
    }

    private void wizardPublicKey() {
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setText(prefs.publicKey());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Public channel key")
                .setMessage("Everyone using the same key shares the Public channel. Keep the default unless your "
                        + "group uses its own. Public is not private: anyone with the key can listen.")
                .setView(pad(key))
                .setCancelable(false)
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setPublicKey(key.getText().toString());
                        wizardProfile();
                    }
                })
                .show();
    }

    /** What the group is for: sets the map point buttons (and the commander view's list). Shared with settings. */
    private void wizardProfile() {
        final org.communitycomms.app.geo.QuickProfiles qp = new org.communitycomms.app.geo.QuickProfiles(this);
        final String[] names = {"Friends and family (meet-ups, events)", "Security", "Firefighting", "Military",
                "Emergency services (response)"};
        final int[] profiles = {org.communitycomms.app.geo.QuickProfiles.FRIENDS, org.communitycomms.app.geo.QuickProfiles.SECURITY,
                org.communitycomms.app.geo.QuickProfiles.FIRE, org.communitycomms.app.geo.QuickProfiles.MILITARY,
                org.communitycomms.app.geo.QuickProfiles.EMERGENCY};
        int sel = 1;
        for (int i = 0; i < profiles.length; i++) if (profiles[i] == qp.profile()) sel = i;
        new android.app.AlertDialog.Builder(this)
                .setTitle("What is your group for?")
                .setCancelable(false)
                .setSingleChoiceItems(names, sel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        qp.setProfile(profiles[which]);
                    }
                })
                .setPositiveButton("Next", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        renderQuick();
                        renderCommanderPoints();
                        wizardDryad();
                    }
                })
                .show();
    }

    private void wizardDryad() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Set up your group")
                .setMessage("Create a DRYAD sheet now? It never expires. Row A becomes channel A and row B "
                        + "channel B, so share the sheet (DRYAD > Show QR, or Settings > Share settings) with "
                        + "your group to give them the same channels.\n\nNo: join the Public channel and wait "
                        + "for someone to send you their settings.")
                .setCancelable(false)
                .setPositiveButton("Create sheet", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            org.communitycomms.app.dryad.DryadSheet s = org.communitycomms.app.dryad.DryadSheet.random(
                                    prefs.callsign() + " group sheet");
                            s.colour = org.communitycomms.app.dryad.SheetColour.random(new java.security.SecureRandom(), null);
                            new org.communitycomms.app.dryad.SheetStore(MainActivity.this).add(s);
                            prefs.setChannel(Prefs.SLOT_A, new String(s.row('A')));
                            prefs.setChannel(Prefs.SLOT_B, new String(s.row('B')));
                            prefs.setSetupDone();
                            selectSlot(Prefs.SLOT_A);
                            Toast.makeText(MainActivity.this, "Sheet made: channels A and B are set. Tap Channels, then Talk here.",
                                    Toast.LENGTH_LONG).show();
                            showPermissionsOnce();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Could not make a sheet: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Join Public", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.setSetupDone();
                        prefs.setShowSlots(true, prefs.showOpenManet());
                        renderSlots();
                        selectSlot(Prefs.SLOT_PUBLIC);
                        Toast.makeText(MainActivity.this, "On Public. When someone sends their settings you will be "
                                + "asked whether to accept them.", Toast.LENGTH_LONG).show();
                        if (!showPermissionsOnce()) requestJoin();
                        else joinWhenBack = true; // after the permissions screen
                    }
                })
                .show();
    }

    private boolean joinWhenBack;

    /**
     * First start: the permissions screen asks for everything up front (microphone, location,
     * camera, notifications, running in the background) instead of one at a time later.
     */
    private boolean showPermissionsOnce() {
        if (prefs.permissionsShown()) return false;
        prefs.setPermissionsShown();
        startActivity(new Intent(this, PermissionsActivity.class));
        return true;
    }

    private View pad(View v) {
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int shownUnread = -1;

    /** Chat button turns amber with a count while messages are waiting unread. */
    private void renderChat() {
        int n = org.communitycomms.app.msg.MessageLog.unread;
        if (n == shownUnread) return;
        shownUnread = n;
        Button b = (Button) findViewById(R.id.chat);
        b.setText(n > 0 ? getString(R.string.chat) + " (" + n + ")" : getString(R.string.chat));
        b.setTextColor(n > 0 ? 0xff000000 : 0xffffffff);
        // mutate(): the background drawable is shared with the other buttons.
        if (n > 0) b.getBackground().mutate().setColorFilter(0xffffb300, android.graphics.PorterDuff.Mode.SRC_ATOP);
        else b.getBackground().mutate().clearColorFilter();
    }

    private void renderSilent() {
        boolean s = prefs.radioSilent();
        Button b = (Button) findViewById(R.id.radio_silent);
        b.setText(s ? R.string.radio_silent_on : R.string.radio_silent_off);
        b.setTextColor(s ? 0xffff5252 : 0xffffffff);
        View call = findViewById(R.id.call);
        call.setVisibility(prefs.callEnabled() ? View.VISIBLE : View.GONE);
        call.setEnabled(!s);
        if (s && joined) {
            String t = getString(R.string.ptt_silent);
            if (!t.contentEquals(ptt.getText())) ptt.setText(t);
            return;
        }
        // No WiFi / Ethernet (no MANET) and no working tunnel: say so on the PTT button.
        if (++netTicks % 7 == 0 || netDown == null) netDown = shownOnline <= 0 && !NetCheck.canReachAnyone(this);
        if (netDown && joined && !isTransmitting()) {
            String t = "PUSH TO TALK\n(network not connected)";
            if (!t.contentEquals(ptt.getText())) ptt.setText(t);
            shownNetDown = true;
        } else if (shownNetDown) {
            shownNetDown = false;
            RadioEngine e = engine();
            if (e != null && e.voxOn()) applyVox();
            else if (!isTransmitting()) ptt.setText(R.string.ptt);
        }
    }

    private int netTicks;
    private Boolean netDown;
    private boolean shownNetDown;

    private void pttPressed() {
        if (prefs.radioSilent()) {
            Toast.makeText(this, "Radio silent: not transmitting. Tap Silent to end it.", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioEngine le = engine();
        if (le != null && le.listenOnly()) {
            Toast.makeText(this, "Listen-only key: you can hear and read this channel, but not send on it.", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.latch()) {
            setTransmit(!isTransmitting());
        } else {
            setTransmit(true);
        }
    }

    private boolean isTransmitting() {
        if (org.communitycomms.app.audio.BurstVoice.recording()) return true;
        CompatVoice c = service == null ? null : service.compat();
        if (c != null) return c.isTransmitting();
        RadioEngine e = engine();
        return e != null && e.isTransmitting();
    }

    private void pttReleased() {
        if (!prefs.latch()) setTransmit(false);
    }

    private void setTransmit(boolean on) {
        if (on && RadioEngine.cwMode) {
            Toast.makeText(this, "CW mode is on: PTT and voice are off. Send CW from DRYAD Enigma (tap the lock there to end CW mode).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.burstVoice()) {
            // Burst mode: record while held, send the recording as one file on release.
            if (on && !org.communitycomms.app.audio.BurstVoice.recording()) {
                if (org.communitycomms.app.audio.BurstVoice.start(this, prefs.burstQuality())) {
                    ptt.setText("RECORDING BURST…\nlet go to send");
                    ptt.setSelected(true);
                } else {
                    Toast.makeText(this, "The microphone is busy", Toast.LENGTH_SHORT).show();
                }
            } else if (!on && org.communitycomms.app.audio.BurstVoice.recording()) {
                String r = org.communitycomms.app.audio.BurstVoice.stopAndSend(this);
                ptt.setSelected(false);
                ptt.setText(R.string.ptt);
                if (r != null) Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        CompatVoice c = service == null ? null : service.compat();
        if (c != null) {
            if (!c.setTransmit(on)) Toast.makeText(this, "Channel busy: someone else is talking (half duplex)", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioEngine e = engine();
        if (e != null) e.setTransmit(on);
    }

    private RadioEngine engine() {
        return service == null ? null : service.engine();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code != KeyEvent.KEYCODE_UNKNOWN && code == prefs.pttKey()) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) pttPressed();
            else if (ev.getAction() == KeyEvent.ACTION_UP) pttReleased();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ---- join ----

    /** Everything a join depends on; a change made in Settings while on air triggers a re-join. */
    private String joinSettings() {
        return slot + "|" + prefs.channel(slot) + "|" + prefs.callsign() + "|" + prefs.netMode() + "|" + prefs.port()
                + "|" + prefs.extraPeers() + "|" + prefs.netInterface()
                // Dual watch settings and the other channel's name: changing them re-joins too.
                + "|" + prefs.dualWatch() + prefs.dualWatchPlay() + prefs.dualWatchSave() + prefs.dualWatchStereo() + prefs.recordAllVoice()
                // Tunnel comms: a new relay address, key or remote list re-joins too.
                + "|" + prefs.tunnelServer() + prefs.tunnelKey().hashCode() + prefs.tunnelRelayOn() + prefs.tunnelRemotes().hashCode()
                + prefs.tunnelClientOn()
                + "|" + prefs.keyRotation()
                + "|" + watchNames();
    }

    /** Every channel listened to with its name: a change (Listen, swap, new name) re-joins. */
    private String watchNames() {
        StringBuilder sb = new StringBuilder();
        for (int s : prefs.watchSlots(slot)) sb.append(s).append('=').append(prefs.channel(s)).append(prefs.channelMuted(s)).append(';');
        return sb.toString();
    }

    private void rejoinIfSettingsChanged() {
        if (!joined || service == null || joinedWith == null || joinedWith.equals(joinSettings())) return;
        if (prefs.channel(slot).length() == 0) {
            service.leave();
            return;
        }
        Toast.makeText(this, "Settings changed: re-joining", Toast.LENGTH_SHORT).show();
        setTransmit(false);
        doJoin();
    }

    private void requestJoin() {
        if (prefs.channel(slot).length() == 0) {
            Toast.makeText(this, "Set a name for " + label(slot) + " in Settings (menu)", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasPermissions()) {
            askPermissions();
            return;
        }
        startRadioService();
        if (service != null) doJoin();
        else joinAfterPermission = true;
    }

    /** Joining a channel not joined before needs the PIN (when one is set). */
    private void doJoin() {
        final String ch = prefs.channel(slot);
        if (PinLock.isSet(this) && PinLock.isNewChannel(this, ch)) {
            PinLock.require(this, "Enter your PIN to join a new channel.", new PinLock.Done() {
                @Override
                public void onUnlocked() {
                    PinLock.rememberChannel(MainActivity.this, ch);
                    doJoinNow();
                }
            }, null);
            return;
        }
        PinLock.rememberChannel(this, ch);
        doJoinNow();
    }

    private void doJoinNow() {
        if (service == null) return;
        String cs = prefs.callsign();
        if (cs.length() == 0) {
            cs = "Station-" + Integer.toHexString((int) (Math.random() * 0xffff));
            prefs.setCallsign(cs);
        }
        String ch = prefs.channel(slot);
        joinedWith = joinSettings();
        if (slot == Prefs.SLOT_OPENMANET) {
            service.joinCompat(ch);
            return;
        }
        service.join(ch, cs, prefs.netMode(), prefs.port(), prefs.extraPeers());
    }

    private void startRadioService() {
        Intent i = new Intent(this, RadioService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @TargetApi(23)
    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @TargetApi(23)
    private void askPermissions() {
        List<String> want = new ArrayList<String>();
        want.add(Manifest.permission.RECORD_AUDIO);
        // Asked together, so "send location on transmit" (on by default) works from the first talk.
        if (prefs.locationOnTransmit()) want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) want.add("android.permission.POST_NOTIFICATIONS");
        requestPermissions(want.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingQuick == SHARE_AFTER_PERMISSION) toggleRapidShare();
            else if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingQuick >= 0) quickPoi(pendingQuick);
            else Toast.makeText(this, "Location permission is needed for map points", Toast.LENGTH_LONG).show();
            pendingQuick = -1;
            return;
        }
        if (requestCode != REQ_PERMS) return;
        if (Build.VERSION.SDK_INT >= 23 && hasPermissions()) requestJoin();
        else Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show();
    }

    // ---- service callbacks ----

    @Override
    public void onJoinState(String s, boolean isJoined) {
        joined = isJoined;
        lastStatus = s;
        status.setText(label(slot) + " · " + s);
        join.setText(R.string.channels_button);
        renderBroadcast();
        ptt.setEnabled(isJoined);
        if (isJoined) applyVox();
        else ptt.setText(R.string.ptt);
        renderRoster();
    }

    @Override
    public void onTransmitResult(List<String> heardBy, boolean reminder) {
        TextView ack = (TextView) findViewById(R.id.ack_status);
        View resend = findViewById(R.id.resend);
        View dismiss = findViewById(R.id.dismiss);
        findViewById(R.id.ack_bar).setVisibility(View.VISIBLE);
        View later = findViewById(R.id.retry_later);
        if (heardBy.isEmpty() && prefs.silentListeners()) {
            // Radio silent support: no acknowledgements expected, so no "failed" warning.
            ack.setTextColor(0xffcfe3d4);
            ack.setText("Sent (radio silent support: listeners do not acknowledge)");
            resend.setVisibility(View.GONE);
            later.setVisibility(View.GONE);
            dismiss.setVisibility(View.VISIBLE);
        } else if (heardBy.isEmpty()) {
            String retry = service == null ? null : service.retryStatus();
            ack.setTextColor(0xffff8a80);
            ack.setText(retry != null ? "Not acknowledged. " + retry
                    : reminder ? "Last message still not acknowledged. Retry?"
                    : "Transmission failed: nobody acknowledged it.");
            resend.setVisibility(View.VISIBLE);
            later.setVisibility(retry == null ? View.VISIBLE : View.GONE);
            dismiss.setVisibility(View.VISIBLE);
        } else {
            ack.setTextColor(0xffa5d6a7);
            StringBuilder sb = new StringBuilder("Heard by ");
            for (int i = 0; i < heardBy.size(); i++) sb.append(i == 0 ? "" : ", ").append(heardBy.get(i));
            ack.setText(sb);
            resend.setVisibility(View.GONE);
            later.setVisibility(View.GONE);
            dismiss.setVisibility(View.GONE);
        }
    }

    // ---- VOX (set up in Settings) ----

    /** Turns VOX on or off in the engine to match the setting (only on encrypted channels). */
    private void applyVox() {
        RadioEngine e = engine();
        if (e == null) return;
        e.setVoxThresholdDb(prefs.voxThresholdDb());
        e.setVox(prefs.vox() && !RadioEngine.cwMode);
        renderVox();
    }

    private void renderVox() {
        RadioEngine e = engine();
        if (e != null && e.voxOn() != (prefs.vox() && !RadioEngine.cwMode)) e.setVox(prefs.vox() && !RadioEngine.cwMode);
        if (prefs.vox() && e != null && e.voxOn()) {
            ptt.setEnabled(false);
            ptt.setText(e.isTransmitting() ? "VOX: TRANSMITTING" : "VOX: listening");
            ptt.setSelected(e.isTransmitting());
        }
    }

    /** Friendly kind of network from the interface name Android gives it. */
    static String kindOf(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("ap") || n.startsWith("swlan")) return "WiFi";
        if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("tap")
                || n.startsWith("ipsec") || n.startsWith("zt")) return "VPN";
        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) return "USB tethering";
        if (n.startsWith("eth") || n.startsWith("en")) return "Ethernet (USB adapter)";
        if (n.startsWith("bt-pan") || n.startsWith("bnep")) return "Bluetooth";
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("radio")) return "Mobile data";
        return "Network";
    }

    /** "Retry in…": 5 min / 15 min / 1 hour / at a time of day, repeated 1–99 times. */
    private void askRetryLater() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final RadioGroup when = new RadioGroup(this);
        final String[] labels = {"In 5 minutes", "In 15 minutes", "In 1 hour", "At a time…"};
        final long[] delays = {5 * 60000L, 15 * 60000L, 60 * 60000L, -1};
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(100 + i);
            rb.setText(labels[i]);
            when.addView(rb);
        }
        when.check(100);
        box.addView(when);
        TextView tl = new TextView(this);
        tl.setText("Number of tries (1–99):");
        box.addView(tl);
        final android.widget.NumberPicker times = new android.widget.NumberPicker(this);
        times.setMinValue(1);
        times.setMaxValue(99);
        times.setValue(3);
        times.setWrapSelectorWheel(false);
        box.addView(times);
        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setText("Tries stop as soon as anyone acknowledges. \"At a time\" tries then repeat every 5 minutes.");
        box.addView(note);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Retry the message")
                .setView(box)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        int i = when.getCheckedRadioButtonId() - 100;
                        final int n = times.getValue();
                        if (delays[i] > 0) {
                            startRetries(delays[i], delays[i], n);
                        } else {
                            java.util.Calendar now = java.util.Calendar.getInstance();
                            new android.app.TimePickerDialog(MainActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                                @Override
                                public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                                    java.util.Calendar at = java.util.Calendar.getInstance();
                                    at.set(java.util.Calendar.HOUR_OF_DAY, h);
                                    at.set(java.util.Calendar.MINUTE, m);
                                    at.set(java.util.Calendar.SECOND, 0);
                                    if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(java.util.Calendar.DAY_OF_MONTH, 1);
                                    startRetries(at.getTimeInMillis() - System.currentTimeMillis(), 5 * 60000L, n);
                                }
                            }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE),
                                    android.text.format.DateFormat.is24HourFormat(MainActivity.this)).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startRetries(long first, long interval, int n) {
        if (service == null) return;
        service.scheduleRetries(first, interval, n);
        ((TextView) findViewById(R.id.ack_status)).setText(service.retryStatus());
        findViewById(R.id.resend).setVisibility(View.VISIBLE);
        findViewById(R.id.retry_later).setVisibility(View.GONE);
        findViewById(R.id.dismiss).setVisibility(View.VISIBLE);
    }

    private void hideAckBar() {
        findViewById(R.id.ack_bar).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.ack_status)).setText("");
        findViewById(R.id.resend).setVisibility(View.GONE);
        findViewById(R.id.retry_later).setVisibility(View.GONE);
        findViewById(R.id.dismiss).setVisibility(View.GONE);
    }


    @Override
    public void onDryadOffer(String fromName, String text) {
        final java.util.List<org.communitycomms.app.dryad.DryadSheet> sheets;
        try {
            sheets = org.communitycomms.app.dryad.DryadSheet.parseAll(text);
        } catch (IllegalArgumentException e) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (org.communitycomms.app.dryad.DryadSheet s : sheets) names.append("\n• ").append(s.name);
        new android.app.AlertDialog.Builder(this)
                .setTitle(fromName + " sent " + sheets.size() + " DRYAD sheet(s)")
                .setMessage("Import these?" + names)
                .setPositiveButton("Import", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        org.communitycomms.app.dryad.SheetStore store = new org.communitycomms.app.dryad.SheetStore(MainActivity.this);
                        try {
                            int added = store.importAll(sheets);
                            int dup = sheets.size() - added;
                            Toast.makeText(MainActivity.this, "Imported " + added + " sheet(s)"
                                    + (dup > 0 ? ", " + dup + " already held" : ""), Toast.LENGTH_SHORT).show();
                        } catch (java.io.IOException e) {
                            Toast.makeText(MainActivity.this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Ignore", null)
                .show();
    }

    @Override
    public void onRosterChanged() {
        renderRoster();
    }

    @Override
    public void onTransmitChanged(boolean on) {
        ptt.setSelected(on);
        if (miniPtt != null) miniPtt.setSelected(on);
        RadioEngine e = engine();
        if (e != null && e.voxOn()) {
            ptt.setText(on ? "VOX: TRANSMITTING" : "VOX: listening");
        } else {
            ptt.setEnabled(joined);
            ptt.setText(on ? R.string.ptt_tx : R.string.ptt);
        }
    }

    private int shownOnline = -1;
    private String shownTalker;

    /**
     * Contacts (n): n = online now, red when nobody is. Whoever is talking shows on the button,
     * so the home screen still says who you are hearing. The full list is on the Contacts screen.
     */
    private void renderRoster() {
        long now = System.currentTimeMillis();
        String talker = null;
        CompatVoice cv = service == null ? null : service.compat();
        RadioEngine e = engine();
        if (cv != null) {
            for (CompatVoice.Talker t : cv.talkers()) if (t.talking(now)) talker = t.label();
            if (ptt.isSelected() != cv.isTransmitting()) onTransmitChanged(cv.isTransmitting());
        } else if (e != null) {
            for (RadioEngine.Station s : e.stations()) if (s.isTalking(now)) talker = s.name;
            for (RadioEngine m : e.monitors()) {
                if (talker == null) for (RadioEngine.Station s : m.stations()) if (s.isTalking(now)) talker = s.name;
            }
            boolean tx = e.isTransmitting();
            if (ptt.isSelected() != tx) onTransmitChanged(tx);
        }
        int online = Contacts.get(this).onlineCount();
        if (online == shownOnline && (talker == null ? shownTalker == null : talker.equals(shownTalker))) {
            renderStatus();
            renderWatch();
            return;
        }
        renderWatch();
        shownOnline = online;
        shownTalker = talker;
        contactsButton.setText(talker != null ? "▶ " + talker : getString(R.string.contacts) + " (" + online + ")");
        if (online == 0 && talker == null && !prefs.silentListeners()) {
            contactsButton.setTextColor(0xffffffff);
            contactsButton.getBackground().mutate().setColorFilter(0xffc62828, android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            contactsButton.setTextColor(talker != null ? 0xffa5d6a7 : 0xffffffff);
            contactsButton.getBackground().mutate().clearColorFilter();
        }
        renderStatus();
    }

    private String shownWatch = "";

    /**
     * Dual watch: channels only listened to get a white-centred circle; the one on air keeps the
     * normal (yellow) checked circle.
     */
    private void renderWatch() {
        RadioService rs = service;
        List<Integer> w = rs == null ? new ArrayList<Integer>() : rs.watchingSlots();
        String key = w.toString() + java.util.Arrays.toString(homeSlots);
        if (key.equals(shownWatch)) return;
        shownWatch = key;
        android.content.res.TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorSingle});
        int normal = ta.getResourceId(0, 0);
        ta.recycle();
        for (int i = 0; i < SLOT_IDS.length; i++) {
            android.widget.RadioButton rb = (android.widget.RadioButton) findViewById(SLOT_IDS[i]);
            rb.setButtonDrawable(i < homeSlots.length && w.contains(homeSlots[i]) ? R.drawable.radio_listen : normal);
        }
    }

    private int shownStatus = -2;

    private void renderStatus() {
        int s = service == null ? prefs.status() : service.radioStatus();
        boolean vol = service != null && service.stealthFromVolume();
        if (s * 2 + (vol ? 1 : 0) == shownStatus) return;
        shownStatus = s * 2 + (vol ? 1 : 0);
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int ringer = am == null ? android.media.AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        statusButton.setText(!vol ? Contacts.statusName(s)
                : ringer == android.media.AudioManager.RINGER_MODE_VIBRATE ? "Stealth (vibrate)"
                : ringer == android.media.AudioManager.RINGER_MODE_SILENT ? "Stealth (silent)" : "Stealth (vol 0)");
        statusButton.setTextColor(Contacts.statusColour(s, true));
    }

    /** Available / Away (voice heard is also saved to Chat) / Stealth (silent, saved and buzzes). */
    private void chooseStatus() {
        final String[] names = {"Available", "Away: voice heard is also saved in Chat for playback",
                "Stealth: no sound, no ringing; saved in Chat" + (prefs.stealthVibrate() ? ", buzzes" : "")};
        int cur = prefs.status();
        new android.app.AlertDialog.Builder(this)
                .setTitle("My status (others see it in Contacts)")
                .setSingleChoiceItems(names, cur, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        if (service != null) service.chooseStatus(which);
                        else prefs.setStatus(which);
                        shownStatus = -2;
                        renderStatus();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

