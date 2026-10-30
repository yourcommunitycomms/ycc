package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.msg.MessageLog;
import org.communitycomms.app.msg.Report;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat: received and sent messages, reports, map points and pictures on the channel on air,
 * oldest at the top. Type at the bottom to send a text message.
 */
public final class ChatActivity extends Activity {
    private ListView list;
    private TextView header;
    private EditText input;
    private final List<MessageLog.Entry> rows = new ArrayList<MessageLog.Entry>();
    private final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();
    private final Adapter adapter = new Adapter();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Chat");
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        header = new TextView(this);
        header.setTextSize(12);
        header.setTextColor(0xff99aabb);
        header.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        // The header line with a small Filter button at its end (which kinds of item to show).
        LinearLayout headRow = new LinearLayout(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(header, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        filterBtn = new Button(this);
        filterBtn.setTextSize(12);
        filterBtn.setAllCaps(false);
        filterBtn.setMinWidth(0);
        filterBtn.setMinimumWidth(0);
        filterBtn.setMinHeight(0);
        filterBtn.setMinimumHeight((int) (36 * d));
        filterBtn.setPadding((int) (10 * d), 0, (int) (10 * d), 0);
        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseFilter();
            }
        });
        headRow.addView(filterBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (36 * d)));
        root.addView(headRow);
        // Pinned announcements, always at the top (tap one to unpin it if it is yours).
        pinBar = new LinearLayout(this);
        pinBar.setOrientation(LinearLayout.VERTICAL);
        pinBar.setBackgroundColor(0xff3e2f0b);
        pinBar.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        pinBar.setVisibility(View.GONE);
        root.addView(pinBar);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout tools = new LinearLayout(this);
        // Equal boxes in line: no baseline shift when a label wraps onto two lines.
        tools.setBaselineAligned(false);
        tools.addView(button("Report…", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(ChatActivity.this, ReportsActivity.class));
            }
        }), weight());
        tools.addView(button("Picture / file", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(ChatActivity.this, FilesActivity.class));
            }
        }), weight());
        tools.addView(button("Map", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(ChatActivity.this, MapActivity.class));
            }
        }), weight());
        tools.addView(button("Announce…", new Runnable() {
            @Override
            public void run() {
                announce();
            }
        }), weight());
        int gap = (int) (4 * d);
        tools.setPadding(gap, 0, gap, 0);
        root.addView(tools);

        // Recipient, message box and Send on one line, all the same height and centred.
        int h = (int) (56 * d);
        LinearLayout send = new LinearLayout(this);
        send.setBaselineAligned(false);
        send.setGravity(Gravity.CENTER_VERTICAL);
        send.setPadding(gap, 0, gap, gap);
        to = new Button(this);
        to.setTextSize(12);
        to.setAllCaps(false);
        to.setMinWidth(0);
        to.setMinimumWidth((int) (88 * d));
        to.setMinimumHeight(0);
        to.setMinHeight(0);
        to.setPadding((int) (8 * d), 0, (int) (8 * d), 0);
        to.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRecipient();
            }
        });
        renderTo();
        send.addView(to, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h));
        input = new EditText(this);
        input.setHint("Message");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(4);
        send.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button sendBtn = button("Send", new Runnable() {
            @Override
            public void run() {
                sendText();
            }
        });
        sendBtn.setMinimumWidth((int) (88 * d));
        send.addView(sendBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h));
        root.addView(send);
        setContentView(root);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                open(rows.get(pos));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        MessageLog.ui = refresh;
        RadioService.chatVisible = true;
        MessageLog.unread = 0;
        render();
    }

    @Override
    protected void onPause() {
        if (MessageLog.ui == refresh) MessageLog.ui = null;
        RadioService.chatVisible = false;
        super.onPause();
    }

    static int channelOnAir() {
        RadioService s = RadioService.instance();
        RadioEngine e = s == null ? null : s.engine();
        return e == null ? 0 : e.channelTag();
    }

    /** Tags of the channels only listened to (dual watch), shown alongside the one on air. */
    private int[] watchTags = new int[0];

    private boolean watched(int tag) {
        for (int t : watchTags) if (t == tag) return true;
        return false;
    }

    private void render() {
        int tag = channelOnAir();
        RadioService rs = RadioService.instance();
        watchTags = tag == 0 || rs == null ? new int[0] : rs.watchTags();
        header.setText(tag == 0 ? "Not on a channel: showing history from every channel. Join one to send."
                : "Channel on air (tag " + Integer.toHexString(tag) + ")"
                + (watchTags.length > 0 ? ", plus the channels you are listening to (marked LISTEN ONLY)" : "")
                + ". Tap an item for details.");
        rows.clear();
        List<MessageLog.Entry> all;
        int pick = chatChannel();
        if (pick == -1) {
            all = MessageLog.get(this).all(0, new int[0]);
            header.setText("Showing every channel (Filter to change). Tap an item for details.");
        } else if (pick >= 0) {
            Integer pt = org.communitycomms.app.geo.PoiFilter.tag(this, pick);
            if (pt == null) { // working the channel out (a moment): show the one on air meanwhile
                all = MessageLog.get(this).all(tag, watchTags);
                list.postDelayed(refresh, 800);
            } else {
                all = pt == 0 ? new ArrayList<MessageLog.Entry>() : MessageLog.get(this).all(pt, new int[0]);
                header.setText("Showing channel " + Prefs.letter(pick) + " only (Filter to change)"
                        + (pt == tag ? "" : ". You send on the channel on air") + ".");
            }
        } else {
            all = MessageLog.get(this).all(tag, watchTags);
        }
        // Pinned announcements stay at the top; unpin notices are not shown.
        java.util.Set<Long> down = org.communitycomms.app.msg.MessageCenter.unpinned(all);
        List<MessageLog.Entry> pins = new ArrayList<MessageLog.Entry>();
        for (MessageLog.Entry m : all) {
            if (org.communitycomms.app.msg.MessageCenter.isUnpin(m)) continue;
            if (org.communitycomms.app.msg.MessageCenter.isPin(m) && !down.contains(m.id)) pins.add(m);
            else if ((hidden() & (1 << category(m))) == 0) rows.add(m);
        }
        int h = hidden();
        int hiddenKinds = Integer.bitCount(h);
        filterBtn.setText((h == 0 || h == DEFAULT_HIDDEN) && pick == -2 ? "Filter" : "Filter (" + (pick == -2 ? "" : pick == -1 ? "all ch" : Prefs.letter(pick))
                + (pick != -2 && hiddenKinds > 0 ? ", " : "") + (hiddenKinds > 0 ? hiddenKinds + " hidden" : "") + ")");
        pinnedCount = pins.size();
        if (!pins.isEmpty()) showPins(pins);
        else pinBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ---- filter: which kinds of item to show (pinned announcements always show) ----

    private Button filterBtn;
    static final String[] CATEGORIES = {"Messages", "Voice", "Pictures and files", "Map points", "Reports",
            "Status changes and notices (auto acks, lost contact, away / available)"};

    /** 0 messages, 1 voice, 2 pictures and files, 3 map points, 4 reports, 5 status changes and notices. */
    static int category(MessageLog.Entry m) {
        if (m.type == MessageLog.TYPE_REPORT) return 4;
        if (m.type == MessageLog.TYPE_POI) return 3;
        if (m.type == MessageLog.TYPE_FILE) return m.mime != null && m.mime.startsWith("audio/") ? 1 : 2;
        if (m.local && m.body != null && m.body.startsWith("MISSED CALL")) return 1; // someone tried to reach you: never hidden as a notice
        return m.ack || m.local ? 5 : 0;
    }

    /** Status changes and notices start hidden. */
    private static final int DEFAULT_HIDDEN = 1 << 5;

    private int hidden() {
        return getSharedPreferences("settings", MODE_PRIVATE).getInt("chatHidden", DEFAULT_HIDDEN);
    }

    /** Chat > Filter > Channel: -2 the channel on air (and those listened to), -1 all, or a slot. */
    private int chatChannel() {
        return getSharedPreferences("settings", MODE_PRIVATE).getInt("chatChannel", -2);
    }

    private static final String[] CHANNEL_NAMES = {"Channel on air (and the ones you listen to)", "All channels",
            "A (Private)", "B", "Public"};
    private static final int[] CHANNEL_VALUES = {-2, -1, Prefs.SLOT_A, Prefs.SLOT_B, Prefs.SLOT_PUBLIC};

    private void chooseFilter() {
        float d = getResources().getDisplayMetrics().density;
        final int h = hidden();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        TextView cl = new TextView(this);
        cl.setText("Channel");
        box.addView(cl);
        final android.widget.Spinner chan = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> ca = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, CHANNEL_NAMES);
        ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chan.setAdapter(ca);
        int cur = chatChannel();
        for (int i = 0; i < CHANNEL_VALUES.length; i++) if (CHANNEL_VALUES[i] == cur) chan.setSelection(i);
        box.addView(chan);
        TextView sl = new TextView(this);
        sl.setText("Show");
        sl.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(sl);
        final android.widget.CheckBox[] show = new android.widget.CheckBox[CATEGORIES.length];
        for (int i = 0; i < show.length; i++) {
            show[i] = new android.widget.CheckBox(this);
            show[i].setText(CATEGORIES[i]);
            show[i].setChecked((h & (1 << i)) == 0);
            box.addView(show[i]);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Chat filter")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        int out = 0;
                        for (int i = 0; i < show.length; i++) if (!show[i].isChecked()) out |= 1 << i;
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("chatHidden", out)
                                .putInt("chatChannel", CHANNEL_VALUES[chan.getSelectedItemPosition()]).commit();
                        render();
                    }
                })
                .setNeutralButton("Show all", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("chatHidden", 0)
                                .putInt("chatChannel", -2).commit();
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    // ---- pinned announcements ----

    private LinearLayout pinBar;
    private int pinnedCount;

    private void showPins(List<MessageLog.Entry> pins) {
        pinBar.removeAllViews();
        pinBar.setVisibility(View.VISIBLE);
        for (final MessageLog.Entry m : pins) {
            TextView t = new TextView(this);
            t.setTextSize(15);
            t.setTextColor(0xfff0c75e);
            t.setText("PINNED · " + (m.mine ? "Me" : m.creator) + ": " + m.body.substring(org.communitycomms.app.msg.MessageCenter.PIN.length()));
            t.setPadding(0, 2, 0, 2);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!m.mine) {
                        Toast.makeText(ChatActivity.this, "Pinned by " + m.creator + " (only they can unpin it)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(ChatActivity.this)
                            .setMessage("Unpin this announcement for everyone?")
                            .setPositiveButton("Unpin", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    RadioService s = RadioService.instance();
                                    if (s == null || s.messages() == null || !s.messages().unpin(m.id)) {
                                        Toast.makeText(ChatActivity.this, "Join the channel first", Toast.LENGTH_SHORT).show();
                                    }
                                    render();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
            pinBar.addView(t);
        }
    }

    /** An announcement pinned at the top of everyone's Chat, sent again to anyone who joins later. */
    private void announce() {
        final EditText e = new EditText(this);
        e.setHint("e.g. Assembly point is the north gate");
        e.setText(input.getText());
        new AlertDialog.Builder(this)
                .setTitle("Pinned announcement")
                .setMessage("Stays at the top of Chat on every phone on this channel, and is sent to anyone who joins later.")
                .setView(e)
                .setPositiveButton("Pin and send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = e.getText().toString().trim();
                        if (t.length() == 0) return;
                        RadioService s = RadioService.instance();
                        if (s == null || s.messages() == null || !s.messages().sendPin(t)) {
                            Toast.makeText(ChatActivity.this, "Join a channel first", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        input.setText("");
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- private messages ----

    private Button to;
    /** Callsign a private message goes to, or null for everyone on the channel. */
    private String privateTo;

    private void renderTo() {
        to.setText(privateTo == null ? "To:\nEveryone" : "Private:\n" + privateTo);
        to.setTextColor(privateTo == null ? 0xffffffff : 0xffffcc80);
    }

    private void pickRecipient() {
        RadioService s = RadioService.instance();
        final List<String> who = s == null || s.messages() == null ? new ArrayList<String>() : s.messages().privateRecipients();
        final String[] items = new String[who.size() + 1];
        items[0] = "Everyone on the channel";
        for (int i = 0; i < who.size(); i++) items[i + 1] = "Private to " + who.get(i);
        new AlertDialog.Builder(this)
                .setTitle(who.isEmpty() ? "Nobody's private-message key heard yet (they arrive within a minute)" : "Send to")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        privateTo = w == 0 ? null : who.get(w - 1);
                        renderTo();
                    }
                })
                .show();
    }

    private void sendText() {
        String t = input.getText().toString().trim();
        if (t.length() == 0) return;
        RadioService s = RadioService.instance();
        if (privateTo != null) {
            String why = s == null || s.messages() == null ? "Join a channel first" : s.messages().sendPrivate(privateTo, t);
            if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show();
            else input.setText("");
            return;
        }
        if (s == null || s.messages() == null || !s.messages().sendText(t)) {
            Toast.makeText(this, "Join a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (RadioEngine.radioSilent) {
            Toast.makeText(this, "Radio silent: kept on this phone. Others pick it up by catch-up sync after the silence ends.",
                    Toast.LENGTH_LONG).show();
        }
        input.setText("");
    }

    private void open(final MessageLog.Entry m) {
        if (m.state == MessageLog.STATE_SCHEDULED) {
            new AlertDialog.Builder(this)
                    .setTitle("Scheduled report")
                    .setMessage("Due " + when(m.time) + ". It goes out on its channel as soon as it is due and that channel is on air.")
                    .setPositiveButton("Keep", null)
                    .setNegativeButton("Cancel it", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            MessageLog.get(ChatActivity.this).remove(m.id);
                        }
                    })
                    .show();
            return;
        }
        if (m.state == MessageLog.STATE_MISSING) {
            Toast.makeText(this, m.body.endsWith("fetching…") ? "Missed voice: another phone is sending its recording."
                    : m.type == MessageLog.TYPE_FILE ? "This phone missed that file. Ask " + m.creator + " to send it again."
                    : "Missed while away; asking the channel for it.", Toast.LENGTH_LONG).show();
            return;
        }
        if (m.type == MessageLog.TYPE_REPORT) {
            ReportsActivity.showReport(this, m);
        } else if (m.type == MessageLog.TYPE_POI) {
            startActivity(new Intent(this, MapActivity.class));
        } else if (m.type == MessageLog.TYPE_FILE) {
            if (m.mime.startsWith("audio/") && new File(m.path).exists()) play(m.path);
            else if (m.isImage() && new File(m.path).exists()) showImage(m);
            else if (!m.mine && ConfigShare.isConfig(new File(m.path)) && new File(m.path).exists())
                ConfigShare.offerApply(this, new File(m.path), m.creator, null);
            else if (!m.mine && org.communitycomms.app.geo.TilePack.isTilePack(new File(m.path)) && new File(m.path).exists())
                org.communitycomms.app.geo.TilePack.offerImport(this, new File(m.path));
            else if (!m.mine && new File(m.path).exists()) openFile(new File(m.path));
            else Toast.makeText(this, "The file is no longer on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    private android.media.MediaPlayer player;
    private String playing;

    /** Tap a saved voice recording to play it; tap again to stop. */
    private void play(String path) {
        boolean same = path.equals(playing);
        stopPlaying();
        if (same) return;
        try {
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(android.media.MediaPlayer mp) {
                    stopPlaying();
                }
            });
            player.prepare();
            player.start();
            playing = path;
            Toast.makeText(this, "Playing (tap again to stop)", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            stopPlaying();
            Toast.makeText(this, "Could not play it: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlaying() {
        if (player != null) player.release();
        player = null;
        playing = null;
    }

    @Override
    protected void onDestroy() {
        stopPlaying();
        super.onDestroy();
    }

    private void showImage(MessageLog.Entry m) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(m.path, 1600);
        Bitmap bmp = BitmapFactory.decodeFile(m.path, o);
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(m.body).setView(iv)
                .setPositiveButton("Close", null);
        if (!m.mine) {
            final File f = new File(m.path);
            b.setNeutralButton("Open in…", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    openFile(f);
                }
            });
        }
        b.show();
    }

    private void openFile(File f) {
        android.net.Uri u = FilesProvider.received(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, getContentResolver().getType(u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this phone opens that file type", Toast.LENGTH_LONG).show();
        }
    }

    private static int sampleFor(String path, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
        return s;
    }

    private Bitmap thumb(String path) {
        if (thumbs.containsKey(path)) return thumbs.get(path);
        Bitmap b = null;
        if (new File(path).exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleFor(path, 240);
            b = BitmapFactory.decodeFile(path, o);
        }
        thumbs.put(path, b);
        return b;
    }

    static String when(long t) {
        boolean today = System.currentTimeMillis() - t < 20 * 3600000L;
        return (today ? DateFormat.getTimeInstance(DateFormat.SHORT)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(new Date(t));
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, (int) (48 * getResources().getDisplayMetrics().density), 1);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            MessageLog.Entry m = rows.get(i);
            float d = getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(ChatActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) ((m.mine ? 48 : 8) * d), (int) (6 * d), (int) ((m.mine ? 8 : 48) * d), (int) (6 * d));
            TextView top = new TextView(ChatActivity.this);
            top.setTextSize(12);
            top.setTextColor(m.mine ? 0xff90caf9 : 0xffa5d6a7);
            String kind = m.type == MessageLog.TYPE_REPORT ? "  REPORT" : m.type == MessageLog.TYPE_POI ? "  MAP POINT"
                    : m.type == MessageLog.TYPE_FILE ? (m.isImage() ? "  PICTURE" : "  FILE") : m.ack ? "  AUTO ACK" : "";
            if (m.privateTo.length() > 0) kind = m.mine ? "  PRIVATE to " + m.privateTo : "  PRIVATE to me";
            if (m.mime.startsWith("audio/")) kind = "  VOICE (tap to play)";
            if (watched(m.channelTag)) kind += "  · LISTEN ONLY";
            top.setText((m.mine ? "Me" : m.creator) + "  " + when(m.time) + kind);
            row.addView(top);
            TextView body = new TextView(ChatActivity.this);
            body.setTextSize(16);
            if (m.state == MessageLog.STATE_MISSING) {
                body.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
                body.setTextColor(0xff9e9e9e);
                body.setText(m.type == MessageLog.TYPE_FILE
                        ? "Missed " + (m.body.length() > 0 ? "\"" + m.body + "\"" : "a file") + " while away. Ask them to resend it."
                        : "Missed " + (m.type == MessageLog.TYPE_REPORT ? "a report" + (m.body.length() > 0 ? " (" + m.body + ")" : "")
                        : "a message") + " while away. Fetching it…");
            } else if (m.state == MessageLog.STATE_SCHEDULED) {
                body.setTextColor(0xffffcc80);
                Report r = Report.parse(m.body);
                body.setText("Scheduled for " + when(m.time) + ": " + (r == null ? "report" : r.title) + " (tap to cancel)");
            } else if (m.type == MessageLog.TYPE_REPORT) {
                Report r = Report.parse(m.body);
                body.setText(r == null ? "(unreadable report)" : r.title + "\n" + r.readable(true));
                body.setMaxLines(8);
            } else if (m.type == MessageLog.TYPE_POI) {
                body.setText((m.removed ? "Removed: " : "") + m.body);
            } else if (m.type == MessageLog.TYPE_FILE) {
                Bitmap t = m.isImage() ? thumb(m.path) : null;
                if (t != null) {
                    ImageView iv = new ImageView(ChatActivity.this);
                    iv.setImageBitmap(t);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxHeight((int) (160 * d));
                    row.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                body.setText(m.body);
                body.setTextSize(13);
            } else {
                body.setText(m.body);
                if (m.privateTo.length() > 0) body.setTextColor(0xffffcc80);
            }
            row.addView(body);
            return row;
        }
    }
}
