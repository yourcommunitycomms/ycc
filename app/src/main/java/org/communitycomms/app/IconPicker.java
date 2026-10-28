package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.communitycomms.app.geo.Poi;
import org.communitycomms.app.geo.PoiIcons;
import org.communitycomms.app.geo.QuickProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Map point icon chooser: the current profile's icons first, then every icon grouped by category
 * (scroll down), with a filter box ("car", "fire", "hostile"…).
 */
public final class IconPicker {
    public interface Picked {
        void onPicked(int icon);
    }

    private IconPicker() {
    }

    /** A row: a category heading (icon < 0) or an icon. */
    private static final class Row {
        final String heading;
        final int icon;

        Row(String heading, int icon) {
            this.heading = heading;
            this.icon = icon;
        }
    }

    public static void pick(final Activity a, String title, final Picked done) {
        final float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), 0);
        final EditText filter = new EditText(a);
        filter.setHint("Filter, e.g. car, fire, hostile");
        filter.setSingleLine(true);
        box.addView(filter);
        final ListView list = new ListView(a);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (380 * d)));
        final List<Row> rows = new ArrayList<Row>();
        final BaseAdapter adapter = new BaseAdapter() {
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
            public boolean isEnabled(int i) {
                return rows.get(i).icon >= 0;
            }

            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                final Row r = rows.get(pos);
                if (r.icon < 0) {
                    TextView h = new TextView(a);
                    h.setText(r.heading);
                    h.setTextSize(13);
                    h.setTextColor(0xfff0c75e);
                    h.setPadding((int) (6 * d), (int) (10 * d), 0, (int) (2 * d));
                    return h;
                }
                LinearLayout row = new LinearLayout(a);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding((int) (8 * d), (int) (5 * d), (int) (8 * d), (int) (5 * d));
                View pic = new View(a) {
                    @Override
                    protected void onDraw(Canvas cv) {
                        PoiIcons.draw(cv, r.icon, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.9f);
                    }
                };
                row.addView(pic, new LinearLayout.LayoutParams((int) (34 * d), (int) (34 * d)));
                TextView t = new TextView(a);
                t.setText("  " + Poi.ICON_NAMES[r.icon]);
                t.setTextSize(16);
                row.addView(t);
                return row;
            }
        };
        list.setAdapter(adapter);
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                rows.clear();
                String q = filter.getText().toString().trim().toLowerCase(Locale.US);
                if (q.length() > 0) {
                    for (int i = 0; i < Poi.ICON_NAMES.length; i++) {
                        if (i == Poi.ICON_PHOTO) continue;
                        boolean hit = Poi.ICON_NAMES[i].toLowerCase(Locale.US).contains(q);
                        for (int c = 0; c < PoiIcons.CATEGORIES.length && !hit; c++) {
                            if (PoiIcons.CATEGORY_NAMES[c].toLowerCase(Locale.US).contains(q)) {
                                for (int ic : PoiIcons.CATEGORIES[c]) if (ic == i) hit = true;
                            }
                        }
                        if (hit) rows.add(new Row(null, i));
                    }
                    if (rows.isEmpty()) rows.add(new Row("Nothing matches \"" + q + "\"", -1));
                } else {
                    QuickProfiles qp = new QuickProfiles(a);
                    rows.add(new Row("This profile: " + QuickProfiles.NAMES[qp.profile()], -1));
                    List<Integer> mine = new ArrayList<Integer>();
                    for (QuickProfiles.Item it : qp.items()) {
                        if (it.kind == QuickProfiles.KIND_POINT && !mine.contains(it.icon)) mine.add(it.icon);
                    }
                    for (int i : mine) rows.add(new Row(null, i));
                    for (int c = 0; c < PoiIcons.CATEGORIES.length; c++) {
                        rows.add(new Row(PoiIcons.CATEGORY_NAMES[c], -1));
                        for (int i : PoiIcons.CATEGORIES[c]) rows.add(new Row(null, i));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };
        fill.run();
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                fill.run();
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Row r = rows.get(pos);
                if (r.icon < 0) return;
                dlg.dismiss();
                done.onPicked(r.icon);
            }
        });
        dlg.show();
    }

    /** A button that shows the chosen icon's name and opens the picker; get the choice with chosen[0]. */
    public static Button button(final Activity a, final int[] chosen) {
        final Button b = new Button(a);
        b.setAllCaps(false);
        b.setText("Icon: " + Poi.ICON_NAMES[chosen[0]] + " (tap to change)");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(a, "Icon", new Picked() {
                    @Override
                    public void onPicked(int icon) {
                        chosen[0] = icon;
                        b.setText("Icon: " + Poi.ICON_NAMES[icon] + " (tap to change)");
                    }
                });
            }
        });
        return b;
    }
}
