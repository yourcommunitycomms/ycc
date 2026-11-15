package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;

/**
 * Cloud group: a community channel like Public, with its own relay server (a "community" user on an
 * internet relay, so many phones share one relay key), separate from this phone's own tunnel and
 * internet relay. Name, key (the channel passcode), relay address and relay key; a share link and QR
 * code that carry only this group; and the location switch for Public and cloud groups.
 * <p>
 * Link: ycc://cloud/BASE64URL(JSON); QR: "YCCCLOUD1|" + JSON; JSON {"v":1,"n":name,"k":key,"r":relay,"rk":relay key}.
 */
public final class CloudGroupActivity extends Activity {
    public static final String QR_PREFIX = "YCCCLOUD1|";
    public static final String LINK_PREFIX = "ycc://cloud/";
    /** A scanned or pasted code to offer (QR text or link). */
    public static final String EXTRA_CODE = "code";
    private static final String EXTRA_NAME = "name", EXTRA_RELAY = "relay", EXTRA_RKEY = "rkey";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int REQ_SCAN = 41;
    private EditText name, key, relay, relayKey;
    private TextView status;
    private CheckBox shareLoc;
    private final Handler handler = new Handler();

    /** Relay admin: a new cloud group user on the relay; fill in its relay and key here. */
    static void openForRelay(Context c, String groupName, String relayAddr, String relayKey) {
        c.startActivity(new Intent(c, CloudGroupActivity.class).putExtra(EXTRA_NAME, groupName)
                .putExtra(EXTRA_RELAY, relayAddr).putExtra(EXTRA_RKEY, relayKey));
    }

    public static boolean isCode(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith(QR_PREFIX) || t.startsWith(LINK_PREFIX);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Cloud group");
        final Prefs pr = new Prefs(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(note("A community channel like Public that reaches people anywhere through its own Cloud Server (a relay), "
                + "separate from your own tunnel and internet relay. Treat it as public: anyone with its link can join "
                + "and hear it. It shows as \"Cloud\" with the other channels."));
        root.addView(head("\nGroup"));
        name = field(root, "Group name (shown on your phone)", pr.cloudName(), false);
        key = field(root, "Group key (the channel passcode; everyone uses the same)", pr.cloudKey(), false);
        root.addView(head("\nCloud Server"));
        relay = field(root, "Relay address (host:port)", pr.cloudRelay(), false);
        relayKey = field(root, "Relay key (a cloud group user on that relay)", pr.cloudRelayKey(), true);
        root.addView(note("The relay key comes from the relay's admin (Relay admin > Add cloud group), or with a share link."));

        LinearLayout r1 = row(root);
        button(r1, "Save").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        });
        button(r1, "Talk on it").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (save(false)) talk();
            }
        });
        LinearLayout r2 = row(root);
        button(r2, "Share link").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLink();
            }
        });
        button(r2, "Show QR code").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQr();
            }
        });
        LinearLayout r2b = row(root);
        button(r2b, "Show receive-only code").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReceiveOnly();
            }
        });
        root.addView(note("Receive-only code: phones that join with it can listen and read the group, but cannot "
                + "talk, send messages, positions or check-ins on it (like the Public listen key)."));
        LinearLayout r3 = row(root);
        button(r3, "Scan QR").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The app's own scanner hands back whatever it read; a cloud group code is then offered to join.
                startActivityForResult(new Intent(CloudGroupActivity.this, QrScanActivity.class)
                        .putExtra(QrScanActivity.EXTRA_ANY_TEXT, true), REQ_SCAN);
            }
        });
        button(r3, "Paste a link").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteLink();
            }
        });
        button(r3, "Remove group").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRemove();
            }
        });
        status = note("");
        root.addView(status);

        root.addView(head("\nYour location"));
        shareLoc = new CheckBox(this);
        shareLoc.setText("Share my location with public and cloud groups");
        shareLoc.setChecked(pr.shareLocationPublic());
        shareLoc.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                if (on) warnLocation();
                else {
                    pr.setShareLocationPublic(false);
                    Toast.makeText(CloudGroupActivity.this, "Your location will not go to Public or cloud groups", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(shareLoc);
        root.addView(note("Off (the default): no position goes to Public or a cloud group: not Send my location, not the "
                + "timer or distance updates, not check-ins, not map points. Private channels are not affected, and you "
                + "still see everyone's messages."));
        setContentView(scroll);

        Intent i = getIntent();
        if (i.getStringExtra(EXTRA_RELAY) != null) { // from Relay admin: a new cloud group user
            if (name.getText().length() == 0) name.setText(i.getStringExtra(EXTRA_NAME));
            relay.setText(i.getStringExtra(EXTRA_RELAY));
            relayKey.setText(i.getStringExtra(EXTRA_RKEY));
            if (key.getText().length() == 0) key.setText(randomKey());
            Toast.makeText(this, "Check the name and key, tap Save, then Share link", Toast.LENGTH_LONG).show();
        }
        String code = i.getStringExtra(EXTRA_CODE);
        if (code == null && i.getData() != null) code = i.getData().toString(); // a ycc://cloud/ link opened
        if (code != null) offer(code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCAN || res != RESULT_OK || data == null) return;
        String text = data.getStringExtra(QrScanActivity.EXTRA_RESULT);
        if (isCode(text)) offer(text);
        else Toast.makeText(this, "That QR code is not a cloud group code", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Prefs pr = new Prefs(CloudGroupActivity.this);
            String s = RadioService.cloudState();
            status.setText((receiveOnly(pr) ? "Receive only: this phone can listen and read this group, not send on it.\n" : "")
                    + (!pr.cloudSet() ? "No cloud group set."
                    : s != null ? s : !pr.cloudRelaySet() ? "No relay set: it works only on your own network."
                    : "Not connected: talk on or listen to Cloud to connect to its relay."));
            handler.postDelayed(this, 2000);
        }
    };

    private boolean save(boolean toast) {
        String n = name.getText().toString().trim(), k = key.getText().toString().trim();
        String r = relay.getText().toString().trim(), rk = relayKey.getText().toString().trim();
        if (k.length() == 0) {
            Toast.makeText(this, "Type a group key first", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (r.length() > 0 && r.lastIndexOf(':') <= 0) {
            Toast.makeText(this, "The relay address needs a port, e.g. host:47100", Toast.LENGTH_LONG).show();
            return false;
        }
        Prefs pr = new Prefs(this);
        if (k.equals(pr.publicKey().trim()) || k.equals(pr.channel(Prefs.SLOT_A).trim()) || k.equals(pr.channel(Prefs.SLOT_B).trim())) {
            Toast.makeText(this, "That key is already one of your other channels: pick another", Toast.LENGTH_LONG).show();
            return false;
        }
        boolean keyChanged = !k.equals(pr.cloudKey().trim());
        pr.setCloud(n, k, r, rk);
        RadioService rs = RadioService.instance();
        if (rs != null) rs.applyCloudNow();
        if (keyChanged && rs != null && rs.engine() != null && pr.activeSlot() == Prefs.SLOT_CLOUD) {
            talk(); // on air with the old key: join again with the new one
        }
        if (toast) Toast.makeText(this, "Cloud group saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void talk() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ChannelsActivity.EXTRA_ACTION, ChannelsActivity.JOIN).putExtra(ChannelsActivity.EXTRA_SLOT, Prefs.SLOT_CLOUD));
    }

    // ---- link and QR ----

    /** This phone holds only the group's receive-only code (a listen key): it can listen and read, not send. */
    static boolean receiveOnly(Prefs pr) {
        return pr.cloudSet() && org.communitycomms.app.crypto.ListenKey.isListenKey(pr.cloudKey());
    }

    private static JSONObject json(Prefs pr) throws org.json.JSONException {
        return json(pr, pr.cloudKey());
    }

    /** k = the full group key, or its receive-only listen key (then "ro":1). */
    private static JSONObject json(Prefs pr, String k) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("n", pr.cloudName());
        o.put("k", k);
        if (org.communitycomms.app.crypto.ListenKey.isListenKey(k)) o.put("ro", 1);
        if (pr.cloudRelay().length() > 0) o.put("r", pr.cloudRelay());
        if (pr.cloudRelayKey().length() > 0) o.put("rk", pr.cloudRelayKey());
        return o;
    }

    /** The share link for the saved group, or null. */
    static String link(Context c) {
        Prefs pr = new Prefs(c);
        if (!pr.cloudSet()) return null;
        try {
            return LINK_PREFIX + Base64.encodeToString(json(pr).toString().getBytes(UTF8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    static String qrText(Context c) {
        Prefs pr = new Prefs(c);
        if (!pr.cloudSet()) return null;
        try {
            return QR_PREFIX + json(pr).toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Reads a QR text or a link; null if it is not a cloud group code. */
    static JSONObject parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        try {
            if (t.startsWith(QR_PREFIX)) return new JSONObject(t.substring(QR_PREFIX.length()));
            int at = t.indexOf(LINK_PREFIX);
            if (at >= 0) {
                String b64 = t.substring(at + LINK_PREFIX.length()).trim();
                int end = 0;
                while (end < b64.length() && (Character.isLetterOrDigit(b64.charAt(end)) || b64.charAt(end) == '-' || b64.charAt(end) == '_')) end++;
                return new JSONObject(new String(Base64.decode(b64.substring(0, end), Base64.URL_SAFE | Base64.NO_WRAP), UTF8));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean savedOk() {
        if (!new Prefs(this).cloudSet()) {
            Toast.makeText(this, "Save a cloud group first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void shareLink() {
        if (!savedOk()) return;
        final String l = link(this);
        new AlertDialog.Builder(this)
                .setTitle("Share this cloud group?")
                .setMessage("The link holds the group key and its relay key: anyone who gets it can join, hear "
                        + "everything on it and use its relay. Share it only where you mean to.")
                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs pr = new Prefs(CloudGroupActivity.this);
                        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Cloud group " + pr.cloudName())
                                .putExtra(Intent.EXTRA_TEXT, "Join the cloud group \"" + pr.cloudName() + "\" in Your Community Communications: "
                                        + "open this link on a phone with the app, or paste it in Cloud group > Paste a link.\n" + l);
                        try {
                            startActivity(Intent.createChooser(s, "Share the cloud group link"));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(CloudGroupActivity.this, "No app on this phone can share text", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showQr() {
        if (!savedOk()) return;
        ArrayList<String> p = new ArrayList<String>();
        ArrayList<String> t = new ArrayList<String>();
        p.add(qrText(this));
        t.add("Cloud group " + new Prefs(this).cloudName() + " (anyone who scans it can join)");
        QrShowActivity.start(this, p, t, null);
    }

    /**
     * The receive-only code: the group's listen key (worked out from the full key, a few seconds on old
     * phones) in the same link / QR form, with "ro":1. Phones that join with it can only listen and read.
     */
    private void showReceiveOnly() {
        if (!savedOk()) return;
        final Prefs pr = new Prefs(this);
        final String key = pr.cloudKey();
        Toast.makeText(this, "Working out the receive-only code…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String lk;
                try {
                    lk = org.communitycomms.app.crypto.ListenKey.isListenKey(key) ? key.trim()
                            : org.communitycomms.app.crypto.ListenKey.fromFull(key).shareable();
                } catch (Exception e) {
                    lk = null;
                }
                final String listen = lk;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (listen == null) {
                            Toast.makeText(CloudGroupActivity.this, "Could not make the receive-only code", Toast.LENGTH_LONG).show();
                            return;
                        }
                        offerReceiveOnly(pr, listen);
                    }
                });
            }
        }, "cloud-listen-key").start();
    }

    private void offerReceiveOnly(Prefs pr, String listen) {
        final String qr, link;
        try {
            JSONObject o = json(pr, listen);
            qr = QR_PREFIX + o.toString();
            link = LINK_PREFIX + Base64.encodeToString(o.toString().getBytes(UTF8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (org.json.JSONException e) {
            return;
        }
        final String name = pr.cloudName();
        new AlertDialog.Builder(this)
                .setTitle("Receive-only code")
                .setMessage("Phones that join with this code can listen to \"" + name + "\" and read its messages, but "
                        + "cannot talk, send messages, positions or check-ins on it. It still holds the relay key, so "
                        + "they use the relay to listen.")
                .setPositiveButton("Show QR code", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ArrayList<String> p = new ArrayList<String>();
                        ArrayList<String> t = new ArrayList<String>();
                        p.add(qr);
                        t.add("Cloud group " + name + " (receive only: listen and read)");
                        QrShowActivity.start(CloudGroupActivity.this, p, t, null);
                    }
                })
                .setNeutralButton("Share link", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Cloud group " + name + " (receive only)")
                                .putExtra(Intent.EXTRA_TEXT, "Listen to the cloud group \"" + name + "\" in Your Community Communications "
                                        + "(receive only): open this link on a phone with the app, or paste it in Cloud group > Paste a link.\n" + link);
                        try {
                            startActivity(Intent.createChooser(s, "Share the receive-only link"));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(CloudGroupActivity.this, "No app on this phone can share text", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pasteLink() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence c = cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0 ? null
                : cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (c == null || !isCodeIn(c.toString())) {
            Toast.makeText(this, "Copy a cloud group link first (it starts " + LINK_PREFIX + ")", Toast.LENGTH_LONG).show();
            return;
        }
        offer(c.toString());
    }

    private static boolean isCodeIn(String s) {
        return s.contains(LINK_PREFIX) || s.trim().startsWith(QR_PREFIX);
    }

    /** Asks before taking a scanned / opened / pasted group (it replaces the current one). */
    private void offer(String code) {
        final JSONObject o = parse(code);
        if (o == null || o.optString("k").trim().length() == 0) {
            Toast.makeText(this, "That cloud group link is damaged", Toast.LENGTH_LONG).show();
            return;
        }
        final Prefs pr = new Prefs(this);
        final String n = o.optString("n").trim(), k = o.optString("k").trim(), r = o.optString("r").trim(), rk = o.optString("rk").trim();
        final boolean ro = o.optInt("ro") == 1 || org.communitycomms.app.crypto.ListenKey.isListenKey(k);
        new AlertDialog.Builder(this)
                .setTitle((ro ? "Listen to" : "Join") + " cloud group " + (n.length() > 0 ? "\"" + n + "\"" : "") + "?")
                .setMessage((ro ? "RECEIVE ONLY: this phone will be able to listen and read, but not talk, send messages, "
                        + "positions or check-ins on this group.\n\n" : "")
                        + (pr.cloudSet() && !k.equals(pr.cloudKey()) ? "It replaces your cloud group \"" + pr.cloudName() + "\".\n\n" : "")
                        + (r.length() > 0 ? "It uses its own Cloud Server (" + r + ")." : "It has no relay: it works only on your own network.")
                        + "\n\nTreat it as public: anyone with the link can hear it. Your location is not sent to it unless you "
                        + "tick \"Share my location with public and cloud groups\".")
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        name.setText(n);
                        key.setText(k);
                        relay.setText(r);
                        relayKey.setText(rk);
                        if (save(false)) {
                            pr.setShown(Prefs.SLOT_CLOUD, true);
                            talk();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove() {
        final Prefs pr = new Prefs(this);
        if (!pr.cloudSet()) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove the cloud group?")
                .setMessage("This phone forgets \"" + pr.cloudName() + "\", its key and its relay. Join again from its link.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean onIt = pr.activeSlot() == Prefs.SLOT_CLOUD;
                        pr.setCloud("", "", "", "");
                        pr.setListening(Prefs.SLOT_CLOUD, false);
                        name.setText("");
                        key.setText("");
                        relay.setText("");
                        relayKey.setText("");
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.applyCloudNow();
                        if (onIt) {
                            pr.setActiveSlot(Prefs.SLOT_A);
                            startActivity(new Intent(CloudGroupActivity.this, MainActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra(ChannelsActivity.EXTRA_ACTION, ChannelsActivity.JOIN).putExtra(ChannelsActivity.EXTRA_SLOT, Prefs.SLOT_A));
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void warnLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Share your location with everyone?")
                .setMessage("WARNING: with this on, your position goes to EVERYONE on Public and on cloud groups: people "
                        + "you may not know, anywhere the relay reaches. Once sent it cannot be taken back, and anyone "
                        + "can keep it, map it or pass it on.\n\nThis covers Send my location, timed and distance updates, "
                        + "check-ins and map points on those channels.\n\nOnly turn it on if you are sure.")
                .setPositiveButton("Yes, share my location", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new Prefs(CloudGroupActivity.this).setShareLocationPublic(true);
                        Toast.makeText(CloudGroupActivity.this, "Your location now goes to Public and cloud groups too", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("No, keep it off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shareLoc.setChecked(false);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        shareLoc.setChecked(false);
                    }
                })
                .show();
    }

    private static String randomKey() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("cloud-");
        for (int i = 0; i < 16; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    // ---- small helpers ----

    private EditText field(LinearLayout root, String label, String value, boolean secret) {
        root.addView(note(label));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : 0));
        root.addView(e);
        return e;
    }

    private TextView head(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTextColor(0xffa5d6a7);
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private Button button(LinearLayout r, String label) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        r.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return bt;
    }
}
