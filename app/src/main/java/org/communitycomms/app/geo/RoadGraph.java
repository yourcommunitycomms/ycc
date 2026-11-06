package org.communitycomms.app.geo;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Offline street routing without any Android parts (so it can be tested on a PC):
 * <ul>
 * <li>{@link #buildCell} turns OpenStreetMap roads for one routing cell (Overpass JSON) into a small
 * file: only junctions are kept as nodes; the road between two junctions is one edge with its length,
 * kind, one-way flag, name and shape.</li>
 * <li>{@link #load} joins any number of cell files into one graph (a road crossing a cell edge is in
 * both cells, and the OpenStreetMap node ids join them up).</li>
 * <li>{@link #route} finds the quickest way (A*) for driving or walking, with turn instructions.</li>
 * </ul>
 */
public final class RoadGraph {
    public static final String MAGIC = "YCR1";

    /** Road kinds, in the order stored in the files. */
    static final String[] KINDS = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street",
            "service", "road", "track", "pedestrian", "footway", "path", "cycleway", "bridleway", "steps"};
    /** km/h by kind; 0 = not allowed. */
    private static final int[] CAR_KMH = {100, 60, 80, 50, 65, 45, 55, 40, 45, 35, 40, 30, 10, 15, 30, 15, 0, 0, 0, 0, 0, 0};
    private static final int[] FOOT_KMH = {0, 0, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 3};
    public static final int CAR = 0, FOOT = 1;
    static final int F_ONEWAY = 1, F_NOCAR = 2, F_NOFOOT = 4;

    /** The Overpass query for a box: every road and path, with tags, then their points. */
    public static String query(double s, double w, double n, double e) {
        StringBuilder k = new StringBuilder();
        for (String x : KINDS) k.append(k.length() > 0 ? "|" : "").append(x);
        return String.format(Locale.US, "[out:json][timeout:300];way[\"highway\"~\"^(%s)$\"](%.5f,%.5f,%.5f,%.5f);out body qt;>;out skel qt;",
                k, s, w, n, e);
    }

    // ---- building a cell file ----

    /**
     * Reads Overpass JSON for the box s,w,n,e and writes the cell file (gzip). Returns the number of
     * road pieces (edges) written; 0 means no roads there (still a valid, empty cell).
     */
    public static int buildCell(InputStream json, double s, double w, double n, double e, OutputStream out) throws IOException {
        Json p = new Json(new BufferedReader(new InputStreamReader(json, "UTF-8"), 65536));
        // Ways
        List<long[]> wNodes = new ArrayList<long[]>();
        List<int[]> wInfo = new ArrayList<int[]>(); // kind, flags, name
        Map<String, Integer> nameIdx = new HashMap<String, Integer>();
        List<String> names = new ArrayList<String>();
        // Nodes
        long[] ids = new long[1 << 16];
        int[] la = new int[ids.length], lo = new int[ids.length];
        int nn = 0;
        p.beginObject();
        while (p.hasNext()) {
            String key = p.nextName();
            if (!key.equals("elements")) {
                p.skipValue();
                continue;
            }
            p.beginArray();
            long[] tmp = new long[256];
            while (p.hasNext()) {
                p.beginObject();
                String type = "";
                long id = 0;
                double lat = Double.NaN, lon = Double.NaN;
                int count = 0;
                Map<String, String> tags = null;
                while (p.hasNext()) {
                    String f = p.nextName();
                    if (f.equals("type")) type = p.nextString();
                    else if (f.equals("id")) id = Long.parseLong(p.nextString());
                    else if (f.equals("lat")) lat = Double.parseDouble(p.nextString());
                    else if (f.equals("lon")) lon = Double.parseDouble(p.nextString());
                    else if (f.equals("nodes")) {
                        p.beginArray();
                        count = 0;
                        while (p.hasNext()) {
                            if (count == tmp.length) tmp = Arrays.copyOf(tmp, count * 2);
                            tmp[count++] = Long.parseLong(p.nextString());
                        }
                        p.endArray();
                    } else if (f.equals("tags")) {
                        tags = new HashMap<String, String>();
                        p.beginObject();
                        while (p.hasNext()) {
                            String tk = p.nextName();
                            tags.put(tk, p.nextString());
                        }
                        p.endObject();
                    } else p.skipValue();
                }
                p.endObject();
                if (type.equals("node") && !Double.isNaN(lat)) {
                    if (nn == ids.length) {
                        ids = Arrays.copyOf(ids, nn * 2);
                        la = Arrays.copyOf(la, nn * 2);
                        lo = Arrays.copyOf(lo, nn * 2);
                    }
                    ids[nn] = id;
                    la[nn] = (int) Math.round(lat * 1e7);
                    lo[nn] = (int) Math.round(lon * 1e7);
                    nn++;
                } else if (type.equals("way") && tags != null && count >= 2) {
                    int kind = Arrays.asList(KINDS).indexOf(tags.get("highway"));
                    if (kind < 0) continue;
                    String access = tags.get("access"), mv = tags.get("motor_vehicle"), mc = tags.get("motorcar"), foot = tags.get("foot");
                    if ("no".equals(tags.get("area")) == false && "yes".equals(tags.get("area")) && kind >= 16) continue; // a square, not a path
                    int flags = 0;
                    boolean privateWay = "private".equals(access) || "no".equals(access);
                    if (privateWay || "no".equals(mv) || "private".equals(mv) || "no".equals(mc)) flags |= F_NOCAR;
                    if ((privateWay && !"yes".equals(foot)) || "no".equals(foot)) flags |= F_NOFOOT;
                    String ow = tags.get("oneway");
                    boolean reverse = "-1".equals(ow);
                    if ("yes".equals(ow) || "true".equals(ow) || "1".equals(ow) || reverse
                            || (ow == null && (kind == 0 || "roundabout".equals(tags.get("junction"))))) flags |= F_ONEWAY;
                    long[] nodes = Arrays.copyOf(tmp, count);
                    if (reverse) for (int i = 0; i < count / 2; i++) {
                        long t = nodes[i];
                        nodes[i] = nodes[count - 1 - i];
                        nodes[count - 1 - i] = t;
                    }
                    String name = tags.get("name");
                    if (name == null) name = tags.get("ref");
                    int ni = -1;
                    if (name != null && name.length() > 0) {
                        if (name.length() > 120) name = name.substring(0, 120);
                        Integer k = nameIdx.get(name);
                        if (k == null) {
                            k = names.size();
                            names.add(name);
                            nameIdx.put(name, k);
                        }
                        ni = k;
                    }
                    wNodes.add(nodes);
                    wInfo.add(new int[]{kind, flags, ni});
                }
            }
            p.endArray();
        }
        p.endObject();
        // Sort the points by id for lookups.
        sort(ids, la, lo, 0, nn - 1);
        int[] uses = new int[nn];
        int s7 = (int) Math.round(s * 1e7), w7 = (int) Math.round(w * 1e7), n7 = (int) Math.round(n * 1e7), e7 = (int) Math.round(e * 1e7);
        for (long[] way : wNodes) {
            for (int i = 0; i < way.length; i++) {
                int k = Arrays.binarySearch(ids, 0, nn, way[i]);
                if (k < 0) continue;
                uses[k] += (i == 0 || i == way.length - 1) ? 2 : 1;
            }
        }
        // A junction: where roads meet, a road ends, or (outside this cell) where another cell's roads may meet it.
        int[] jIdx = new int[nn];
        int nj = 0;
        for (int i = 0; i < nn; i++) {
            boolean outside = la[i] < s7 || la[i] > n7 || lo[i] < w7 || lo[i] > e7;
            jIdx[i] = uses[i] >= 2 || (outside && uses[i] > 0) ? nj++ : -1;
        }
        java.io.ByteArrayOutputStream edgeBytes = new java.io.ByteArrayOutputStream();
        DataOutputStream eo = new DataOutputStream(edgeBytes);
        int edges = 0;
        int[] pLa = new int[1024], pLo = new int[1024];
        for (int wi = 0; wi < wNodes.size(); wi++) {
            long[] way = wNodes.get(wi);
            int[] info = wInfo.get(wi);
            int from = -1, np = 0;
            double len = 0;
            int prev = -1;
            for (int i = 0; i < way.length; i++) {
                int k = Arrays.binarySearch(ids, 0, nn, way[i]);
                if (k < 0) { // a point missing from the answer: break the road here
                    from = -1;
                    prev = -1;
                    continue;
                }
                if (prev >= 0) len += dist(la[prev], lo[prev], la[k], lo[k]);
                prev = k;
                boolean last = i == way.length - 1;
                if (from < 0) {
                    if (jIdx[k] >= 0) {
                        from = k;
                        np = 0;
                        len = 0;
                    }
                    continue;
                }
                if (jIdx[k] >= 0 || last || np >= 30000) {
                    if (jIdx[k] < 0) { // forced split: make it a junction
                        continue; // (very rare; the road simply ends at the previous junction)
                    }
                    eo.writeInt(jIdx[from]);
                    eo.writeInt(jIdx[k]);
                    eo.writeByte(info[0]);
                    eo.writeByte(info[1]);
                    eo.writeInt(info[2]);
                    eo.writeFloat((float) len);
                    eo.writeShort(np);
                    for (int q = 0; q < np; q++) {
                        eo.writeInt(pLa[q]);
                        eo.writeInt(pLo[q]);
                    }
                    edges++;
                    from = k;
                    np = 0;
                    len = 0;
                } else {
                    if (np == pLa.length) {
                        pLa = Arrays.copyOf(pLa, np * 2);
                        pLo = Arrays.copyOf(pLo, np * 2);
                    }
                    pLa[np] = la[k];
                    pLo[np] = lo[k];
                    np++;
                }
            }
        }
        eo.flush();
        DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(out), 65536));
        o.writeBytes(MAGIC);
        o.writeInt(s7);
        o.writeInt(w7);
        o.writeInt(n7);
        o.writeInt(e7);
        o.writeInt(names.size());
        for (String nm : names) o.writeUTF(nm);
        o.writeInt(nj);
        for (int i = 0; i < nn; i++) {
            if (jIdx[i] < 0) continue;
            o.writeLong(ids[i]);
            o.writeInt(la[i]);
            o.writeInt(lo[i]);
        }
        o.writeInt(edges);
        edgeBytes.writeTo(o);
        o.close();
        return edges;
    }

    private static void sort(long[] k, int[] a, int[] b, int lo, int hi) {
        while (lo < hi) {
            long pivot = k[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (k[i] < pivot) i++;
                while (k[j] > pivot) j--;
                if (i <= j) {
                    long t = k[i];
                    k[i] = k[j];
                    k[j] = t;
                    int u = a[i];
                    a[i] = a[j];
                    a[j] = u;
                    u = b[i];
                    b[i] = b[j];
                    b[j] = u;
                    i++;
                    j--;
                }
            }
            if (j - lo < hi - i) {
                sort(k, a, b, lo, j);
                lo = i;
            } else {
                sort(k, a, b, i, hi);
                hi = j;
            }
        }
    }

    static double dist(int la1, int lo1, int la2, int lo2) {
        return Geo.distanceM(la1 / 1e7, lo1 / 1e7, la2 / 1e7, lo2 / 1e7);
    }

    // ---- the graph ----

    int n;
    long[] ids;
    int[] nLa, nLo;
    int m;
    int[] ea, eb, eName, gOff;
    byte[] eKind, eFlags;
    float[] eLen;
    int[] gLa, gLo;
    String[] names;
    int[] adjStart, adj; // adj = edge * 2 + direction (0: a to b, 1: b to a)

    public int nodes() {
        return n;
    }

    public int edges() {
        return m;
    }

    /** Joins cell files into one graph. Unreadable files are skipped. */
    public static RoadGraph load(List<File> files) throws IOException {
        List<long[]> fIds = new ArrayList<long[]>();
        List<int[][]> fNodes = new ArrayList<int[][]>();
        List<int[][]> fEdges = new ArrayList<int[][]>();
        List<float[]> fLen = new ArrayList<float[]>();
        List<int[][]> fGeo = new ArrayList<int[][]>();
        List<String[]> fNames = new ArrayList<String[]>();
        long totalIds = 0;
        for (File f : files) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f), 65536), 65536));
            try {
                byte[] mg = new byte[4];
                in.readFully(mg);
                if (!new String(mg, "US-ASCII").equals(MAGIC)) continue;
                in.readInt();
                in.readInt();
                in.readInt();
                in.readInt();
                String[] nm = new String[in.readInt()];
                for (int i = 0; i < nm.length; i++) nm[i] = in.readUTF();
                int nj = in.readInt();
                long[] id = new long[nj];
                int[] la = new int[nj], lo = new int[nj];
                for (int i = 0; i < nj; i++) {
                    id[i] = in.readLong();
                    la[i] = in.readInt();
                    lo[i] = in.readInt();
                }
                int ne = in.readInt();
                int[] a = new int[ne], b = new int[ne], kind = new int[ne], flags = new int[ne], name = new int[ne], off = new int[ne + 1];
                float[] len = new float[ne];
                int[] gla = new int[1024], glo = new int[1024];
                int gp = 0;
                for (int i = 0; i < ne; i++) {
                    a[i] = in.readInt();
                    b[i] = in.readInt();
                    kind[i] = in.readByte();
                    flags[i] = in.readByte();
                    name[i] = in.readInt();
                    len[i] = in.readFloat();
                    int np = in.readShort() & 0xffff;
                    off[i] = gp;
                    if (gp + np > gla.length) {
                        int sz = Math.max(gla.length * 2, gp + np);
                        gla = Arrays.copyOf(gla, sz);
                        glo = Arrays.copyOf(glo, sz);
                    }
                    for (int q = 0; q < np; q++) {
                        gla[gp] = in.readInt();
                        glo[gp] = in.readInt();
                        gp++;
                    }
                }
                off[ne] = gp;
                fIds.add(id);
                fNodes.add(new int[][]{la, lo});
                fEdges.add(new int[][]{a, b, kind, flags, name, off});
                fLen.add(len);
                fGeo.add(new int[][]{gla, glo});
                fNames.add(nm);
                totalIds += nj;
            } catch (IOException e) {
                // a damaged file: skip it
            } finally {
                in.close();
            }
        }
        RoadGraph g = new RoadGraph();
        long[] all = new long[(int) totalIds];
        int k = 0;
        for (long[] id : fIds) {
            System.arraycopy(id, 0, all, k, id.length);
            k += id.length;
        }
        Arrays.sort(all);
        int u = 0;
        for (int i = 0; i < all.length; i++) if (i == 0 || all[i] != all[i - 1]) all[u++] = all[i];
        g.n = u;
        g.ids = Arrays.copyOf(all, u);
        g.nLa = new int[u];
        g.nLo = new int[u];
        int m = 0, gpts = 0;
        for (int fi = 0; fi < fIds.size(); fi++) {
            m += fLen.get(fi).length;
            gpts += fEdges.get(fi)[5][fLen.get(fi).length];
        }
        g.m = m;
        g.ea = new int[m];
        g.eb = new int[m];
        g.eName = new int[m];
        g.gOff = new int[m + 1];
        g.eKind = new byte[m];
        g.eFlags = new byte[m];
        g.eLen = new float[m];
        g.gLa = new int[gpts];
        g.gLo = new int[gpts];
        Map<String, Integer> nameIdx = new HashMap<String, Integer>();
        List<String> names = new ArrayList<String>();
        int e = 0, gp = 0;
        for (int fi = 0; fi < fIds.size(); fi++) {
            long[] id = fIds.get(fi);
            int[][] nd = fNodes.get(fi), ed = fEdges.get(fi), geo = fGeo.get(fi);
            float[] len = fLen.get(fi);
            String[] nm = fNames.get(fi);
            int[] map = new int[id.length];
            for (int i = 0; i < id.length; i++) {
                map[i] = Arrays.binarySearch(g.ids, id[i]);
                g.nLa[map[i]] = nd[0][i];
                g.nLo[map[i]] = nd[1][i];
            }
            int[] nameMap = new int[nm.length];
            for (int i = 0; i < nm.length; i++) {
                Integer x = nameIdx.get(nm[i]);
                if (x == null) {
                    x = names.size();
                    names.add(nm[i]);
                    nameIdx.put(nm[i], x);
                }
                nameMap[i] = x;
            }
            for (int i = 0; i < len.length; i++) {
                g.ea[e] = map[ed[0][i]];
                g.eb[e] = map[ed[1][i]];
                g.eKind[e] = (byte) ed[2][i];
                g.eFlags[e] = (byte) ed[3][i];
                g.eName[e] = ed[4][i] >= 0 && ed[4][i] < nameMap.length ? nameMap[ed[4][i]] : -1;
                g.eLen[e] = len[i];
                g.gOff[e] = gp;
                int from = ed[5][i], to = ed[5][i + 1];
                System.arraycopy(geo[0], from, g.gLa, gp, to - from);
                System.arraycopy(geo[1], from, g.gLo, gp, to - from);
                gp += to - from;
                e++;
            }
        }
        g.gOff[m] = gp;
        g.names = names.toArray(new String[0]);
        // Adjacency (both directions; whether a direction may be used is checked while routing).
        int[] deg = new int[u + 1];
        for (int i = 0; i < m; i++) {
            deg[g.ea[i]]++;
            deg[g.eb[i]]++;
        }
        g.adjStart = new int[u + 1];
        for (int i = 0; i < u; i++) g.adjStart[i + 1] = g.adjStart[i] + deg[i];
        g.adj = new int[g.adjStart[u]];
        int[] fill = Arrays.copyOf(g.adjStart, u);
        for (int i = 0; i < m; i++) {
            g.adj[fill[g.ea[i]]++] = i * 2;
            g.adj[fill[g.eb[i]]++] = i * 2 + 1;
        }
        return g;
    }

    // ---- routing ----

    private static double speed(int kind, int mode) {
        int kmh = mode == CAR ? CAR_KMH[kind] : FOOT_KMH[kind];
        return kmh / 3.6;
    }

    private boolean[] eBlocked;

    /**
     * Roads closed for routing (a tree down, a flood): every road piece passing within 25 m of one of
     * these points (lat, lon pairs) is avoided. null or empty = none.
     */
    public synchronized void setBlocked(double[] pts) {
        if (pts == null || pts.length < 2) {
            eBlocked = null;
            return;
        }
        boolean[] b = new boolean[m];
        for (int k = 0; k + 1 < pts.length; k += 2) {
            double lat = pts[k], lon = pts[k + 1];
            double kx = 111320 * Math.cos(Math.toRadians(lat)), ky = 110540;
            for (int e = 0; e < m; e++) {
                if (b[e]) continue;
                double ax = (pLon(e, 0, true) - lon) * kx, ay = (pLat(e, 0, true) - lat) * ky;
                if (Math.sqrt(ax * ax + ay * ay) - eLen[e] > 25) continue;
                int c = pointCount(e);
                for (int i = 1; i < c && !b[e]; i++) {
                    double bx = (pLon(e, i, true) - lon) * kx, by = (pLat(e, i, true) - lat) * ky;
                    double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
                    double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
                    double px = ax + t * dx, py = ay + t * dy;
                    if (Math.sqrt(px * px + py * py) <= 25) b[e] = true;
                    ax = bx;
                    ay = by;
                }
            }
        }
        eBlocked = b;
    }

    /**
     * A route through points in order (lat, lon pairs: start, any via points, destination), the
     * legs joined into one. Null if any leg cannot be found.
     */
    public synchronized Route routeVia(double[] pts, int mode, String destName) {
        int legs = pts.length / 2 - 1;
        if (legs < 1) return null;
        Route all = null;
        for (int i = 0; i < legs; i++) {
            boolean last = i == legs - 1;
            Route r = route(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], mode, last ? destName : "");
            if (r == null) return null;
            if (all == null) {
                all = r;
            } else {
                int a = all.pts.length / 2, b = r.pts.length / 2;
                double base = all.lengthM;
                all.pts = Arrays.copyOf(all.pts, (a + b - 1) * 2);
                System.arraycopy(r.pts, 2, all.pts, a * 2, (b - 1) * 2);
                double[] along = Arrays.copyOf(all.along, a + b - 1);
                for (int k = 1; k < b; k++) along[a - 1 + k] = base + r.along[k];
                all.along = along;
                for (int k = 1; k < r.steps.size(); k++) { // not the new leg's "Head …"
                    Step s = r.steps.get(k);
                    all.steps.add(new Step(base + s.atM, s.lat, s.lon, s.text, s.turn));
                }
                all.lengthM += r.lengthM;
                all.seconds += r.seconds;
                all.endOffM = r.endOffM;
            }
            if (!last) {
                // The leg's arrival becomes "pass the via point".
                Step arrive = all.steps.remove(all.steps.size() - 1);
                all.steps.add(new Step(arrive.atM, arrive.lat, arrive.lon, "Pass via point " + (i + 1), 0));
                all.viaAlong.add(arrive.atM);
            }
        }
        return all;
    }

    private boolean allowed(int e, int dir, int mode) {
        boolean[] bl = eBlocked;
        if (bl != null && bl[e]) return false;
        int f = eFlags[e], kind = eKind[e];
        if (mode == CAR) return CAR_KMH[kind] > 0 && (f & F_NOCAR) == 0 && !((f & F_ONEWAY) != 0 && dir == 1);
        return FOOT_KMH[kind] > 0 && (f & F_NOFOOT) == 0;
    }

    /** One instruction: where (metres from the start, and the point), and what to do. */
    public static final class Step {
        public final double atM, lat, lon;
        public final String text;
        public final int turn; // degrees, negative = left; 999 = arrive

        Step(double atM, double lat, double lon, String text, int turn) {
            this.atM = atM;
            this.lat = lat;
            this.lon = lon;
            this.text = text;
            this.turn = turn;
        }
    }

    public static final class Route {
        /** The way as lat, lon pairs. */
        public double[] pts;
        /** Metres from the start to each point (same count as points). */
        public double[] along;
        public double lengthM, seconds;
        public List<Step> steps = new ArrayList<Step>();
        /** How far the start and end are from the nearest road. */
        public double startOffM, endOffM;
        /** Metres from the start to each via point passed. */
        public List<Double> viaAlong = new ArrayList<Double>();
    }

    /** Where a point meets the nearest usable road: the edge and the metres along it from its a end. */
    private static final class Snap {
        int e = -1;
        double alongM, offM = Double.MAX_VALUE, lat, lon;
    }

    private int pointCount(int e) {
        return gOff[e + 1] - gOff[e] + 2;
    }

    private double pLat(int e, int i, boolean fromA) {
        int c = pointCount(e);
        int j = fromA ? i : c - 1 - i;
        if (j == 0) return nLa[ea[e]] / 1e7;
        if (j == c - 1) return nLa[eb[e]] / 1e7;
        return gLa[gOff[e] + j - 1] / 1e7;
    }

    private double pLon(int e, int i, boolean fromA) {
        int c = pointCount(e);
        int j = fromA ? i : c - 1 - i;
        if (j == 0) return nLo[ea[e]] / 1e7;
        if (j == c - 1) return nLo[eb[e]] / 1e7;
        return gLo[gOff[e] + j - 1] / 1e7;
    }

    private Snap snap(double lat, double lon, int mode) {
        Snap best = new Snap();
        double kx = 111320 * Math.cos(Math.toRadians(lat)), ky = 110540;
        for (int e = 0; e < m; e++) {
            if (!allowed(e, 0, mode) && !allowed(e, 1, mode)) continue;
            int c = pointCount(e);
            double along = 0;
            double ax = (pLon(e, 0, true) - lon) * kx, ay = (pLat(e, 0, true) - lat) * ky;
            // Quick reject: both ends and the length say it cannot be nearer than the best so far.
            double endD = Math.sqrt(ax * ax + ay * ay);
            if (endD - eLen[e] > best.offM) continue;
            for (int i = 1; i < c; i++) {
                double bx = (pLon(e, i, true) - lon) * kx, by = (pLat(e, i, true) - lat) * ky;
                double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
                double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
                double px = ax + t * dx, py = ay + t * dy, d = Math.sqrt(px * px + py * py);
                double seg = Math.sqrt(l2);
                if (d < best.offM) {
                    best.offM = d;
                    best.e = e;
                    best.alongM = along + t * seg;
                    best.lat = lat + py / ky;
                    best.lon = lon + px / kx;
                }
                along += seg;
                ax = bx;
                ay = by;
            }
            // The stored length uses the earth's curve: scale "along" to it.
            if (best.e == e && along > 0) best.alongM = best.alongM * eLen[e] / along;
        }
        return best;
    }

    /** The quickest way, or null if the two points are not joined by usable roads in the loaded data. */
    public Route route(double lat1, double lon1, double lat2, double lon2, int mode, String destName) {
        if (m == 0) return null;
        Snap s = snap(lat1, lon1, mode), t = snap(lat2, lon2, mode);
        if (s.e < 0 || t.e < 0) return null;
        double maxV = mode == CAR ? 100 / 3.6 : 5 / 3.6;
        double[] dist = new double[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        Heap h = new Heap();
        double tLat = t.lat, tLon = t.lon;
        // From the start point to the ends of its road.
        double vs = speed(eKind[s.e], mode);
        if (allowed(s.e, 0, mode)) relax(eb[s.e], (eLen[s.e] - s.alongM) / vs, -2, dist, prev, h, tLat, tLon, maxV);
        if (allowed(s.e, 1, mode)) relax(ea[s.e], s.alongM / vs, -3, dist, prev, h, tLat, tLon, maxV);
        double best = Double.MAX_VALUE;
        int bestNode = -1, bestDir = -1;
        double vt = speed(eKind[t.e], mode);
        boolean direct = false;
        if (s.e == t.e) {
            int dir = t.alongM >= s.alongM ? 0 : 1;
            if (allowed(s.e, dir, mode)) {
                best = Math.abs(t.alongM - s.alongM) / vs;
                direct = true;
            }
        }
        boolean[] done = new boolean[n];
        while (h.size > 0) {
            int u = h.pop();
            if (done[u]) continue;
            if (dist[u] + Geo.distanceM(nLa[u] / 1e7, nLo[u] / 1e7, tLat, tLon) / maxV >= best) break;
            done[u] = true;
            // Onto the target road?
            if (u == ea[t.e] && allowed(t.e, 0, mode)) {
                double c = dist[u] + t.alongM / vt;
                if (c < best) {
                    best = c;
                    bestNode = u;
                    bestDir = 0;
                    direct = false;
                }
            }
            if (u == eb[t.e] && allowed(t.e, 1, mode)) {
                double c = dist[u] + (eLen[t.e] - t.alongM) / vt;
                if (c < best) {
                    best = c;
                    bestNode = u;
                    bestDir = 1;
                    direct = false;
                }
            }
            for (int k = adjStart[u]; k < adjStart[u + 1]; k++) {
                int x = adj[k], e = x >> 1, dir = x & 1;
                if (!allowed(e, dir, mode)) continue;
                int v = dir == 0 ? eb[e] : ea[e];
                if (done[v]) continue;
                relax(v, dist[u] + eLen[e] / speed(eKind[e], mode), x, dist, prev, h, tLat, tLon, maxV);
            }
        }
        if (best == Double.MAX_VALUE) return null;
        // Collect the edges back from the target.
        List<Integer> path = new ArrayList<Integer>();
        int startDir = -1;
        if (!direct) {
            int u = bestNode;
            while (true) {
                int x = prev[u];
                if (x == -2 || x == -3) {
                    startDir = x == -2 ? 0 : 1;
                    break;
                }
                path.add(0, x);
                int e = x >> 1;
                u = (x & 1) == 0 ? ea[e] : eb[e];
            }
        }
        Route r = new Route();
        r.seconds = best;
        r.startOffM = s.offM;
        r.endOffM = t.offM;
        Pts pts = new Pts();
        pts.add(s.lat, s.lon);
        List<int[]> turns = new ArrayList<int[]>(); // point index of each junction, and the edge*2+dir leaving it
        if (direct) {
            partial(pts, s.e, s.alongM, t.alongM);
        } else {
            // Start road: from the start point to the junction it leaves by.
            partial(pts, s.e, s.alongM, startDir == 0 ? eLen[s.e] : 0);
            int arriveX = s.e * 2 + startDir;
            for (int x : path) {
                turns.add(new int[]{pts.size - 1, arriveX, x});
                int e = x >> 1;
                boolean fromA = (x & 1) == 0;
                int c = pointCount(e);
                for (int i = 1; i < c; i++) pts.add(pLat(e, i, fromA), pLon(e, i, fromA));
                arriveX = x;
            }
            turns.add(new int[]{pts.size - 1, arriveX, t.e * 2 + bestDir});
            partial(pts, t.e, bestDir == 0 ? 0 : eLen[t.e], t.alongM);
        }
        pts.add(tLat, tLon);
        r.pts = Arrays.copyOf(pts.v, pts.size * 2);
        r.along = new double[pts.size];
        for (int i = 1; i < pts.size; i++) {
            r.along[i] = r.along[i - 1] + Geo.distanceM(r.pts[i * 2 - 2], r.pts[i * 2 - 1], r.pts[i * 2], r.pts[i * 2 + 1]);
        }
        r.lengthM = r.along[pts.size - 1];
        // Instructions.
        int startEdge = direct ? s.e : s.e;
        String first = nameOf(startEdge);
        r.steps.add(new Step(0, r.pts[0], r.pts[1], "Head " + Geo.compass(bearingAt(r, 0, true)) + (first.length() > 0 ? " on " + first : ""), 0));
        String curName = first;
        // Junctions worth a word: a real turn where roads meet, or a new road name.
        List<int[]> cand = new ArrayList<int[]>(); // point index, edge*2+dir leaving
        for (int[] tn : turns) {
            int pi = tn[0];
            double bIn = bearingAt(r, pi, false), bOut = bearingAt(r, pi, true);
            int turn = (int) Math.round(((bOut - bIn + 540) % 360) - 180);
            String outName = nameOf(tn[2] >> 1);
            int meeting = adjStart[nodeAt(tn) + 1] - adjStart[nodeAt(tn)];
            if ((Math.abs(turn) >= 35 && meeting > 2) || (outName.length() > 0 && !outName.equals(nameOf(tn[1] >> 1)))) {
                cand.add(new int[]{pi, tn[2]});
            }
        }
        // Junctions close together (divided roads, slip lanes) are one manoeuvre.
        for (int i = 0; i < cand.size(); ) {
            int j = i;
            while (j + 1 < cand.size() && r.along[cand.get(j + 1)[0]] - r.along[cand.get(i)[0]] < 35) j++;
            int piA = cand.get(i)[0], piB = cand.get(j)[0];
            double bIn = bearingAt(r, piA, false), bOut = bearingAt(r, piB, true);
            int turn = (int) Math.round(((bOut - bIn + 540) % 360) - 180);
            int at = Math.abs(turn);
            String outName = nameOf(cand.get(j)[1] >> 1);
            boolean nameChange = outName.length() > 0 && !outName.equals(curName);
            if (nameChange || at >= 45) {
                String what = at < 35 ? "Continue" : at < 60 ? (turn < 0 ? "Bear left" : "Bear right")
                        : at < 130 ? (turn < 0 ? "Turn left" : "Turn right") : at < 165 ? (turn < 0 ? "Sharp left" : "Sharp right") : "Make a U-turn";
                r.steps.add(new Step(r.along[piA], r.pts[piA * 2], r.pts[piA * 2 + 1], what + (outName.length() > 0 ? " onto " + outName : ""), turn));
            }
            if (outName.length() > 0) curName = outName;
            i = j + 1;
        }
        r.steps.add(new Step(r.lengthM, tLat, tLon, "Arrive" + (destName != null && destName.length() > 0 ? " at " + destName : ""), 999));
        return r;
    }

    private int nodeAt(int[] tn) {
        int x = tn[2], e = x >> 1;
        return (x & 1) == 0 ? ea[e] : eb[e];
    }

    private String nameOf(int e) {
        return e >= 0 && eName[e] >= 0 && eName[e] < names.length ? names[eName[e]] : "";
    }

    /** Bearing leaving (out) or arriving at (in) point i, using a point about 25 m away to steady it. */
    private static double bearingAt(Route r, int i, boolean out) {
        int cnt = r.pts.length / 2;
        int j = i;
        if (out) {
            do j++; while (j < cnt - 1 && r.along[j] - r.along[i] < 25);
            j = Math.min(j, cnt - 1);
            return Geo.bearing(r.pts[i * 2], r.pts[i * 2 + 1], r.pts[j * 2], r.pts[j * 2 + 1]);
        }
        do j--; while (j > 0 && r.along[i] - r.along[j] < 25);
        j = Math.max(j, 0);
        return Geo.bearing(r.pts[j * 2], r.pts[j * 2 + 1], r.pts[i * 2], r.pts[i * 2 + 1]);
    }

    /** Adds the points of edge e between two distances from its a end (either way round), ending exactly at "to". */
    private void partial(Pts pts, int e, double from, double to) {
        int c = pointCount(e);
        double[] cum = new double[c];
        for (int i = 1; i < c; i++) cum[i] = cum[i - 1] + Geo.distanceM(pLat(e, i - 1, true), pLon(e, i - 1, true), pLat(e, i, true), pLon(e, i, true));
        double scale = cum[c - 1] > 0 ? cum[c - 1] / Math.max(1e-6, eLen[e]) : 1;
        from *= scale;
        to *= scale;
        if (to >= from) {
            for (int i = 1; i < c - 1; i++) if (cum[i] > from && cum[i] < to) pts.add(pLat(e, i, true), pLon(e, i, true));
        } else {
            for (int i = c - 2; i >= 1; i--) if (cum[i] < from && cum[i] > to) pts.add(pLat(e, i, true), pLon(e, i, true));
        }
        // The end point itself.
        int seg = 1;
        while (seg < c - 1 && cum[seg] < to) seg++;
        double segLen = cum[seg] - cum[seg - 1];
        double f = segLen <= 0 ? 0 : (to - cum[seg - 1]) / segLen;
        f = Math.max(0, Math.min(1, f));
        pts.add(pLat(e, seg - 1, true) + f * (pLat(e, seg, true) - pLat(e, seg - 1, true)),
                pLon(e, seg - 1, true) + f * (pLon(e, seg, true) - pLon(e, seg - 1, true)));
    }

    private void relax(int v, double c, int via, double[] dist, int[] prev, Heap h, double tLat, double tLon, double maxV) {
        if (c >= dist[v]) return;
        dist[v] = c;
        prev[v] = via;
        h.push(v, c + Geo.distanceM(nLa[v] / 1e7, nLo[v] / 1e7, tLat, tLon) / maxV);
    }

    private static final class Pts {
        double[] v = new double[512];
        int size;

        void add(double lat, double lon) {
            if (size > 0 && v[size * 2 - 2] == lat && v[size * 2 - 1] == lon) return;
            if (size * 2 + 2 > v.length) v = Arrays.copyOf(v, v.length * 2);
            v[size * 2] = lat;
            v[size * 2 + 1] = lon;
            size++;
        }
    }

    /** A binary min-heap of (priority, node); duplicates are allowed and skipped when popped. */
    private static final class Heap {
        double[] key = new double[1024];
        int[] val = new int[1024];
        int size;

        void push(int v, double k) {
            if (size == key.length) {
                key = Arrays.copyOf(key, size * 2);
                val = Arrays.copyOf(val, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (key[p] <= k) break;
                key[i] = key[p];
                val[i] = val[p];
                i = p;
            }
            key[i] = k;
            val[i] = v;
        }

        int pop() {
            int top = val[0];
            double k = key[--size];
            int v = val[size];
            int i = 0;
            while (true) {
                int c = i * 2 + 1;
                if (c >= size) break;
                if (c + 1 < size && key[c + 1] < key[c]) c++;
                if (key[c] >= k) break;
                key[i] = key[c];
                val[i] = val[c];
                i = c;
            }
            key[i] = k;
            val[i] = v;
            return top;
        }
    }

    // ---- a small streaming JSON reader (numbers come back as text) ----

    static final class Json {
        private final BufferedReader r;
        private int peeked = -2;
        private final StringBuilder sb = new StringBuilder();

        Json(BufferedReader r) {
            this.r = r;
        }

        private int read() throws IOException {
            if (peeked != -2) {
                int c = peeked;
                peeked = -2;
                return c;
            }
            return r.read();
        }

        private int peekChar() throws IOException {
            if (peeked == -2) peeked = r.read();
            return peeked;
        }

        private int next() throws IOException {
            int c;
            do c = read(); while (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':');
            return c;
        }

        private int peekToken() throws IOException {
            int c;
            while (true) {
                c = peekChar();
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':') read();
                else return c;
            }
        }

        void beginObject() throws IOException {
            expect('{');
        }

        void endObject() throws IOException {
            expect('}');
        }

        void beginArray() throws IOException {
            expect('[');
        }

        void endArray() throws IOException {
            expect(']');
        }

        private void expect(char c) throws IOException {
            int got = next();
            if (got != c) throw new IOException("bad map data (expected " + c + ", got " + (got < 0 ? "end" : String.valueOf((char) got)) + ")");
        }

        boolean hasNext() throws IOException {
            int c = peekToken();
            return c != '}' && c != ']' && c >= 0;
        }

        String nextName() throws IOException {
            return nextString();
        }

        /** A string, number, true/false or null, as text. */
        String nextString() throws IOException {
            int c = next();
            sb.setLength(0);
            if (c == '"') {
                while (true) {
                    c = read();
                    if (c < 0) throw new IOException("bad map data (unfinished text)");
                    if (c == '"') break;
                    if (c == '\\') {
                        c = read();
                        switch (c) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u': {
                                char[] hx = new char[4];
                                for (int i = 0; i < 4; i++) hx[i] = (char) read();
                                sb.append((char) Integer.parseInt(new String(hx), 16));
                                break;
                            }
                            default: sb.append((char) c);
                        }
                    } else sb.append((char) c);
                }
                return sb.toString();
            }
            sb.append((char) c);
            while (true) {
                int p = peekChar();
                if (p < 0 || p == ',' || p == '}' || p == ']' || p == ' ' || p == '\n' || p == '\r' || p == '\t' || p == ':') break;
                sb.append((char) read());
            }
            return sb.toString();
        }

        void skipValue() throws IOException {
            int c = peekToken();
            if (c == '{' || c == '[') {
                read();
                int depth = 1;
                boolean inStr = false;
                while (depth > 0) {
                    int x = read();
                    if (x < 0) throw new IOException("bad map data");
                    if (inStr) {
                        if (x == '\\') read();
                        else if (x == '"') inStr = false;
                    } else if (x == '"') inStr = true;
                    else if (x == '{' || x == '[') depth++;
                    else if (x == '}' || x == ']') depth--;
                }
            } else {
                nextString();
            }
        }
    }
}
