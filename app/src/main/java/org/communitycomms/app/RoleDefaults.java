package org.communitycomms.app;

import android.content.Context;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * What picking a device role turns on. Only ever turns things on (never unticks what someone chose), and
 * never sets Anchored: that is a power state, not a role. A setup QR code applies the role's defaults and then
 * its own fields, so the code wins; in Settings the list of changes is shown first.
 */
final class RoleDefaults {
    private RoleDefaults() {
    }

    /** "I lead a team": the team tools. */
    static final String[] LEAD_FEATURES = {"array", "report", "tasks", "calendar"};

    static void turnOnLeadFeatures(Prefs p) {
        for (String k : LEAD_FEATURES) p.setMenuOn(k, true);
    }

    /** Advanced and Region features each role starts with. */
    static String[] features(Context c, String role) {
        if (Prefs.ROLE_COMMANDER.equals(role)) {
            boolean lan = new org.communitycomms.app.geo.LanTiles(c).configured();
            return lan ? new String[]{"array", "tasks", "calendar", "bluetti", "cast", "nomad"}
                    : new String[]{"array", "tasks", "calendar", "bluetti", "cast"};
        }
        if (Prefs.ROLE_CAMERA.equals(role)) return new String[]{"bluetti"};
        return new String[0];
    }

    /** The changes picking this role would make here, in words (empty: nothing changes). */
    static List<String> changes(Context c, String role) {
        Prefs p = new Prefs(c);
        List<String> out = new ArrayList<String>();
        for (String k : features(c, role)) {
            if (!p.menuOn(k)) out.add("Turn on " + Prefs.menuLabel(k).replaceAll(" \\(.*", ""));
        }
        if (Prefs.ROLE_COMMANDER.equals(role)) {
            if (!p.recordAllVoice()) out.add("Record all voice");
            if (!p.dualWatch()) out.add("Dual watch (hear other channels while talking on one)");
        }
        if (Prefs.ROLE_INTERCOM.equals(role) && p.appMode() != Prefs.MODE_INTERCOM) out.add("The intercom screen (rooms) as the home screen, recording all voice");
        if (Prefs.ROLE_TV.equals(role) && p.homeStart() != Prefs.HOME_TV) out.add("Open in TV mode when the app starts");
        if (!Prefs.ROLE_INTERCOM.equals(role) && p.appMode() == Prefs.MODE_INTERCOM) out.add("Leave the intercom screen (back to the Advanced group type)");
        if (Prefs.ROLE_CAMERA.equals(role)) out.add("Offer the camera (Reolink) setup");
        if (Prefs.ROLE_GATEWAY.equals(role)) out.add("Offer the Cloud Server or tunnel setup");
        return out;
    }

    /**
     * The roles as a single-choice list in alternating colours (name in bold and gold, then what it is for),
     * so the six are easy to tell apart. Plain views: works on Android 4.1.
     */
    static android.widget.ListAdapter roleList(final Context c) {
        final CharSequence[] rows = new CharSequence[Prefs.ROLE_NAMES.length];
        for (int i = 0; i < rows.length; i++) {
            android.text.SpannableString s = new android.text.SpannableString(Prefs.ROLE_NAMES[i] + "\n" + Prefs.ROLE_NOTES[i]);
            int n = Prefs.ROLE_NAMES[i].length();
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, n, 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(0xfff0c75e), 0, n, 0);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), n, s.length(), 0);
            rows[i] = s;
        }
        return new android.widget.ArrayAdapter<CharSequence>(c, android.R.layout.simple_list_item_single_choice, rows) {
            @Override
            public android.view.View getView(int pos, android.view.View convert, android.view.ViewGroup parent) {
                android.view.View v = super.getView(pos, convert, parent);
                v.setBackgroundColor(pos % 2 == 0 ? 0xff1f3d2b : 0xff2c2c2c);
                int p = (int) (10 * c.getResources().getDisplayMetrics().density);
                v.setPadding(p + p / 2, p, (int) (52 * c.getResources().getDisplayMetrics().density), p); // room for the radio button
                // The stock row has a fixed height (Android 4.1 clips the second line): let it grow.
                v.setLayoutParams(new android.widget.AbsListView.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                v.setMinimumHeight(0);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(0xffffffff);
                    ((android.widget.TextView) v).setSingleLine(false);
                }
                return v;
            }
        };
    }

    /** Settings > Me > Device role: pick one, see what changes, confirm. after runs once it is set. */
    static void choose(final android.app.Activity a, final Runnable after) {
        final Prefs p = new Prefs(a);
        new android.app.AlertDialog.Builder(a).setTitle("What is this device for?")
                .setSingleChoiceItems(roleList(a), p.roleIndex(), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        confirm(a, Prefs.ROLE_KEYS[which], after);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void confirm(final android.app.Activity a, final String role, final Runnable after) {
        final Prefs p = new Prefs(a);
        List<String> ch = changes(a, role);
        final Runnable set = new Runnable() {
            @Override
            public void run() {
                apply(a, role, true);
                p.setRole(role);
                if (after != null) after.run();
                Toast.makeText(a, "Device role: " + p.roleName(), Toast.LENGTH_SHORT).show();
                if (Prefs.ROLE_CAMERA.equals(role) || Prefs.ROLE_GATEWAY.equals(role)) {
                    AdvancedSetup.offerForRole(a, role);
                }
            }
        };
        if (ch.isEmpty()) {
            set.run();
            return;
        }
        StringBuilder sb = new StringBuilder("These will change:\n");
        for (String s : ch) sb.append("\n• ").append(s);
        sb.append("\n\nNothing you turned on is turned off, and Anchored is never set by a role.");
        new android.app.AlertDialog.Builder(a).setTitle(Prefs.ROLE_NAMES[java.util.Arrays.asList(Prefs.ROLE_KEYS).indexOf(role)])
                .setMessage(sb.toString())
                .setPositiveButton("Change", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        set.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Turns on the role's defaults (home: also the home screen it opens into). */
    static void apply(Context c, String role, boolean home) {
        Prefs p = new Prefs(c);
        for (String k : features(c, role)) p.setMenuOn(k, true);
        if (Prefs.ROLE_COMMANDER.equals(role)) {
            p.setRecordAllVoice(true);
            if (!p.dualWatch()) p.setDualWatch(true, p.dualWatchPlay(), p.dualWatchSave());
        }
        if (Prefs.ROLE_INTERCOM.equals(role) && c instanceof android.app.Activity) {
            MainActivity.applyMode((android.app.Activity) c, Prefs.MODE_INTERCOM);
        }
        if (Prefs.ROLE_TV.equals(role)) p.setHomeStart(Prefs.HOME_TV);
        else if (home && p.homeStart() == Prefs.HOME_TV) p.setHomeStart(Prefs.HOME_SCREEN);
    }
}
