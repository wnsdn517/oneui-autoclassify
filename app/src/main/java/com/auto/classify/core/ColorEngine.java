package com.auto.classify.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Icon color analysis. Every method reduces an icon to something groupable:
 * <ul>
 *   <li>POSITION   – color at a chosen point of the icon (x%, y%), 3x3 averaged</li>
 *   <li>AVERAGE    – mean of all opaque pixels</li>
 *   <li>MODE       – most frequent color after 4-bit-per-channel quantization</li>
 *   <li>CENTROID   – center of the largest cluster of a chroma-weighted k-means (the "main" color)</li>
 *   <li>SPREAD     – color standard deviation: 단색 / 투톤 / 다채로움</li>
 *   <li>SIMILARITY – apps clustered by their main color in Lab space into N groups</li>
 * </ul>
 * The first four end in a hue-named folder (빨강 … 분홍, achromatic icons in 흑백).
 */
public final class ColorEngine {

    public enum Method { POSITION, AVERAGE, MODE, CENTROID, SPREAD, SIMILARITY }

    public static final String ACHROMATIC = "흑백";
    private static final int SIZE = 48;

    // Pink and magenta count as red: people group them together.
    private static final float[] HUE_EDGES = {15, 40, 65, 160, 195, 255, 300, 360};
    private static final String[] HUE_NAMES = {"빨강", "주황", "노랑", "초록", "청록", "파랑", "보라", "빨강"};

    private ColorEngine() {
    }

    /** Icon rendered to 48x48 ARGB pixels. */
    public static int[] pixels(Drawable icon) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        icon.setBounds(0, 0, SIZE, SIZE);
        icon.draw(c);
        int[] px = new int[SIZE * SIZE];
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        bmp.recycle();
        return px;
    }

    /** Folder name for one icon by a per-icon method (not SIMILARITY). Null for a blank icon. */
    public static String classify(int[] px, Method method, float x, float y) {
        if (method == Method.SPREAD) return spread(px);
        Integer color = representative(px, method, x, y);
        return color == null ? null : hueName(color);
    }

    public static Integer representative(int[] px, Method method, float x, float y) {
        switch (method) {
            case POSITION:
                Integer at = sample(px, x, y);
                return at != null ? at : average(px);
            case AVERAGE:
                return average(withoutPlate(px));
            case MODE:
                return mode(withoutPlate(px));
            default:
                return centroid(withoutPlate(px));
        }
    }

    /**
     * Drops a plain white, gray or black plate behind the glyph, so the plate does not decide the
     * group (it would otherwise match the page background). Icons whose corners are colored, or
     * where the plate is most of the icon, are returned unchanged.
     */
    static int[] withoutPlate(int[] px) {
        int[] corners = {px[0], px[SIZE - 1], px[SIZE * (SIZE - 1)], px[SIZE * SIZE - 1]};
        long r = 0, g = 0, b = 0;
        for (int p : corners) {
            if (Color.alpha(p) < 128) return px;
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
        }
        int pr = (int) (r / 4), pg = (int) (g / 4), pb = (int) (b / 4);
        float[] hsv = new float[3];
        Color.RGBToHSV(pr, pg, pb, hsv);
        if (hsv[1] * hsv[2] >= 0.18f) return px;
        int[] out = px.clone();
        int opaque = 0, kept = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (Color.alpha(p) < 128) continue;
            opaque++;
            boolean plate = Math.abs(Color.red(p) - pr) <= 24 && Math.abs(Color.green(p) - pg) <= 24
                    && Math.abs(Color.blue(p) - pb) <= 24;
            if (plate) out[i] = 0;
            else kept++;
        }
        return kept * 10 >= opaque ? out : px;
    }

    public static String hueName(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] * hsv[2] < 0.18f) return ACHROMATIC;
        for (int i = 0; i < HUE_EDGES.length; i++) if (hsv[0] < HUE_EDGES[i]) return HUE_NAMES[i];
        return HUE_NAMES[0];
    }

    // ---- per-icon reductions --------------------------------------------------------------

    private static Integer sample(int[] px, float x, float y) {
        int cx = Math.round(clamp01(x) * (SIZE - 1)), cy = Math.round(clamp01(y) * (SIZE - 1));
        long r = 0, g = 0, b = 0, n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int xx = cx + dx, yy = cy + dy;
                if (xx < 0 || yy < 0 || xx >= SIZE || yy >= SIZE) continue;
                int p = px[yy * SIZE + xx];
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
        }
        return n == 0 ? null : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static Integer average(int[] px) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
            n++;
        }
        return n == 0 ? null : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /**
     * Most frequent 4-bit-per-channel color. White plates and gray outlines usually win a plain
     * count, so they are skipped whenever the icon has a fair share of colored pixels.
     */
    private static Integer mode(int[] px) {
        int opaque = 0, colored = 0;
        float[] hsv = new float[3];
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            opaque++;
            Color.colorToHSV(p, hsv);
            if (hsv[1] * hsv[2] >= 0.18f) colored++;
        }
        boolean skipGray = colored >= opaque * CHROMATIC_SHARE;
        int[] bins = new int[4096];
        int best = -1;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            if (skipGray) {
                Color.colorToHSV(p, hsv);
                if (hsv[1] * hsv[2] < 0.18f) continue;
            }
            int k = ((Color.red(p) >> 4) << 8) | ((Color.green(p) >> 4) << 4) | (Color.blue(p) >> 4);
            if (++bins[k] > (best < 0 ? 0 : bins[best])) best = k;
        }
        if (best < 0) return null;
        return Color.rgb(((best >> 8) << 4) + 8, (((best >> 4) & 15) << 4) + 8, ((best & 15) << 4) + 8);
    }

    /** Share of colored pixels below which an icon counts as black/white/gray. */
    private static final float CHROMATIC_SHARE = 0.10f;

    /**
     * Center of the heaviest colored cluster of a 4-means over opaque pixels. White plates and
     * gray outlines usually outnumber the brand color, so achromatic clusters only win when the
     * icon has almost no color at all.
     */
    private static Integer centroid(int[] px) {
        List<float[]> pts = new ArrayList<>();
        int colored = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            float r = Color.red(p) / 255f, g = Color.green(p) / 255f, b = Color.blue(p) / 255f;
            float chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            if (chroma >= 0.2f) colored++;
            pts.add(new float[]{r, g, b, 0.3f + chroma});
        }
        if (pts.isEmpty()) return null;
        float[][] centers = kmeans(pts, 4, 3);
        float[] weight = new float[centers.length];
        for (float[] p : pts) weight[nearest(centers, p, 3)] += p[3];
        boolean preferColor = colored >= pts.size() * CHROMATIC_SHARE;
        int best = -1;
        for (int i = 0; i < centers.length; i++) {
            float[] c = centers[i];
            float chroma = Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
            if (preferColor && chroma < 0.15f) continue;
            if (best < 0 || weight[i] > weight[best]) best = i;
        }
        if (best < 0) best = 0;
        float[] c = centers[best];
        return Color.rgb(Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255));
    }

    /** Mean per-channel standard deviation of opaque pixels, bucketed. */
    static String spread(int[] px) {
        double[] sum = new double[3], sq = new double[3];
        int n = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            int[] ch = {Color.red(p), Color.green(p), Color.blue(p)};
            for (int i = 0; i < 3; i++) {
                sum[i] += ch[i];
                sq[i] += (double) ch[i] * ch[i];
            }
            n++;
        }
        if (n == 0) return null;
        double sd = 0;
        for (int i = 0; i < 3; i++) {
            double mean = sum[i] / n;
            sd += Math.sqrt(Math.max(0, sq[i] / n - mean * mean));
        }
        sd = sd / 3 / 255;
        return sd < 0.12 ? "단색" : sd < 0.25 ? "투톤" : "다채로움";
    }

    // ---- similarity clustering ------------------------------------------------------------

    /** Result of clustering: group name per package and the group centers (Lab) for later installs. */
    public static final class Groups {
        public final Map<String, String> byPackage = new LinkedHashMap<>();
        public final List<float[]> centers = new ArrayList<>();
        public final List<String> names = new ArrayList<>();
    }

    /** Below this Lab chroma an icon reads as gray/white/black. */
    private static final float GRAY_CHROMA = 14f;

    /**
     * Space the clustering runs in: hue matters most, so chromatic colors sit on a ring of
     * radius 50 (a/b direction only) and lightness counts at 0.35; grays stay near the center.
     * Plain Lab k-means split "blue" into light/dark blues and grays into three gray groups.
     */
    private static float[] simSpace(float[] lab) {
        float chroma = (float) Math.hypot(lab[1], lab[2]);
        float l = lab[0] * 0.35f;
        if (chroma < GRAY_CHROMA) return new float[]{lab[1] * 0.5f, lab[2] * 0.5f, l};
        return new float[]{lab[1] / chroma * 50, lab[2] / chroma * 50, l};
    }

    /** Folder name for a set of Lab colors: hue name, or 흰색/회색/검정 for grays. */
    private static String groupName(float[] meanLab) {
        float chroma = (float) Math.hypot(meanLab[1], meanLab[2]);
        if (chroma < GRAY_CHROMA) return meanLab[0] >= 75 ? "흰색" : meanLab[0] <= 30 ? "검정" : "회색";
        return hueName(labToColor(meanLab));
    }

    /**
     * Groups icons by color similarity. Clusters with the same color name become "밝은 X" /
     * "짙은 X" when their lightness clearly differs, otherwise they are merged; never "X 2".
     */
    public static Groups similarity(Map<String, Integer> mainColors, int groups) {
        Groups out = new Groups();
        List<float[]> pts = new ArrayList<>();
        List<float[]> labs = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : mainColors.entrySet()) {
            if (e.getValue() == null) continue;
            float[] lab = lab(e.getValue());
            float[] s = simSpace(lab);
            pts.add(new float[]{s[0], s[1], s[2], 1});
            labs.add(lab);
            pkgs.add(e.getKey());
        }
        if (pts.isEmpty()) return out;
        int k = Math.max(1, Math.min(groups, pts.size()));
        float[][] centers = kmeans(pts, k, 3);

        // Mean real Lab per cluster, for naming.
        int[] member = new int[pts.size()];
        float[][] mean = new float[k][3];
        int[] count = new int[k];
        for (int i = 0; i < pts.size(); i++) {
            int c = nearest(centers, pts.get(i), 3);
            member[i] = c;
            count[c]++;
            for (int d = 0; d < 3; d++) mean[c][d] += labs.get(i)[d];
        }
        String[] names = new String[k];
        Map<String, List<Integer>> byBase = new LinkedHashMap<>();
        for (int c = 0; c < k; c++) {
            if (count[c] == 0) continue;
            for (int d = 0; d < 3; d++) mean[c][d] /= count[c];
            byBase.computeIfAbsent(groupName(mean[c]), x -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<Integer>> e : byBase.entrySet()) {
            List<Integer> cs = e.getValue();
            String base = e.getKey();
            for (int c : cs) names[c] = base;
            if (cs.size() < 2 || base.equals("흰색") || base.equals("검정") || base.equals("회색")) continue;
            cs.sort((a, b) -> Float.compare(mean[a][0], mean[b][0]));
            int dark = cs.get(0), light = cs.get(cs.size() - 1);
            if (mean[light][0] - mean[dark][0] >= 18) {
                names[dark] = "짙은 " + base;
                names[light] = "밝은 " + base;
                // Anything in between joins whichever end it is closer to.
                for (int i = 1; i < cs.size() - 1; i++) {
                    int c = cs.get(i);
                    names[c] = mean[c][0] - mean[dark][0] < mean[light][0] - mean[c][0] ? names[dark] : names[light];
                }
            }
        }
        for (int c = 0; c < k; c++) {
            if (names[c] == null) continue;
            out.centers.add(centers[c]);
            out.names.add(names[c]);
        }
        for (int i = 0; i < pts.size(); i++) out.byPackage.put(pkgs.get(i), names[member[i]]);
        return out;
    }

    /** Group of an icon's main color given previously computed centers. */
    public static String nearestGroup(int color, List<float[]> centers, List<String> names) {
        if (centers.isEmpty()) return hueName(color);
        return names.get(nearest(centers.toArray(new float[0][]), simSpace(lab(color)), 3));
    }

    // ---- math -----------------------------------------------------------------------------

    /** Weighted k-means (weight in index 3 when present), k-means++ style deterministic seeding. */
    private static float[][] kmeans(List<float[]> pts, int k, int dims) {
        k = Math.min(k, pts.size());
        float[][] centers = new float[k][];
        centers[0] = pts.get(0).clone();
        for (int c = 1; c < k; c++) {
            float bestD = -1;
            float[] pick = pts.get(0);
            for (float[] p : pts) {
                float d = dist(centers[nearest(centers, p, dims, c)], p, dims);
                if (d > bestD) {
                    bestD = d;
                    pick = p;
                }
            }
            centers[c] = pick.clone();
        }
        for (int iter = 0; iter < 12; iter++) {
            float[][] sum = new float[k][dims];
            float[] w = new float[k];
            for (float[] p : pts) {
                int i = nearest(centers, p, dims);
                float pw = p.length > dims ? p[dims] : 1;
                for (int d = 0; d < dims; d++) sum[i][d] += p[d] * pw;
                w[i] += pw;
            }
            for (int i = 0; i < k; i++) {
                if (w[i] == 0) continue;
                for (int d = 0; d < dims; d++) centers[i][d] = sum[i][d] / w[i];
            }
        }
        return centers;
    }

    private static int nearest(float[][] centers, float[] p, int dims) {
        return nearest(centers, p, dims, centers.length);
    }

    private static int nearest(float[][] centers, float[] p, int dims, int count) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float d = dist(centers[i], p, dims);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static float dist(float[] a, float[] b, int dims) {
        float s = 0;
        for (int d = 0; d < dims; d++) s += (a[d] - b[d]) * (a[d] - b[d]);
        return s;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static float[] lab(int color) {
        double r = lin(Color.red(color) / 255.0), g = lin(Color.green(color) / 255.0), b = lin(Color.blue(color) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new float[]{(float) (116 * fy - 16), (float) (500 * (fx - fy)), (float) (200 * (fy - fz))};
    }

    static int labToColor(float[] lab) {
        double fy = (lab[0] + 16) / 116.0, fx = fy + lab[1] / 500.0, fz = fy - lab[2] / 200.0;
        double x = 0.95047 * finv(fx), y = finv(fy), z = 1.08883 * finv(fz);
        double r = 3.2406 * x - 1.5372 * y - 0.4986 * z;
        double g = -0.9689 * x + 1.8758 * y + 0.0415 * z;
        double b = 0.0557 * x - 0.2040 * y + 1.0570 * z;
        return Color.rgb(gamma(r), gamma(g), gamma(b));
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int gamma(double c) {
        c = Math.max(0, Math.min(1, c));
        double v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(v * 255);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static double finv(double t) {
        double t3 = t * t * t;
        return t3 > 216.0 / 24389 ? t3 : (116 * t - 16) / (24389.0 / 27);
    }
}
