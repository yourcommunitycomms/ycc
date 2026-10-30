package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.communitycomms.app.geo.Geo;
import org.communitycomms.app.geo.LocationHelper;
import org.communitycomms.app.geo.MapPrefs;
import org.communitycomms.app.geo.TileMapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * "Plot last advertised": everyone's last shared position on the map, and underneath a compass
 * list with direction, distance and time since each check-in.
 */
public final class MapActivity extends Activity {
    private TileMapView map;
    private View mapPad;
    private MapIconButton poiFilterButton;
    private View fakeHereButton;

    private String fakeLabel() {
        return org.communitycomms.app.geo.FakeGps.enabled(this) ? "Fake GPS: on (tap to turn off)" : "Fake GPS: off (tap to turn on)";
    }

    private MapIconButton shareKey, askKey;

    /** Share lit while sharing; Ask dimmed on Public and OpenMANET*. */
    private void renderShareKeys() {
        RadioService rs = RadioService.instance();
        if (shareKey != null) shareKey.setLit(rs != null && rs.rapidShareLeftMs() > 0);
        int s = new Prefs(this).activeSlot();
        if (askKey != null) askKey.setAlpha(rs != null && rs.engine() != null && RadioService.privateSlot(s) ? 1f : 0.4f);
    }

    private final RadioService.LocationAsk locationAsk = new RadioService.LocationAsk() {
        @Override
        public void ask(String from) {
            ShareLocation.answerRequest(MapActivity.this, from, new Runnable() {
                @Override
                public void run() {
                    ShareLocation.start(MapActivity.this, new Runnable() {
                        @Override
                        public void run() {
                            renderShareKeys();
                        }
                    });
                }
            });
        }
    };

    private android.widget.Button layerButton;
    private static final String[] LAYER_NAMES = {"Street map", "Satellite", "Topographic"};
    private static final int[] LAYER_PRESETS = {0, 1, 3};

    private String layerWord() {
        int p = mp.onlinePreset();
        return p == 1 ? "Satellite" : p == 3 ? "Topo" : p == 0 ? "Street" : "Layer";
    }

    /** One of the feeds that can be ticked under Layer. */
    private interface LayerFeed {
        /** False if it has to be set up first. */
        boolean set(boolean on);

        void setUp();
    }

    /** The map type, and ticks for what is drawn over it. */
    private void chooseLayer() {
        if (ships == null) ships = new org.communitycomms.app.geo.ShipFeed(this);
        if (adsb == null) adsb = new org.communitycomms.app.geo.AdsbFeed(this);
        if (alerts == null) alerts = new org.communitycomms.app.geo.CapFeed(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        android.widget.RadioGroup types = new android.widget.RadioGroup(this);
        for (int i = 0; i < LAYER_PRESETS.length; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setId(i + 1);
            r.setText(LAYER_NAMES[i]);
            types.addView(r);
            if (LAYER_PRESETS[i] == mp.onlinePreset()) types.check(r.getId());
        }
        types.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                mp.setOnline(LAYER_PRESETS[id - 1], mp.customUrl());
                tilePrompt.apply();
                layerButton.setText(layerWord());
                refresh();
            }
        });
        box.addView(types);
        TextView over = new TextView(this);
        over.setText("\nOver the map:");
        box.addView(over);
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        layerTick(box, "Ships and boats (AIS)", ships.enabled(), dlg, new LayerFeed() {
            @Override
            public boolean set(boolean on) {
                return ships.setOn(on);
            }

            @Override
            public void setUp() {
                setUpShips();
            }
        });
        layerTick(box, "Aircraft (ADS-B)", adsb.source() != org.communitycomms.app.geo.AdsbFeed.OFF, dlg, new LayerFeed() {
            @Override
            public boolean set(boolean on) {
                return adsb.setOn(on);
            }

            @Override
            public void setUp() {
                setUpAdsb();
            }
        });
        layerTick(box, "Emergency alerts", alerts.enabled(), dlg, new LayerFeed() {
            @Override
            public boolean set(boolean on) {
                return alerts.setOn(on);
            }

            @Override
            public void setUp() {
                setUpAlerts();
            }
        });
        dlg[0] = new android.app.AlertDialog.Builder(this).setTitle("Map layer").setView(box)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void layerTick(LinearLayout box, String name, boolean on, final android.app.AlertDialog[] dlg, final LayerFeed feed) {
        android.widget.CheckBox tick = new android.widget.CheckBox(this);
        tick.setText(name);
        tick.setChecked(on);
        tick.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean now) {
                if (feed.set(now)) {
                    feedsChanged();
                } else {
                    dlg[0].dismiss();
                    feed.setUp();
                }
            }
        });
        box.addView(tick);
    }

    /** A feed was turned on or off, or set up. */
    private void feedsChanged() {
        RadioService rs = RadioService.instance();
        if (rs != null) {
            rs.applyAisShare();
            rs.applyAdsbShare();
        }
        startShips();
        startAdsb();
        startAlerts();
        refresh();
    }

    private void setUpAlerts() {
        if (alerts == null) alerts = new org.communitycomms.app.geo.CapFeed(this);
        alerts.choose(this, new Runnable() {
            @Override
            public void run() {
                feedsChanged();
            }
        });
    }

    private void setUpShips() {
        if (ships == null) ships = new org.communitycomms.app.geo.ShipFeed(this);
        ships.choose(this, new Runnable() {
            @Override
            public void run() {
                feedsChanged();
            }
        });
    }

    private void setUpAdsb() {
        if (adsb == null) adsb = new org.communitycomms.app.geo.AdsbFeed(this);
        adsb.choose(this, new Runnable() {
            @Override
            public void run() {
                feedsChanged();
            }
        });
    }

    /** The word under the points filter icon: the channel letter, or All. */
    private String poiWord() {
        if (org.communitycomms.app.geo.PoiFilter.layer(this).length() > 0) return "Layer";
        int s = org.communitycomms.app.geo.PoiFilter.slot(this);
        return s < 0 ? "Points" : "Pts " + Prefs.letter(s);
    }

    /** One Sync button: send mine and ask for theirs, or resync (clear others' points first). */
    private void syncPoisChoice() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Map points")
                .setItems(new String[]{"Sync: send mine and ask everyone for theirs",
                        "Resync: clear others' points (and expired ones), then fetch again"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int which) {
                                if (which == 1) {
                                    resyncPois();
                                    return;
                                }
                                RadioService rs = RadioService.instance();
                                if (rs == null || !rs.sendAllPois(true)) {
                                    android.widget.Toast.makeText(MapActivity.this, "Join a channel first", android.widget.Toast.LENGTH_SHORT).show();
                                } else {
                                    android.widget.Toast.makeText(MapActivity.this, "Sent my POIs and asked everyone for theirs",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Clears map points from others (expired ones go too), keeps mine, then asks everyone for theirs. */
    private void resyncPois() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Resync map points")
                .setMessage("Removes map points made by others from this phone (and any expired ones), keeps yours, "
                        + "then sends yours and asks everyone on the channel for theirs. Use it when the map shows "
                        + "old or doubled points.")
                .setPositiveButton("Resync", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        RadioService rs = RadioService.instance();
                        if (rs == null || rs.engine() == null) {
                            android.widget.Toast.makeText(MapActivity.this, "Join a channel first", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String me = new org.communitycomms.app.Prefs(MapActivity.this).callsign();
                        org.communitycomms.app.geo.PoiStore store = org.communitycomms.app.geo.PoiStore.get(MapActivity.this);
                        int n = 0;
                        for (org.communitycomms.app.geo.Poi p : store.all()) {
                            if (!me.equalsIgnoreCase(p.creator)) {
                                store.remove(p.id);
                                n++;
                            }
                        }
                        rs.sendAllPois(true);
                        android.widget.Toast.makeText(MapActivity.this, "Cleared " + n + " point(s) from others; asked everyone for theirs",
                                android.widget.Toast.LENGTH_LONG).show();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Which channel's map points to show: all, or one of A, B, Public, C–H (those with a name). */
    private void choosePoiFilter() {
        final org.communitycomms.app.Prefs pr = new org.communitycomms.app.Prefs(this);
        final java.util.List<Integer> slots = new java.util.ArrayList<Integer>();
        java.util.List<String> names = new java.util.ArrayList<String>();
        slots.add(-1);
        names.add("All channels");
        for (int s : org.communitycomms.app.Prefs.order()) {
            if (s == org.communitycomms.app.Prefs.SLOT_OPENMANET || pr.channel(s).trim().length() == 0) continue;
            slots.add(s);
            names.add(MainActivity.label(s));
            org.communitycomms.app.geo.PoiFilter.tag(this, s); // start working the tag out now
        }
        // Layers (e.g. a bulk add): show only that layer, from any channel.
        final java.util.List<String> layers = new java.util.ArrayList<String>();
        for (org.communitycomms.app.geo.Poi p : org.communitycomms.app.geo.PoiStore.get(this).all()) {
            String l = org.communitycomms.app.geo.Poi.layer(p.note);
            if (l.length() > 0 && !layers.contains(l)) layers.add(l);
        }
        final int firstLayer = names.size();
        for (String l : layers) names.add("Layer: " + l);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Show map points from")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which >= firstLayer) {
                            org.communitycomms.app.geo.PoiFilter.setSlot(MapActivity.this, -1);
                            org.communitycomms.app.geo.PoiFilter.setLayer(MapActivity.this, layers.get(which - firstLayer));
                            poiFilterButton.setWord(poiWord());
                            refresh();
                            return;
                        }
                        org.communitycomms.app.geo.PoiFilter.setLayer(MapActivity.this, "");
                        org.communitycomms.app.geo.PoiFilter.setSlot(MapActivity.this, slots.get(which));
                        poiFilterButton.setWord(poiWord());
                        refresh();
                    }
                })
                .show();
    }
    private MapIconButton padToggle;

    private String padLabel() {
        return org.communitycomms.app.geo.MapPad.shown(this) ? "Hide map controls" : "Show map controls";
    }
    private TextView list, sourceLabel, moveText;
    private LinearLayout moveBar;
    /** The map point being moved, or null. */
    private org.communitycomms.app.geo.Poi moving;
    private MapPrefs mp;
    private Location me;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            showSources();
            handler.postDelayed(this, 2000);
        }
    };

    private TextView crossLabel;
    private String shownCross = "";
    /** Where the + is: lat / lon and military grid (MGRS), kept up to date as the map moves. */
    private final Runnable crossTick = new Runnable() {
        @Override
        public void run() {
            double lat = map.centerLat(), lon = map.centerLon();
            String s = "+ " + org.communitycomms.app.geo.Geo.latLon(lat, lon) + "   "
                    + org.communitycomms.app.geo.Mgrs.of(lat, lon);
            if (!s.equals(shownCross)) {
                shownCross = s;
                crossLabel.setText(s);
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Map");
        mp = new MapPrefs(this);
        org.communitycomms.app.geo.TilePrefetch.cleanMixedCache(this);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout top = root;
        // Shown while moving a map point: drag the map under the +, then Save here.
        moveBar = new LinearLayout(this);
        moveBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        moveBar.setBackgroundColor(0xff3e2723);
        moveBar.setPadding((int) (8 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        moveBar.setVisibility(View.GONE);
        moveText = new TextView(this);
        moveText.setTextColor(0xffffcc80);
        moveBar.addView(moveText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Button moveSave = new android.widget.Button(this);
        moveSave.setText("Save here");
        moveSave.setAllCaps(false);
        moveSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                org.communitycomms.app.geo.Poi p = moving;
                endMove();
                if (p != null) editPoi(p, map.centerLat(), map.centerLon());
            }
        });
        moveBar.addView(moveSave);
        android.widget.Button moveCancel = new android.widget.Button(this);
        moveCancel.setText("Cancel");
        moveCancel.setAllCaps(false);
        moveCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endMove();
            }
        });
        moveBar.addView(moveCancel);
        root.addView(moveBar);
        map = new TileMapView(this, null);
        // Over the map: fold the panel below away (▼ / ▲) and add a point at the + straight away.
        android.widget.FrameLayout mapBox = new android.widget.FrameLayout(this);
        mapBox.addView(map);
        // Map controls (arrows, zoom, centre on me) for a TV remote, a mouse or gloves.
        mapPad = org.communitycomms.app.geo.MapPad.attach(mapBox, map, new Runnable() {
            @Override
            public void run() {
                if (me != null) map.center(me.getLatitude(), me.getLongitude(), Math.max(map.zoom(), 14));
                else locate(true);
            }
        }, android.view.Gravity.BOTTOM | android.view.Gravity.LEFT);
        final android.widget.Button fold = overMap("▼");
        fold.setTextSize(22);
        // The panel folds towards the edge it sits on: bottom in portrait, right in landscape.
        boolean foldRight = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.widget.FrameLayout.LayoutParams fl = new android.widget.FrameLayout.LayoutParams(
                (int) (52 * d), (int) (44 * d), foldRight ? android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL
                : android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        if (foldRight) fl.rightMargin = (int) (6 * d);
        else fl.bottomMargin = (int) (6 * d);
        mapBox.addView(fold, fl);
        android.widget.Button addHere = overMap("+ POI");
        android.widget.FrameLayout.LayoutParams al = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) (44 * d), android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
        al.bottomMargin = (int) (6 * d);
        al.rightMargin = (int) (6 * d);
        mapBox.addView(addHere, al);
        // Fake GPS mode: put my fake position at the + (testing, demos).
        android.widget.Button fakeHere = overMap("+ Fake being here");
        android.widget.FrameLayout.LayoutParams fh = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) (44 * d), android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
        fh.bottomMargin = (int) (56 * d);
        fh.rightMargin = (int) (6 * d);
        fakeHere.setVisibility(org.communitycomms.app.geo.FakeGps.enabled(this) ? View.VISIBLE : View.GONE);
        mapBox.addView(fakeHere, fh);
        fakeHereButton = fakeHere;
        fakeHere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                org.communitycomms.app.geo.FakeGps.setHere(MapActivity.this, map.centerLat(), map.centerLon());
                myPos.stop();
                myPos.start(); // picks up the new fake spot straight away
                android.widget.Toast.makeText(MapActivity.this, "Fake GPS centre and position set to the + (" + org.communitycomms.app.geo.FakeGps.centre(MapActivity.this) + ")", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // Search for a suburb, town or street (offline lists for this country, or OpenStreetMap online).
        android.widget.Button search = overMap("Search");
        android.widget.FrameLayout.LayoutParams sl = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) (44 * d), android.view.Gravity.TOP | android.view.Gravity.LEFT);
        sl.topMargin = (int) (8 * d);
        sl.leftMargin = (int) (8 * d);
        mapBox.addView(search, sl);
        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MapSearch.show(MapActivity.this, map.centerLat(), map.centerLon(), new MapSearch.Go() {
                    @Override
                    public void to(double lat, double lon, int zoom, String label) {
                        map.center(lat, lon, Math.max(map.zoom(), zoom));
                        refresh();
                        android.widget.Toast.makeText(MapActivity.this, label + ": the + is on it (Add POI at + to mark it)",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        // Layer: street, satellite or topographic, one tap (each keeps its own saved tiles).
        layerButton = overMap(layerWord());
        android.widget.FrameLayout.LayoutParams ll = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) (44 * d), android.view.Gravity.TOP | android.view.Gravity.LEFT);
        ll.topMargin = (int) (58 * d);
        ll.leftMargin = (int) (8 * d);
        mapBox.addView(layerButton, ll);
        layerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLayer();
            }
        });
        // Tools: the ruler (tap two points for the distance and bearing); a scale bar sits under these buttons.
        final android.widget.Button tools = overMap("Tools");
        android.widget.FrameLayout.LayoutParams rl = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (int) (44 * d), android.view.Gravity.TOP | android.view.Gravity.LEFT);
        rl.topMargin = (int) (108 * d);
        rl.leftMargin = (int) (8 * d);
        mapBox.addView(tools, rl);
        tools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(MapActivity.this).setTitle("Map tools")
                        .setItems(new String[]{map.rulerOn() ? "Ruler off" : "Ruler (distance and bearing between two points)"},
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        boolean on = !map.rulerOn();
                                        map.setRuler(on);
                                        tools.setText(on ? "Tools: ruler" : "Tools");
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null).show();
            }
        });
        map.setScaleBar(180);
        addHere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPoi(null, map.centerLat(), map.centerLon());
            }
        });
        // Portrait: the map above, the buttons below. Landscape: the buttons in a column on the right.
        final boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout mapSide = root;
        LinearLayout sideRow = null;
        if (land) {
            sideRow = new LinearLayout(this);
            sideRow.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(sideRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            mapSide = new LinearLayout(this);
            mapSide.setOrientation(LinearLayout.VERTICAL);
            sideRow.addView(mapSide, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        mapSide.addView(mapBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, land ? 1 : 3));
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        final android.content.SharedPreferences ui = getSharedPreferences("settings", MODE_PRIVATE);
        final Runnable applyFold = new Runnable() {
            @Override
            public void run() {
                boolean folded = ui.getBoolean("mapPanelFolded", false);
                panel.setVisibility(folded ? View.GONE : View.VISIBLE);
                fold.setText(land ? (folded ? "◀" : "▶") : (folded ? "▲" : "▼"));
            }
        };
        fold.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.edit().putBoolean("mapPanelFolded", !ui.getBoolean("mapPanelFolded", false)).commit();
                applyFold.run();
            }
        });
        crossLabel = new TextView(this);
        crossLabel.setTextSize(land ? 13 : 14);
        crossLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        crossLabel.setTextColor(0xfff0c75e);
        crossLabel.setPadding((int) (8 * d), (int) (2 * d), (int) (8 * d), 0);
        mapSide.addView(crossLabel);
        sourceLabel = new TextView(this);
        sourceLabel.setTextSize(12);
        sourceLabel.setPadding((int) (8 * d), (int) (2 * d), (int) (8 * d), 0);
        sourceLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tilePrompt.choose();
            }
        });
        if (land) sideRow.addView(panel, new LinearLayout.LayoutParams((int) (236 * d), LinearLayout.LayoutParams.MATCH_PARENT));
        else root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        // Everything below folds away with ▼ (▶ in landscape). Landscape: the text, then the icons in a column.
        if (land) {
            panel.setOrientation(LinearLayout.HORIZONTAL);
            root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            panel.addView(root, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        } else {
            root = panel;
        }

        // Icon buttons (long press says what each does): two rows in portrait, one column in landscape.
        List<MapIconButton> keys = new ArrayList<MapIconButton>();
        keys.add(new MapIconButton(this, MapIconButton.ME, "Me", "Centre the map on me", new Runnable() {
            @Override
            public void run() {
                if (me != null) map.center(me.getLatitude(), me.getLongitude(), Math.max(map.zoom(), 14));
                else locate(true);
            }
        }));
        keys.add(new MapIconButton(this, MapIconButton.EVERYONE, "All", "Fit everyone on the map", new Runnable() {
            @Override
            public void run() {
                fitAll();
            }
        }));
        keys.add(new MapIconButton(this, MapIconButton.ADD, "POI", "Add a map point at the +", new Runnable() {
            @Override
            public void run() {
                editPoi(null, map.centerLat(), map.centerLon());
            }
        }));
        keys.add(new MapIconButton(this, MapIconButton.SYNC, "Sync", "Sync map points (or resync: clear others' and fetch again)",
                new Runnable() {
                    @Override
                    public void run() {
                        syncPoisChoice();
                    }
                }));
        keys.add(poiFilterButton = new MapIconButton(this, MapIconButton.FILTER, poiWord(), "Which channels' map points to show",
                new Runnable() {
                    @Override
                    public void run() {
                        choosePoiFilter();
                    }
                }));
        keys.add(padToggle = new MapIconButton(this, MapIconButton.PAD, "Pad", "Show or hide the arrows and zoom over the map",
                new Runnable() {
                    @Override
                    public void run() {
                        // Arrows, zoom and centre over the map (all maps); off by default. Pinch and drag always work.
                        boolean on = !org.communitycomms.app.geo.MapPad.shown(MapActivity.this);
                        org.communitycomms.app.geo.MapPad.setShown(MapActivity.this, on);
                        mapPad.setVisibility(on ? View.VISIBLE : View.GONE);
                        padToggle.setLit(on);
                    }
                }));
        padToggle.setLit(org.communitycomms.app.geo.MapPad.shown(this));
        final MapIconButton[] fakeToggle = new MapIconButton[1];
        fakeToggle[0] = new MapIconButton(this, MapIconButton.FAKE, "Fake", "Fake GPS on or off (lit = on)", new Runnable() {
            @Override
            public void run() {
                // Training / demos: a random spot near the Fake GPS centre (Settings > Mission and data).
                boolean on = !org.communitycomms.app.geo.FakeGps.enabled(MapActivity.this);
                String centre = org.communitycomms.app.geo.FakeGps.centre(MapActivity.this);
                if (on && centre.trim().length() == 0) centre = Geo.latLon(map.centerLat(), map.centerLon()); // none yet: the +
                org.communitycomms.app.geo.FakeGps.set(MapActivity.this, on, centre);
                fakeToggle[0].setLit(on);
                if (fakeHereButton != null) fakeHereButton.setVisibility(on ? View.VISIBLE : View.GONE);
                if (!on) org.communitycomms.app.geo.ObscureAsk.afterFakeOff(MapActivity.this, null);
                android.widget.Toast.makeText(MapActivity.this, on ? "Fake GPS on: you show as a spot near "
                        + org.communitycomms.app.geo.FakeGps.centre(MapActivity.this) : "Fake GPS off: your real position is used",
                        android.widget.Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        fakeToggle[0].setLit(org.communitycomms.app.geo.FakeGps.enabled(this));
        keys.add(fakeToggle[0]);
        keys.add(shareKey = new MapIconButton(this, MapIconButton.SHARE, "Share", "Share my location (30 minutes, or longer)",
                new Runnable() {
                    @Override
                    public void run() {
                        ShareLocation.start(MapActivity.this, new Runnable() {
                            @Override
                            public void run() {
                                renderShareKeys();
                            }
                        });
                    }
                }));
        keys.add(askKey = new MapIconButton(this, MapIconButton.ASK, "Ask", "Ask everyone on this private channel to share their location",
                new Runnable() {
                    @Override
                    public void run() {
                        RadioService rs = RadioService.instance();
                        if (rs == null || !rs.requestLocations()) {
                            android.widget.Toast.makeText(MapActivity.this, "Only on a private channel (A, B or another keyed channel), not Public",
                                    android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            android.widget.Toast.makeText(MapActivity.this, "Asked everyone on this channel to share their location",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                }));
        keys.add(new MapIconButton(this, MapIconButton.TILES, "Tiles", "Sync map tiles with the LAN storage", new Runnable() {
            @Override
            public void run() {
                syncTiles();
            }
        }));
        if (land) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            for (MapIconButton key : keys) {
                column.addView(key, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (46 * d)));
            }
            ScrollView keysScroll = new ScrollView(this); // a short phone in landscape scrolls them
            keysScroll.setVerticalScrollBarEnabled(false);
            keysScroll.addView(column);
            panel.addView(keysScroll, new LinearLayout.LayoutParams((int) (56 * d), LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            int perRow = 5; // two rows of five
            LinearLayout row = null;
            for (int i = 0; i < keys.size(); i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(this);
                    row.setPadding((int) (2 * d), 0, (int) (2 * d), 0);
                    root.addView(row);
                }
                row.addView(keys.get(i), new LinearLayout.LayoutParams(0, (int) (54 * d), 1));
            }
        }
        root.addView(sourceLabel);

        ScrollView sv = new ScrollView(this);
        list = new TextView(this);
        list.setTypeface(android.graphics.Typeface.MONOSPACE);
        list.setTextSize(14);
        list.setPadding((int) (8 * d), 0, (int) (8 * d), (int) (8 * d));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(top);
        applyFold.run();

        map.setOnMarkerTap(new TileMapView.OnMarkerTap() {
            @Override
            public void onMarkerTap(TileMapView.Marker m) {
                if (m.tag instanceof org.communitycomms.app.geo.Poi) showPoi((org.communitycomms.app.geo.Poi) m.tag);
                else if (m.tag instanceof org.communitycomms.app.geo.AdsbFeed.Aircraft) {
                    new android.app.AlertDialog.Builder(MapActivity.this)
                            .setMessage(org.communitycomms.app.geo.AdsbFeed.details((org.communitycomms.app.geo.AdsbFeed.Aircraft) m.tag))
                            .setPositiveButton(android.R.string.ok, null).show();
                } else if (m.tag instanceof org.communitycomms.app.geo.CapFeed.Alert) {
                    new android.app.AlertDialog.Builder(MapActivity.this).setTitle("Emergency alert")
                            .setMessage(org.communitycomms.app.geo.CapFeed.details((org.communitycomms.app.geo.CapFeed.Alert) m.tag))
                            .setPositiveButton(android.R.string.ok, null).show();
                } else if (m.tag instanceof org.communitycomms.app.geo.ShipFeed.Ship) {
                    new android.app.AlertDialog.Builder(MapActivity.this)
                            .setMessage(org.communitycomms.app.geo.ShipFeed.details((org.communitycomms.app.geo.ShipFeed.Ship) m.tag))
                            .setPositiveButton(android.R.string.ok, null).show();
                } else showStation(m);
            }
        });
        map.setOnLongPress(new TileMapView.OnLongPress() {
            @Override
            public void onLongPress(double lat, double lon) {
                editPoi(null, lat, lon);
            }
        });
        map.center(mp.centerLat(), mp.centerLon(), mp.zoom());
        tilePrompt = new org.communitycomms.app.geo.OnlineTilePrompt(this, map, new Runnable() {
            @Override
            public void run() {
                showSources();
            }
        });
        myPos = new org.communitycomms.app.geo.MyPosition(this, new org.communitycomms.app.geo.MyPosition.Listener() {
            @Override
            public void onMyPosition(Location loc, float heading) {
                if (loc != null) me = loc;
                map.setMe(loc, heading);
            }
        });
        locate(false);
        // Tapped on the home map: open that point straight away (details, edit, remove).
        long poiId = getIntent().getLongExtra(EXTRA_POI, 0);
        if (poiId != 0 && b == null) {
            for (org.communitycomms.app.geo.Poi p : org.communitycomms.app.geo.PoiStore.get(this).all()) {
                if (p.id == poiId) {
                    map.center(p.lat, p.lon, Math.max(map.zoom(), 15));
                    showPoi(p);
                    break;
                }
            }
        }
        String who = getIntent().getStringExtra(EXTRA_CONTACT);
        Contacts.Contact c = who == null ? null : Contacts.get(this).find(who);
        if (c != null && c.hasFix()) {
            focus = c;
            if (b == null) map.center(c.lat, c.lon, Math.max(map.zoom(), 15));
        }
    }

    static final String EXTRA_POI = "poi";
    /** Opened from Contacts: centre on this callsign's last advertised position. */
    static final String EXTRA_CONTACT = "contact";
    private Contacts.Contact focus;
    private org.communitycomms.app.geo.OnlineTilePrompt tilePrompt;
    private org.communitycomms.app.geo.MyPosition myPos;

    @Override
    protected void onResume() {
        super.onResume();
        tilePrompt.resume();
        myPos.start();
        handler.post(tick);
        handler.post(crossTick);
        RadioService.poiUi = poiRefresh;
        startAdsb();
        startShips();
        startAlerts();
        RadioService.locationAsk = locationAsk;
        renderShareKeys();
    }

    // ---- ATAK: map files and map source ----

    private static final int REQ_MAP_FILE = 41;

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (BulkPois.onResult(this, req, res, data)) return; // bulk add: a QR part or a file
        if (req != REQ_MAP_FILE || res != RESULT_OK || data == null || data.getData() == null) return;
        // Copy the picked file in (SQLite needs a real file), then offer to import it.
        final android.net.Uri u = data.getData();
        String name = "map.mbtiles";
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0 && c.getString(col) != null) name = c.getString(col);
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (!name.toLowerCase(java.util.Locale.US).matches(".*\\.(mbtiles|sqlite|sqlitedb)$")) name = name + ".mbtiles";
        final java.io.File f = new java.io.File(org.communitycomms.app.FilesProvider.exportDir(this), name.replaceAll("[/\\\\]", "_"));
        try {
            java.io.InputStream in = getContentResolver().openInputStream(u);
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
            org.communitycomms.app.geo.AtakTiles.offerImport(this, f);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not read that file: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Writes what this phone has for the area on screen (this zoom and 3 closer) as MBTiles or osmdroid SQLite, then shares it. */
    private void exportForAtak() {
        final String[] kinds = {"MBTiles (.mbtiles: ATAK, QGIS, most map apps)", "osmdroid SQLite (.sqlite: ATAK's own download format)"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Export this area for ATAK")
                .setItems(kinds, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, final int which) {
                        final double[] b = map.bounds();
                        final int z = map.zoom();
                        final java.io.File[] roots = {mp.offlineFolder(), mp.onlineCache()};
                        final String name = "YCC map " + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date());
                        final java.io.File out = new java.io.File(org.communitycomms.app.FilesProvider.exportDir(MapActivity.this),
                                name.replace(' ', '_') + (which == 0 ? ".mbtiles" : ".sqlite"));
                        android.widget.Toast.makeText(MapActivity.this, "Making the map file…", android.widget.Toast.LENGTH_SHORT).show();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String err = null;
                                int n = 0;
                                try {
                                    n = org.communitycomms.app.geo.AtakTiles.export(roots, b[0], b[1], b[2], b[3], Math.max(1, z - 1),
                                            Math.min(19, z + 3), which == 0 ? org.communitycomms.app.geo.AtakTiles.MBTILES
                                                    : org.communitycomms.app.geo.AtakTiles.OSMDROID, name, out);
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final String fe = err;
                                final int fn = n;
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (fe != null || fn == 0) {
                                            android.widget.Toast.makeText(MapActivity.this, fe != null ? "Could not make it: " + fe
                                                    : "No tiles here on this phone yet: view the area (or sync tiles) first",
                                                    android.widget.Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        shareFile(out, "application/octet-stream", fn + " tiles. In ATAK, put the file in atak/imagery (or import it).");
                                    }
                                });
                            }
                        }, "atak-export").start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void shareFile(java.io.File f, String mime, String note) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(android.content.Intent.EXTRA_STREAM, org.communitycomms.app.FilesProvider.export(f));
        i.putExtra(android.content.Intent.EXTRA_SUBJECT, f.getName());
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        android.widget.Toast.makeText(this, note, android.widget.Toast.LENGTH_LONG).show();
        try {
            startActivity(android.content.Intent.createChooser(i, "Send " + f.getName()));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Saved as " + f.getPath(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * An ATAK map source (MOBAC XML) for the LAN tile store's copy of this map, as a file to share and
     * a QR code. ATAK cannot log in, so the store must let anyone on the LAN read its "Maps cache".
     */
    private void atakMapSource() {
        org.communitycomms.app.geo.LanTiles lan = new org.communitycomms.app.geo.LanTiles(this);
        if (!lan.configured()) {
            android.widget.Toast.makeText(this, "Set the LAN storage location first (Settings)", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String chosen = mp.chosenUrl();
        String srcName = MapPrefs.ONLINE_NAMES[Math.max(0, Math.min(MapPrefs.ONLINE_NAMES.length - 1, mp.onlinePreset()))].split(" \\(")[0];
        final String xml = org.communitycomms.app.geo.AtakTiles.mapSourceXml("YCC LAN - " + srcName, lan.base(),
                org.communitycomms.app.geo.LanTiles.sourceId(chosen), 2, 19, chosen.contains(".png"));
        final java.io.File f = new java.io.File(org.communitycomms.app.FilesProvider.exportDir(this), "YCC_LAN_map.xml");
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(xml.getBytes("UTF-8"));
            os.close();
        } catch (java.io.IOException e) {
            android.widget.Toast.makeText(this, "Could not write it: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("ATAK map source")
                .setMessage("ATAK phones on this network can use the LAN tile store as a map. Send them this file "
                        + "(they put it in atak/imagery or import it), or let them scan the QR code.\n\n"
                        + "ATAK cannot log in, so the store must let anyone on the LAN read its \"Maps cache\" folder.")
                .setPositiveButton("Share file", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        shareFile(f, "text/xml", "ATAK map source");
                    }
                })
                .setNeutralButton("Show QR", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        java.util.ArrayList<String> p = new java.util.ArrayList<String>(), t = new java.util.ArrayList<String>();
                        p.add(xml);
                        t.add("ATAK map source (LAN tiles)");
                        QrShowActivity.start(MapActivity.this, p, t, null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- LAN storage location (shared maps cache) ----

    /** Syncs what is on screen (this zoom and the next two) both ways with the LAN storage location. */
    private void syncTiles() {
        final org.communitycomms.app.geo.LanTiles lan = new org.communitycomms.app.geo.LanTiles(this);
        if (!lan.configured()) {
            LanStorageDialog.show(this, new Runnable() {
                @Override
                public void run() {
                    if (new org.communitycomms.app.geo.LanTiles(MapActivity.this).configured()) syncTiles();
                }
            });
            return;
        }
        if (mp.onlineUrl().length() == 0) {
            android.widget.Toast.makeText(this, "Choose a map source first (Data / files > Maps)", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final double[] b = map.bounds();
        final int z = map.zoom();
        // The source this phone really fetches (Google's road map on Android 4.x).
        final String src = org.communitycomms.app.geo.LanTiles.sourceId(mp.onlineUrl());
        final java.io.File cache = mp.onlineCache();
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("Sync tiles with " + lan.base());
        pd.setMessage("Checking…");
        pd.setCancelable(false);
        final Thread[] t = new Thread[1];
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Stop", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int w) {
                if (t[0] != null) t[0].interrupt();
            }
        });
        pd.show();
        t[0] = new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = lan.syncArea(src, cache, b[0], b[1], b[2], b[3], z, Math.min(19, z + 2),
                        new org.communitycomms.app.geo.LanTiles.Progress() {
                            @Override
                            public void update(final String text) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        pd.setMessage(text);
                                    }
                                });
                            }
                        });
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pd.isShowing()) pd.dismiss();
                        map.retryMissing();
                        new android.app.AlertDialog.Builder(MapActivity.this).setMessage(r)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNeutralButton("LAN storage…", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        LanStorageDialog.show(MapActivity.this, null);
                                    }
                                }).show();
                    }
                });
            }
        }, "lan-sync");
        t[0].start();
    }

    // ---- aircraft (ADS-B) ----

    private org.communitycomms.app.geo.AdsbFeed adsb;

    private org.communitycomms.app.geo.ShipFeed ships;

    private void startShips() {
        if (ships == null) ships = new org.communitycomms.app.geo.ShipFeed(this);
        ships.setCentre(map.centerLat(), map.centerLon());
        ships.start(new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        ships.setCentre(map.centerLat(), map.centerLon());
                        refresh();
                        String note = org.communitycomms.app.geo.ShipFeed.autoOffNote;
                        if (note != null) {
                            org.communitycomms.app.geo.ShipFeed.autoOffNote = null;
                            new android.app.AlertDialog.Builder(MapActivity.this).setTitle("Ships turned off")
                                    .setMessage(note).setPositiveButton(android.R.string.ok, null).show();
                        }
                    }
                });
            }
        });
    }

    private org.communitycomms.app.geo.CapFeed alerts;

    private void startAlerts() {
        if (alerts == null) alerts = new org.communitycomms.app.geo.CapFeed(this);
        alerts.setCentre(map.centerLat(), map.centerLon());
        alerts.start(new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        alerts.setCentre(map.centerLat(), map.centerLon());
                        refresh();
                    }
                });
            }
        });
    }

    /** Emergency alerts (CAP): shaded areas and a marker each. Shared with the TV screen. */
    static void addAlerts(List<TileMapView.Marker> marks, org.communitycomms.app.geo.CapFeed alerts) {
        if (alerts == null) return;
        for (org.communitycomms.app.geo.CapFeed.Alert a : alerts.alerts()) {
            TileMapView.Marker m = new TileMapView.Marker(org.communitycomms.app.geo.CapFeed.label(a), a.lat, a.lon,
                    org.communitycomms.app.geo.CapFeed.colour(a), a);
            if (!a.polygons.isEmpty()) m.area = a.polygons;
            marks.add(0, m);
        }
    }

    /** Ships as boat markers (tap for details). Shared with the TV screen. */
    static void addShips(List<TileMapView.Marker> marks, org.communitycomms.app.geo.ShipFeed ships) {
        if (ships == null) return;
        for (org.communitycomms.app.geo.ShipFeed.Ship s : ships.ships()) {
            marks.add(new TileMapView.Marker(org.communitycomms.app.geo.ShipFeed.label(s), s.lat, s.lon, 0xff26a69a, s, 46));
        }
    }

    private void startAdsb() {
        if (adsb == null) adsb = new org.communitycomms.app.geo.AdsbFeed(this);
        adsb.setCentre(map.centerLat(), map.centerLon());
        adsb.start(new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        adsb.setCentre(map.centerLat(), map.centerLon());
                        refresh();
                    }
                });
            }
        });
    }

    /** Aircraft as plane markers (tap for details). Shared with the TV screen. */
    static void addAircraft(List<TileMapView.Marker> marks, org.communitycomms.app.geo.AdsbFeed adsb) {
        if (adsb == null) return;
        for (org.communitycomms.app.geo.AdsbFeed.Aircraft a : adsb.aircraft()) {
            marks.add(new TileMapView.Marker(org.communitycomms.app.geo.AdsbFeed.label(a), a.lat, a.lon, 0xff42a5f5, a, 8));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu m) {
        m.add(0, 2, 0, "Online map tiles…");
        m.add(0, 10, 0, "Data Tile Management…");
        m.add(0, 1, 0, "Aircraft (ADS-B)…");
        m.add(0, 3, 0, "Ships (AIS)…");
        m.add(0, 7, 0, "Emergency alerts (CAP)…");
        m.add(0, 8, 0, "Bulk add points…");
        m.add(0, 9, 0, "Export points…");
        m.add(0, 4, 0, "Import map file (ATAK, MBTiles)…");
        m.add(0, 5, 0, "Export this area for ATAK…");
        m.add(0, 6, 0, "ATAK map source for the LAN tiles…");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 2) {
            tilePrompt.choose();
            return true;
        }
        if (item.getItemId() == 4) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(android.content.Intent.createChooser(i, "MBTiles or ATAK / osmdroid SQLite file"), REQ_MAP_FILE);
            } catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(this, "No file picker on this phone", android.widget.Toast.LENGTH_LONG).show();
            }
            return true;
        }
        if (item.getItemId() == 5) {
            exportForAtak();
            return true;
        }
        if (item.getItemId() == 6) {
            atakMapSource();
            return true;
        }
        if (item.getItemId() == 10) {
            org.communitycomms.app.geo.TilePrefetch.show(this, map.centerLat(), map.centerLon(), new Runnable() {
                @Override
                public void run() {
                    map.retryMissing();
                    refresh();
                }
            });
            return true;
        }
        if (item.getItemId() == 8) {
            BulkPois.showImport(this, null, new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
            return true;
        }
        if (item.getItemId() == 9) {
            BulkPois.showExport(this);
            return true;
        }
        if (item.getItemId() == 7) {
            setUpAlerts();
            return true;
        }
        if (item.getItemId() == 3) {
            setUpShips();
            return true;
        }
        if (item.getItemId() == 1) {
            setUpAdsb();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final Runnable poiRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    private void showSources() {
        boolean online = map.hasOnline();
        String off = mp.offlineSetName();
        int mins = tilePrompt == null ? 0 : tilePrompt.minutesLeft();
        sourceLabel.setText("Tiles: " + (off.length() > 0 ? "offline \"" + off + "\"" : "no offline set")
                + (online ? " + online (" + MapPrefs.ONLINE_NAMES[mp.onlinePreset()].split(" \\(")[0] + ")"
                + (mp.onlineEnabled() ? " always" : mins >= 120 ? " for " + (mins + 59) / 60 + " h" : mins > 0 ? " for " + mins + " min" : "")
                : ", online off") + " (tap to change)"
                + (new Prefs(this).radioSilent() ? " (radio silent)" : "")
                + lanStatus()
                // OSM's servers only accept ciphers Android 4.x does not have; Google's still work.
                + (online && mp.usingOldAndroidFallback()
                ? "\nAndroid 4.x cannot reach " + MapPrefs.ONLINE_NAMES[mp.onlinePreset()].split(" \\(")[0]
                + ", so Google's road map is used instead." : "")
                + (online && android.os.Build.VERSION.SDK_INT < 20 && (mp.overlay().equals("web:3") || mp.overlay().equals("web:5"))
                ? "\nThe OpenTopoMap / Esri overlay needs Android 5 or newer." : "")
                + adsbStatus());
    }

    /** The shared maps cache at work: tiles read from the LAN storage and given to it since the app started. */
    private String lanStatus() {
        if (!new org.communitycomms.app.geo.LanTiles(this).configured()) return "\nLAN maps cache: not set up (Settings > Network and sync)";
        int got = TileMapView.lanGot.get(), sent = TileMapView.lanSent.get(), failed = TileMapView.lanFailed.get();
        return "\nLAN maps cache: " + got + " tile(s) from it, " + sent + " given to it"
                + (failed > 0 ? ", " + failed + " could not be given (check the address and login)" : "");
    }

    private String adsbStatus() {
        String s = "";
        if (adsb != null && adsb.source() != org.communitycomms.app.geo.AdsbFeed.OFF) {
            s += adsb.problem().length() > 0 ? "\n" + adsb.problem() : " · aircraft: " + adsb.aircraft().size();
        }
        if (ships != null && ships.enabled()) {
            s += ships.problem().length() > 0 ? "\n" + ships.problem() : " · ships: " + ships.ships().size();
        }
        if (alerts != null && alerts.enabled()) {
            s += alerts.problem().length() > 0 ? "\n" + alerts.problem() : " · alerts: " + alerts.alerts().size();
        }
        return s;
    }

    /** Tile servers whose encryption Android 4.x cannot do (Google's still works there). */
    private static boolean needsNewTls(int preset) {
        return preset == 0 || preset == 3 || preset == 5;
    }

    @Override
    protected void onPause() {
        tilePrompt.pause();
        myPos.stop();
        adsb.stop();
        if (ships != null) ships.stop();
        if (alerts != null) alerts.stop();
        if (RadioService.locationAsk == locationAsk) RadioService.locationAsk = null;
        if (RadioService.poiUi == poiRefresh) RadioService.poiUi = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(crossTick);
        mp.saveView(map.centerLat(), map.centerLon(), map.zoom());
        super.onPause();
    }

    /** A small see-through dark button drawn over the map. */
    private android.widget.Button overMap(String t) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(0xffffffff);
        b.setTextSize(16);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        b.setPadding(p, 0, p, 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xcc1f3d2b);
        g.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), 0xfff0c75e);
        g.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        b.setBackgroundDrawable(g);
        return b;
    }

    private Button button(String t, final Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private void locate(final boolean centre) {
        LocationHelper.getFix(this, 15000, new LocationHelper.Callback() {
            @Override
            public void onFix(Location loc, String problem) {
                if (loc != null) {
                    me = loc;
                    if (centre) map.center(loc.getLatitude(), loc.getLongitude(), Math.max(map.zoom(), 14));
                    refresh();
                } else if (centre) {
                    android.widget.Toast.makeText(MapActivity.this, problem, android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private List<RadioEngine.Station> withFix() {
        RadioService s = RadioService.instance();
        RadioEngine e = s == null ? null : s.engine();
        List<RadioEngine.Station> out = new ArrayList<RadioEngine.Station>();
        if (e == null) return out;
        for (RadioEngine.Station st : e.stations()) if (!Double.isNaN(st.lat)) out.add(st);
        return out;
    }

    private final java.util.Map<String, android.graphics.Bitmap> thumbs = new java.util.HashMap<String, android.graphics.Bitmap>();

    /** Everyone's last position, every POI (photo POIs as a thumbnail) and me. Shared with the map home screen. */
    static List<TileMapView.Marker> markers(android.content.Context c, Location me,
                                            java.util.Map<String, android.graphics.Bitmap> thumbs) {
        List<TileMapView.Marker> marks = new ArrayList<TileMapView.Marker>();
        long now = System.currentTimeMillis();
        RadioService rs = RadioService.instance();
        RadioEngine e = rs == null ? null : rs.engine();
        if (e != null) {
            for (RadioEngine.Station s : e.stations()) {
                if (Double.isNaN(s.lat)) continue;
                boolean stale = now - s.fixTimeMs > 15 * 60000;
                marks.add(new TileMapView.Marker(s.fake ? s.name + " (Fake)" : s.name, s.lat, s.lon,
                        stale ? 0xff9e9e9e : 0xffff7043, s, s.icon));
            }
        }
        // ATAK compatible: ATAK users heard on its mesh multicast.
        for (AtakBridge.Contact a : AtakBridge.contacts()) {
            marks.add(new TileMapView.Marker(a.callsign + " (ATAK)", a.lat, a.lon, 0xff29b6f6, a, -1));
        }
        for (org.communitycomms.app.geo.Poi p : org.communitycomms.app.geo.PoiStore.get(c).all()) {
            if (!org.communitycomms.app.geo.PoiFilter.shows(c, p)) continue; // "Points: A" etc. (full map)
            String note = org.communitycomms.app.geo.Poi.readable(p.note).replace('\n', ' ');
            String label = note.length() > 18 ? note.substring(0, 18) + "…" : note;
            org.communitycomms.app.geo.Poi.Shape shape = org.communitycomms.app.geo.Poi.shape(p.note);
            if (shape != null && shape.type == org.communitycomms.app.geo.Poi.TEXT) {
                label = org.communitycomms.app.geo.Poi.readable(p.note).replace("\n", " / "); // the whole text in the box
            }
            TileMapView.Marker m = new TileMapView.Marker(label, p.lat, p.lon, 0, p, p.icon);
            m.shape = shape;
            m.aim = org.communitycomms.app.geo.Poi.aim(p.note);
            int[] pose = org.communitycomms.app.geo.Poi.pose(p.note);
            m.rotation = pose[0];
            m.sizePct = pose[1];
            m.arrow = pose[2] == 1;
            String path = photoPath(c, p);
            if (path != null) m.thumb = thumb(path, thumbs);
            marks.add(m);
        }
        return marks; // me is drawn by the map itself (arrow pointing the way the phone faces)
    }

    /** The picture a POI links to, if this phone has it (sent from here or received). */
    static String photoPath(android.content.Context c, org.communitycomms.app.geo.Poi p) {
        Long key = org.communitycomms.app.geo.Poi.photoKey(p.note);
        if (key == null) return null;
        org.communitycomms.app.msg.MessageLog.Entry m = org.communitycomms.app.msg.MessageLog.get(c).find(key);
        return m != null && m.path.length() > 0 && new java.io.File(m.path).exists() ? m.path : null;
    }

    static android.graphics.Bitmap thumb(String path, java.util.Map<String, android.graphics.Bitmap> cache) {
        if (cache.containsKey(path)) return cache.get(path);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 96) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(path, o);
        cache.put(path, b);
        return b;
    }

    private void refresh() {
        List<RadioEngine.Station> st = withFix();
        long now = System.currentTimeMillis();
        List<TileMapView.Marker> marks = markers(this, me, thumbs);
        addAircraft(marks, adsb);
        addShips(marks, ships);
        addAlerts(marks, alerts);
        renderShareKeys();
        showSources();
        if (focus != null) {
            // Someone from Contacts who is no longer on this channel: their last known spot, in grey.
            boolean shown = false;
            for (RadioEngine.Station s : st) shown |= s.name.equals(focus.name);
            if (!shown) marks.add(new TileMapView.Marker(focus.name, focus.lat, focus.lon, 0xff9e9e9e, focus));
        }
        map.setMarkers(marks);
        if (myPos != null) map.setMeProblem(myPos.problem());

        final Location ref = me;
        Collections.sort(st, new Comparator<RadioEngine.Station>() {
            @Override
            public int compare(RadioEngine.Station a, RadioEngine.Station b) {
                if (ref == null) return a.name.compareToIgnoreCase(b.name);
                return Double.compare(Geo.distanceM(ref.getLatitude(), ref.getLongitude(), a.lat, a.lon),
                        Geo.distanceM(ref.getLatitude(), ref.getLongitude(), b.lat, b.lon));
            }
        });
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (st.isEmpty()) {
            sb.append(RadioService.instance() == null || RadioService.instance().engine() == null
                    ? "Join a channel to see where people are."
                    : "Nobody has shared a location yet. Ask them to tap Send location on the Data screen.");
        }
        for (RadioEngine.Station s : st) {
            // Name coloured by status (yellow away, red stealth, grey offline); map markers stay as they are.
            int ns = sb.length();
            sb.append(s.name);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Contacts.statusColour(s.status,
                    now - s.lastSeenMs < Contacts.ONLINE_MS)), ns, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (s.fake) sb.append(" (Fake)");
            sb.append("  ");
            if (ref != null) {
                double brg = Geo.bearing(ref.getLatitude(), ref.getLongitude(), s.lat, s.lon);
                sb.append(Geo.arrow(brg)).append(' ').append(Geo.compass(brg)).append(' ')
                        .append(String.valueOf(Math.round(brg))).append("°  ")
                        .append(Geo.distance(Geo.distanceM(ref.getLatitude(), ref.getLongitude(), s.lat, s.lon)));
            } else {
                sb.append(Geo.latLon(s.lat, s.lon));
            }
            sb.append("  ").append(Geo.age(now - s.fixTimeMs));
            sb.append('\n');
        }
        if (ref == null && !st.isEmpty()) sb.append("\n(Waiting for your own GPS fix to show direction and distance.)");
        list.setText(sb);
    }

    private void fitAll() {
        List<RadioEngine.Station> st = withFix();
        if (st.isEmpty() && me == null) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (RadioEngine.Station s : st) {
            minLat = Math.min(minLat, s.lat);
            maxLat = Math.max(maxLat, s.lat);
            minLon = Math.min(minLon, s.lon);
            maxLon = Math.max(maxLon, s.lon);
        }
        if (me != null) {
            minLat = Math.min(minLat, me.getLatitude());
            maxLat = Math.max(maxLat, me.getLatitude());
            minLon = Math.min(minLon, me.getLongitude());
            maxLon = Math.max(maxLon, me.getLongitude());
        }
        int zoom = 17;
        while (zoom > 3) {
            double w = (Geo.lonToX(maxLon, zoom) - Geo.lonToX(minLon, zoom)) * 256;
            double h = (Geo.latToY(minLat, zoom) - Geo.latToY(maxLat, zoom)) * 256;
            if (w < map.getWidth() * 0.6 && h < map.getHeight() * 0.6) break;
            zoom--;
        }
        map.center((minLat + maxLat) / 2, (minLon + maxLon) / 2, zoom);
    }

    // ---- POIs ----

    private static final String[] LIFE_LABELS = {"15 minutes", "1 hour", "6 hours", "24 hours", "7 days", "Never expires"};
    private static final long[] LIFE_MS = {15 * 60000L, 3600000L, 6 * 3600000L, 24 * 3600000L, 7 * 24 * 3600000L, 0};

    private static String expiryText(org.communitycomms.app.geo.Poi p) {
        if (p.expiresAt == 0) return "never expires";
        long left = p.expiresAt - System.currentTimeMillis();
        long m = Math.max(0, left / 60000);
        return "expires in " + (m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min");
    }

    private void showPoi(final org.communitycomms.app.geo.Poi p) {
        StringBuilder sb = new StringBuilder();
        sb.append(org.communitycomms.app.geo.Poi.ICON_NAMES[p.icon]).append("\n")
                .append(Geo.latLon(p.lat, p.lon)).append("\n").append(expiryText(p))
                .append("\nby ").append(p.creator);
        if (!p.editor.equals(p.creator)) sb.append(", last edited by ").append(p.editor);
        if (me != null) {
            double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), p.lat, p.lon);
            sb.append("\n").append(Geo.compass(brg)).append(' ').append(Math.round(brg)).append("°, ")
                    .append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), p.lat, p.lon)));
        }
        String note = org.communitycomms.app.geo.Poi.readable(p.note);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        final String photo = photoPath(this, p);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        android.widget.Button nav = new android.widget.Button(this);
        nav.setText("Navigate / open in Google Maps…");
        nav.setAllCaps(false);
        nav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInMaps(MapActivity.this, p.lat, p.lon, org.communitycomms.app.geo.Poi.readable(p.note));
            }
        });
        box.addView(nav);
        b.setView(box);
        if (photo != null) {
            // The linked picture, tap for full size.
            android.widget.ImageView iv = new android.widget.ImageView(this);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 4;
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(photo, o));
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight((int) (240 * getResources().getDisplayMetrics().density));
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    android.widget.ImageView big = new android.widget.ImageView(MapActivity.this);
                    android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
                    bo.inSampleSize = 2;
                    big.setImageBitmap(android.graphics.BitmapFactory.decodeFile(photo, bo));
                    big.setAdjustViewBounds(true);
                    new AlertDialog.Builder(MapActivity.this).setView(big).setPositiveButton("Close", null).show();
                }
            });
            box.addView(iv, 0);
        } else if (org.communitycomms.app.geo.Poi.photoKey(p.note) != null) {
            sb.append("\n(The photo has not reached this phone.)");
        }
        b.setTitle(note.length() == 0 ? "POI" : note)
                .setMessage(sb)
                .setPositiveButton("Edit & resend", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        editPoi(p, p.lat, p.lon);
                    }
                })
                .setNeutralButton("Remove", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        confirmRemove(p);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Hands a point to Google Maps (driving directions) or any other map / navigation app on the
     * phone (OsmAnd, Organic Maps, Waze…). Needs that app; Google Maps needs internet for routing.
     */
    static void openInMaps(Activity a, double lat, double lon, String label) {
        String ll = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon);
        String name = label == null || label.length() == 0 ? "Map point" : label;
        java.util.List<android.content.Intent> choices = new java.util.ArrayList<android.content.Intent>();
        android.content.Intent nav = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("google.navigation:q=" + ll));
        nav.setPackage("com.google.android.apps.maps");
        if (nav.resolveActivity(a.getPackageManager()) != null) choices.add(nav);
        android.content.Intent geo = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:" + ll + "?q=" + ll + "(" + android.net.Uri.encode(name) + ")"));
        if (geo.resolveActivity(a.getPackageManager()) == null && choices.isEmpty()) {
            android.widget.Toast.makeText(a, "No map or navigation app on this phone (" + ll + ")", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.content.Intent chooser = android.content.Intent.createChooser(geo, "Open " + name + " in");
        if (!choices.isEmpty()) {
            chooser.putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, choices.toArray(new android.os.Parcelable[0]));
        }
        try {
            a.startActivity(chooser);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(a, "No map app found", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Remove for everyone, asking whether to announce it (ticked by default). */
    private void confirmRemove(final org.communitycomms.app.geo.Poi p) {
        final android.widget.CheckBox announce = new android.widget.CheckBox(this);
        announce.setText("Announce the removal (spoken on everyone's phone)");
        announce.setChecked(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        announce.setPadding(announce.getPaddingLeft(), pad / 2, pad, 0);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(announce);
        new AlertDialog.Builder(this)
                .setTitle("Remove this map point for everyone?")
                .setView(box)
                .setPositiveButton("Remove", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        // Removal = a newer version that has already expired, so everyone drops it.
                        org.communitycomms.app.geo.Poi gone = copy(p);
                        gone.version++;
                        gone.expiresAt = System.currentTimeMillis() - 1;
                        gone.editor = myName();
                        gone.note = org.communitycomms.app.geo.Poi.withQuiet(gone.note, !announce.isChecked());
                        RadioService rs = RadioService.instance();
                        if (rs != null) rs.sendPoi(gone);
                        org.communitycomms.app.geo.PoiStore.get(MapActivity.this).remove(p.id);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String myName() {
        String n = new Prefs(this).callsign();
        return n.length() == 0 ? "?" : n;
    }

    private static org.communitycomms.app.geo.Poi copy(org.communitycomms.app.geo.Poi p) {
        org.communitycomms.app.geo.Poi c = org.communitycomms.app.geo.Poi.fromLine(p.toLine());
        return c;
    }

    /** Move a map point: the map centres on it, drag it under the + and tap Save here. */
    private void startMove(org.communitycomms.app.geo.Poi p) {
        moving = p;
        String n = org.communitycomms.app.geo.Poi.readable(p.note);
        moveText.setText("Moving " + (n.length() == 0 ? "the map point" : "\"" + n + "\"")
                + ": drag the map until the + is where it goes.");
        moveBar.setVisibility(View.VISIBLE);
        map.center(p.lat, p.lon, Math.max(map.zoom(), 16));
    }

    private void endMove() {
        moving = null;
        moveBar.setVisibility(View.GONE);
    }

    /** New POI (old == null) at lat/lon, or edit of an existing one (moved to lat/lon); then sends it. */
    private void editPoi(final org.communitycomms.app.geo.Poi old, final double lat, final double lon) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        TextView where = new TextView(this);
        boolean moved = old != null && (old.lat != lat || old.lon != lon);
        where.setText(Geo.latLon(lat, lon) + (moved ? "  (moved "
                + Geo.distance(Geo.distanceM(old.lat, old.lon, lat, lon)) + ")" : ""));
        box.addView(where);
        final android.widget.EditText note = new android.widget.EditText(this);
        note.setHint("Note");
        note.setText(old == null ? "" : org.communitycomms.app.geo.Poi.readable(old.note));
        box.addView(note);
        final int[] icon = {old == null ? new org.communitycomms.app.geo.QuickProfiles(this).iconOrder()[0] : old.icon};
        box.addView(IconPicker.button(this, icon));
        // Turn, size and direction arrow for the icon (a unit lined up with its real formation and heading).
        final int[] oldPose = old == null ? new int[]{0, 100, 0} : org.communitycomms.app.geo.Poi.pose(old.note);
        final LinearLayout poseBox = new LinearLayout(this);
        poseBox.setOrientation(LinearLayout.VERTICAL);
        final TextView rotLabel = new TextView(this), sizeLabel = new TextView(this);
        final android.widget.SeekBar rot = new android.widget.SeekBar(this), size = new android.widget.SeekBar(this);
        rot.setMax(72); // 5° steps
        rot.setProgress(Math.round(oldPose[0] / 5f));
        size.setMax(39); // 50% to 1000% in 25% steps
        size.setProgress(Math.max(0, Math.min(39, (oldPose[1] - 50) / 25)));
        final android.widget.CheckBox arrow = new android.widget.CheckBox(this);
        arrow.setText("Direction arrow (the way it faces or moves)");
        arrow.setChecked(oldPose[2] == 1);
        poseBox.addView(rotLabel);
        poseBox.addView(rot);
        poseBox.addView(sizeLabel);
        poseBox.addView(size);
        poseBox.addView(arrow);
        box.addView(poseBox);
        final Runnable showPose = new Runnable() {
            @Override
            public void run() {
                int deg = rot.getProgress() * 5 % 360;
                rotLabel.setText("Rotation: " + deg + "° " + Geo.compass(deg) + " (0 = the icon as drawn, facing north)");
                sizeLabel.setText("Size: " + (50 + size.getProgress() * 25) + "%");
            }
        };
        showPose.run();
        final boolean[] poseTouched = {false};
        android.widget.SeekBar.OnSeekBarChangeListener poseChange = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                showPose.run();
                if (user) poseTouched[0] = true;
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        };
        rot.setOnSeekBarChangeListener(poseChange);
        size.setOnSeekBarChangeListener(poseChange);
        // Draw as: the icon, or a shape (older phones still show the icon).
        final org.communitycomms.app.geo.Poi.Shape oldShape = old == null ? null : org.communitycomms.app.geo.Poi.shape(old.note);
        final android.widget.Spinner drawAs = new android.widget.Spinner(this);
        String[] shapes = new String[org.communitycomms.app.geo.Poi.SHAPE_NAMES.length + 1];
        shapes[0] = "Draw as: the icon";
        for (int i = 0; i < org.communitycomms.app.geo.Poi.SHAPE_NAMES.length; i++) shapes[i + 1] = "Draw as: " + org.communitycomms.app.geo.Poi.SHAPE_NAMES[i];
        drawAs.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, shapes));
        drawAs.setSelection(oldShape == null ? 0 : oldShape.type + 1);
        box.addView(drawAs);
        final LinearLayout shapeBox = new LinearLayout(this);
        shapeBox.setOrientation(LinearLayout.VERTICAL);
        final android.widget.EditText sizeA = new android.widget.EditText(this), sizeB = new android.widget.EditText(this);
        sizeA.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeB.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final TextView sizeHelp = new TextView(this);
        shapeBox.addView(sizeHelp);
        LinearLayout sizes = new LinearLayout(this);
        sizes.addView(sizeA, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sizes.addView(sizeB, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        shapeBox.addView(sizes);
        final android.widget.Spinner fgSpin = new android.widget.Spinner(this), bgSpin = new android.widget.Spinner(this);
        String[] fgNames = new String[COLOUR_NAMES.length], bgNames = new String[COLOUR_NAMES.length + 1];
        for (int i = 0; i < COLOUR_NAMES.length; i++) fgNames[i] = "Line / text: " + COLOUR_NAMES[i];
        bgNames[0] = "Fill: none";
        for (int i = 0; i < COLOUR_NAMES.length; i++) bgNames[i + 1] = "Fill: " + COLOUR_NAMES[i];
        fgSpin.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, fgNames));
        bgSpin.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, bgNames));
        fgSpin.setSelection(oldShape == null ? 0 : colourIndex(oldShape.fg, 0));
        bgSpin.setSelection(oldShape == null ? 1 : ((oldShape.bg >>> 24) == 0 ? 0 : colourIndex(oldShape.bg | 0xff000000, 0) + 1));
        shapeBox.addView(fgSpin);
        shapeBox.addView(bgSpin);
        box.addView(shapeBox);
        if (oldShape != null) {
            double[] ab = shapeSizes(old.lat, old.lon, oldShape);
            sizeA.setText(String.valueOf(Math.round(ab[0])));
            sizeB.setText(String.valueOf(Math.round(ab[1])));
        }
        drawAs.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                int t = pos - 1;
                shapeBox.setVisibility(t < 0 ? View.GONE : View.VISIBLE);
                poseBox.setVisibility(t < 0 ? View.VISIBLE : View.GONE);
                sizeB.setVisibility(t == org.communitycomms.app.geo.Poi.CIRCLE || t == org.communitycomms.app.geo.Poi.TEXT ? View.GONE : View.VISIBLE);
                sizeA.setVisibility(t == org.communitycomms.app.geo.Poi.TEXT ? View.GONE : View.VISIBLE);
                sizeA.setHint(t == org.communitycomms.app.geo.Poi.CIRCLE ? "Radius (m)" : t >= org.communitycomms.app.geo.Poi.LINE ? "Length (m)" : "Width (m)");
                sizeB.setHint(t >= org.communitycomms.app.geo.Poi.LINE ? "Bearing (°)" : "Height (m)");
                sizeHelp.setText(t == org.communitycomms.app.geo.Poi.TEXT ? "The note is shown in a box on the map."
                        : t >= org.communitycomms.app.geo.Poi.LINE ? "Starts at this point and runs along the bearing."
                        : "Centred on this point.");
                if (sizeA.getText().length() == 0) sizeA.setText(t >= org.communitycomms.app.geo.Poi.LINE ? "200" : "100");
                if (sizeB.getText().length() == 0) sizeB.setText(t >= org.communitycomms.app.geo.Poi.LINE ? "0" : "60");
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        final android.widget.CheckBox toPublic = new android.widget.CheckBox(this);
        toPublic.setText("Also share on Public (and every later change)");
        toPublic.setChecked(old != null && org.communitycomms.app.geo.Poi.isPublic(old.note));
        final android.widget.Spinner life = new android.widget.Spinner(this);
        String[] lifeLabels = LIFE_LABELS;
        if (old != null) {
            lifeLabels = new String[LIFE_LABELS.length + 1];
            lifeLabels[0] = "Keep (" + expiryText(old) + ")";
            System.arraycopy(LIFE_LABELS, 0, lifeLabels, 1, LIFE_LABELS.length);
        }
        life.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, lifeLabels));
        life.setSelection(old == null ? 1 : 0);
        box.addView(life);
        final android.widget.CheckBox announce = new android.widget.CheckBox(this);
        announce.setText(old == null ? "Announce it (spoken on everyone's phone)" : "Announce the change (spoken on everyone's phone)");
        announce.setChecked(true);
        box.addView(announce);
        box.addView(toPublic);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        AlertDialog.Builder dlg = new AlertDialog.Builder(this);
        if (old != null) dlg.setNeutralButton("Move…", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface di, int w) {
                startMove(old);
            }
        });
        dlg.setTitle(old == null ? "Send a POI" : "Edit & resend POI")
                .setView(scroll)
                .setPositiveButton("Send", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        int li = life.getSelectedItemPosition() - (old == null ? 0 : 1);
                        org.communitycomms.app.geo.Poi p;
                        if (old == null) {
                            p = org.communitycomms.app.geo.Poi.create(lat, lon, icon[0],
                                    note.getText().toString().trim(), LIFE_MS[li], myName());
                        } else {
                            p = copy(old);
                            p.version++;
                            p.lat = lat;
                            p.lon = lon;
                            p.icon = icon[0];
                            p.note = note.getText().toString().trim();
                            Long photoKey = org.communitycomms.app.geo.Poi.photoKey(old.note);
                            if (photoKey != null) p.note += "\n" + org.communitycomms.app.geo.Poi.photoTag(photoKey); // keep the picture link
                            p.editor = myName();
                            if (li >= 0) p.expiresAt = LIFE_MS[li] == 0 ? 0 : System.currentTimeMillis() + LIFE_MS[li];
                        }
                        int t = drawAs.getSelectedItemPosition() - 1;
                        org.communitycomms.app.geo.Poi.Shape sh = null;
                        if (t >= 0) {
                            sh = new org.communitycomms.app.geo.Poi.Shape();
                            sh.type = t;
                            double a = number(sizeA, 100), b2 = number(sizeB, 60);
                            if (t == org.communitycomms.app.geo.Poi.CIRCLE) {
                                sh.radiusM = a;
                                sh.lat2 = p.lat;
                                sh.lon2 = p.lon;
                            } else if (t == org.communitycomms.app.geo.Poi.LINE || t == org.communitycomms.app.geo.Poi.ARROW) {
                                double[] e = Geo.destination(p.lat, p.lon, b2, a);
                                sh.lat2 = e[0];
                                sh.lon2 = e[1];
                            } else if (t == org.communitycomms.app.geo.Poi.TEXT) {
                                sh.lat2 = p.lat;
                                sh.lon2 = p.lon;
                            } else { // rectangle / oval: lat2, lon2 = the north-east corner
                                double[] n = Geo.destination(p.lat, p.lon, 0, b2 / 2);
                                double[] e = Geo.destination(p.lat, p.lon, 90, a / 2);
                                sh.lat2 = n[0];
                                sh.lon2 = e[1];
                            }
                            sh.fg = COLOURS[fgSpin.getSelectedItemPosition()];
                            int bi = bgSpin.getSelectedItemPosition();
                            sh.bg = bi == 0 ? 0 : (COLOURS[bi - 1] & 0x00ffffff) | 0x50000000;
                        }
                        p.note = org.communitycomms.app.geo.Poi.withShape(p.note, sh);
                        p.note = org.communitycomms.app.geo.Poi.withPublic(p.note, toPublic.isChecked());
                        boolean arrowChanged = arrow.isChecked() != (oldPose[2] == 1);
                        if (t < 0) p.note = org.communitycomms.app.geo.Poi.withPose(p.note, rot.getProgress() * 5,
                                50 + size.getProgress() * 25, arrow.isChecked());
                        // Only turned, resized or given an arrow: update everyone's map without a spoken announcement.
                        boolean poseOnly = old != null && (poseTouched[0] || arrowChanged)
                                && org.communitycomms.app.geo.Poi.readable(old.note).equals(note.getText().toString().trim())
                                && old.icon == icon[0] && old.lat == lat && old.lon == lon && t < 0 && li < 0
                                && org.communitycomms.app.geo.Poi.shape(old.note) == null;
                        p.note = org.communitycomms.app.geo.Poi.withQuiet(p.note, poseOnly || !announce.isChecked());
                        RadioService rs = RadioService.instance();
                        boolean sent = rs != null && rs.sendPoi(p);
                        if (!sent) {
                            org.communitycomms.app.geo.PoiStore.get(MapActivity.this).apply(p);
                            android.widget.Toast.makeText(MapActivity.this,
                                    "Saved on this phone. Join a channel and tap Sync POIs to share it.",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static final String[] COLOUR_NAMES = {"Red", "Orange", "Yellow", "Green", "Blue", "Purple", "White", "Black"};
    static final int[] COLOURS = {0xffff5252, 0xffff9800, 0xffffeb3b, 0xff66bb6a, 0xff42a5f5, 0xffab47bc, 0xffffffff, 0xff101418};

    private static int colourIndex(int c, int dflt) {
        for (int i = 0; i < COLOURS.length; i++) if ((COLOURS[i] & 0xffffff) == (c & 0xffffff)) return i;
        return dflt;
    }

    private static double number(android.widget.EditText e, double dflt) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (RuntimeException x) {
            return dflt;
        }
    }

    /** Width/height (m) of a rectangle or oval, or length/bearing of a line, for editing it again. */
    private static double[] shapeSizes(double lat, double lon, org.communitycomms.app.geo.Poi.Shape s) {
        if (s.type == org.communitycomms.app.geo.Poi.CIRCLE) return new double[]{s.radiusM, 0};
        if (s.type == org.communitycomms.app.geo.Poi.LINE || s.type == org.communitycomms.app.geo.Poi.ARROW) {
            return new double[]{Geo.distanceM(lat, lon, s.lat2, s.lon2), Geo.bearing(lat, lon, s.lat2, s.lon2)};
        }
        return new double[]{2 * Geo.distanceM(lat, lon, lat, s.lon2), 2 * Geo.distanceM(lat, lon, s.lat2, lon)};
    }

    /**
     * Rows showing each icon next to its name, the current quick-point profile's icons first.
     * Position -> icon with icon(pos); icon -> position with position(icon).
     */
    static final class IconAdapter extends android.widget.BaseAdapter {
        private final android.content.Context c;
        final int[] order;

        IconAdapter(android.content.Context c) {
            this.c = c;
            order = new org.communitycomms.app.geo.QuickProfiles(c).iconOrder();
        }

        int icon(int pos) {
            return order[pos];
        }

        int position(int icon) {
            for (int i = 0; i < order.length; i++) if (order[i] == icon) return i;
            return 0;
        }

        @Override
        public int getCount() {
            return order.length;
        }

        @Override
        public Object getItem(int i) {
            return org.communitycomms.app.geo.Poi.ICON_NAMES[order[i]];
        }

        @Override
        public long getItemId(int i) {
            return order[i];
        }

        @Override
        public View getView(int pos, View convert, android.view.ViewGroup parent) {
            final int i = order[pos];
            final float d = c.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
            View pic = new View(c) {
                @Override
                protected void onDraw(android.graphics.Canvas cv) {
                    org.communitycomms.app.geo.PoiIcons.draw(cv, i, getWidth() / 2f, getHeight() / 2f, getWidth() * 0.9f);
                }
            };
            row.addView(pic, new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d)));
            TextView t = new TextView(c);
            t.setText("  " + org.communitycomms.app.geo.Poi.ICON_NAMES[i]);
            t.setTextSize(16);
            row.addView(t);
            return row;
        }
    }

    private void showStation(TileMapView.Marker m) {
        if (m.tag instanceof Contacts.Contact) {
            Contacts.Contact c = (Contacts.Contact) m.tag;
            StringBuilder sb = new StringBuilder();
            sb.append(Geo.latLon(c.lat, c.lon)).append("\n±").append(Math.round(c.accuracy)).append(" m, position ")
                    .append(Geo.age(System.currentTimeMillis() - c.fixTimeMs)).append("\nlast seen ")
                    .append(Geo.age(System.currentTimeMillis() - c.lastSeenMs));
            if (me != null) {
                double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), c.lat, c.lon);
                sb.append("\n").append(Geo.compass(brg)).append(' ').append(Math.round(brg)).append("°, ")
                        .append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), c.lat, c.lon)));
            }
            new AlertDialog.Builder(this).setTitle(c.name).setMessage(sb).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        if (!(m.tag instanceof RadioEngine.Station)) return;
        RadioEngine.Station s = (RadioEngine.Station) m.tag;
        StringBuilder sb = new StringBuilder();
        if (s.fake) sb.append("(Fake GPS position)\n");
        sb.append(Geo.latLon(s.lat, s.lon)).append("\n±").append(Math.round(s.accuracy)).append(" m, ")
                .append(Geo.age(System.currentTimeMillis() - s.fixTimeMs));
        if (me != null) {
            double brg = Geo.bearing(me.getLatitude(), me.getLongitude(), s.lat, s.lon);
            sb.append("\n").append(Geo.compass(brg)).append(' ').append(Math.round(brg)).append("°, ")
                    .append(Geo.distance(Geo.distanceM(me.getLatitude(), me.getLongitude(), s.lat, s.lon)));
        }
        new AlertDialog.Builder(this).setTitle(s.name).setMessage(sb).setPositiveButton(android.R.string.ok, null).show();
    }
}
