package org.communitycomms.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.communitycomms.app.audio.Morse;

import java.io.File;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CW (Morse) for the Enigma page: settings (message format, speed, tone, repeats, clear air), the
 * CW files (made here, received, joined or split), and CW mode, which puts the phone in stealth with
 * PTT and voice off so the only audio in or out is CW through the radio it is plugged into.
 * <p>
 * Message format variables (any case): %R% a random letter, %SheetID% (or %ID%), %StartRow% (or
 * %ROW%), %CipherText% (or %CIPHER%), %Call% my callsign. Prosigns go in angle brackets, e.g. &lt;AR&gt;.
 */
public final class Cw {
    private Cw() {
    }

    public static final String DEFAULT_FORMAT = "%SheetID% %StartRow% %CipherText% <AR>";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cw", Context.MODE_PRIVATE);
    }

    public static String format(Context c) {
        String f = sp(c).getString("format", DEFAULT_FORMAT).trim();
        return f.length() == 0 ? DEFAULT_FORMAT : f;
    }

    public static Morse.Params params(Context c) {
        SharedPreferences s = sp(c);
        Morse.Params p = new Morse.Params();
        p.wpm = s.getInt("wpm", 15);
        p.toneHz = s.getInt("tone", 700);
        p.repeats = s.getInt("repeats", 1);
        p.preMs = s.getInt("preMs", 500);
        p.postMs = s.getInt("postMs", 1000);
        p.leadMs = s.getInt("leadMs", 0);
        return p;
    }

    /** Receiving: this long with no tone ends a message (seconds). */
    public static int clearAirSec(Context c) {
        return sp(c).getInt("clearAir", 3);
    }

    // ---- CW mode (the lock on the Enigma page) ----

    public static boolean mode() {
        return RadioEngine.cwMode;
    }

    /** CW mode on: stealth, PTT and VOX off, voice muted; off: back to normal. Kept across restarts and crashes. */
    public static void setMode(Context c, boolean on) {
        RadioEngine.cwMode = on;
        sp(c).edit().putBoolean("mode", on).commit();
        RadioService rs = RadioService.instance();
        if (rs != null) rs.cwModeChanged();
    }

    /** At app start: back into CW mode if it was on when the app last stopped (a crash included). */
    public static void restore(Context c) {
        RadioEngine.cwMode = sp(c).getBoolean("mode", false);
    }

    // ---- received CW into Chat, and private texts out by CW ----

    /** CW settings: put what Receive CW hears in Chat ("CW Decoded: …" or "CW received: …"). */
    public static boolean autoChat(Context c) {
        return sp(c).getBoolean("autoChat", false);
    }

    /** CW settings: in CW mode, private texts received are encoded and sent out by CW. */
    public static boolean autoRelay(Context c) {
        return sp(c).getBoolean("autoRelay", false);
    }

    /** The sheet and the next unused start row, kept by the Enigma page for the relay. */
    public static void remember(Context c, String id, String nextRow) {
        sp(c).edit().putString("lastId", id).putString("nextRow", nextRow).commit();
    }

    static final String DECODED = "CW Decoded: ", RECEIVED = "CW received: ", SENT = "Sent by CW: ";

    /** Sends a line to the channel on air (or keeps it on this phone when not joined). */
    public static void toChat(Context c, String line) {
        if (!autoChat(c)) return;
        RadioService rs = RadioService.instance();
        org.communitycomms.app.msg.MessageCenter mc = rs == null ? null : rs.messages();
        if (mc == null || !mc.sendText(line)) {
            android.widget.Toast.makeText(c, "Not on a channel: the CW text was not put in Chat", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * A text came in (RadioService): in CW mode with the relay on, a private one (a private channel, or
     * a message just to me) is encoded on the remembered sheet and row and queued to send by CW.
     */
    static void onText(Context c, org.communitycomms.app.msg.MessageLog.Entry m) {
        if (!RadioEngine.cwMode || !autoRelay(c) || m == null || m.mine || m.local || m.ack
                || m.type != org.communitycomms.app.msg.MessageLog.TYPE_TEXT || m.body == null) return;
        String b = m.body.trim();
        if (b.startsWith(DECODED) || b.startsWith(RECEIVED) || b.startsWith(SENT) || b.startsWith(RadioService.LOC_REQUEST)
                || org.communitycomms.app.msg.MessageCenter.isPin(m) || org.communitycomms.app.msg.MessageCenter.isUnpin(m)) return;
        boolean privateMsg = m.privateTo != null && m.privateTo.length() > 0;
        if (!privateMsg && !RadioService.privateSlot(new Prefs(c).activeSlot())) return;
        String id = sp(c).getString("lastId", ""), rowLabel = sp(c).getString("nextRow", "A");
        org.communitycomms.app.dryad.DryadSheet sheet = null;
        for (org.communitycomms.app.dryad.SheetStore.Entry e : new org.communitycomms.app.dryad.SheetStore(c).loadAll(false)) {
            if (e.sheet.id().equals(id)) sheet = e.sheet;
        }
        int row = sheet == null ? -1 : sheet.rowIndex(rowLabel);
        if (sheet == null || row < 0) {
            note(c, m, "CW relay: not sent (encode once on the DRYAD Enigma page so it knows the sheet and row)");
            return;
        }
        String plainText = EnigmaActivity.cleanPlain(m.creator + " " + b, new java.util.HashSet<String>()).trim();
        if (plainText.length() == 0) return;
        String cipherText = EnigmaActivity.encode(sheet, row, plainText);
        String msg = build(c, id, sheet.rowLabel(row), cipherText);
        try {
            File f = newFile(c, "CW", id + sheet.rowLabel(row) + "-relay");
            Morse.writeWav(f, Morse.render(msg, params(c)));
            setNote(f, msg);
            remember(c, id, sheet.rowLabel((row + plainText.length()) % sheet.rowCount()));
            CwPlayer.queue(c, f);
            note(c, m, SENT + plainText.replace('\n', ' ') + " (sheet " + id + ", row " + sheet.rowLabel(row) + ")");
        } catch (Exception e) {
            note(c, m, "CW relay: could not make the CW (" + e.getMessage() + ")");
        }
    }

    /** A note in Chat on this phone only. */
    private static void note(Context c, org.communitycomms.app.msg.MessageLog.Entry about, String text) {
        org.communitycomms.app.msg.MessageLog.Entry n = new org.communitycomms.app.msg.MessageLog.Entry();
        n.id = RANDOM.nextLong();
        n.type = org.communitycomms.app.msg.MessageLog.TYPE_TEXT;
        n.time = System.currentTimeMillis();
        n.creator = new Prefs(c).callsign();
        n.channelTag = about.channelTag;
        n.body = text;
        n.local = true;
        n.mine = true;
        org.communitycomms.app.msg.MessageLog.get(c).put(n);
    }

    // ---- the message text ----

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String build(Context c, String id, String row, String cipher) {
        String f = format(c);
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("%([A-Za-z]+)%").matcher(f);
        int last = 0;
        while (m.find()) {
            out.append(f, last, m.start());
            String v = m.group(1).toUpperCase(Locale.US);
            if (v.equals("R")) out.append((char) ('A' + RANDOM.nextInt(26)));
            else if (v.equals("SHEETID") || v.equals("ID")) out.append(id);
            else if (v.equals("STARTROW") || v.equals("ROW")) out.append(row);
            else if (v.equals("CIPHERTEXT") || v.equals("CIPHER")) out.append(cipher);
            else if (v.equals("CALL")) out.append(new Prefs(c).callsign());
            else out.append(m.group());
            last = m.end();
        }
        out.append(f.substring(last));
        return out.toString().toUpperCase(Locale.US);
    }

    /** {sheet id, start row, cipher text} read back out of decoded CW using the format, or null. */
    public static String[] parse(Context c, String text) {
        String t = text.toUpperCase(Locale.US).trim();
        for (int pass = 0; pass < 2; pass++) {
            Pattern p = pattern(format(c), pass == 1);
            Matcher m = p.matcher(t);
            if (m.find()) {
                String[] out = {"", "", ""};
                for (int g = 1; g <= m.groupCount(); g++) {
                    String name = names.get(g - 1);
                    String v = m.group(g) == null ? "" : m.group(g);
                    if (name.equals("ID")) out[0] = v;
                    else if (name.equals("ROW")) out[1] = v;
                    else if (name.equals("CIPHER")) out[2] = v;
                }
                if (out[2].length() > 0) return out;
            }
        }
        return null;
    }

    private static final List<String> names = new ArrayList<String>();

    /** The format as a pattern; loose = spaces may be missing (a gap heard short). */
    private static synchronized Pattern pattern(String f, boolean loose) {
        names.clear();
        StringBuilder re = new StringBuilder();
        Matcher m = Pattern.compile("%([A-Za-z]+)%|<([A-Za-z]+)>|\\s+|.", Pattern.DOTALL).matcher(f.toUpperCase(Locale.US));
        while (m.find()) {
            String g = m.group();
            if (m.group(1) != null) {
                String v = m.group(1);
                if (v.equals("R")) re.append("[A-Z0-9?]");
                else if (v.equals("SHEETID") || v.equals("ID")) {
                    re.append("([A-Z]{5})");
                    names.add("ID");
                } else if (v.equals("STARTROW") || v.equals("ROW")) {
                    re.append("([A-Z]{1,2})");
                    names.add("ROW");
                } else if (v.equals("CIPHERTEXT") || v.equals("CIPHER")) {
                    re.append("([A-Z.@?]+)");
                    names.add("CIPHER");
                } else if (v.equals("CALL")) re.append("[A-Z0-9/]+");
                else re.append(Pattern.quote(g));
            } else if (m.group(2) != null) {
                re.append("\\s*").append(Pattern.quote("<" + m.group(2) + ">"));
            } else if (g.trim().length() == 0) {
                re.append(loose ? "\\s*" : "\\s+");
            } else {
                re.append(Pattern.quote(g));
            }
        }
        return Pattern.compile(re.toString());
    }

    // ---- files ----

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "cw");
        d.mkdirs();
        return d;
    }

    /** Every CW file, newest first. */
    public static List<File> files(Context c) {
        File[] f = dir(c).listFiles();
        List<File> out = new ArrayList<File>();
        if (f == null) return out;
        for (File x : f) if (x.getName().endsWith(".wav")) out.add(x);
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return new ArrayList<File>(Arrays.asList(arr));
    }

    /** Generated messages only (for Send CW), newest first. */
    public static List<File> sendable(Context c) {
        List<File> out = new ArrayList<File>();
        for (File f : files(c)) if (!f.getName().startsWith("RX-")) out.add(f);
        return out;
    }

    public static File newFile(Context c, String prefix, String tag) {
        String when = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String t = tag == null || tag.length() == 0 ? "" : "-" + tag.replaceAll("[^A-Za-z0-9]", "");
        File f = new File(dir(c), prefix + "-" + when + t + ".wav");
        for (int i = 2; f.exists(); i++) f = new File(dir(c), prefix + "-" + when + t + "-" + i + ".wav");
        return f;
    }

    /** The text beside a CW file (what it says), or "". */
    public static String note(File wav) {
        File t = new File(wav.getPath() + ".txt");
        if (!t.exists()) return "";
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(t);
            byte[] b = new byte[(int) Math.min(4096, t.length())];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static void setNote(File wav, String text) {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(wav.getPath() + ".txt");
            o.write(text.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {
        }
    }

    public static void delete(File wav) {
        wav.delete();
        new File(wav.getPath() + ".txt").delete();
    }

    /** "CW-20261029-101500-ABCDE-A · 12 s · ABCDE A ..." for lists. */
    public static String label(File f) {
        long sec = Math.max(0, (f.length() - 44) / 2 / Morse.RATE);
        String n = note(f);
        String when = new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(f.lastModified()));
        String kind = f.getName().startsWith("RX-") ? "Received" : f.getName().startsWith("JOIN-") ? "Joined"
                : f.getName().startsWith("PART-") ? "Part" : f.getName().startsWith("UP-") ? "Uploaded" : "Made";
        return kind + " " + when + " · " + sec + " s" + (n.length() > 0 ? "\n" + (n.length() > 80 ? n.substring(0, 78) + "…" : n) : "");
    }
}
