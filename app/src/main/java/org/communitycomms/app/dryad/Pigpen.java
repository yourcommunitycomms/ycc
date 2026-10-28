package org.communitycomms.app.dryad;

/**
 * Keyed pigpen with 28 symbols, as fixed slots:
 * <pre>
 *  0-8   tic-tac-toe grid, no dot (row by row)
 *  9-17  tic-tac-toe grid, with dot
 *  18-21 X arms, no dot (top, right, bottom, left)
 *  22-25 X arms, with dot
 *  26    plain X                (the centre of the first X)
 *  27    X with a dot in each of its four triangles (centre of the second X)
 * </pre>
 * Classic pigpen fills the slots A..Z, '.', '@' in order, so the plain X is a space and the dotted
 * X an end of line. Keyed with a DRYAD row, slot i holds the row's i-th symbol instead.
 * Decoded '.' is shown as a space and '@' as a new line.
 */
public final class Pigpen {
    public static final int SLOTS = 28;
    public static final char[] CLASSIC = DryadSheet.ALPHABET.toCharArray();

    private Pigpen() {
    }

    /** Text character to key symbol: space becomes '.', new line becomes '@'. */
    static char toSymbol(char c) {
        if (c == ' ') return DryadSheet.SPACE;
        if (c == '\n') return DryadSheet.END_OF_LINE;
        return Character.toUpperCase(c);
    }

    /** Key symbol to display character: '.' is a space, '@' a new line. */
    public static char toText(char symbol) {
        if (symbol == DryadSheet.SPACE) return ' ';
        if (symbol == DryadSheet.END_OF_LINE) return '\n';
        return symbol;
    }

    /** Slot for each character of text, or -1 for anything else (digits etc., kept as-is). */
    public static int[] encode(char[] key, String text) {
        int[] out = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = toSymbol(text.charAt(i));
            out[i] = -1;
            for (int s = 0; s < SLOTS; s++) {
                if (key[s] == c) {
                    out[i] = s;
                    break;
                }
            }
        }
        return out;
    }

    public static char decode(char[] key, int slot) {
        return toText(key[slot]);
    }

    // Geometry helpers for drawing.

    public static boolean isGrid(int slot) {
        return slot < 18;
    }

    public static boolean isCentre(int slot) {
        return slot >= 26;
    }

    public static boolean hasDot(int slot) {
        return (slot >= 9 && slot < 18) || (slot >= 22 && slot < 26) || slot == 27;
    }

    /** For grid slots: cell 0..8. For X arms: 0 top, 1 right, 2 bottom, 3 left. */
    public static int cell(int slot) {
        if (slot < 9) return slot;
        if (slot < 18) return slot - 9;
        if (slot < 22) return slot - 18;
        if (slot < 26) return slot - 22;
        return -1;
    }
}
