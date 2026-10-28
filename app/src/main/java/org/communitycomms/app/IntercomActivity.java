package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Intercom mode: a phone or tablet mounted on a wall, used like an old home intercom. Big buttons
 * for each room (a saved callsign) and All; pick one and talk (hold, or Hands-free to toggle).
 * Talking to one room sends voice only that phone plays (VOICE_TO). Messages and the recording of
 * every call are in History (Chat). Optional gate / door camera from a snapshot address.
 * The radio itself (join, channel) is run by the home screen underneath.
 */
public final class IntercomActivity extends Activity {
    /** "Full radio screen" was asked for: the home screen stays up instead of coming back here. */
    static boolean showFullRadio;

    private Prefs prefs;
    private LinearLayout rooms;
    private TextView status;
    private Button talk, handsFree;
    private String target; // null = All
    private boolean latched;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            keepOnAir();
            if (++ticks % 7 == 0) renderRooms(); // "(not heard)" follows who is on air
            handler.postDelayed(this, 700);
        }
    };

    private long offAirSince;
    private int ticks;

    // ---- blackout: button, and automatic after a minute without a touch ----

    private static final int AUTO_BLACK_S = 60;
    private Button blackout;
    private final Runnable goBlack = new Runnable() {
        @Override
        public void run() {
            LightDiscipline.blackout(IntercomActivity.this);
        }
    };

    private void renderBlackout() {
        blackout.setText(prefs.intercomAutoBlack() ? "Blackout (auto " + AUTO_BLACK_S + " s)" : "Blackout (auto off)");
    }

    private void restartBlackTimer() {
        handler.removeCallbacks(goBlack);
        if (prefs.intercomAutoBlack()) handler.postDelayed(goBlack, AUTO_BLACK_S * 1000L);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartBlackTimer();
    }

    /** The intercom stays on its private channel: off air for 10 s (dropped, left) joins it again. */
    private void keepOnAir() {
        RadioService s = RadioService.instance();
        if (s != null && (s.engine() != null || s.compat() != null)) {
            offAirSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (offAirSince == 0) offAirSince = now;
        if (now - offAirSince < 10000) return;
        offAirSince = now; // next try in 10 s
        String key = prefs.channel(Prefs.SLOT_A);
        if (key.length() == 0) return;
        if (s == null) {
            // The radio service is not running: the home screen underneath starts it and joins.
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        prefs.setActiveSlot(Prefs.SLOT_A);
        s.join(key, prefs.callsign(), prefs.netMode(), prefs.port(), prefs.extraPeers());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // no title bar: just the menu dots
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float d = getResources().getDisplayMetrics().density;
        // Upright: everything stacked. Sideways (wall tablet): rooms on the left, every button down the right.
        final boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (28 * d));
        LinearLayout left = root, right = root;
        if (land) {
            left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.4f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            rp.leftMargin = (int) (10 * d);
            root.addView(right, rp);
        }
        // Top line: status, with the menu dots at the right.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        if (land) left.addView(status);
        else top.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final View dots = new View(this) {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                p.setColor(0xffcfe3d4);
                float r = getWidth() * 0.07f, cx = getWidth() / 2f;
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, getHeight() / 2f + i * r * 3.2f, r, p);
            }
        };
        dots.setContentDescription("Menu");
        dots.setClickable(true);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menu(dots);
            }
        });
        if (land) top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(dots, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));
        right.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rooms = new LinearLayout(this);
        rooms.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(rooms);
        left.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button add = small("+ Room", new Runnable() {
            @Override
            public void run() {
                addRoom();
            }
        });
        Button history = small("History / messages", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(IntercomActivity.this, ChatActivity.class));
            }
        });
        Button cam = small("Camera", new Runnable() {
            @Override
            public void run() {
                camera();
            }
        });
        blackout = small("", new Runnable() {
            @Override
            public void run() {
                LightDiscipline.blackout(IntercomActivity.this);
            }
        });
        blackout.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean on = !prefs.intercomAutoBlack();
                prefs.setIntercomAutoBlack(on);
                renderBlackout();
                restartBlackTimer();
                Toast.makeText(IntercomActivity.this, on ? "Auto blackout on: the screen goes black after "
                        + AUTO_BLACK_S + " s without a touch (double-tap to wake)" : "Auto blackout off", Toast.LENGTH_LONG).show();
                return true;
            }
        });
        renderBlackout();
        if (land) {
            for (Button bt : new Button[]{add, history, cam, blackout}) {
                right.addView(bt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * d)));
            }
        } else {
            root.addView(FilesActivity.row(add, history, cam, blackout));
        }

        LinearLayout talkRow = new LinearLayout(this);
        talk = new Button(this);
        talk.setTextSize(26);
        talk.setTextColor(0xffffffff);
        talk.setBackgroundResource(R.drawable.ptt_background);
        talk.setAllCaps(false);
        talk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (latched) return false; // hands-free: Talk does nothing, use the toggle
                if (e.getAction() == MotionEvent.ACTION_DOWN) transmit(true);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) transmit(false);
                return false;
            }
        });
        if (land) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            tp.topMargin = (int) (8 * d);
            right.addView(talk, tp);
        } else {
            talkRow.addView(talk, new LinearLayout.LayoutParams(0, (int) (130 * d), 3));
        }
        handsFree = new Button(this);
        handsFree.setAllCaps(false);
        handsFree.setTextSize(16);
        handsFree.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latched = !latched;
                transmit(latched);
            }
        });
        if (land) {
            right.addView(handsFree, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (90 * d)));
        } else {
            talkRow.addView(handsFree, new LinearLayout.LayoutParams(0, (int) (130 * d), 1));
            root.addView(talkRow);
        }
        setContentView(root);
    }

    /** The menu dots: setup and the rarely used screens. */
    private void menu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        // No Exit: the intercom is a fixture on the wall (Settings > Mode changes that).
        final String[] items = {"Settings", "Audio", "Contacts", "Radio streams", "Set intercom key"};
        for (int i = 0; i < items.length; i++) {
            if (i == 3 && !prefs.streamsEnabled()) continue; // Internet audio off in Settings
            pm.getMenu().add(0, i, i, items[i]);
        }
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem it) {
                switch (it.getItemId()) {
                    case 0:
                        startActivity(new Intent(IntercomActivity.this, SettingsActivity.class));
                        break;
                    case 1:
                        startActivity(new Intent(IntercomActivity.this, AudioActivity.class));
                        break;
                    case 2:
                        startActivity(new Intent(IntercomActivity.this, ContactsActivity.class));
                        break;
                    case 3:
                        startActivity(new Intent(IntercomActivity.this, StreamsActivity.class));
                        break;
                    default:
                        startActivity(new Intent(IntercomActivity.this, KeysActivity.class));
                        break;
                }
                return true;
            }
        });
        pm.show();
    }

    private Button small(String t, final Runnable r) {
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

    @Override
    protected void onResume() {
        super.onResume();
        renderRooms();
        handler.post(tick);
        restartBlackTimer();
        askOverlayOnce();
    }

    private static boolean overlayAsked;

    /** Android 10+ only lets the intercom come back by itself with "display over other apps" allowed. */
    @android.annotation.TargetApi(23)
    private void askOverlayOnce() {
        if (overlayAsked || android.os.Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(this)) return;
        overlayAsked = true;
        new AlertDialog.Builder(this)
                .setTitle("Keep the intercom on screen")
                .setMessage("If someone leaves the intercom (home button or another app), it comes back by itself after "
                        + "a minute. Android needs \"Display over other apps\" allowed for that.")
                .setPositiveButton("Allow…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                            Toast.makeText(IntercomActivity.this, "Settings > Apps > this app > Display over other apps",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(goBlack);
        if (latched || isTalking()) {
            latched = false;
            transmit(false);
        }
        super.onPause();
    }

    /** Back leaves the intercom showing (it is the wall screen): goes to the phone's home instead. */
    @Override
    public void onBackPressed() {
        if (prefs.appMode() == Prefs.MODE_INTERCOM) moveTaskToBack(true);
        else super.onBackPressed();
    }

    private List<String> roomList() {
        List<String> l = new ArrayList<String>();
        for (String s : prefs.intercomRooms().split("\n")) if (s.trim().length() > 0) l.add(s.trim());
        return l;
    }

    private void saveRooms(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append('\n');
        prefs.setIntercomRooms(sb.toString());
    }

    private void renderRooms() {
        rooms.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        List<String> names = new ArrayList<String>();
        names.add(null); // All
        names.addAll(roomList());
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                rooms.addView(row);
            }
            final String n = names.get(i);
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(22);
            boolean on = n == null ? target == null : n.equals(target);
            Contacts.Contact c = n == null ? null : Contacts.get(this).find(n);
            boolean online = c != null && c.online(System.currentTimeMillis());
            b.setText((n == null ? "Everyone" : n) + (n == null || online ? "" : "\n(not heard)"));
            b.setTextColor(on ? 0xff0f1f16 : 0xffffffff);
            b.setBackgroundColor(on ? 0xfff0c75e : online || n == null ? 0xff2e5a3e : 0xff4a4a4a);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    target = n;
                    renderRooms();
                    renderStatus();
                }
            });
            if (n != null) b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    roomOptions(n);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (96 * d), 1);
            lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
            row.addView(b, lp);
        }
        if (names.size() % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (names.size() == 1) {
            TextView t = new TextView(this);
            t.setText("Tap + Room to add the other intercoms (people or rooms that have been heard on the channel). "
                    + "Long-press a room to message or remove it.");
            t.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), 0);
            rooms.addView(t);
        }
    }

    private void roomOptions(final String n) {
        new AlertDialog.Builder(this)
                .setTitle(n)
                .setItems(new String[]{"Send a message", "Call (ring)", "Remove from the intercom"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        RadioService s = RadioService.instance();
                        if (w == 0) message(n);
                        else if (w == 1) {
                            boolean ok = s != null && s.engine() != null && s.engine().sendCall(n);
                            Toast.makeText(IntercomActivity.this, ok ? "Ringing " + n : "Not on a channel", Toast.LENGTH_SHORT).show();
                        } else {
                            List<String> l = roomList();
                            l.remove(n);
                            saveRooms(l);
                            if (n.equals(target)) target = null;
                            renderRooms();
                        }
                    }
                })
                .show();
    }

    private void message(final String to) {
        final EditText e = new EditText(this);
        e.setHint("Message to " + to);
        new AlertDialog.Builder(this)
                .setTitle("Message " + to)
                .setView(e)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        RadioService s = RadioService.instance();
                        String t = e.getText().toString().trim();
                        if (t.length() == 0) return;
                        if (s == null || s.messages() == null || s.engine() == null) {
                            Toast.makeText(IntercomActivity.this, "Not on a channel", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String problem = s.messages().sendPrivate(to, t);
                        Toast.makeText(IntercomActivity.this, problem == null ? "Sent to " + to : problem, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addRoom() {
        final List<String> have = roomList();
        final List<String> heard = new ArrayList<String>();
        for (Contacts.Contact c : Contacts.get(this).all()) if (!have.contains(c.name)) heard.add(c.name);
        final String[] items = new String[heard.size() + 1];
        for (int i = 0; i < heard.size(); i++) items[i] = heard.get(i);
        items[heard.size()] = "Type a callsign…";
        new AlertDialog.Builder(this)
                .setTitle("Add a room")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (w < heard.size()) {
                            have.add(heard.get(w));
                            saveRooms(have);
                            renderRooms();
                            return;
                        }
                        final EditText e = new EditText(IntercomActivity.this);
                        e.setHint("Callsign of that intercom");
                        new AlertDialog.Builder(IntercomActivity.this)
                                .setView(e)
                                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        String n = e.getText().toString().trim().replace("\n", " ");
                                        if (n.length() == 0 || have.contains(n)) return;
                                        have.add(n);
                                        saveRooms(have);
                                        renderRooms();
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private RadioEngine engine() {
        RadioService s = RadioService.instance();
        return s == null ? null : s.engine();
    }

    private boolean isTalking() {
        RadioEngine e = engine();
        return e != null && e.isTransmitting();
    }

    private void transmit(boolean on) {
        RadioEngine e = engine();
        if (e == null) {
            if (on) Toast.makeText(this, "Not on a channel yet", Toast.LENGTH_SHORT).show();
            latched = false;
            renderStatus();
            return;
        }
        if (on) e.voiceTarget = target;
        e.setTransmit(on);
        if (!on) e.voiceTarget = null;
        renderStatus();
    }

    private void renderStatus() {
        RadioEngine e = engine();
        boolean tx = e != null && e.isTransmitting();
        String who = target == null ? "everyone" : target;
        String heard = "";
        if (e != null) {
            long now = System.currentTimeMillis();
            for (RadioEngine.Station s : e.stations()) if (s.isTalking(now)) heard += (heard.length() > 0 ? ", " : "") + s.name;
        }
        status.setText(e == null ? "Not on a channel" : tx ? "Talking to " + who + "…"
                : heard.length() > 0 ? heard + " is talking" : "Talk to: " + who);
        status.setTextColor(tx ? 0xffff8a80 : heard.length() > 0 ? 0xffa5d6a7 : 0xffcfe3d4);
        talk.setText(tx ? "Talking to " + who : latched ? "Hands-free" : "Hold to talk\nto " + who);
        handsFree.setText(latched ? "Stop\nhands-free" : "Hands-\nfree");
        handsFree.setTextColor(latched ? 0xffff8a80 : 0xffffffff);
    }

    // ---- gate / door camera ----

    private volatile boolean camOn;

    private void camera() {
        String url = prefs.intercomCamera();
        if (url.length() == 0) {
            final EditText e = new EditText(this);
            e.setHint("http://192.168.1.50/snapshot.jpg");
            new AlertDialog.Builder(this)
                    .setTitle("Camera snapshot address")
                    .setMessage("The still-picture address of the gate or door camera (most IP cameras have one; "
                            + "add user:password@ before the host if it needs a sign-in). It refreshes every second.")
                    .setView(e)
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface di, int w) {
                            prefs.setIntercomCamera(e.getText().toString());
                            if (prefs.intercomCamera().length() > 0) camera();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        final ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setMinimumHeight((int) (200 * getResources().getDisplayMetrics().density));
        final TextView note = new TextView(this);
        note.setText("Connecting…");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(iv);
        box.addView(note);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Camera")
                .setView(box)
                .setPositiveButton("Close", null)
                .setNeutralButton("Change address", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        prefs.setIntercomCamera("");
                        camera();
                    }
                })
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                camOn = false;
            }
        });
        camOn = true;
        dlg.show();
        final File out = new File(getCacheDir(), "intercom-cam.jpg");
        final String address = url;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (camOn) {
                    final String problem = org.communitycomms.app.camera.NetworkCamera.snapshot(address, out);
                    final android.graphics.Bitmap bm = problem == null ? BitmapFactory.decodeFile(out.getPath()) : null;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (bm != null) iv.setImageBitmap(bm);
                            note.setText(problem == null ? "Live (refreshes every second)" : "Camera: " + problem);
                        }
                    });
                    try {
                        Thread.sleep(problem == null ? 1000 : 3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "intercom-cam").start();
    }
}
