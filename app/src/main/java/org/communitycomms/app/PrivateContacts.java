package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Person to person private comms, no channel needed: one phone shows a QR with a fresh key, who it is for and
 * its remote server. Each private contact is listened to on its own key, and listed at the bottom of Contacts.
 */
public final class PrivateContacts {
    public static final String PREFIX = "YCCPRIV1|";
    private static final String FILE = "private-contacts.json";

    private PrivateContacts() {
    }

    public static final class Person {
        public String name = "", key = "", remote = "";
        public boolean unknown;
    }

    public static List<Person> load(Context c) {
        List<Person> out = new ArrayList<Person>();
        try {
            String t = ChannelArray.read(c, FILE);
            if (t.length() == 0) return out;
            JSONArray a = new JSONArray(t);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Person p = new Person();
                p.name = o.optString("name");
                p.key = o.optString("key");
                p.remote = o.optString("remote");
                p.unknown = o.optBoolean("unknown");
                out.add(p);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Person> all) {
        JSONArray a = new JSONArray();
        try {
            for (Person p : all) a.put(new JSONObject().put("name", p.name).put("key", p.key).put("remote", p.remote).put("unknown", p.unknown));
        } catch (JSONException ignored) {
        }
        ChannelArray.write(c, FILE, a.toString());
    }

    private static void add(Context c, String name, String key, String remote, boolean unknown) {
        List<Person> all = load(c);
        Person p = new Person();
        p.name = name;
        p.key = key;
        p.remote = remote;
        p.unknown = unknown;
        all.add(p);
        save(c, all);
    }

    public static void delete(Context c, Person gone) {
        List<Person> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).key.equals(gone.key)) all.remove(i);
        save(c, all);
    }

    /** Asks who it is for, then shows the QR. */
    public static void share(final Activity a) {
        final Prefs pr = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        TextView t = new TextView(a);
        t.setText("A private link between you and one person, with no channel. They scan the QR code.");
        box.addView(t);
        final EditText who = new EditText(a), remote = new EditText(a);
        who.setSingleLine(true);
        who.setHint("Their callsign");
        remote.setSingleLine(true);
        remote.setHint("Your remote server and port (optional)");
        remote.setText(pr.tunnelServer());
        box.addView(who);
        box.addView(remote);
        new AlertDialog.Builder(a).setTitle("Share my private comms").setView(box)
                .setPositiveButton("Show QR", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String to = who.getText().toString().trim();
                        if (to.length() == 0) {
                            Toast.makeText(a, "Type their callsign", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String key = ChannelArray.newKey(), srv = remote.getText().toString().trim();
                        add(a, to, key, "", false);
                        JSONObject o = new JSONObject();
                        try {
                            o.put("key", key).put("from", pr.callsign()).put("to", to).put("remote", srv);
                        } catch (JSONException e) {
                            return;
                        }
                        ArrayList<String> p = new ArrayList<String>(), n = new ArrayList<String>();
                        p.add(PREFIX + o);
                        n.add("Private comms for " + to);
                        QrShowActivity.start(a, p, n, null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** A scanned QR. A code meant for another callsign can be ignored, or kept as an unknown contact. */
    public static void offer(final Activity a, String text, final Runnable done) {
        final JSONObject o;
        try {
            o = new JSONObject(text.substring(PREFIX.length()));
        } catch (JSONException e) {
            Toast.makeText(a, "That code is damaged", Toast.LENGTH_LONG).show();
            done.run();
            return;
        }
        final String from = o.optString("from"), to = o.optString("to"), key = o.optString("key"), remote = o.optString("remote");
        final boolean mine = to.equalsIgnoreCase(new Prefs(a).callsign());
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Private comms")
                .setMessage(mine ? "Add " + from + " as a private contact?"
                        : "This was made for " + to + ", but your callsign is " + new Prefs(a).callsign()
                        + ". Ignore it, or keep it as an unknown contact?")
                .setNegativeButton(mine ? android.R.string.cancel : R.string.ignore_it, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        done.run();
                    }
                })
                .setPositiveButton(mine ? "Add" : "Unknown contact", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        add(a, mine ? from : "Unknown contact (" + from + ")", key, remote, !mine);
                        Toast.makeText(a, "Added under Contacts", Toast.LENGTH_LONG).show();
                        done.run();
                    }
                });
        b.setCancelable(false).show();
    }

    /** Talks to one key: the spare channel H goes on air with it, and its remote server is added. */
    public static void talk(Activity a, String key, String remote) {
        Prefs pr = new Prefs(a);
        pr.setChannel(Prefs.SLOT_H, key);
        if (remote != null && remote.trim().length() > 0 && !pr.extraPeers().contains(remote.trim())) {
            pr.setNetwork(pr.netMode(), pr.port(), pr.extraPeers().trim().length() == 0 ? remote.trim() : pr.extraPeers().trim() + "," + remote.trim());
        }
        a.startActivity(new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ChannelsActivity.EXTRA_ACTION, ChannelsActivity.JOIN).putExtra(ChannelsActivity.EXTRA_SLOT, Prefs.SLOT_H));
    }
}
