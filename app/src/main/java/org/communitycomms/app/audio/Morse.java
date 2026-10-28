package org.communitycomms.app.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Morse code (CW) for the Enigma page: text to a tone WAV (8 kHz mono, soft-edged keying so it does
 * not click on a radio) and a decoder for recordings (finds the tone, measures the dots and dashes
 * and the gaps, whatever the speed). Prosigns are written in angle brackets, e.g. &lt;AR&gt;, and sent
 * as one run. Timing: dot 1 unit, dash 3, gap inside a letter 1, between letters 3, between words 7;
 * one unit = 1200 / WPM ms.
 */
public final class Morse {
    private Morse() {
    }

    public static final int RATE = 8000;
    private static final Map<Character, String> CODE = new HashMap<Character, String>();
    private static final Map<String, Character> BACK = new HashMap<String, Character>();

    static {
        String[] t = {"A.-", "B-...", "C-.-.", "D-..", "E.", "F..-.", "G--.", "H....", "I..", "J.---", "K-.-", "L.-..",
                "M--", "N-.", "O---", "P.--.", "Q--.-", "R.-.", "S...", "T-", "U..-", "V...-", "W.--", "X-..-", "Y-.--",
                "Z--..", "0-----", "1.----", "2..---", "3...--", "4....-", "5.....", "6-....", "7--...", "8---..",
                "9----.", "..-.-.-", ",--..--", "?..--..", "'.----.", "!-.-.--", "/-..-.", "(-.--.", ")-.--.-",
                "&.-...", ":---...", ";-.-.-.", "=-...-", "+.-.-.", "--....-", "\"\u0000", "@.--.-."};
        for (String s : t) {
            if (s.length() < 2 || s.charAt(1) == '\u0000') continue;
            CODE.put(s.charAt(0), s.substring(1));
            BACK.put(s.substring(1), s.charAt(0));
        }
        CODE.put('"', ".-..-.");
        BACK.put(".-..-.", '"');
        CODE.put('_', "..--.-");
        BACK.put("..--.-", '_');
        BACK.put("...-.-", '%'); // <SK>, written back as <SK>
    }

    /** Settings for one transmission. */
    public static final class Params {
        public int wpm = 15, toneHz = 700, repeats = 1, preMs = 500, postMs = 1000;
    }

    /** Dots, dashes and gaps as unit counts: positive = tone on, negative = silence. */
    static List<Integer> timing(String text) {
        List<Integer> out = new ArrayList<Integer>();
        String s = text.toUpperCase(Locale.US).trim();
        boolean prevChar = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (prevChar) out.add(-7);
                prevChar = false;
                while (i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1))) i++;
                continue;
            }
            String code;
            if (c == '<') { // a prosign: its letters run together
                int end = s.indexOf('>', i);
                if (end < 0) continue;
                StringBuilder b = new StringBuilder();
                for (int k = i + 1; k < end; k++) {
                    String one = CODE.get(s.charAt(k));
                    if (one != null) b.append(one);
                }
                code = b.toString();
                i = end;
            } else {
                code = CODE.get(c);
            }
            if (code == null || code.length() == 0) continue;
            if (prevChar) out.add(-3);
            for (int k = 0; k < code.length(); k++) {
                if (k > 0) out.add(-1);
                out.add(code.charAt(k) == '.' ? 1 : 3);
            }
            prevChar = true;
        }
        return out;
    }

    /** Length of a transmission in ms (with its repeats and the clear air before and after). */
    public static long durationMs(String text, Params p) {
        long units = 0;
        for (int u : timing(text)) units += Math.abs(u);
        long unitMs = 1200 / Math.max(5, p.wpm);
        int reps = Math.max(1, p.repeats);
        return p.preMs + p.postMs + reps * units * unitMs + (reps - 1) * 14 * unitMs;
    }

    /** The tone for a message, repeated, with silence before and after. */
    public static short[] render(String text, Params p) {
        List<Integer> t = timing(text);
        int unit = RATE * 1200 / Math.max(5, p.wpm) / 1000;
        int reps = Math.max(1, p.repeats);
        int total = (int) ((long) durationMs(text, p) * RATE / 1000) + RATE / 10;
        short[] pcm = new short[total];
        int at = p.preMs * RATE / 1000;
        int ramp = Math.max(8, RATE * 5 / 1000); // 5 ms raised-cosine edges
        double w = 2 * Math.PI * p.toneHz / RATE;
        for (int r = 0; r < reps; r++) {
            if (r > 0) at += 14 * unit;
            for (int u : t) {
                int len = Math.abs(u) * unit;
                if (u > 0) {
                    for (int i = 0; i < len && at + i < total; i++) {
                        double env = 1;
                        if (i < ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * i / ramp);
                        else if (i > len - ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * (len - i) / ramp);
                        pcm[at + i] = (short) (Math.sin(w * (at + i)) * env * 22000);
                    }
                }
                at += len;
            }
        }
        int end = Math.min(total, at + p.postMs * RATE / 1000);
        return Arrays.copyOf(pcm, Math.max(end, 1));
    }

    // ---- decoding ----

    private static final int BLOCK = 40; // 5 ms at 8 kHz

    private static double goertzel(short[] x, int off, int n, double f) {
        double w = 2 * Math.PI * f / RATE, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        int end = Math.min(x.length, off + n);
        for (int i = off; i < end; i++) {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }

    /** The strongest tone between 300 and 1500 Hz in the loudest parts, or 0 if there is none. */
    public static int findTone(short[] x) {
        int blocks = x.length / (BLOCK * 2);
        if (blocks < 4) return 0;
        double[] e = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            double s = 0;
            for (int i = b * BLOCK * 2; i < (b + 1) * BLOCK * 2; i++) s += (double) x[i] * x[i];
            e[b] = s;
        }
        double[] sorted = e.clone();
        Arrays.sort(sorted);
        double cut = sorted[(int) (blocks * 0.8)];
        int best = 0;
        double bestP = 0;
        for (int f = 300; f <= 1500; f += 20) {
            double p = 0;
            int used = 0;
            for (int b = 0; b < blocks && used < 400; b++) {
                if (e[b] < cut || e[b] == 0) continue;
                p += goertzel(x, b * BLOCK * 2, BLOCK * 2, f);
                used++;
            }
            if (p > bestP) {
                bestP = p;
                best = f;
            }
        }
        // Refine to 5 Hz round the best.
        int fine = best;
        for (int f = best - 15; f <= best + 15; f += 5) {
            double p = 0;
            int used = 0;
            for (int b = 0; b < blocks && used < 400; b++) {
                if (e[b] < cut || e[b] == 0) continue;
                p += goertzel(x, b * BLOCK * 2, BLOCK * 2, f);
                used++;
            }
            if (p > bestP) {
                bestP = p;
                fine = f;
            }
        }
        return fine;
    }

    /** Decodes a recording to text ("" when no Morse is found). toneHz 0 = find it. */
    public static String decode(short[] x, int toneHz) {
        int f = toneHz > 0 ? toneHz : findTone(x);
        if (f == 0) return "";
        int blocks = x.length / BLOCK - 1;
        if (blocks < 4) return "";
        double[] env = new double[blocks];
        for (int b = 0; b < blocks; b++) env[b] = Math.sqrt(goertzel(x, b * BLOCK, BLOCK * 2, f));
        double[] s = env.clone();
        Arrays.sort(s);
        double noise = s[(int) (blocks * 0.2)], sig = s[(int) (blocks * 0.95)];
        if (sig < noise * 3 + 1) return "";
        double hi = noise + (sig - noise) * 0.5, lo = noise + (sig - noise) * 0.3;
        // Runs of tone on / off, in blocks.
        List<int[]> runs = new ArrayList<int[]>();
        boolean on = false;
        int len = 0;
        for (int b = 0; b < blocks; b++) {
            boolean now = on ? env[b] > lo : env[b] > hi;
            if (now == on) {
                len++;
            } else {
                runs.add(new int[]{on ? 1 : 0, len});
                on = now;
                len = 1;
            }
        }
        runs.add(new int[]{on ? 1 : 0, len});
        // Drop blips (a block or two of tone, or of silence inside a dash).
        List<int[]> clean = new ArrayList<int[]>();
        for (int[] r : runs) {
            if (r[1] <= 1 && !clean.isEmpty()) {
                clean.get(clean.size() - 1)[1] += r[1];
                continue;
            }
            if (!clean.isEmpty() && clean.get(clean.size() - 1)[0] == r[0]) clean.get(clean.size() - 1)[1] += r[1];
            else clean.add(r);
        }
        // The unit: two clusters of tone lengths (dots and dashes), or the short gaps if only one kind.
        List<Integer> ons = new ArrayList<Integer>(), offs = new ArrayList<Integer>();
        for (int i = 0; i < clean.size(); i++) {
            int[] r = clean.get(i);
            if (r[0] == 1) ons.add(r[1]);
            else if (i > 0 && i < clean.size() - 1) offs.add(r[1]);
        }
        if (ons.isEmpty()) return "";
        double c1 = Integer.MAX_VALUE, c2 = 0;
        for (int v : ons) {
            c1 = Math.min(c1, v);
            c2 = Math.max(c2, v);
        }
        for (int it = 0; it < 12; it++) {
            double s1 = 0, s2 = 0;
            int n1 = 0, n2 = 0;
            for (int v : ons) {
                if (Math.abs(v - c1) <= Math.abs(v - c2)) {
                    s1 += v;
                    n1++;
                } else {
                    s2 += v;
                    n2++;
                }
            }
            if (n1 > 0) c1 = s1 / n1;
            if (n2 > 0) c2 = s2 / n2;
        }
        double unit;
        if (c2 >= c1 * 2) {
            unit = (c1 + c2 / 3) / 2;
        } else {
            List<Integer> g = new ArrayList<Integer>(offs);
            java.util.Collections.sort(g);
            double gap = g.isEmpty() ? c1 : g.get(g.size() / 4);
            unit = Math.min(c1, gap);
            if (c1 > unit * 2) unit = c1 / 3; // all dashes
        }
        unit = Math.max(1, unit);
        StringBuilder out = new StringBuilder(), sym = new StringBuilder();
        for (int i = 0; i < clean.size(); i++) {
            int[] r = clean.get(i);
            if (r[0] == 1) {
                sym.append(r[1] < unit * 2 ? '.' : '-');
            } else if (sym.length() > 0) {
                double u = r[1] / unit;
                boolean last = i == clean.size() - 1;
                if (u >= 2 || last) {
                    out.append(letter(sym.toString()));
                    sym.setLength(0);
                    if (u >= 5 && !last) out.append(' ');
                }
            }
        }
        if (sym.length() > 0) out.append(letter(sym.toString()));
        return out.toString().trim();
    }

    private static String letter(String code) {
        Character c = BACK.get(code);
        if (c == null) return "?";
        if (c == '+') return "<AR>";
        if (c == '%') return "<SK>";
        return String.valueOf(c);
    }

    /** True if a block of sound is louder than the noise by the given factor (for "is anyone sending?"). */
    public static double rms(short[] x, int off, int n) {
        double s = 0;
        int end = Math.min(x.length, off + n);
        for (int i = off; i < end; i++) s += (double) x[i] * x[i];
        return Math.sqrt(s / Math.max(1, end - off));
    }

    // ---- WAV files ----

    public static void writeWav(File out, short[] pcm) throws Exception {
        int data = pcm.length * 2;
        ByteBuffer h = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes("US-ASCII")).putInt(36 + data).put("WAVE".getBytes("US-ASCII"));
        h.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE)
                .putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes("US-ASCII")).putInt(data);
        for (short s : pcm) h.putShort(s);
        FileOutputStream fo = new FileOutputStream(out);
        try {
            fo.write(h.array());
        } finally {
            fo.close();
        }
    }

    /** Reads one of our WAVs (16-bit mono; other rates are resampled to 8 kHz roughly). */
    public static short[] readWav(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            in.close();
        }
        ByteBuffer b = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        int rate = RATE, channels = 1, bits = 16;
        b.position(12);
        while (b.remaining() >= 8) {
            byte[] id = new byte[4];
            b.get(id);
            int size = b.getInt();
            String name = new String(id, "US-ASCII");
            if (name.equals("fmt ")) {
                int p = b.position();
                b.getShort();
                channels = b.getShort();
                rate = b.getInt();
                b.getInt();
                b.getShort();
                bits = b.getShort();
                b.position(p + size);
            } else if (name.equals("data")) {
                int n = Math.min(size, b.remaining()) / (bits / 8) / Math.max(1, channels);
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) {
                    int sum = 0;
                    for (int ch = 0; ch < channels; ch++) sum += bits == 16 ? b.getShort() : ((b.get() & 0xff) - 128) << 8;
                    pcm[i] = (short) (sum / Math.max(1, channels));
                }
                if (rate == RATE) return pcm;
                short[] r = new short[(int) ((long) n * RATE / rate)];
                for (int i = 0; i < r.length; i++) r[i] = pcm[(int) Math.min(n - 1, (long) i * rate / RATE)];
                return r;
            } else {
                b.position(Math.min(b.limit(), b.position() + size + (size & 1)));
            }
        }
        return new short[0];
    }

    /** Joins recordings with a gap of silence between them. */
    public static short[] join(List<short[]> parts, int gapMs) {
        int gap = gapMs * RATE / 1000, n = 0;
        for (short[] p : parts) n += p.length;
        n += gap * Math.max(0, parts.size() - 1);
        short[] out = new short[n];
        int at = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) at += gap;
            System.arraycopy(parts.get(i), 0, out, at, parts.get(i).length);
            at += parts.get(i).length;
        }
        return out;
    }

    /** Splits a recording at silences of at least silenceMs (each part keeps a little quiet round it). */
    public static List<short[]> split(short[] x, int silenceMs) {
        List<short[]> out = new ArrayList<short[]>();
        int blk = RATE / 50; // 20 ms
        int blocks = x.length / blk;
        if (blocks == 0) {
            out.add(x);
            return out;
        }
        double[] e = new double[blocks];
        for (int b = 0; b < blocks; b++) e[b] = rms(x, b * blk, blk);
        double[] s = e.clone();
        Arrays.sort(s);
        double noise = s[(int) (blocks * 0.2)], peak = s[(int) (blocks * 0.98)];
        double thr = noise + (peak - noise) * 0.25;
        int need = Math.max(1, silenceMs / 20), pad = 10; // 200 ms kept each side
        int start = -1, quiet = 0, lastLoud = -1;
        for (int b = 0; b < blocks; b++) {
            if (e[b] > thr) {
                if (start < 0) start = b;
                lastLoud = b;
                quiet = 0;
            } else if (start >= 0 && ++quiet >= need) {
                out.add(Arrays.copyOfRange(x, Math.max(0, start - pad) * blk, Math.min(blocks, lastLoud + pad) * blk));
                start = -1;
                quiet = 0;
            }
        }
        if (start >= 0) out.add(Arrays.copyOfRange(x, Math.max(0, start - pad) * blk, x.length));
        if (out.isEmpty()) out.add(x);
        return out;
    }
}
