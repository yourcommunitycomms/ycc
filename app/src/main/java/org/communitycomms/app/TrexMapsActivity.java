package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.LanTiles;
import org.communitycomms.app.geo.TrexMaps;
import org.communitycomms.app.geo.TrexShare;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Data import and layers: the T.REX Arms Topo Map (.mbtiles baked on maps.trex-arms.com). How to make
 * and download the file, Import (copied into the app's own folder and sent to the cache server), the
 * files on this phone (Send to cache server, Remove) and the files on the cache server (Download).
 * The map itself is chosen under Layer on the map.
 */
public final class TrexMapsActivity extends Activity {
    private static final int REQ_PICK = 1;

    private LinearLayout list, remoteList;
    private TextView credit, shareStatus;
    private final Handler handler = new Handler();
    private boolean wasBusy;
    private List<TrexShare.Remote> remote;
    private boolean remoteAsked, remoteFailed;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean busy = TrexShare.busy();
            shareStatus.setText(TrexShare.status());
            shareStatus.setVisibility(TrexShare.status().length() > 0 ? View.VISIBLE : View.GONE);
            if (wasBusy && !busy) { // an upload or download just finished
                render();
                loadRemote();
            }
            wasBusy = busy;
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Data import and layers");
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(head(TrexMaps.NAME));
        root.addView(note("An offline topographic map (OpenStreetMap with hill shading, zoom 12 to 15) baked as an TAK "
                + ".mbtiles file on the T.REX Arms Maps site. Choose it under Layer on the map (" + TrexMaps.NAME
                + "); outside its area the usual map shows."));
        LinearLayout r1 = row(root);
        button(r1, "How to make the file").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                howTo();
            }
        });
        button(r1, "Import .mbtiles file").setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        shareStatus = note("");
        shareStatus.setTextColor(0xfff0c75e);
        root.addView(shareStatus);

        root.addView(head("\nOn this phone"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        root.addView(head("\nOn the cache server"));
        remoteList = new LinearLayout(this);
        remoteList.setOrientation(LinearLayout.VERTICAL);
        root.addView(remoteList);
        Button refresh = new Button(this);
        refresh.setText("Check the cache server again");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadRemote();
            }
        });
        root.addView(refresh, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        credit = note("");
        credit.setTextSize(12);
        root.addView(credit);
        root.addView(note("\nImported files are also sent to the cache server (Settings > LAN storage location) in its folder \""
                + TrexShare.FOLDER + "\", apart from the TAK imports and offline map sets. Other phones can view the map "
                + "from there as they pan, or Download it to keep it for offline use."));
        setContentView(scroll);
        render();
        loadRemote();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wasBusy = TrexShare.busy();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void render() {
        list.removeAllViews();
        List<TrexMaps.Entry> files = TrexMaps.list(this);
        if (files.isEmpty()) list.addView(note("None yet. Tap How to make the file, then Import .mbtiles file, or Download one below."));
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        for (final TrexMaps.Entry e : files) {
            TextView t = new TextView(this);
            t.setText(e.title());
            t.setTextSize(16);
            t.setPadding(0, pad, 0, 0);
            list.addView(t);
            list.addView(note(TrexMaps.describe(e)));
            LinearLayout r = row(list);
            button(r, "Send to cache server").setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    send(e);
                }
            });
            button(r, "Remove").setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRemove(e);
                }
            });
        }
        credit.setText(files.isEmpty() ? "" : "\nShown on the map: " + TrexMaps.attribution(files));
        renderRemote();
    }

    private void renderRemote() {
        remoteList.removeAllViews();
        if (!new LanTiles(this).configured()) {
            remoteList.addView(note("No cache server is set (Settings > LAN storage location)."));
            return;
        }
        if (remote == null) {
            remoteList.addView(note(remoteFailed ? "The cache server did not answer." : remoteAsked ? "Checking…" : ""));
            if (!remoteFailed) return;
            remote = TrexShare.remote(this); // the last list seen
            if (remote.isEmpty()) return;
            remoteList.addView(note("Last seen there:"));
        }
        if (remote.isEmpty()) {
            remoteList.addView(note("Nothing there yet. Import a file on this phone (it is sent there), or tap Send to cache server."));
            return;
        }
        List<TrexMaps.Entry> local = TrexMaps.list(this);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        for (final TrexShare.Remote r : remote) {
            boolean here = false;
            for (TrexMaps.Entry e : local) if (e.file.getName().equalsIgnoreCase(r.file)) here = true;
            TextView t = new TextView(this);
            t.setText(r.title);
            t.setTextSize(16);
            t.setPadding(0, pad, 0, 0);
            remoteList.addView(t);
            StringBuilder s = new StringBuilder();
            s.append(r.size > 0 ? TrexMaps.size(r.size) : "size unknown");
            if (r.tiles > 0) s.append(String.format(Locale.US, " · %,d tiles", r.tiles));
            if (r.maxZoom >= 0) s.append(" · zoom ").append(r.minZoom).append(" to ").append(r.maxZoom);
            if (r.hasBounds) s.append("\nBounds: ").append(Geo.latLon(r.north, r.west)).append(" to ").append(Geo.latLon(r.south, r.east));
            s.append("\nFile: ").append(r.file);
            s.append(r.tilesDone ? "\nViewable from the server as you pan: yes"
                    : r.tilesShared > 0 ? String.format(Locale.US, "\nViewable from the server as you pan: %,d of %,d tiles", r.tilesShared, r.tiles)
                    : "\nViewable from the server as you pan: not yet (Send to cache server from the phone that has it)");
            if (here) s.append("\nAlso on this phone.");
            remoteList.addView(note(s.toString()));
            if (!here) {
                Button dl = new Button(this);
                dl.setText("Download" + (r.size > 0 ? " (" + TrexMaps.size(r.size) + ")" : ""));
                dl.setAllCaps(false);
                dl.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!TrexShare.startDownload(TrexMapsActivity.this, r)) toast("Wait for the current upload or download to finish");
                        else wasBusy = true;
                    }
                });
                remoteList.addView(dl, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private void loadRemote() {
        if (!new LanTiles(this).configured()) {
            renderRemote();
            return;
        }
        remote = null;
        remoteAsked = true;
        remoteFailed = false;
        renderRemote();
        new AsyncTask<Void, Void, List<TrexShare.Remote>>() {
            @Override
            protected List<TrexShare.Remote> doInBackground(Void... v) {
                return TrexShare.refresh(TrexMapsActivity.this);
            }

            @Override
            protected void onPostExecute(List<TrexShare.Remote> r) {
                if (isFinishing()) return;
                remote = r;
                remoteFailed = r == null;
                renderRemote();
            }
        }.execute();
    }

    private void send(TrexMaps.Entry e) {
        if (!new LanTiles(this).configured()) {
            toast("No cache server is set (Settings > LAN storage location)");
            return;
        }
        if (!TrexShare.startUpload(this, e)) toast("Wait for the current upload or download to finish");
        else wasBusy = true;
    }

    private void howTo() {
        new AlertDialog.Builder(this)
                .setTitle("Make the " + TrexMaps.NAME + " file")
                .setMessage("This needs the internet once; afterwards the map works offline.\n\n"
                        + "1. Tap Open maps site (maps.trex-arms.com opens in the browser).\n"
                        + "2. Move the map to your area and pick the area to bake. About 11,000 tiles (zoom 12 to 15) "
                        + "covers a city region; bigger areas make bigger files.\n"
                        + "3. Bake it as an TAK .mbtiles file and download it. On a phone it lands in Download; "
                        + "on a PC, copy it to the phone (USB, LAN storage or a memory card).\n"
                        + "4. Come back here, tap Import .mbtiles file and pick it. The app copies it into its own "
                        + "folder (you can delete the download afterwards) and sends it to the cache server for the other phones.\n\n"
                        + "Map data " + TrexMaps.OSM + ", plus the hillshade sources the file names.")
                .setPositiveButton("Open maps site", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(TrexMaps.SITE));
                            i.addCategory(Intent.CATEGORY_BROWSABLE);
                            startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            toast("No browser on this phone");
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pick the .mbtiles file"), REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast("No file picker on this phone");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) importFile(data.getData());
    }

    private void importFile(final Uri uri) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Importing");
        pd.setMessage("Copying the map file into the app…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, String, Object>() {
            @Override
            protected Object doInBackground(Void... v) {
                try {
                    return TrexMaps.importFrom(TrexMapsActivity.this, uri, new TrexMaps.Progress() {
                        @Override
                        public void copied(long done, long total) {
                            publishProgress("Copying the map file into the app…\n" + TrexMaps.size(done)
                                    + (total > 0 ? " of " + TrexMaps.size(total) : ""));
                        }
                    });
                } catch (IOException e) {
                    return e.getMessage();
                } catch (RuntimeException e) {
                    return String.valueOf(e.getMessage());
                }
            }

            @Override
            protected void onProgressUpdate(String... s) {
                pd.setMessage(s[0]);
            }

            @Override
            protected void onPostExecute(Object r) {
                if (isFinishing()) return;
                try {
                    pd.dismiss();
                } catch (RuntimeException ignored) {
                }
                if (r instanceof TrexMaps.Entry) {
                    TrexMaps.Entry e = (TrexMaps.Entry) r;
                    boolean lan = new LanTiles(TrexMapsActivity.this).configured();
                    boolean sending = lan && TrexShare.startUpload(TrexMapsActivity.this, e);
                    if (sending) wasBusy = true;
                    render();
                    new AlertDialog.Builder(TrexMapsActivity.this)
                            .setTitle("Imported")
                            .setMessage(e.title() + "\n\n" + TrexMaps.describe(e) + "\n\n"
                                    + (sending ? "It is being sent to the cache server for the other phones (progress on this screen)."
                                    : lan ? "Tap Send to cache server when the current transfer has finished."
                                    : "No cache server is set, so it is only on this phone.")
                                    + "\n\nChoose " + TrexMaps.NAME + " under Layer on the map to see it.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    new AlertDialog.Builder(TrexMapsActivity.this)
                            .setTitle("Not imported")
                            .setMessage("That file can't be used: " + r + ".\n\nPick the .mbtiles file baked on the "
                                    + "T.REX Arms Maps site. Nothing was kept.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        }.execute();
    }

    private void confirmRemove(final TrexMaps.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + e.title() + "?")
                .setMessage("Deletes this phone's copy (" + TrexMaps.size(e.file.length()) + "). The copy on the cache server stays; "
                        + "Download it again to get it back.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        TrexMaps.remove(TrexMapsActivity.this, e);
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
