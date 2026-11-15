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
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.communitycomms.app.geo.TrexMaps;

import java.io.IOException;
import java.util.List;

/**
 * Data import and layers: the T.REX Arms Topo Map (.mbtiles baked on maps.trex-arms.com). How to
 * make and download the file, Import (copied into the app's own folder), the list of imported files
 * with size and bounds, Remove, and the on/off switch for the map. Kept apart from the ATAK imports
 * and offline sets.
 */
public final class TrexMapsActivity extends Activity {
    private static final int REQ_PICK = 1;

    private LinearLayout list;
    private CheckBox show;
    private TextView credit;

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
        root.addView(note("An offline topographic map (OpenStreetMap with hill shading, zoom 12 to 15) baked as an ATAK "
                + ".mbtiles file on the T.REX Arms Maps site. After import it works with no internet and is drawn first "
                + "wherever it covers; outside its area the usual map shows."));
        show = new CheckBox(this);
        show.setText("Show " + TrexMaps.NAME + " on the map");
        show.setChecked(TrexMaps.enabled(this));
        show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                if (on && TrexMaps.list(TrexMapsActivity.this).isEmpty()) {
                    bt.setChecked(false);
                    toast("Import a .mbtiles file first");
                    return;
                }
                TrexMaps.setEnabled(TrexMapsActivity.this, on);
            }
        });
        root.addView(show);
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
        root.addView(head("\nImported files"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        credit = note("");
        credit.setTextSize(12);
        root.addView(credit);
        root.addView(note("\nThese files stay on this phone: they are not part of the ATAK imports, offline map sets, "
                + "Send map tiles or sync."));
        setContentView(scroll);
        render();
    }

    private void render() {
        list.removeAllViews();
        List<TrexMaps.Entry> files = TrexMaps.list(this);
        if (files.isEmpty()) {
            list.addView(note("None yet. Tap How to make the file, then Import .mbtiles file."));
            if (show.isChecked()) show.setChecked(false);
        }
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        for (final TrexMaps.Entry e : files) {
            TextView t = new TextView(this);
            t.setText(e.title());
            t.setTextSize(16);
            t.setPadding(0, pad, 0, 0);
            list.addView(t);
            list.addView(note(TrexMaps.describe(e)));
            Button rm = new Button(this);
            rm.setText("Remove");
            rm.setAllCaps(false);
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRemove(e);
                }
            });
            list.addView(rm, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        credit.setText(files.isEmpty() ? "" : "\nShown on the map: " + TrexMaps.attribution(files));
    }

    private void howTo() {
        new AlertDialog.Builder(this)
                .setTitle("Make the " + TrexMaps.NAME + " file")
                .setMessage("This needs the internet once; afterwards the map works offline.\n\n"
                        + "1. Tap Open maps site (maps.trex-arms.com opens in the browser).\n"
                        + "2. Move the map to your area and pick the area to bake. About 11,000 tiles (zoom 12 to 15) "
                        + "covers a city region; bigger areas make bigger files.\n"
                        + "3. Bake it as an ATAK .mbtiles file and download it. On a phone it lands in Download; "
                        + "on a PC, copy it to the phone (USB, LAN storage or a memory card).\n"
                        + "4. Come back here, tap Import .mbtiles file and pick it. The app copies it into its own "
                        + "folder, so you can delete the download afterwards.\n\n"
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
                    TrexMaps.setEnabled(TrexMapsActivity.this, true);
                    show.setChecked(true);
                    render();
                    new AlertDialog.Builder(TrexMapsActivity.this)
                            .setTitle("Imported")
                            .setMessage(e.title() + "\n\n" + TrexMaps.describe(e) + "\n\nIt is switched on for the map.")
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
                .setMessage("Deletes the app's copy (" + TrexMaps.size(e.file.length()) + "). Import it again to get it back.")
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
