package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

import org.communitycomms.app.geo.LanTiles;

import org.json.JSONObject;

/**
 * Phone setup QR code from the LAN storage's admin page (Phone setup tab):
 * "YCCSETUP1|" + JSON {"name","url","user","pass","tunnel","tkey","vps","vkey","rmode","mrange","mrole","pub","chA","omg","omh","home","defaults"}.
 * "mrole" (on / off / auto: gateway on the MANET or remote off it) comes from the Relay admin screen.
 * A code for a remote person (the bridge tab) may hold only the tunnel, with no "url".
 * Asks, checks the storage login works, saves it, loads the network defaults and DRYAD sheets from it
 * (when "defaults" is set), then sets the tunnel server, Public key and channel A.
 */
public final class SetupQr {
    public static final String PREFIX = "YCCSETUP1|";

    private SetupQr() {
    }

    public static boolean is(String text) {
        return text != null && text.startsWith(PREFIX);
    }

    /** Options for a setup code made on this phone (Settings > Phone setup QR code). */
    static final class Extras {
        boolean channelA;
        String role = "", group = "", call = "";
        boolean lead, nameLock, anchored, features = true;
    }

    /** The same code as the admin page makes, from this phone's own settings, plus the new fields. */
    public static String make(android.content.Context c, boolean withChannelA) {
        Extras x = new Extras();
        x.channelA = withChannelA;
        return make(c, x);
    }

    static String make(android.content.Context c, Extras x) {
        android.content.SharedPreferences sp = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        LanTiles lan = new LanTiles(c);
        Prefs p = new Prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("name", "from " + sp.getString("callsign", "a phone"));
            if (lan.configured()) { // a LAN storage server is optional now
                o.put("url", lan.base());
                put(o, "user", lan.user());
                put(o, "pass", sp.getString("lanPass", ""));
                o.put("defaults", 1);
            }
            put(o, "tunnel", p.tunnelServer());
            put(o, "tkey", p.tunnelKey());
            put(o, "vps", p.vpsServer()); // internet relay (Cloud Server): address, key, mode, MANET range
            put(o, "vkey", p.vpsKey());
            if (p.vpsServer().length() > 0) {
                put(o, "rmode", p.relayMode());
                put(o, "mrange", p.manetRange());
            }
            if (!Prefs.PUBLIC_CHANNEL.equals(p.publicKey())) put(o, "pub", p.publicKey());
            if (x.channelA) put(o, "chA", p.channel(Prefs.SLOT_A));
            if (p.showOpenManet()) {
                put(o, "omg", p.openManetGroup());
                put(o, "omh", sp.getString("openmanetHost", ""));
            }
            if (sp.getBoolean("homeNetOn", false)) put(o, "home", sp.getString("homeNetSsid", ""));
            if (x.features) {
                put(o, "menus", p.menusOn()); // older apps read "menus"
                put(o, "features", p.menusOn()); // Advanced and Region features
            }
            o.put("burst", p.burstVoice() ? 1 : 0); // the group's voice mode
            put(o, "role", x.role);
            put(o, "group", x.group);
            if (x.lead) o.put("lead", 1);
            if (x.call != null && x.call.trim().length() > 0) {
                o.put("call", x.call.trim());
                if (x.nameLock) o.put("nameLock", true);
            }
            if (x.anchored) o.put("anchored", 1);
            if (MeshTest.app != null && RoLogin.held() && x.features) { // the read-only node login (it can only read)
                o.put("ro", new JSONObject().put("u", RoLogin.user()).put("p", RoLogin.pass()));
            }
            int dm = DataControl.mode(c); // the group's data preset (and its lock, if this phone has one)
            if (dm != DataControl.USER) {
                o.put("data", DataControl.KEYS[dm]);
                if (DataControl.locked(c)) o.put("dataLock", 1);
            }
        } catch (org.json.JSONException e) {
            return null;
        }
        return PREFIX + o.toString();
    }
    /**
     * Relay admin screen: a setup code for one internet relay user (relay address, that user's key,
     * relay mode, and the role: "on" the MANET for a site's gateway, "off" for a remote phone).
     */
    public static String makeRelayUser(String name, String relay, String key, String role) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name + " (internet relay)");
            o.put("vps", relay.trim());
            o.put("vkey", key.trim());
            o.put("rmode", "vps");
            o.put("mrole", role);
        } catch (org.json.JSONException e) {
            return null;
        }
        return PREFIX + o.toString();
    }

    private static void put(JSONObject o, String k, String v) throws org.json.JSONException {
        if (v != null && v.trim().length() > 0) o.put(k, v.trim());
    }

    /**
     * Settings > Phone setup QR code: what the new device gets (channel A, its role, group type, a callsign that
     * can be locked, Anchored, this phone's Advanced and Region features), then the code full screen.
     */
    public static void show(final Activity a) {
        final Prefs p = new Prefs(a);
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);
        android.widget.TextView intro = new android.widget.TextView(a);
        intro.setText("New devices scan this in the setup wizard (or Settings > Scan QR). It is applied straight away. "
                + (new LanTiles(a).configured() ? "It holds the LAN storage password: only show it to your own group." : ""));
        box.addView(intro);
        final android.widget.CheckBox chA = new android.widget.CheckBox(a);
        chA.setText("Include channel A (" + p.channel(Prefs.SLOT_A) + ")");
        if (p.channel(Prefs.SLOT_A).length() > 0) box.addView(chA);
        final String[] roleNames = new String[Prefs.ROLE_NAMES.length + 1];
        roleNames[0] = "Role: leave it to the device";
        for (int i = 0; i < Prefs.ROLE_NAMES.length; i++) roleNames[i + 1] = "Role: " + Prefs.ROLE_NAMES[i];
        final android.widget.Spinner role = spinner(a, roleNames);
        box.addView(role);
        final String[] groupNames = new String[Prefs.MODE_NAMES.length + 1];
        groupNames[0] = "Group type: leave it to the device";
        for (int i = 0; i < Prefs.MODE_NAMES.length; i++) groupNames[i + 1] = "Group type: " + Prefs.MODE_NAMES[i];
        final android.widget.Spinner group = spinner(a, groupNames);
        box.addView(group);
        final android.widget.CheckBox lead = new android.widget.CheckBox(a);
        lead.setText("I lead a team (team tools ticked)");
        box.addView(lead);
        final android.widget.EditText call = new android.widget.EditText(a);
        call.setSingleLine(true);
        call.setHint("Callsign for the new device (optional)");
        call.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(call);
        final android.widget.CheckBox lock = new android.widget.CheckBox(a);
        lock.setText("Lock the callsign (they can't change it; a new setup QR can)");
        box.addView(lock);
        final android.widget.CheckBox anch = new android.widget.CheckBox(a);
        anch.setText("Anchored (the new device is on stable power)");
        box.addView(anch);
        final android.widget.CheckBox feats = new android.widget.CheckBox(a);
        String on = p.menusOn();
        feats.setText("Advanced and Region features from this phone" + (on.length() > 0 ? " (" + on.replace(",", ", ") + ")" : " (none on)"));
        feats.setChecked(true);
        box.addView(feats);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Phone setup QR code")
                .setView(sv)
                .setPositiveButton("Show the code", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        Extras x = new Extras();
                        x.channelA = chA.isChecked();
                        int r = role.getSelectedItemPosition();
                        x.role = r > 0 ? Prefs.ROLE_KEYS[r - 1] : "";
                        int g = group.getSelectedItemPosition();
                        x.group = g > 0 ? Prefs.GROUP_KEYS[g - 1] : "";
                        x.lead = lead.isChecked();
                        x.call = call.getText().toString();
                        x.nameLock = lock.isChecked();
                        x.anchored = anch.isChecked();
                        x.features = feats.isChecked();
                        java.util.ArrayList<String> payloads = new java.util.ArrayList<String>();
                        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
                        payloads.add(make(a, x));
                        titles.add("Phone setup" + (x.call.trim().length() > 0 ? " for " + x.call.trim().toUpperCase() : ""));
                        QrShowActivity.start(a, payloads, titles, null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static android.widget.Spinner spinner(Activity a, String[] items) {
        android.widget.Spinner s = new android.widget.Spinner(a);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        return s;
    }

    /**
     * A scanned setup code is applied straight away (the owner's choice: the code can set everything), then a
     * summary says what it set. Only a LAN storage that can't be reached asks first. onCancel runs if the code
     * is damaged or empty (the scanner carries on).
     */
    public static void offer(final Activity a, String text, final Runnable onCancel, final Runnable onDone) {
        final JSONObject o;
        try {
            o = new JSONObject(text.substring(PREFIX.length()));
        } catch (Exception e) {
            Toast.makeText(a, "That setup QR code is damaged", Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }
        if (o.has("team")) {
            offerTeam(a, o, onCancel, onDone);
            return;
        }
        final String url = o.optString("url").trim();
        final boolean hasLan = url.startsWith("http://") || url.startsWith("https://");
        boolean any = hasLan;
        for (String k : new String[]{"tunnel", "vps", "pub", "chA", "omg", "home", "menus", "features", "role", "group", "call", "anchored", "lead", "burst", "prof", "cloud", "update", "autoLoc", "obscure", "shareLocPub", "mapsOnline", "fakeGps", "data"}) {
            if (o.has(k) && o.optString(k).length() > 0) any = true;
        }
        if (!any) {
            Toast.makeText(a, "That setup QR code has nothing to set", Toast.LENGTH_LONG).show();
            if (onCancel != null) onCancel.run();
            return;
        }
        if (hasLan && o.has("pass")) check(a, o, url, onCancel, onDone);
        else apply(a, o, hasLan ? url : "", onDone); // no password in the code (a site APK): set the address, ask later
    }

    /** What a setup code set, for the summary after applying it. */
    static String describe(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        String url = o.optString("url");
        if (url.length() > 0) sb.append("LAN storage: ").append(url).append('\n');
        if (o.optString("tunnel").length() > 0) sb.append("Tunnel server: ").append(o.optString("tunnel")).append('\n');
        if (o.optString("vps").length() > 0) {
            sb.append("Cloud Server: ").append(o.optString("vps"));
            String mr = o.optString("mrole");
            if (mr.equals("on")) sb.append(" (on the MANET: a gateway for the site)");
            else if (mr.equals("off")) sb.append(" (off the MANET: a remote)");
            sb.append('\n');
        }
        int r = java.util.Arrays.asList(Prefs.ROLE_KEYS).indexOf(o.optString("role"));
        if (r >= 0) sb.append("Role: ").append(Prefs.ROLE_NAMES[r]).append('\n');
        int g = java.util.Arrays.asList(Prefs.GROUP_KEYS).indexOf(o.optString("group"));
        if (g >= 0) sb.append("Group type: ").append(Prefs.MODE_NAMES[g]).append('\n');
        if (o.optInt("lead") == 1) sb.append("Team leader tools on\n");
        if (o.optString("call").length() > 0) sb.append("Callsign: ").append(o.optString("call").toUpperCase())
                .append(o.optBoolean("nameLock") ? " (locked)" : "").append('\n');
        if (o.optInt("anchored") == 1) sb.append("Anchored (stable power)\n");
        if (o.optString("pub").length() > 0) sb.append("Public channel key\n");
        if (o.optString("chA").length() > 0) sb.append("Channel A\n");
        if (o.optString("omg").length() > 0) sb.append("OpenMANET group ").append(o.optString("omg")).append('\n');
        if (o.optString("home").length() > 0) sb.append("Home WiFi: ").append(o.optString("home")).append('\n');
        String f = o.optString("features").length() > 0 ? o.optString("features") : o.optString("menus");
        if (f.length() > 0) sb.append("Advanced and Region features: ").append(f.replace(",", ", ")).append('\n');
        if (o.optJSONObject("ro") != null) sb.append("Read-only mesh node login: ").append(o.optJSONObject("ro").optString("u", RoLogin.DEFAULT_USER)).append('\n');
        if (o.optString("mtRole").length() > 0) sb.append("Mesh testing: ").append(o.optString("mtRole")).append('\n');
        if (o.has("burst")) sb.append("Voice: ").append(o.optInt("burst") == 1 ? "burst mode" : "live").append('\n');
        if (o.optString("prof").length() > 0) sb.append("Profile: ").append(o.optString("prof")).append('\n');
        if (o.optJSONObject("cloud") != null) sb.append("Cloud group: ").append(o.optJSONObject("cloud").optString("n")).append('\n');
        if (o.optString("update").length() > 0) sb.append("Its own update address\n");
        if (o.has("autoLoc")) sb.append("Share location when asked: ").append(o.optInt("autoLoc") == 1 ? "automatically" : "ask each time").append('\n');
        if (o.has("obscure")) sb.append("Obscure position: ").append(o.optInt("obscure") == 0 ? "exact" : org.communitycomms.app.geo.Geo.range(o.optInt("obscure"))).append('\n');
        if (o.has("shareLocPub")) sb.append("Location on Public / cloud: ").append(o.optInt("shareLocPub") == 1 ? "on" : "off").append('\n');
        if (o.has("fakeGps")) sb.append("Fake GPS: ").append(o.optInt("fakeGps") == 1 ? "on, near " + o.optString("fakeCentre") : "off").append('\n');
        if (o.has("mapsOnline")) sb.append("Online map tiles: ").append(o.optInt("mapsOnline") == 1 ? "always" : "ask first").append('\n');
        int dm = java.util.Arrays.asList(DataControl.KEYS).indexOf(o.optString("data"));
        if (dm >= 0) sb.append("Data: ").append(DataControl.NAMES[dm]).append(o.optInt("dataLock") == 1 ? " (locked)" : "").append('\n');
        return sb.toString().trim();
    }
    /** A team code from the Channel array: every set of settings can be left out. */
    private static void offerTeam(final Activity a, final JSONObject o, final Runnable onCancel, final Runnable onDone) {
        final String[][] sets = {{"chA", "Team channel (A)"}, {"pub", "Public channel"}, {"tunnel", "Remote relay: " + o.optString("tunnel")},
                {"url", "Local cache server: " + o.optString("url")}, {"prof", "Profile: " + o.optString("prof")},
                {"area", "Map area to download: " + o.optString("area")}, {"code", "Approved code for reports"}};
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final android.widget.CheckBox[] ticks = new android.widget.CheckBox[sets.length];
        for (int i = 0; i < sets.length; i++) {
            ticks[i] = new android.widget.CheckBox(a);
            ticks[i].setText(sets[i][1]);
            ticks[i].setChecked(true);
            if (o.has(sets[i][0])) box.addView(ticks[i]);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("Set up for team " + o.optString("team")).setView(sv).setCancelable(false)
                .setPositiveButton("Set up", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        for (int i = 0; i < sets.length; i++) {
                            if (!ticks[i].isChecked()) o.remove(sets[i][0]);
                        }
                        if (!o.has("tunnel")) o.remove("tkey");
                        String url = o.optString("url").trim();
                        if (url.startsWith("http://") || url.startsWith("https://")) check(a, o, url, onCancel, onDone);
                        else apply(a, o, "", onDone);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (onCancel != null) onCancel.run();
                    }
                }).show();
    }

    private static void check(final Activity a, final JSONObject o, final String url, final Runnable onCancel,
                              final Runnable onDone) {
        Toast.makeText(a, "Checking the LAN storage...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = new LanTiles(a, url, o.optString("user"), o.optString("pass")).test();
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (result.startsWith("OK") || result.startsWith("Reached")) {
                            apply(a, o, url, onDone);
                            return;
                        }
                        new AlertDialog.Builder(a)
                                .setTitle("Could not use the LAN storage")
                                .setMessage(result + "\n\nCheck the phone is on the right WiFi or MANET, then scan again.")
                                .setPositiveButton("Set up anyway", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        apply(a, o, url, onDone);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        if (onCancel != null) onCancel.run();
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    private static void applyTeam(Activity a, JSONObject o, Prefs p) {
        if (!o.has("team")) return;
        p.setTeamName(o.optString("team"));
        if (o.has("code")) p.setReportCode(o.optString("code"));
        // A team phone gets the team menus: tasks, and reports when it has an approved code.
        p.setMenuOn("tasks", true);
        if (o.has("code")) p.setMenuOn("report", true);
        if (o.optString("menus").length() > 0) p.turnOnMenus(o.optString("menus"));
        if (o.has("burst")) p.setBurstVoice(o.optInt("burst") == 1);
        if (o.has("chA") && o.has("pub")) { // talk on the team channel, hear Public
            p.setDualWatch(true, true, false);
            p.setListening(Prefs.SLOT_PUBLIC, true);
            p.setActiveSlot(Prefs.SLOT_A);
        }
        if (o.has("prof")) {
            int at = java.util.Arrays.asList(org.communitycomms.app.geo.QuickProfiles.NAMES).indexOf(o.optString("prof"));
            if (at >= 0) new org.communitycomms.app.geo.QuickProfiles(a).setProfile(at);
            else Toast.makeText(a, "Profile " + o.optString("prof") + " is not known here, so it was left as it is", Toast.LENGTH_LONG).show();
        }
        double[] area = ChannelArray.area(o.optString("area"));
        if (area != null) {
            TilePrefetchService.start(a, area[0], area[1], (int) area[2], "0", org.communitycomms.app.geo.TilePrefetch.upTo(14), area[2] > 20);
        }
    }

    /** The fields added with roles: role, group type, team lead, callsign (and its lock), Anchored, features. */
    static void applyNewFields(Activity a, JSONObject o, Prefs p) {
        if (o.optString("features").length() > 0) p.turnOnMenus(o.optString("features"));
        String role = o.optString("role");
        if (java.util.Arrays.asList(Prefs.ROLE_KEYS).contains(role)) {
            RoleDefaults.apply(a, role, false); // the role's defaults first; what the code says below wins
            p.setRole(role);
        }
        int g = java.util.Arrays.asList(Prefs.GROUP_KEYS).indexOf(o.optString("group"));
        if (g >= 0 && !Prefs.ROLE_INTERCOM.equals(p.role())) {
            if (g == Prefs.MODE_INTERCOM) p.setRole(Prefs.ROLE_INTERCOM);
            MainActivity.applyMode(a, g);
        }
        if (o.has("lead")) {
            p.setLead(o.optInt("lead") == 1);
            if (p.lead()) RoleDefaults.turnOnLeadFeatures(p);
        }
        String call = o.optString("call").trim();
        a.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("qrSetCall", call.length() > 0).commit();
        if (call.length() > 0) {
            p.setCallsign(call.toUpperCase(java.util.Locale.US));
            p.setNameLock(o.optBoolean("nameLock", false));
            RadioService rs = RadioService.instance();
            RadioEngine en = rs == null ? null : rs.engine();
            if (en != null) { // heard as the new name straight away
                en.setCallsign(p.callsign());
                for (RadioEngine m : en.monitors()) m.setCallsign(p.callsign());
            }
        }
        if (o.optInt("anchored") == 1) PowerMode.setAnchor(a, true);
        int prof = java.util.Arrays.asList(org.communitycomms.app.geo.QuickProfiles.NAMES).indexOf(o.optString("prof"));
        if (prof >= 0 && !o.has("team")) new org.communitycomms.app.geo.QuickProfiles(a).setProfile(prof);
        applyCloud(p, o.optJSONObject("cloud"));
        applyFakeGps(a, o);
        // The wizard's location and maps choices (from the server's Phone setup page)
        if (o.has("autoLoc")) a.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("autoAcceptLocation", o.optInt("autoLoc") == 1).commit();
        if (o.has("obscure")) org.communitycomms.app.geo.FakeGps.setObscureM(a, o.optInt("obscure", org.communitycomms.app.geo.FakeGps.OBSCURE_DEFAULT_M));
        if (o.has("shareLocPub")) p.setShareLocationPublic(o.optInt("shareLocPub") == 1);
        if (o.has("mapsOnline")) new org.communitycomms.app.geo.MapPrefs(a).setOnlineEnabled(o.optInt("mapsOnline") == 1);
        JSONObject ro = o.optJSONObject("ro"); // the read-only node login (server Phone setup, a site APK or a phone's QR)
        RoLogin.applySetup(a, ro);
        String mt = o.optString("mtRole"); // Mesh testing: this phone's job ("stationary" / "roaming")
        if (("stationary".equals(mt) || "roaming".equals(mt)) && MeshTest.app != null) MeshTest.sp().edit().putString("mt_role", mt).commit();
        DataControl.apply(a, o); // "data" preset and "dataLock" (after mapsOnline, so the preset wins)
        String up = o.optString("update").trim(); // a personal APK's own update address
        if (up.startsWith("http://") || up.startsWith("https://")) {
            a.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putString("updateUrl", up).commit();
        }
    }

    /** Fake GPS from a setup code or an APK stamp: "fakeGps" 1 / 0 and "fakeCentre" "lat, lon". */
    static void applyFakeGps(android.content.Context c, JSONObject o) {
        if (!o.has("fakeGps")) return;
        boolean on = o.optInt("fakeGps") == 1;
        String centre = o.optString("fakeCentre", org.communitycomms.app.geo.FakeGps.centre(c));
        if (!org.communitycomms.app.geo.FakeGps.set(c, on, centre) && on) {
            android.util.Log.w("SetupQr", "Fake GPS not turned on: bad coordinates");
        }
    }

    /** A cloud group in a setup code or an APK stamp: {"n","k","r","rk"} as in its share link. */
    static void applyCloud(Prefs p, JSONObject c) {
        if (c == null || c.optString("k").trim().length() == 0) return;
        p.setCloud(c.optString("n"), c.optString("k"), c.optString("r"), c.optString("rk"));
        p.setMenuOn("cloud", true);
        p.setShown(Prefs.SLOT_CLOUD, true);
    }

    private static void apply(final Activity a, final JSONObject o, String url, final Runnable onDone) {
        final LanTiles lan = new LanTiles(a);
        // Without a user or password in the code, keep the ones this phone already has.
        if (url.length() > 0) {
            lan.save(url, o.has("user") ? o.optString("user") : lan.user(), o.has("pass") ? o.optString("pass") : null,
                    lan.shareWithManet());
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg = "Phone set up";
                if (o.optInt("defaults") == 1) {
                    byte[] b = lan.getPath(ConfigShare.NETWORK_DEFAULTS);
                    try {
                        JSONObject net = b == null ? null : new JSONObject(new String(b, "UTF-8"));
                        if (net != null && "UCCONFIG1".equals(net.optString("format"))) {
                            ConfigShare.apply(a, net);
                            msg = "Phone set up with the network defaults";
                        } else {
                            msg = "Phone set up (the storage has no network defaults yet)";
                        }
                    } catch (Exception e) {
                        msg = "Phone set up, but the network defaults could not be loaded";
                    }
                }
                final String done = msg;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // After the defaults, which would otherwise clear the channel and Public key.
                        Prefs p = new Prefs(a);
                        if (o.optString("tunnel").length() > 0) p.setTunnel(o.optString("tunnel"), o.optString("tkey"));
                        if (o.optString("vps").length() > 0) {
                            String rm = o.optString("rmode", "vps");
                            p.setVps("direct".equals(rm) || "both".equals(rm) ? rm : "vps", o.optString("vps"), o.optString("vkey"));
                            String mr = o.optString("mrole"); // from the Relay admin screen: gateway or remote
                            p.setManet(mr.equals("on") || mr.equals("off") || mr.equals("auto") ? mr : p.manetRole(),
                                    o.optString("mrange", Prefs.DEFAULT_MANET_RANGE));
                            RadioService rs = RadioService.instance();
                            if (rs != null) rs.applyVpsNow(); // on air: connect with the new relay settings now
                        }
                        if (o.optString("pub").length() > 0) p.setPublicKey(o.optString("pub"));
                        if (o.optString("chA").length() > 0) p.setChannel(Prefs.SLOT_A, o.optString("chA"));
                        android.content.SharedPreferences.Editor ed =
                                a.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit();
                        if (o.optString("omg").length() > 0) {
                            p.setOpenManetGroup(o.optString("omg"));
                            ed.putBoolean("showOpenManet", true);
                            if (o.optString("omh").length() > 0) ed.putString("openmanetHost", o.optString("omh"));
                        }
                        if (o.optString("home").length() > 0) {
                            ed.putBoolean("homeNetOn", true).putString("homeNetSsid", o.optString("home"));
                        }
                        ed.commit();
                        if (o.optString("omg").length() > 0) p.burstOffFor("OpenMANET");
                        if (o.optString("menus").length() > 0) p.turnOnMenus(o.optString("menus"));
                        if (o.has("burst")) p.setBurstVoice(o.optInt("burst") == 1);
                        applyNewFields(a, o, p);
                        applyTeam(a, o, p);
                        p.setSetupDone();
                        String what = describe(o);
                        if (o.optString("url").length() > 0 && !o.has("pass")) {
                            what += "\n\nThe LAN storage password was not included: enter it in Settings > Data and backups > LAN storage.";
                        }
                        new AlertDialog.Builder(a).setTitle(done)
                                .setMessage((what.length() > 0 ? "Set:\n" + what : "")
                                        + (o.optString("tunnel").length() > 0 ? "\n\nTurn the tunnel on in Settings when away from the MANET." : ""))
                                .setCancelable(false)
                                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface di, int w) {
                                        if (onDone != null) onDone.run();
                                    }
                                }).show();
                    }
                });
            }
        }).start();
    }
}
