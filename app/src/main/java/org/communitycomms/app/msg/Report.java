package org.communitycomms.app.msg;

import java.util.ArrayList;
import java.util.List;

/**
 * A filled-in report: template code, title and (label, value) lines. The labels travel with the
 * report, so the receiver does not need the template (custom templates just work).
 *
 * Text form: "UBR1", code, title, then one "label\tvalue" line per field (tabs, newlines and
 * backslashes escaped).
 */
public final class Report {
    public String code = "";
    public String title = "";
    public final List<String> labels = new ArrayList<String>();
    public final List<String> values = new ArrayList<String>();

    public String toText() {
        StringBuilder sb = new StringBuilder("UBR1\n").append(esc(code)).append('\n').append(esc(title)).append('\n');
        for (int i = 0; i < labels.size(); i++) {
            sb.append(esc(labels.get(i))).append('\t').append(esc(values.get(i))).append('\n');
        }
        return sb.toString();
    }

    public static Report parse(String s) {
        String[] lines = s.split("\n");
        if (lines.length < 3 || !lines[0].equals("UBR1")) return null;
        Report r = new Report();
        r.code = unesc(lines[1]);
        r.title = unesc(lines[2]);
        for (int i = 3; i < lines.length; i++) {
            int tab = lines[i].indexOf('\t');
            if (tab < 0) continue;
            r.labels.add(unesc(lines[i].substring(0, tab)));
            r.values.add(unesc(lines[i].substring(tab + 1)));
        }
        return r;
    }

    /** Multi-line plain text for display and for a POI note. Empty fields are skipped. */
    public String readable(boolean skipEmpty) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            String v = values.get(i).trim();
            if (skipEmpty && v.length() == 0) continue;
            sb.append(labels.get(i)).append(": ").append(v.length() == 0 ? "-" : v).append('\n');
        }
        return sb.toString().trim();
    }

    /** First decimal "lat, lon" found in the fields (e.g. from Insert my location), or null. */
    public double[] findLatLon() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)");
        for (String v : values) {
            java.util.regex.Matcher m = p.matcher(v);
            if (m.find()) {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (la >= -90 && la <= 90 && lo >= -180 && lo <= 180) return new double[]{la, lo};
            }
        }
        return null;
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
