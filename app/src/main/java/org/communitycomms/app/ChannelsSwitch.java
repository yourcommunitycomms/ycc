package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Top of the channels pages: Simple channels | Advanced channels. This is the same setting as the group type
 * (Settings > Me): Simple is one private key, Public and the Cloud group; Advanced has A to H, DRYAD and the rest.
 */
final class ChannelsSwitch {
    private ChannelsSwitch() {
    }

    static View header(final Activity a, final boolean simpleNow) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), 0);
        Button simple = tab(a, "Simple channels", simpleNow);
        Button adv = tab(a, "Advanced channels", !simpleNow);
        simple.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!simpleNow) toSimple(a);
            }
        });
        adv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (simpleNow) toAdvanced(a);
            }
        });
        row.addView(simple, new LinearLayout.LayoutParams(0, (int) (46 * d), 1));
        row.addView(adv, new LinearLayout.LayoutParams(0, (int) (46 * d), 1));
        return row;
    }

    private static Button tab(Activity a, String text, boolean on) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(on ? 0xff0f1f16 : 0xffffffff);
        if (on) b.getBackground().mutate().setColorFilter(0xfff0c75e, android.graphics.PorterDuff.Mode.SRC_ATOP);
        return b;
    }

    private static void toAdvanced(final Activity a) {
        new AlertDialog.Builder(a).setTitle("Advanced channels")
                .setMessage("Channels A to H, Public, the Cloud group and OpenMANET, with listening, pins and tunnels, plus "
                        + "DRYAD sheets, Pigpen and Enigma. Your private key stays as channel A.\n\nSwitch to Advanced?")
                .setPositiveButton("Advanced", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        MainActivity.applyMode(a, Prefs.MODE_ADVANCED);
                        a.startActivity(new Intent(a, ChannelsActivity.class));
                        a.finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /**
     * Back to Simple without asking: the simple group type used last (General if none), keeping the quick button
     * profile in use. The profile is changed from Shortcuts > Edit current layout, or the group type in Settings > Me.
     */
    private static void toSimple(final Activity a) {
        Prefs p = new Prefs(a);
        org.communitycomms.app.geo.QuickProfiles qp = new org.communitycomms.app.geo.QuickProfiles(a);
        int profile = qp.profile();
        MainActivity.applyMode(a, p.lastSimpleMode());
        qp.setProfile(profile);
        android.widget.Toast.makeText(a, "Simple channels (" + Prefs.MODE_NAMES[p.appMode()] + "): DRYAD, Pigpen and OpenMANET are hidden, nothing is deleted",
                android.widget.Toast.LENGTH_LONG).show();
        a.startActivity(new Intent(a, KeysActivity.class));
        a.finish();
    }
    /** Advanced channels page: links to the key tools. */
    static View links(final Activity a) {
        final Prefs p = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (10 * d), 0, (int) (10 * d), (int) (12 * d));
        TextView h = new TextView(a);
        h.setText("Keys and codes");
        h.setTextSize(12);
        h.setTextColor(0xff99aabb);
        h.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(h);
        java.util.List<Button> row = new java.util.ArrayList<Button>();
        row.add(link(a, "DRYAD sheets", DryadActivity.class));
        row.add(link(a, "Pigpen", PigpenActivity.class));
        row.add(link(a, "Enigma / CW", EnigmaActivity.class));
        box.addView(FilesActivity.row(row.toArray(new Button[0])));
        java.util.List<Button> row2 = new java.util.ArrayList<Button>();
        if (p.menuOn("array")) row2.add(link(a, "Channel array", ChannelArrayActivity.class));
        if (p.cloudFeature()) row2.add(link(a, "Cloud group…", CloudGroupActivity.class));
        if (!row2.isEmpty()) box.addView(FilesActivity.row(row2.toArray(new Button[0])));
        // The same two as the Simple channels page: the same channel QR, so either page reads the other's code.
        Button show = new Button(a);
        show.setText("Show QR");
        show.setAllCaps(false);
        show.setTextSize(13);
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickQr(a);
            }
        });
        Button scan = link(a, "Read QR", QrScanActivity.class);
        box.addView(FilesActivity.row(new Button[]{show, scan}));
        return box;
    }

    /** Show QR on the Advanced page: which channel (only those with a key), then the same QR as the Simple page. */
    private static void pickQr(final Activity a) {
        Prefs p = new Prefs(a);
        final java.util.List<Integer> slots = new java.util.ArrayList<Integer>();
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (int s : Prefs.order()) {
            if (s == Prefs.SLOT_OPENMANET) continue;
            if (s != Prefs.SLOT_CLOUD && p.channel(s).length() == 0) continue;
            if (s == Prefs.SLOT_CLOUD && !p.cloudFeature()) continue;
            slots.add(s);
            names.add(MainActivity.label(s));
        }
        if (slots.isEmpty()) {
            android.widget.Toast.makeText(a, "No channel has a key yet", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (slots.size() == 1) {
            MainActivity.showChannelQr(a, slots.get(0));
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Show the QR code for")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        MainActivity.showChannelQr(a, slots.get(which));
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
        TvFocus.watch(dlg.getWindow());
    }

    private static Button link(final Activity a, String text, final Class<?> screen) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.startActivity(new Intent(a, screen));
            }
        });
        return b;
    }
}
