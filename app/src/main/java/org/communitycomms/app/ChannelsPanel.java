package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Channel control: every channel (A, B, the extra C–H, Public, OpenMANET*) with Talk here / Leave,
 * and with dual watch on a Listen toggle, so a commander can talk on one and hear several. Icons
 * per channel: eye (button on the home screen, at most 4), key (DRYAD sheets), note (kept on this
 * phone, moves with the channel), and more (name, swap with A or B, mute). Shown on its own page
 * (ChannelsActivity) or in the tablet commander view's side panel. Joining and leaving are done by
 * the home screen (permissions, PIN for a new channel) through the Host.
 */
final class ChannelsPanel {
    interface Host {
        /** ChannelsActivity.JOIN with a slot, or LEAVE. */
        void channelAction(String action, int slot);

        /** Names, eyes, listening or notes changed (the commander view applies them at once). */
        void channelsChanged();
    }

    private final Activity a;
    private final Host host;
    private final Prefs prefs;
    private final LinearLayout box;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1500);
        }
    };
    private String shown = "";

    ChannelsPanel(Activity a, Host host) {
        this.a = a;
        this.host = host;
        prefs = new Prefs(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(10);
        box.setPadding(p, p / 2, p, p);
    }

    View view() {
        return box;
    }

    void start() {
        shown = "";
        handler.post(tick);
    }

    void stop() {
        handler.removeCallbacks(tick);
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }

    /** Slot on air (talking), or -1. */
    private int onAir() {
        RadioService rs = RadioService.instance();
        if (rs != null && rs.compat() != null) return Prefs.SLOT_OPENMANET;
        if (rs != null && rs.engine() != null) return prefs.activeSlot();
        return -1;
    }

    private List<Integer> listeningNow() {
        RadioService rs = RadioService.instance();
        return rs == null ? new ArrayList<Integer>() : rs.watchingSlots();
    }

    private void changed() {
        shown = "";
        host.channelsChanged();
        render();
    }

    void render() {
        int air = onAir();
        List<Integer> hear = listeningNow();
        StringBuilder k = new StringBuilder().append(air).append(hear).append(prefs.dualWatch()).append(prefs.extrasOn())
                .append(prefs.watchSlots(prefs.activeSlot())).append(prefs.publicKey()).append(prefs.callsign())
                .append(prefs.tunnelSetUp()).append(prefs.tunnelClientOn());
        for (int s : Prefs.order()) {
            k.append('|').append(prefs.shown(s)).append(prefs.channel(s).length()).append(prefs.channelNote(s).hashCode())
                    .append(prefs.channelMuted(s)).append(prefs.tunnelSlot(s));
        }
        if (k.toString().equals(shown)) return;
        shown = k.toString();
        box.removeAllViews();

        // Who I am on air (tap to change).
        TextView me = new TextView(a);
        me.setTextSize(16);
        me.setText("Callsign: " + (prefs.callsign().length() > 0 ? prefs.callsign() : "(not set)") + "  (tap to change)");
        me.setPadding(0, dp(4), 0, dp(6));
        me.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editCallsign();
            }
        });
        box.addView(me);

        if (prefs.dualWatch() && !samePlan(hear)) {
            TextView t = new TextView(a);
            t.setTextColor(0xffffd54f);
            t.setText("Listening changes apply when you go back to the home screen.");
            box.addView(t);
        }
        for (int slot : Prefs.order()) {
            if (slot >= Prefs.SLOT_C && !prefs.extrasOn()) continue;
            box.addView(channelRow(slot, air, hear));
        }

        if (prefs.tunnelSetUp()) {
            heading("Tunnel (internet relay)");
            final Switch all = new Switch(a);
            all.setText("Use the tunnel for every channel (the same as Settings > Tunnel comms). Off: only channels "
                    + "with their tunnel icon on go through it.");
            all.setChecked(prefs.tunnelClientOn());
            all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton bt, boolean on) {
                    prefs.setTunnelClientOn(on);
                    RadioService rs = RadioService.instance();
                    if (rs != null) rs.applyTunnelNow();
                    changed();
                }
            });
            box.addView(all);
        }
        heading("Radio silent");
        box.addView(Prefs.radioSilentBox(a));
        heading("Listening");
        final Switch dual = new Switch(a);
        dual.setText("Dual watch: talk on one channel and also hear others (tap Listen on each). "
                + "Off: joining one channel leaves the other.");
        dual.setChecked(prefs.dualWatch());
        dual.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setDualWatch(on, prefs.dualWatchPlay(), prefs.dualWatchSave());
                changed();
            }
        });
        box.addView(dual);
        final Switch extras = new Switch(a);
        extras.setText("Extra channels C–H (mostly to listen to; swap one with A or B to talk on it)");
        extras.setChecked(prefs.extrasOn());
        extras.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                prefs.setExtrasOn(on);
                changed();
            }
        });
        box.addView(extras);

        TextView note = new TextView(a);
        note.setTextSize(12);
        note.setPadding(0, dp(12), 0, 0);
        note.setText("Eye: show that channel's button on the home screen (room for " + Prefs.HOME_MAX
                + "). Key: DRYAD sheets. Note: kept on this phone; it moves with the channel when swapped. "
                + (prefs.tunnelSetUp() && !prefs.tunnelClientOn() ? "Tunnel (arch): this channel also goes through the internet relay. " : "")
                + "Three dots: name, swap with A or B, mute.");
        box.addView(note);
    }

    /** True when what is being heard already matches the Listen ticks (nothing waiting to apply). */
    private boolean samePlan(List<Integer> hear) {
        if (onAir() < 0 || a instanceof MainActivity) return true; // the commander view applies at once
        List<Integer> want = prefs.watchSlots(prefs.activeSlot());
        return want.size() == hear.size() && want.containsAll(hear);
    }

    private View channelRow(final int slot, int air, List<Integer> hear) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        boolean talking = air == slot, listening = hear.contains(slot);
        boolean willListen = !talking && prefs.watchSlots(prefs.activeSlot()).contains(slot);
        boolean set = prefs.channel(slot).length() > 0;
        TextView t = new TextView(a);
        t.setTextSize(16);
        String state = talking ? "On air (talk here)" : listening ? "Listening" : willListen && air >= 0 ? "Will listen"
                : set ? "Not joined" : "No name set";
        if ((listening || willListen) && prefs.channelMuted(slot)) state += ", muted";
        String extra = slot == Prefs.SLOT_PUBLIC ? "\nKey: " + prefs.publicKey() : "";
        String n = prefs.channelNote(slot);
        if (n.length() > 0) extra += "\nNote: " + (n.length() > 60 ? n.substring(0, 60) + "…" : n);
        t.setText(MainActivity.label(slot) + "\n" + state + extra);
        t.setTextColor(talking ? 0xffa5d6a7 : listening ? 0xffffd54f : 0xffffffff);
        top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final boolean eye = prefs.shown(slot);
        top.addView(icon(eye ? R.drawable.ic_eye : R.drawable.ic_eye_off, eye ? "On the home screen" : "Not on the home screen",
                new Runnable() {
                    @Override
                    public void run() {
                        if (!eye && prefs.homeSlots().length >= Prefs.HOME_MAX) {
                            Toast.makeText(a, "The home screen has room for " + Prefs.HOME_MAX
                                    + " channels: hide one first", Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setShown(slot, !eye);
                        changed();
                    }
                }));
        if (Prefs.isEncrypted(slot)) {
            top.addView(icon(R.drawable.ic_key, "DRYAD sheets", new Runnable() {
                @Override
                public void run() {
                    a.startActivity(new Intent(a, DryadActivity.class));
                }
            }));
        }
        top.addView(icon(R.drawable.ic_note, "Note on this channel", new Runnable() {
            @Override
            public void run() {
                editNote(slot);
            }
        }));
        if (prefs.tunnelSetUp() && !prefs.tunnelClientOn() && slot != Prefs.SLOT_OPENMANET) {
            final boolean tun = prefs.tunnelSlot(slot);
            top.addView(icon(tun ? R.drawable.ic_tunnel : R.drawable.ic_tunnel_off,
                    tun ? "Tunnel on for this channel" : "Tunnel off for this channel", new Runnable() {
                        @Override
                        public void run() {
                            prefs.setTunnelSlot(slot, !tun);
                            RadioService rs = RadioService.instance();
                            if (rs != null) rs.applyTunnelNow();
                            Toast.makeText(a, !tun ? MainActivity.label(slot) + " now also goes through the tunnel"
                                    : MainActivity.label(slot) + " stays off the tunnel", Toast.LENGTH_SHORT).show();
                            changed();
                        }
                    }));
        }
        if (slot != Prefs.SLOT_OPENMANET) {
            top.addView(icon(R.drawable.ic_more, "More", new Runnable() {
                @Override
                public void run() {
                    more(slot);
                }
            }));
        }
        row.addView(top);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        if (prefs.dualWatch() && slot != Prefs.SLOT_OPENMANET && !talking && air != Prefs.SLOT_OPENMANET) {
            final boolean on = listening || willListen;
            Button l = button(on ? "Listening ✓" : "Listen", new Runnable() {
                @Override
                public void run() {
                    toggleListen(slot, !on);
                }
            });
            l.setEnabled(set);
            buttons.addView(l, new LinearLayout.LayoutParams(dp(130), dp(48)));
        }
        Button jl = button(talking ? "Leave" : "Talk here", new Runnable() {
            @Override
            public void run() {
                if (onAir() == slot) confirmLeave(slot);
                else talkOn(slot);
            }
        });
        if (talking) jl.getBackground().mutate().setColorFilter(0xffc62828, android.graphics.PorterDuff.Mode.SRC_ATOP);
        jl.setEnabled(set);
        buttons.addView(jl, new LinearLayout.LayoutParams(dp(130), dp(48)));
        if (slot != Prefs.SLOT_OPENMANET) {
            // Ask the others for what was missed on this channel (Public off by default).
            CheckBox cu = new CheckBox(a);
            cu.setText("Request catch-ups (ask for missed messages and voice)");
            cu.setTextSize(13);
            cu.setChecked(prefs.channelCatchUp(slot));
            cu.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    prefs.setChannelCatchUp(slot, on);
                }
            });
            row.addView(cu);
        }
        row.addView(buttons);
        return row;
    }

    /** Ticks set explicitly from what is heard now (so the A/B default is kept), then changes one. */
    private void toggleListen(int slot, boolean on) {
        for (int s : prefs.watchSlots(prefs.activeSlot())) prefs.setListening(s, true);
        prefs.setListening(slot, on);
        changed();
    }

    /** Talk on another channel; with dual watch on, the one left keeps being heard. */
    private void talkOn(int slot) {
        int old = onAir();
        if (prefs.dualWatch() && old >= 0 && old != Prefs.SLOT_OPENMANET) {
            for (int s : prefs.watchSlots(prefs.activeSlot())) prefs.setListening(s, true);
            prefs.setListening(old, true);
        }
        host.channelAction(ChannelsActivity.JOIN, slot);
        shown = "";
    }

    private void more(final int slot) {
        final List<String> items = new ArrayList<String>();
        final List<Runnable> runs = new ArrayList<Runnable>();
        if (Prefs.isEncrypted(slot)) {
            items.add("Set the channel name (passcode)…");
            runs.add(new Runnable() {
                @Override
                public void run() {
                    PinLock.require(a, "Enter your PIN to change a channel name.", new PinLock.Done() {
                        @Override
                        public void onUnlocked() {
                            editName(slot);
                        }
                    }, null);
                }
            });
        }
        if (slot >= Prefs.SLOT_C) {
            for (final int target : new int[]{Prefs.SLOT_A, Prefs.SLOT_B}) {
                items.add("Swap with " + Prefs.letter(target) + " (name, note and listening move together)");
                runs.add(new Runnable() {
                    @Override
                    public void run() {
                        prefs.swap(slot, target);
                        Toast.makeText(a, "Swapped " + Prefs.letter(slot) + " and " + Prefs.letter(target), Toast.LENGTH_LONG).show();
                        changed();
                    }
                });
            }
        }
        final boolean muted = prefs.channelMuted(slot);
        items.add(muted ? "Unmute (play out loud while listening)" : "Mute (listen quietly: saved to Chat, not played)");
        runs.add(new Runnable() {
            @Override
            public void run() {
                prefs.setChannelMuted(slot, !muted);
                changed();
            }
        });
        new AlertDialog.Builder(a)
                .setTitle(MainActivity.label(slot))
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        runs.get(which).run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editName(final int slot) {
        final EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setText(prefs.channel(slot));
        e.setHint("Same name = same channel");
        new AlertDialog.Builder(a)
                .setTitle(MainActivity.label(slot) + " name (this is the passcode)")
                .setView(pad(e))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.setChannel(slot, e.getText().toString().trim());
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editCallsign() {
        final android.widget.EditText e = new android.widget.EditText(a);
        e.setSingleLine(true);
        e.setText(prefs.callsign());
        e.setSelectAllOnFocus(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new android.app.AlertDialog.Builder(a).setTitle("Callsign").setView(pad(e))
                .setMessage("The name others see when you talk or send.")
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String n = e.getText().toString().trim();
                        if (n.length() > 0) {
                            prefs.setCallsign(n);
                            RadioService rs = RadioService.instance();
                            RadioEngine en = rs == null ? null : rs.engine();
                            if (en != null) { // heard as the new name straight away, no rejoin
                                en.setCallsign(n);
                                for (RadioEngine m : en.monitors()) m.setCallsign(n);
                            }
                        }
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void editNote(final int slot) {
        final EditText e = new EditText(a);
        e.setMinLines(3);
        e.setText(prefs.channelNote(slot));
        e.setHint("Who is on it, call signs, times…");
        new AlertDialog.Builder(a)
                .setTitle("Note on " + MainActivity.label(slot))
                .setMessage("Kept on this phone only; moves with the channel when it is swapped.")
                .setView(pad(e))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.setChannelNote(slot, e.getText().toString().trim());
                        changed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private View pad(View v) {
        LinearLayout l = new LinearLayout(a);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        l.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private void confirmLeave(int slot) {
        new AlertDialog.Builder(a)
                .setTitle("Leave " + MainActivity.label(slot) + "?")
                .setMessage("You will stop hearing and being heard on it" + (prefs.dualWatch() ? ", and on the channels you are listening to." : "."))
                .setPositiveButton("Leave", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        host.channelAction(ChannelsActivity.LEAVE, -1);
                        shown = "";
                    }
                })
                .setNegativeButton("Stay", null)
                .show();
    }

    private ImageButton icon(int res, String desc, final Runnable r) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(res);
        b.setContentDescription(desc);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(44)));
        return b;
    }

    private Button button(String text, final Runnable r) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private void heading(String s) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(12);
        v.setTextColor(0xff99aabb);
        v.setPadding(0, dp(16), 0, 0);
        box.addView(v);
    }
}
