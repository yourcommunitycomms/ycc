package org.communitycomms.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;

/**
 * Advanced setup: short guided setups for the things that need more than a tick (Cloud Server, cloud group,
 * tunnel, LAN storage server, cameras, batteries, TAK and OpenMANET, channel array). Each one says what it is
 * and what happens next, then opens the screen or steps that do it. Reached from the list (Settings > Advanced
 * and Region features > Advanced setup), when a feature that needs setting up is ticked, and from the roles.
 */
final class AdvancedSetup {
    private AdvancedSetup() {
    }

    static final String[] KEYS = {"cloudserver", "joincloud", "cloud", "tunnel", "lan", "cameras", "bluetti", "atak", "array"};
    static final String[] NAMES = {"Cloud Server and Relay admin", "Join the Cloud Server", "Cloud group: create or join",
            "Internet tunnel through the LAN bridge", "LAN storage server (NOMAD / Raspberry Pi)", "Cameras (Reolink)",
            "Batteries (power stations)", "TAK and OpenMANET", "Channel array for leaders"};
    private static final String[] TEXTS = {
            "For the person who runs the Cloud Server (the internet relay on a small cloud server).\n\n"
                    + "1. Install it on a small cloud server with the release's install-relay.sh.\n"
                    + "2. Scan its admin QR code (Relay admin > Scan).\n"
                    + "3. Add a site (a MANET's gateway), a remote phone or a cloud group.\n"
                    + "4. Show each one's setup QR code to the device that needs it.",
            "For a device that talks through the Cloud Server: away from the MANET (a remote), or a site's gateway on it.\n\n"
                    + "1. Scan the setup QR code the Cloud Server's admin made for you.\n"
                    + "2. It sets the address, your key and whether you are on or off the MANET.\n"
                    + "3. Test: the status line says when the relay is connected.",
            "A community channel like Public with its own Cloud Server.\n\n"
                    + "Join: Scan QR or Paste a link from someone in the group. Create: needs the Relay admin key.\n"
                    + "Location sharing with public and cloud groups stays off unless you turn it on.",
            "Reach your own MANET from the internet through the bridge on the LAN storage server.\n\n"
                    + "1. On the server's web admin, Bridge tab: show your QR code.\n"
                    + "2. Scan it here (or type the server and your row key).\n"
                    + "3. Turn the tunnel on when away from the MANET.",
            "A LAN storage server (Project NOMAD box or Raspberry Pi) keeps maps, updates, DRYAD sheets and backups for everyone.\n\n"
                    + "1. On the server's web admin, Phone setup tab: show the QR code.\n"
                    + "2. Scan it here: the address, login and network defaults are set.\n"
                    + "3. Choose what to sync (LAN sync and updates).",
            "Reolink cameras on the LAN (no internet needed).\n\n"
                    + "The camera wizard finds the cameras, signs in and picks the lenses; then choose which alerts "
                    + "(motion, person, vehicle, offline) go to the channel.",
            "Read-only battery monitoring: Bluetti over Bluetooth, Tesla Powerwall 2, Anker SOLIX and EcoFlow.\n\n"
                    + "Add a battery, then choose whether its level and alerts go to the channel.",
            "TAK compatible: positions and Public chat that TAK and WinTAK show (NOT encrypted).\n"
                    + "OpenMANET: the OpenMANET / TAK Vx talk group and the node menus (not encrypted).\n\n"
                    + "Both are off by default. Turn them on only if your group uses them.",
            "For team leaders: teams AA to ZZ, each with its own key, printed cards and team setup QR codes.\n\n"
                    + "Fill in the teams, then print the cards or show each team's QR code."};

    /** Settings: the whole list. */
    static void showList(final Activity a) {
        new AlertDialog.Builder(a).setTitle("Advanced setup")
                .setItems(NAMES, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        intro(a, KEYS[which]);
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    /** A feature that needs setting up was just ticked: offer its setup (null when it needs none). */
    static void offerForFeature(Activity a, String feature) {
        String key = "relayadmin".equals(feature) ? "cloudserver" : "cloud".equals(feature) ? "cloud"
                : "bluetti".equals(feature) ? "bluetti" : "array".equals(feature) ? "array" : null;
        if (key != null) intro(a, key);
    }

    /** After picking the camera monitor or gateway role. */
    static void offerForRole(final Activity a, String role) {
        if (Prefs.ROLE_CAMERA.equals(role)) {
            intro(a, "cameras");
            return;
        }
        final String[] keys = {"joincloud", "tunnel", "cloudserver"};
        String[] names = {NAMES[1], NAMES[3], NAMES[0]};
        new AlertDialog.Builder(a).setTitle("Network gateway: set up now?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        intro(a, keys[which]);
                    }
                })
                .setNegativeButton("Later", null).show();
    }

    static void intro(final Activity a, final String key) {
        final int i = java.util.Arrays.asList(KEYS).indexOf(key);
        if (i < 0) return;
        new AlertDialog.Builder(a).setTitle(NAMES[i]).setMessage(TEXTS[i])
                .setPositiveButton(startLabel(key), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        start(a, key);
                    }
                })
                .setNegativeButton("Not now", null).show();
    }

    private static String startLabel(String key) {
        if ("joincloud".equals(key) || "lan".equals(key)) return "Scan the QR code";
        if ("atak".equals(key)) return "Choose";
        return "Start";
    }

    private static void start(final Activity a, String key) {
        Prefs p = new Prefs(a);
        if ("cloudserver".equals(key)) {
            p.setMenuOn("relayadmin", true);
            a.startActivity(new Intent(a, RelayAdminActivity.class));
        } else if ("joincloud".equals(key) || "lan".equals(key)) {
            a.startActivity(new Intent(a, QrScanActivity.class).putExtra(QrScanActivity.EXTRA_SETUP_ONLY, true));
        } else if ("cloud".equals(key)) {
            p.setMenuOn("cloud", true);
            a.startActivity(new Intent(a, CloudGroupActivity.class));
        } else if ("tunnel".equals(key)) {
            TunnelSetup.client(a, null);
        } else if ("cameras".equals(key)) {
            a.startActivity(new Intent(a, ReolinkWizardActivity.class));
        } else if ("bluetti".equals(key)) {
            p.setMenuOn("bluetti", true);
            a.startActivity(new Intent(a, BatteryActivity.class));
        } else if ("array".equals(key)) {
            p.setMenuOn("array", true);
            a.startActivity(new Intent(a, ChannelArrayActivity.class));
        } else if ("atak".equals(key)) {
            atak(a);
        }
    }

    /** TAK and OpenMANET: two ticks; TAK's own warning is in Settings > Network, so it opens there. */
    private static void atak(final Activity a) {
        final Prefs p = new Prefs(a);
        final boolean[] on = {p.menuOn("openmanet"), false};
        new AlertDialog.Builder(a).setTitle("TAK and OpenMANET")
                .setMultiChoiceItems(new String[]{"OpenMANET / TAK Vx talk group and node menus",
                        "TAK compatible positions and chat (opens Settings > Network)"}, on.clone(), new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean checked) {
                        on[which] = checked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        p.setMenuOn("openmanet", on[0]);
                        if (on[1]) SettingsActivity.open(a, "network");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
