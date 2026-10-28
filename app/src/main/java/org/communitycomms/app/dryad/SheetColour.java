package org.communitycomms.app.dryad;

import java.security.SecureRandom;

/**
 * Named colours for telling sheets apart at a glance, and by voice ("go to the teal sheet").
 * Chosen to stay distinct from each other on a dark screen.
 */
public final class SheetColour {
    public static final String[] NAMES = {
            "Red", "Orange", "Amber", "Yellow", "Lime", "Green",
            "Teal", "Cyan", "Blue", "Indigo", "Purple", "Pink"};
    private static final int[] ARGB = {
            0xffe53935, 0xfffb8c00, 0xffffb300, 0xfffdd835, 0xffc0ca33, 0xff43a047,
            0xff00897b, 0xff00acc1, 0xff1e88e5, 0xff5c6bc0, 0xff8e24aa, 0xffd81b60};

    private SheetColour() {
    }

    /** ARGB for a colour name; grey for unknown or missing. */
    public static int argb(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equalsIgnoreCase(name)) return ARGB[i];
        }
        return 0xff78909c;
    }

    public static String normalise(String name) {
        for (String n : NAMES) {
            if (n.equalsIgnoreCase(name.trim())) return n;
        }
        return null;
    }

    /** Random colour that differs from the previous one (so neighbours in a batch never match). */
    public static String random(SecureRandom r, String previous) {
        while (true) {
            String c = NAMES[r.nextInt(NAMES.length)];
            if (!c.equals(previous)) return c;
        }
    }
}
