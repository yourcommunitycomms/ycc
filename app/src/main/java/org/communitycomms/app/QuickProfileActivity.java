package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import org.communitycomms.app.geo.PoiIcons;
import org.communitycomms.app.geo.QuickProfiles;

import java.util.List;

/**
 * The Custom quick map point profile: starts as a copy of the profile that was in use; each item's
 * button text, announcement, icon, duration and place (my position / map centre) can be changed,
 * and items added, moved up or deleted. Saved as you go.
 */
public final class QuickProfileActivity extends Activity {
    private static final String[] LIFE_LABELS = {"10 min", "30 min", "1 hour", "2 hours", "6 hours", "12 hours", "24 hours", "Never expires"};
    private static final long[] LIFE_MS = {600000L, 1800000L, 3600000L, 7200000L, 21600000L, 43200000L, 86400000L, 0};

    private QuickProfiles qp;
    private List<QuickProfiles.Item> items;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Custom map points");
        qp = new QuickProfiles(this);
        qp.setProfile(QuickProfiles.CUSTOM);
        items = qp.loadCustom();
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView help = new TextView(this);
        help.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (4 * d));
        help.setText("Tap an item to change it. Announcement placeholders: {me} = your callsign, "
                + "{in10} / {in30} = the time in 10 / 30 minutes. The first 3 or 4 show as buttons on the radio screen.");
        root.addView(help);
        LinearLayout bar = new LinearLayout(this);
        Button add = new Button(this);
        add.setText("Add item");
        Button from = new Button(this);
        from.setText("Start from…");
        bar.addView(add, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(from, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);
        ListView list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int i) {
                return items.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                final QuickProfiles.Item it = items.get(pos);
                LinearLayout row = new LinearLayout(QuickProfileActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (12 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
                View pic = new View(QuickProfileActivity.this) {
                    @Override
                    protected void onDraw(android.graphics.Canvas c) {
                        PoiIcons.draw(c, it.icon, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.9f);
                    }
                };
                row.addView(pic, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
                TextView t = new TextView(QuickProfileActivity.this);
                t.setPadding((int) (12 * d), 0, 0, 0);
                t.setText(it.label + "\nAnnounces \"" + it.note + "\", " + lifeLabel(it.lifeMs)
                        + (it.atMapCentre ? ", at the map's +" : ", at my position"));
                row.addView(t);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                edit(pos);
            }
        });
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                items.add(new QuickProfiles.Item("New point", 0, "New point", 3600000L, false));
                save();
                edit(items.size() - 1);
            }
        });
        from.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFrom();
            }
        });
    }

    private static String lifeLabel(long ms) {
        for (int i = 0; i < LIFE_MS.length; i++) if (LIFE_MS[i] == ms) return LIFE_LABELS[i].toLowerCase();
        return ms == 0 ? "never expires" : (ms / 60000) + " min";
    }

    private static int lifeIndex(long ms) {
        for (int i = 0; i < LIFE_MS.length; i++) if (LIFE_MS[i] == ms) return i;
        return 2;
    }

    private void save() {
        qp.saveCustom(items);
        adapter.notifyDataSetChanged();
    }

    /** Replace the list with a copy of a built-in profile. */
    private void startFrom() {
        final String[] names = {"Friends", "Security", "Firefighting", "Military"};
        new AlertDialog.Builder(this)
                .setTitle("Replace the custom list with a copy of…")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        items.clear();
                        items.addAll(QuickProfiles.builtIn(which));
                        save();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void edit(final int pos) {
        final QuickProfiles.Item it = items.get(pos);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        box.addView(label("Button text"));
        final EditText label = new EditText(this);
        label.setSingleLine(true);
        label.setText(it.label);
        box.addView(label);
        box.addView(label("Announcement (the map point's note, spoken on other phones)"));
        final EditText note = new EditText(this);
        note.setText(it.note);
        box.addView(note);
        box.addView(label("Icon"));
        final int[] icon = {it.icon};
        box.addView(IconPicker.button(this, icon));
        box.addView(label("Lasts"));
        final Spinner life = new Spinner(this);
        life.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, LIFE_LABELS));
        life.setSelection(lifeIndex(it.lifeMs));
        box.addView(life);
        final CheckBox centre = new CheckBox(this);
        centre.setText("Put it at the map's centre + (\"meet me there\"), not at my position");
        centre.setChecked(it.atMapCentre);
        box.addView(centre);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Map point button")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        String l = label.getText().toString().trim();
                        it.label = l.length() == 0 ? "Point" : l;
                        String n = note.getText().toString().trim();
                        it.note = n.length() == 0 ? it.label : n;
                        it.icon = icon[0];
                        it.lifeMs = LIFE_MS[life.getSelectedItemPosition()];
                        it.atMapCentre = centre.isChecked();
                        save();
                    }
                })
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        items.remove(pos);
                        save();
                    }
                })
                .setNegativeButton(pos > 0 ? "Move up" : "Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (pos == 0) return;
                        items.add(pos - 1, items.remove(pos));
                        save();
                    }
                })
                .show();
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        v.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return v;
    }
}
