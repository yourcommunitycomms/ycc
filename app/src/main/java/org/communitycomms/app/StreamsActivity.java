package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Listening in on public audio streams over the internet (e.g. Broadcastify scanner feeds of CFA
 * district dispatch), when there is internet. The list is the user's own (name and stream URL, or
 * just a Broadcastify feed number). One stream plays at a time and keeps playing while the rest
 * of the app is used, until Stop. Nothing is ever sent; this is the only part that needs internet.
 */
public final class StreamsActivity extends Activity {
    private static MediaPlayer player;
    private static String playing = "";
    private static String state = "";

    private Prefs prefs;
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Radio streams");
        prefs = new Prefs(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        TextView help = new TextView(this);
        help.setText("Public scanner streams over the internet, e.g. CFA district dispatch on Broadcastify "
                + "(Australia > Victoria). Add a stream with its name and either its full stream address or just the "
                + "Broadcastify feed number (from the feed page address, …/listen/feed/NUMBER). Needs internet; "
                + "nothing is sent.");
        help.setTextSize(13);
        root.addView(help);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xfff0c75e);
        status.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        root.addView(status);
        Button stop = button("Stop", new Runnable() {
            @Override
            public void run() {
                stopPlayer();
                render();
            }
        });
        Button add = button("Add a stream…", new Runnable() {
            @Override
            public void run() {
                addStream();
            }
        });
        Button find = button("Find feeds (web)", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.broadcastify.com/listen/")));
                } catch (Exception e) {
                    Toast.makeText(StreamsActivity.this, "No web browser", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(FilesActivity.row(stop, add, find));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    private Button button(String t, final Runnable r) {
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

    private List<String[]> streams() {
        List<String[]> out = new ArrayList<String[]>();
        for (String l : prefs.streams().split("\n")) {
            int bar = l.indexOf('|');
            if (bar > 0) out.add(new String[]{l.substring(0, bar), l.substring(bar + 1)});
        }
        return out;
    }

    private void save(List<String[]> s) {
        StringBuilder sb = new StringBuilder();
        for (String[] x : s) sb.append(x[0]).append('|').append(x[1]).append('\n');
        prefs.setStreams(sb.toString());
    }

    /** A bare number is a Broadcastify feed. */
    static String url(String s) {
        s = s.trim();
        return s.matches("\\d+") ? "https://broadcastify.cdnstream1.com/" + s : s;
    }

    private void render() {
        status.setText(player == null ? "Not playing" : "Playing: " + playing + (state.length() > 0 ? " (" + state + ")" : ""));
        list.removeAllViews();
        final List<String[]> s = streams();
        if (s.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No streams yet. Tap Add a stream.");
            list.addView(t);
        }
        for (int i = 0; i < s.size(); i++) {
            final int idx = i;
            final String[] x = s.get(i);
            Button b = button((x[0].equals(playing) && player != null ? "> " : "") + x[0], new Runnable() {
                @Override
                public void run() {
                    play(x[0], url(x[1]));
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(StreamsActivity.this)
                            .setTitle(x[0])
                            .setMessage(url(x[1]))
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface di, int w) {
                                    s.remove(idx);
                                    save(s);
                                    render();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                }
            });
            list.addView(b);
        }
        if (!s.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Long-press a stream to remove it.");
            t.setTextSize(12);
            list.addView(t);
        }
    }

    private void addStream() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        final EditText name = new EditText(this);
        name.setHint("Name, e.g. CFA District 13");
        name.setSingleLine(true);
        box.addView(name);
        final EditText addr = new EditText(this);
        addr.setHint("Stream address or Broadcastify feed number");
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addr);
        new AlertDialog.Builder(this)
                .setTitle("Add a stream")
                .setView(box)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String n = name.getText().toString().trim().replace("|", " ");
                        String a = addr.getText().toString().trim();
                        if (a.length() == 0) return;
                        List<String[]> s = streams();
                        s.add(new String[]{n.length() == 0 ? a : n, a});
                        save(s);
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void play(final String name, String url) {
        stopPlayer();
        playing = name;
        state = "connecting…";
        render();
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (player != m) return;
                    m.start();
                    state = "";
                    render();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    if (player == m) {
                        stopPlayer();
                        Toast.makeText(StreamsActivity.this, "Could not play " + name
                                + ": no internet, the feed is offline, or the address is not an audio stream", Toast.LENGTH_LONG).show();
                        render();
                    }
                    return true;
                }
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            stopPlayer();
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_LONG).show();
            render();
        }
    }

    static void stopPlayer() {
        MediaPlayer m = player;
        player = null;
        state = "";
        if (m != null) {
            try {
                m.stop();
            } catch (IllegalStateException ignored) {
            }
            m.release();
        }
    }
}
