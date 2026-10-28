package org.communitycomms.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import org.communitycomms.app.dryad.DryadSheet;
import org.communitycomms.app.dryad.SheetStore;

import java.util.ArrayList;
import java.util.List;

/** Pick one or many sheets and send them as one text message (any app: SMS, email, notes…). */
public final class DryadShareActivity extends Activity {
    private ListView list;
    private List<SheetStore.Entry> entries;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.share_title);
        entries = new SheetStore(this).loadAll(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        Button all = new Button(this);
        all.setText(R.string.share_all_valid);
        Button none = new Button(this);
        none.setText(R.string.share_none);
        row.addView(all, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(none, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<String>();
        List<Integer> colours = new ArrayList<Integer>();
        for (SheetStore.Entry e : entries) {
            labels.add(DryadActivity.label(e.sheet, now) + "\n" + DryadActivity.window(e.sheet));
            colours.add(e.sheet.colour == null ? null : org.communitycomms.app.dryad.SheetColour.argb(e.sheet.colour));
        }
        list.setAdapter(new org.communitycomms.app.dryad.ColourAdapter(this,
                android.R.layout.simple_list_item_multiple_choice, labels, colours));
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        Button air = new Button(this);
        air.setText(R.string.share_air);
        Button go = new Button(this);
        go.setText(R.string.share_go);
        Button qr = new Button(this);
        qr.setText(R.string.show_qr);
        actions.addView(qr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        qr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<DryadSheet> sel = selectedSheets();
                if (sel.isEmpty()) Toast.makeText(DryadShareActivity.this, "Select at least one sheet", Toast.LENGTH_SHORT).show();
                else showQr(DryadShareActivity.this, sel);
            }
        });
        actions.addView(air, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(go, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);
        air.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSelectedOnAir();
            }
        });
        setContentView(root);

        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long t = System.currentTimeMillis();
                for (int i = 0; i < entries.size(); i++) {
                    int st = entries.get(i).sheet.status(t);
                    list.setItemChecked(i, st == DryadSheet.ACTIVE || st == DryadSheet.UPCOMING);
                }
            }
        });
        none.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                list.clearChoices();
                list.requestLayout();
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        all.performClick();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PinLock.gateScreen(this, "Enter your PIN to share DRYAD sheets.");
    }

    /** One QR per sheet, in the sheet's colour. */
    static void showQr(android.content.Context c, List<DryadSheet> sheets) {
        ArrayList<String> payloads = new ArrayList<String>();
        ArrayList<String> titles = new ArrayList<String>();
        int[] colours = new int[sheets.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < sheets.size(); i++) {
            DryadSheet s = sheets.get(i);
            if (!s.fitsQr()) {
                android.widget.Toast.makeText(c, s.name + " is a huge AA–ZZ sheet: too big for a QR code. Share it as text or on air.",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            payloads.add(s.toQr());
            titles.add(DryadActivity.label(s, now));
            colours[i] = s.colour == null ? 0 : org.communitycomms.app.dryad.SheetColour.argb(s.colour);
        }
        QrShowActivity.start(c, payloads, titles, colours);
    }

    private List<DryadSheet> selectedSheets() {
        SparseBooleanArray checked = list.getCheckedItemPositions();
        List<DryadSheet> out = new ArrayList<DryadSheet>();
        for (int i = 0; i < entries.size(); i++) {
            if (checked.get(i)) out.add(entries.get(i).sheet);
        }
        return out;
    }

    /**
     * Sends sheet text over the channel that is on air, with feedback. On Public it warns first
     * (anyone with the app can read Public) and sends only if the user says so.
     */
    static void sendOnAir(final android.content.Context c, final String text, final int n) {
        if (RadioEngine.radioSilent) {
            Toast.makeText(c, "Radio silent: sheets not sent", Toast.LENGTH_LONG).show();
            return;
        }
        if (RadioService.onPublic()) {
            new android.app.AlertDialog.Builder(c)
                    .setTitle("You are on Public")
                    .setMessage("Anyone on Public with this app can read these " + n + " DRYAD sheet(s), "
                            + "which defeats their purpose if the wrong people are listening. Send anyway?")
                    .setPositiveButton("Send on Public", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            report(c, RadioService.sendDryad(text, true), n);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        report(c, RadioService.sendDryad(text, false), n);
    }

    private static void report(android.content.Context c, int result, int n) {
        Toast.makeText(c, result == RadioService.SEND_OK ? "Sending " + n + " sheet(s) to everyone on the channel"
                : "Join a channel on the radio screen first", Toast.LENGTH_LONG).show();
    }

    /** "Send on air" from the sheet screen: tick which sheets to send, all ticked to start with. */
    static void pickAndSend(final android.content.Context c, final List<SheetStore.Entry> entries) {
        if (entries.isEmpty()) {
            Toast.makeText(c, "No sheets to send", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        final String[] items = new String[entries.size() + 1];
        final boolean[] checked = new boolean[items.length];
        items[0] = "All (" + entries.size() + ")";
        for (int i = 0; i < entries.size(); i++) items[i + 1] = DryadActivity.label(entries.get(i).sheet, now);
        java.util.Arrays.fill(checked, true);
        new android.app.AlertDialog.Builder(c)
                .setTitle("Send sheets on air")
                .setMultiChoiceItems(items, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which, boolean on) {
                        ListView lv = ((android.app.AlertDialog) d).getListView();
                        if (which == 0) { // "All" ticks or clears everything
                            for (int i = 1; i < checked.length; i++) {
                                checked[i] = on;
                                lv.setItemChecked(i, on);
                            }
                        } else {
                            checked[which] = on;
                            boolean all = true;
                            for (int i = 1; i < checked.length; i++) all &= checked[i];
                            checked[0] = all;
                            lv.setItemChecked(0, all);
                        }
                    }
                })
                .setPositiveButton("Send", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        for (int i = 0; i < entries.size(); i++) {
                            if (!checked[i + 1]) continue;
                            if (n > 0) sb.append('\n');
                            sb.append(entries.get(i).sheet.toText());
                            n++;
                        }
                        if (n == 0) Toast.makeText(c, "Select at least one sheet", Toast.LENGTH_SHORT).show();
                        else sendOnAir(c, sb.toString(), n);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String selectedText(int[] countOut) {
        SparseBooleanArray checked = list.getCheckedItemPositions();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (checked.get(i)) {
                if (n > 0) sb.append('\n');
                sb.append(entries.get(i).sheet.toText());
                n++;
            }
        }
        countOut[0] = n;
        return sb.toString();
    }

    private void sendSelectedOnAir() {
        int[] n = new int[1];
        String text = selectedText(n);
        if (n[0] == 0) {
            Toast.makeText(this, "Select at least one sheet", Toast.LENGTH_SHORT).show();
            return;
        }
        sendOnAir(this, text, n[0]);
    }

    private void share() {
        SparseBooleanArray checked = list.getCheckedItemPositions();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        String first = null;
        for (int i = 0; i < entries.size(); i++) {
            if (checked.get(i)) {
                if (first == null) first = entries.get(i).sheet.name;
                if (n > 0) sb.append('\n');
                sb.append(entries.get(i).sheet.toText());
                n++;
            }
        }
        if (n == 0) {
            Toast.makeText(this, "Select at least one sheet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, n == 1 ? first : n + " DRYAD sheets");
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, "Share " + n + " sheet(s)"));
    }
}
