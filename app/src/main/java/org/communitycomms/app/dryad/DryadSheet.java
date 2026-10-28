package org.communitycomms.app.dryad;

import org.communitycomms.app.crypto.ChannelCrypto;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A DRYAD-style sheet extended to include Z: 26 rows (A-Z), each a shuffled alphabet split into
 * ten columns headed 0-9.
 *
 * Current sheets shuffle 28 symbols per row: A-Z plus '.' (space) and '@' (end of line), so a
 * pigpen message keyed from a row also hides where its word and line breaks are. Older sheets
 * have 26 letters per row and still load; their pigpen key gets ".@" appended.
 *
 * Pure Java so it can be shared with the Windows app.
 */
public final class DryadSheet {
    public static final int ROWS = 26;
    /** Rows on a huge sheet: AA to ZZ. */
    public static final int BIG_ROWS = 676;
    /** Symbols per row on new sheets. */
    public static final int LETTERS = 28;
    public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ.@";
    /** Default special symbols, and the canonical ones used inside pigpen keys. */
    public static final char SPACE = '.', END_OF_LINE = '@';
    /** Symbols a Modern DRYAD sheet may use for space and end of line ('|' is reserved for QR). */
    public static final String SPECIAL_CHOICES = ".@,;:()[]/-?!#*+=_&%";
    /** Column sizes for 28-symbol rows. */
    public static final int[] COLUMN_SIZES = {4, 3, 3, 3, 2, 3, 2, 3, 3, 2};
    /** Column sizes on the older 26-letter sheets. */
    public static final int[] COLUMN_SIZES_26 = {4, 3, 3, 2, 2, 3, 2, 3, 2, 2};
    private static final String HEADER = "COMMUNITY-COMMS-DRYAD v1";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public String name;
    /** Validity window in epoch ms; 0 means open-ended. */
    public long validFrom;
    public long validTo;
    /** Set when the user marks the sheet used up. */
    public boolean used;
    /** One of SheetColour.NAMES, or null for sheets made before colours existed. */
    public String colour;
    /** rows[r][i]: symbol at position i of row r (labels: rowLabel(r)). */
    private final char[][] rows;
    /** Order the rows are printed in (row indexes), or null for A to Z. Randomised order is optional. */
    public int[] order;
    /** 28 for Modern DRYAD sheets, 26 for classic sheets made before the special symbols. */
    public final int width;
    private final int[] sizes;
    /** The sheet's own symbols for space and end of line (Modern DRYAD only). */
    public final char spaceSymbol, eolSymbol;

    private DryadSheet(String name, char[][] rows, char spaceSymbol, char eolSymbol) {
        this.name = name;
        this.rows = rows;
        this.width = rows[0].length;
        this.sizes = width == 26 ? COLUMN_SIZES_26 : COLUMN_SIZES;
        this.spaceSymbol = spaceSymbol;
        this.eolSymbol = eolSymbol;
    }

    public boolean isModern() {
        return width == LETTERS;
    }

    /** e.g. "Modern DRYAD (. = space, @ = new line)" or "Classic DRYAD". */
    public String formatName() {
        return isModern() ? "Modern DRYAD (" + spaceSymbol + " = space, " + eolSymbol + " = new line)" : "Classic DRYAD";
    }

    public static boolean validSpecials(char space, char eol) {
        return space != eol && SPECIAL_CHOICES.indexOf(space) >= 0 && SPECIAL_CHOICES.indexOf(eol) >= 0;
    }

    public static final int UPCOMING = 0, ACTIVE = 1, EXPIRED = 2, USED = 3;

    public int status(long now) {
        if (used) return USED;
        if (validTo != 0 && now >= validTo) return EXPIRED;
        if (validFrom != 0 && now < validFrom) return UPCOMING;
        return ACTIVE;
    }

    public static DryadSheet random(String name) {
        return random(name, SPACE, END_OF_LINE);
    }

    public static DryadSheet random(String name, char space, char eol) {
        return random(name, space, eol, ROWS, false);
    }

    /** rowCount 26 (A-Z) or 676 (AA-ZZ); shuffleOrder prints the rows in a random order. */
    public static DryadSheet random(String name, char space, char eol, int rowCount, boolean shuffleOrder) {
        final SecureRandom r = new SecureRandom();
        Rng rng = new Rng() {
            @Override
            public int below(int n) {
                return r.nextInt(n);
            }
        };
        DryadSheet s = generate(name, rng, space, eol, rowCount);
        if (shuffleOrder) {
            int[] o = new int[rowCount];
            for (int i = 0; i < rowCount; i++) o[i] = i;
            for (int i = rowCount - 1; i > 0; i--) {
                int j = rng.below(i + 1);
                int t = o[i];
                o[i] = o[j];
                o[j] = t;
            }
            s.order = o;
        }
        return s;
    }

    /** A new random 5-letter identifier for a sheet (put at the start of its name). */
    public static String newId() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append((char) ('A' + r.nextInt(26)));
        return sb.toString();
    }

    /**
     * The sheet's 5-letter identity: the first word of its name when that is 5 capital letters
     * (new sheets), otherwise 5 letters worked out from the sheet itself (same on every phone).
     */
    public String id() {
        if (name != null && name.length() >= 5) {
            String w = name.substring(0, 5);
            if (w.matches("[A-Z]{5}") && (name.length() == 5 || !Character.isLetter(name.charAt(5)))) return w;
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(fingerprint().getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append((char) ('A' + (h[i] & 0xff) % 26));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            return "?????";
        }
    }

    public int rowCount() {
        return rows.length;
    }

    /** "A".."Z" on normal sheets, "AA".."ZZ" on huge ones. */
    public String rowLabel(int r) {
        if (rows.length == ROWS) return String.valueOf((char) ('A' + r));
        return "" + (char) ('A' + r / 26) + (char) ('A' + r % 26);
    }

    /** Row index for a label ("C" or "CK"), or -1. */
    public int rowIndex(String label) {
        String l = label.trim().toUpperCase(java.util.Locale.US);
        if (rows.length == ROWS && l.length() == 1 && l.charAt(0) >= 'A' && l.charAt(0) <= 'Z') return l.charAt(0) - 'A';
        if (rows.length == BIG_ROWS && l.length() == 2 && l.charAt(0) >= 'A' && l.charAt(0) <= 'Z'
                && l.charAt(1) >= 'A' && l.charAt(1) <= 'Z') return (l.charAt(0) - 'A') * 26 + (l.charAt(1) - 'A');
        return -1;
    }

    public char[] rowAt(int r) {
        return rows[r].clone();
    }

    /** Row indexes in printed order. */
    public int[] printOrder() {
        if (order != null && order.length == rows.length) return order;
        int[] o = new int[rows.length];
        for (int i = 0; i < o.length; i++) o[i] = i;
        return o;
    }

    /**
     * Deterministic sheet from a seed phrase (emergency use only): everyone who types the same
     * phrase gets the same sheet.
     */
    public static DryadSheet fromSeed(String name, String seed) throws GeneralSecurityException {
        return fromSeed(name, seed, SPACE, END_OF_LINE);
    }

    public static DryadSheet fromSeed(String name, String seed, char space, char eol) throws GeneralSecurityException {
        byte[] key = ChannelCrypto.pbkdf2Sha256(seed.trim().getBytes(UTF8), "communitycomms/dryad/v1".getBytes(UTF8),
                20000, 32);
        return generate(name, new HashRng(key), space, eol);
    }

    private interface Rng {
        int below(int n);
    }

    /** SHA-256 in counter mode, with rejection sampling so every permutation is equally likely. */
    private static final class HashRng implements Rng {
        private final byte[] key;
        private long counter;
        private byte[] block = new byte[0];
        private int pos;

        HashRng(byte[] key) {
            this.key = key;
        }

        private int nextByte() {
            if (pos >= block.length) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(key);
                    for (int i = 7; i >= 0; i--) md.update((byte) (counter >>> (8 * i)));
                    counter++;
                    block = md.digest();
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
                pos = 0;
            }
            return block[pos++] & 0xff;
        }

        @Override
        public int below(int n) {
            int limit = 256 - (256 % n);
            while (true) {
                int b = nextByte();
                if (b < limit) return b % n;
            }
        }
    }

    private static DryadSheet generate(String name, Rng rng, char space, char eol) {
        return generate(name, rng, space, eol, ROWS);
    }

    private static DryadSheet generate(String name, Rng rng, char space, char eol, int rowCount) {
        if (!validSpecials(space, eol)) throw new IllegalArgumentException("Pick two different special symbols");
        String alphabet = ALPHABET.substring(0, 26) + space + eol;
        char[][] rows = new char[rowCount][LETTERS];
        for (int r = 0; r < rowCount; r++) {
            for (int i = 0; i < LETTERS; i++) rows[r][i] = alphabet.charAt(i);
            for (int i = LETTERS - 1; i > 0; i--) {
                int j = rng.below(i + 1);
                char t = rows[r][i];
                rows[r][i] = rows[r][j];
                rows[r][j] = t;
            }
        }
        return new DryadSheet(name, rows, space, eol);
    }

    /** The symbols of a row, in sheet order (also used as a channel name). */
    public char[] row(char rowLetter) {
        return rows[Character.toUpperCase(rowLetter) - 'A'].clone();
    }

    /**
     * The row as a 28-slot pigpen key, with this sheet's special symbols turned into the
     * canonical '.' (space) and '@' (end of line). Classic 26-letter rows get ".@" on the end.
     */
    public char[] pigpenKey(char rowLetter) {
        return pigpenKeyAt(Character.toUpperCase(rowLetter) - 'A');
    }

    public char[] pigpenKeyAt(int rowIdx) {
        char[] r = rowAt(rowIdx);
        if (r.length == LETTERS) {
            for (int i = 0; i < r.length; i++) {
                if (r[i] == spaceSymbol) r[i] = SPACE;
                else if (r[i] == eolSymbol) r[i] = END_OF_LINE;
            }
            return r;
        }
        char[] k = new char[LETTERS];
        System.arraycopy(r, 0, k, 0, r.length);
        k[26] = SPACE;
        k[27] = END_OF_LINE;
        return k;
    }

    /** Column digit (0-9) a symbol sits under in a given row. */
    public int columnOf(char rowLetter, char letter) {
        char[] r = rows[Character.toUpperCase(rowLetter) - 'A'];
        letter = Character.toUpperCase(letter);
        for (int i = 0; i < width; i++) {
            if (r[i] == letter) return columnOfPosition(i);
        }
        return -1;
    }

    public int columnOfPosition(int pos) {
        int acc = 0;
        for (int c = 0; c < sizes.length; c++) {
            acc += sizes[c];
            if (pos < acc) return c;
        }
        return -1;
    }

    /** Symbols under a digit column in a row, e.g. for encoding numbers. */
    public String lettersFor(char rowLetter, int digit) {
        char[] r = rows[Character.toUpperCase(rowLetter) - 'A'];
        int start = 0;
        for (int c = 0; c < digit; c++) start += sizes[c];
        return new String(r, start, sizes[digit]);
    }

    /** Every symbol of every row; equal for two copies of the same sheet whatever their names. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(rows.length * width);
        for (char[] r : rows) sb.append(r);
        return sb.toString();
    }

    /** One row as printed: symbols grouped by column. */
    public String formatRow(int r) {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        for (int c = 0; c < sizes.length; c++) {
            if (c > 0) sb.append(' ');
            sb.append(rows[r], p, sizes[c]);
            p += sizes[c];
        }
        return sb.toString();
    }

    /** Column header line aligned with formatRow. */
    public String formatHeader() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < sizes.length; c++) {
            if (c > 0) sb.append(' ');
            sb.append(c);
            for (int k = 1; k < sizes[c]; k++) sb.append(' ');
        }
        return sb.toString();
    }

    /** Human-readable and re-importable text form. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("NAME: ").append(name.replace('\n', ' ')).append('\n');
        if (validFrom != 0) sb.append("VALID-FROM: ").append(formatTime(validFrom)).append('\n');
        if (validTo != 0) sb.append("VALID-TO: ").append(formatTime(validTo)).append('\n');
        if (colour != null) sb.append("COLOUR: ").append(colour).append('\n');
        if (used) sb.append("USED: yes\n");
        if (isModern()) sb.append("SPECIAL: ").append(spaceSymbol).append(eolSymbol).append("  = SPACE NEWLINE\n");
        sb.append(rows.length == ROWS ? "   " : "    ").append(formatHeader()).append('\n');
        for (int r : printOrder()) {
            sb.append(rowLabel(r)).append(": ").append(formatRow(r)).append('\n');
        }
        return sb.toString();
    }

    /** UTC, minute precision, e.g. 2026-10-21 06:00Z. */
    public static String formatTime(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private static long parseTime(String s) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(s.trim()).getTime();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException("Bad date " + s);
        }
    }

    /** Parses one or more sheets pasted together (bulk share). */
    public static java.util.List<DryadSheet> parseAll(String text) {
        java.util.List<DryadSheet> out = new java.util.ArrayList<DryadSheet>();
        String[] parts = text.split(java.util.regex.Pattern.quote(HEADER));
        for (String p : parts) {
            if (p.trim().length() == 0) continue;
            out.add(parse(p));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No sheet found");
        return out;
    }

    /** Parses toText() output (also tolerant of extra spaces and blank lines). */
    public static DryadSheet parse(String text) {
        String name = "Imported sheet";
        long from = 0, to = 0;
        boolean used = false;
        String colour = null;
        char space = SPACE, eol = END_OF_LINE;
        String[] rawRows = new String[BIG_ROWS];
        java.util.List<Integer> seenOrder = new java.util.ArrayList<Integer>();
        boolean big = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            String upper = line.toUpperCase();
            if (upper.startsWith("NAME:")) {
                name = line.substring(5).trim();
                continue;
            }
            if (upper.startsWith("VALID-FROM:")) {
                from = parseTime(line.substring(11));
                continue;
            }
            if (upper.startsWith("VALID-TO:")) {
                to = parseTime(line.substring(9));
                continue;
            }
            if (upper.startsWith("USED:")) {
                used = upper.contains("YES");
                continue;
            }
            if (upper.startsWith("COLOUR:") || upper.startsWith("COLOR:")) {
                colour = SheetColour.normalise(line.substring(line.indexOf(':') + 1));
                continue;
            }
            if (upper.startsWith("SPECIAL:")) {
                String sp = line.substring(8).trim();
                if (sp.length() < 2 || !validSpecials(sp.charAt(0), sp.charAt(1))) {
                    throw new IllegalArgumentException("Bad SPECIAL line");
                }
                space = sp.charAt(0);
                eol = sp.charAt(1);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 1 || colon > 2 || line.length() < colon + 2) continue;
            String lab = upper.substring(0, colon);
            if (!lab.matches("[A-Z]{1,2}")) continue;
            int idx = lab.length() == 1 ? lab.charAt(0) - 'A' : (lab.charAt(0) - 'A') * 26 + (lab.charAt(1) - 'A');
            if (lab.length() == 2) big = true;
            rawRows[idx] = line.substring(colon + 1);
            seenOrder.add(idx);
        }
        String alphabet = ALPHABET.substring(0, 26) + space + eol;
        int count = big ? BIG_ROWS : ROWS;
        char[][] rows = new char[count][];
        for (int r = 0; r < count; r++) {
            String rl = big ? "" + (char) ('A' + r / 26) + (char) ('A' + r % 26) : String.valueOf((char) ('A' + r));
            if (rawRows[r] == null) throw new IllegalArgumentException("Missing row " + rl);
            StringBuilder sb = new StringBuilder();
            for (char c : rawRows[r].toCharArray()) {
                char u = Character.toUpperCase(c);
                if (alphabet.indexOf(u) >= 0) sb.append(u);
            }
            String letters = sb.toString();
            if (letters.length() != 26 && letters.length() != LETTERS) {
                throw new IllegalArgumentException("Row " + rl + " needs 28 symbols (A-Z " + space + " " + eol + ")");
            }
            boolean[] seen = new boolean[LETTERS];
            for (int i = 0; i < letters.length(); i++) {
                int k = alphabet.indexOf(letters.charAt(i));
                if (seen[k]) throw new IllegalArgumentException("Row " + rl + " repeats " + letters.charAt(i));
                seen[k] = true;
            }
            rows[r] = letters.toCharArray();
            if (rows[r].length != rows[0].length) throw new IllegalArgumentException("Rows differ in length");
        }
        DryadSheet s = new DryadSheet(name, rows, space, eol);
        // Rows printed out of order (a randomised order): keep that order.
        boolean inOrder = seenOrder.size() == count;
        for (int i = 0; inOrder && i < count; i++) inOrder = seenOrder.get(i) == i;
        if (!inOrder && seenOrder.size() == count) {
            s.order = new int[count];
            for (int i = 0; i < count; i++) s.order[i] = seenOrder.get(i);
        }
        s.validFrom = from;
        s.validTo = to;
        s.used = used;
        s.colour = colour;
        return s;
    }

    // ---- compact form for QR codes ----

    private static final String QR_PREFIX = "UBD1|";

    public static boolean isQr(String s) {
        return s != null && s.startsWith(QR_PREFIX);
    }

    /**
     * One sheet in about 770 bytes: UBD1|name|from|to|colour|used|728 symbols (676 on old
     * sheets), times as base-36 minutes since 1970 ("" = open-ended).
     */
    /** Huge AA-ZZ sheets are too big for one QR code (share them as text or over the air). */
    public boolean fitsQr() {
        return rows.length == ROWS;
    }

    public String toQr() {
        if (!fitsQr()) throw new IllegalArgumentException("Huge AA-ZZ sheets do not fit in a QR code: share as text or on air");
        StringBuilder sb = new StringBuilder(QR_PREFIX);
        sb.append(name.replace('|', '/')).append('|');
        if (validFrom != 0) sb.append(Long.toString(validFrom / 60000, 36));
        sb.append('|');
        if (validTo != 0) sb.append(Long.toString(validTo / 60000, 36));
        sb.append('|').append(colour == null ? "" : colour).append('|').append(used ? '1' : '0').append('|');
        sb.append(fingerprint());
        if (isModern()) sb.append('|').append(spaceSymbol).append(eolSymbol);
        return sb.toString();
    }

    public static DryadSheet fromQr(String s) {
        if (!isQr(s)) throw new IllegalArgumentException("Not a DRYAD QR code");
        String[] f = s.substring(QR_PREFIX.length()).split("\\|", -1);
        // Field 7 (special symbols) exists on Modern DRYAD codes; without it the defaults apply.
        int w = f.length >= 6 ? f[5].length() / ROWS : 0;
        if (f.length < 6 || f.length > 7 || (w != 26 && w != LETTERS) || f[5].length() != ROWS * w) {
            throw new IllegalArgumentException("Damaged DRYAD QR code");
        }
        StringBuilder text = new StringBuilder();
        if (f.length == 7) text.append("SPECIAL: ").append(f[6]).append('\n');
        for (int r = 0; r < ROWS; r++) {
            text.append((char) ('A' + r)).append(": ").append(f[5], r * w, (r + 1) * w).append('\n');
        }
        DryadSheet d = parse(text.toString());
        d.name = f[0].length() == 0 ? "Scanned sheet" : f[0];
        d.validFrom = f[1].length() == 0 ? 0 : Long.parseLong(f[1], 36) * 60000;
        d.validTo = f[2].length() == 0 ? 0 : Long.parseLong(f[2], 36) * 60000;
        d.colour = f[3].length() == 0 ? null : SheetColour.normalise(f[3]);
        d.used = "1".equals(f[4]);
        return d;
    }
}
