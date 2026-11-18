package com.jamhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 純 Java 畫面辨識（不依賴 Android），輸入 ARGB 像素陣列。
 * 所有距離都以螢幕寬度 W 的比例表示，以適應不同解析度。
 */
public final class Detector {

    public static final class Jar {
        public final int x, y;
        public final float[] color; // (s*cos h, s*sin h, v)
        Jar(int x, int y, float[] color) { this.x = x; this.y = y; this.color = color; }
        @Override public String toString() { return "Jar(" + x + "," + y + "," + ColorUtil.name(color) + ")"; }
    }

    public static final class Row {
        public int top, bottom;
        public int left, right;
        public int n;
        public int[] slotX;
        public boolean[] green;
        public boolean[] filled;
        public float[][] color; // 放在格子上的瓶子（或碎掉的顏色）
    }

    public static final class Scene {
        public int W, H;
        public int trayTop;
        public final List<Jar> tray = new ArrayList<>();
        public Row active;    // 最下面那排（目前要放的那排）
        public Row previous;  // 上一排（上一輪結果，可能為 null）
        public String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append(active.n).append('|');
            for (Jar j : tray) sb.append(j.x / 12).append(ColorUtil.name(j.color)).append(',');
            sb.append('|');
            for (int i = 0; i < active.n; i++) sb.append(active.green[i] ? 'G' : active.filled[i] ? 'F' : '_');
            return sb.toString();
        }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("tray=").append(tray).append(" active=");
            appendRow(sb, active);
            if (previous != null) { sb.append(" prev="); appendRow(sb, previous); }
            return sb.toString();
        }
        private static void appendRow(StringBuilder sb, Row r) {
            sb.append('[');
            for (int i = 0; i < r.n; i++) {
                sb.append(r.green[i] ? "G:" : r.filled[i] ? "F:" : "_:");
                sb.append(r.color[i] == null ? "-" : ColorUtil.name(r.color[i]));
                if (i < r.n - 1) sb.append(' ');
            }
            sb.append(']');
        }
    }

    private final int[] px;
    private final int W, H;

    private Detector(int[] px, int w, int h) { this.px = px; this.W = w; this.H = h; }

    public static Scene analyze(int[] argb, int w, int h) {
        return new Detector(argb, w, h).run();
    }

    private int p(int x, int y) { return px[y * W + x]; }
    private static int R(int c) { return (c >> 16) & 255; }
    private static int G(int c) { return (c >> 8) & 255; }
    private static int B(int c) { return c & 255; }

    private static boolean isTray(int c) { int r = R(c), g = G(c), b = B(c); return b > r + 40 && b > g + 20 && b > 90; }
    private static boolean isBeige(int c) { int r = R(c), g = G(c), b = B(c); return r > 150 && r - b < 80 && r - b > 15 && g > 120; }
    private static boolean isGreen(int c) { int r = R(c), g = G(c), b = B(c); return g > r + 35 && g > b + 40 && g > 90; }
    private static boolean isTile(int c) { return isBeige(c) || isGreen(c); }
    /** 玻璃瓶的亮邊：亮、低飽和 */
    private static boolean isGlass(int c) {
        int r = R(c), g = G(c), b = B(c);
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx > 158 && (mx - mn) < 0.32f * mx;
    }
    /** 木頭桌面 */
    private static boolean isWood(int c) {
        float[] hsv = ColorUtil.hsv(c);
        return hsv[0] > 8 && hsv[0] < 32 && hsv[1] > 0.45f && hsv[2] < 0.72f;
    }

    private Scene run() {
        Scene s = new Scene();
        s.W = W; s.H = H;
        int trayTop = findTrayTop();
        if (trayTop < 0) return null;
        s.trayTop = trayTop;
        findTrayJars(s, trayTop);
        Row active = findRow(trayTop - 3);
        if (active == null) return null;
        // 幾何檢查：平台在托盤上方不遠、磚塊高度與寬度合理，排除活動首頁等其他畫面
        float gapW = (trayTop - active.bottom) / (float) W;
        float tileH = (active.bottom - active.top) / (float) W;
        float pitch = (active.right - active.left + 1) / (float) active.n / W;
        if (gapW < 0.04f || gapW > 0.20f || tileH < 0.045f || tileH > 0.12f || pitch < 0.08f || pitch > 0.19f) return null;
        s.active = active;
        int gap = (int) (W * 0.15f);
        s.previous = findRowAbove(active.top - gap, active);
        return s;
    }

    private int findTrayTop() {
        int step = Math.max(1, (int) (W * 0.05f));
        for (int y = (int) (H * 0.5f); y < H - (int) (W * 0.15f); y++) {
            int cnt = 0, tot = 0;
            for (int x = (int) (W * 0.02f); x < W * 0.98f; x += step) { tot++; if (isTray(p(x, y))) cnt++; }
            if (cnt >= tot * 0.6f) {
                // 往下 0.15W 也要大致是托盤
                int y2 = y + (int) (W * 0.15f), cnt2 = 0;
                for (int x = (int) (W * 0.02f); x < W * 0.98f; x += step) if (isTray(p(x, y2)) || isGlass(p(x, y2))) cnt2++;
                if (cnt2 >= tot * 0.4f) return y;
            }
        }
        return -1;
    }

    private void findTrayJars(Scene s, int trayTop) {
        int y0 = trayTop + (int) (W * 0.02f);
        int y1 = Math.min(H - 1, trayTop + (int) (W * 0.24f));
        int[] col = new int[W];
        for (int x = 0; x < W; x++) {
            int c = 0;
            for (int y = y0; y < y1; y += 2) if (isGlass(p(x, y))) c++;
            col[x] = c;
        }
        int bridge = Math.max(2, (int) (W * 0.006f));
        int minW = (int) (W * 0.05f), maxW = (int) (W * 0.16f);
        int x = 0;
        while (x < W) {
            if (col[x] >= 2) {
                int st = x, last = x;
                while (x < W) {
                    if (col[x] >= 2) last = x;
                    else if (x - last > bridge) break;
                    x++;
                }
                int en = last + 1;
                if (en - st >= minW && en - st <= maxW) addTrayJar(s, st, en, y0, y1);
            }
            x++;
        }
    }

    private void addTrayJar(Scene s, int st, int en, int y0, int y1) {
        int top = -1, bot = -1;
        for (int y = y0; y < y1; y += 2) {
            for (int xx = st; xx < en; xx += 3) if (isGlass(p(xx, y))) { if (top < 0) top = y; bot = y; break; }
        }
        if (top < 0 || bot - top < W * 0.06f) return;
        int cx = (st + en) / 2, half = Math.max(3, (en - st) / 4);
        float[] c = sampleColor(cx - half, cx + half, top + (bot - top) / 3, bot - (bot - top) / 8, false);
        if (c == null) return;
        s.tray.add(new Jar(cx, (top + bot) / 2, c));
    }

    /** 取區域中飽和像素的平均顏色特徵；太少飽和像素回傳 null */
    private float[] sampleColor(int x0, int x1, int y0, int y1, boolean excludeWood) {
        x0 = Math.max(0, x0); x1 = Math.min(W - 1, x1); y0 = Math.max(0, y0); y1 = Math.min(H - 1, y1);
        double a = 0, b = 0, v = 0; int n = 0, total = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            total++;
            int c = p(x, y);
            float[] hsv = ColorUtil.hsv(c);
            if (hsv[1] > 0.5f && hsv[2] > 0.35f) {
                if (excludeWood && isWood(c)) continue;
                if (isTray(c) && hsv[1] < 0.62f) continue;
                double rad = Math.toRadians(hsv[0]);
                a += hsv[1] * Math.cos(rad); b += hsv[1] * Math.sin(rad); v += hsv[2]; n++;
            }
        }
        if (total == 0 || n < Math.max(12, total * 0.25)) return null;
        return new float[]{(float) (a / n), (float) (b / n), (float) (v / n)};
    }

    private int countTileCols(int y, int[] cols) {
        int n = 0; for (int x : cols) if (isTile(p(x, y))) n++; return n;
    }

    /** 從 yStart 往上找第一個平台（磚塊列） */
    private Row findRow(int yStart) {
        int[] cols = new int[]{(int) (W * 0.30f), (int) (W * 0.40f), (int) (W * 0.50f), (int) (W * 0.60f), (int) (W * 0.70f)};
        int yb = -1;
        int yMin = (int) (H * 0.3f);
        for (int y = yStart; y > yMin; y--) if (countTileCols(y, cols) >= 3) { yb = y; break; }
        if (yb < 0) return null;
        int yt = yb;
        while (yt > yMin && countTileCols(yt - 1, cols) >= 2) yt--;
        if (yb - yt < W * 0.03f) return null;
        return buildRow(yt, yb);
    }

    private Row findRowAbove(int yStart, Row below) {
        int limit = below.top - (int) (W * 0.40f);
        int[] cols = new int[below.n];
        System.arraycopy(below.slotX, 0, cols, 0, below.n);
        for (int y = yStart; y > Math.max(limit, (int) (H * 0.3f)); y--) {
            if (countTileCols(y, cols) >= Math.max(2, (cols.length + 1) / 2)) {
                int yb = y, yt = y;
                while (yt > 0 && countTileCols(yt - 1, cols) >= Math.max(1, cols.length / 2)) yt--;
                if (yb - yt < W * 0.03f) return null;
                Row r = buildRow(yt, yb);
                if (r == null || r.n != below.n) return null;
                return r;
            }
        }
        return null;
    }

    /** 用磚塊之間的暗縫數格數；找不到縫時用寬度估計（每格約 0.15W） */
    private int countTiles(int L, int Rr, int yf) {
        int len = Rr - L + 1;
        float[] lum = new float[len];
        for (int i = 0; i < len; i++) {
            float sum = 0; int c = 0;
            for (int dy = -8; dy <= 8; dy += 4) {
                int y = Math.max(0, Math.min(H - 1, yf + dy));
                int p = p(L + i, y); sum += (R(p) + G(p) + B(p)) / 3f; c++;
            }
            lum[i] = sum / c;
        }
        int k = Math.max(3, (int) (W * 0.005f));
        int margin = (int) (W * 0.03f), minGap = (int) (W * 0.05f);
        int[] seam = new int[16]; int seams = 0, lastSeam = -minGap;
        for (int i = Math.max(2 * k, margin); i < len - Math.max(2 * k, margin) && seams < 16; i++) {
            float v = lum[i];
            boolean isMin = true;
            for (int j = i - k; j <= i + k; j++) if (lum[j] < v) { isMin = false; break; }
            if (!isMin) continue;
            float left = 0, right = 0;
            for (int j = 1; j <= k; j++) { left += lum[i - k - j]; right += lum[i + k + j]; }
            left /= k; right /= k;
            if (left - v > Math.max(15, left * 0.2f) && right - v > Math.max(15, right * 0.2f) && i - lastSeam >= minGap) {
                seam[seams++] = i; lastSeam = i;
            }
        }
        float byWidth = len / (W * 0.150f);
        // 找出能解釋所有暗縫位置的格數，取最接近寬度估計的那個
        int best = Math.round(byWidth); float bestDiff = 99;
        for (int n = 2; n <= 10; n++) {
            float pitch = len / (float) n;
            boolean ok = true;
            for (int i = 0; i < seams && ok; i++) {
                float q = seam[i] / pitch;
                if (Math.abs(q - Math.round(q)) * pitch > W * 0.02f) ok = false;
            }
            if (!ok) continue;
            float d = Math.abs(n - byWidth);
            if (d < bestDiff) { bestDiff = d; best = n; }
        }
        return best;
    }

    private Row buildRow(int yt, int yb) {
        int yf = yb - (int) ((yb - yt) * 0.25f);
        // 從畫面中央附近的磚塊往左右延伸（容許磚塊間的細縫），避免被其他物件干擾
        int seed = -1;
        for (int d = 0; d < W * 0.15f && seed < 0; d++) {
            if (isTile(p(W / 2 + d, yf))) seed = W / 2 + d;
            else if (isTile(p(W / 2 - d, yf))) seed = W / 2 - d;
        }
        if (seed < 0) return null;
        int bridge = (int) (W * 0.02f);
        int L = seed, Rr = seed;
        for (int x = seed, miss = 0; x >= 0 && miss <= bridge; x--) { if (isTile(p(x, yf))) { L = x; miss = 0; } else miss++; }
        for (int x = seed, miss = 0; x < W && miss <= bridge; x++) { if (isTile(p(x, yf))) { Rr = x; miss = 0; } else miss++; }
        int n = countTiles(L, Rr, yf);
        if (n < 2 || n > 10) return null;
        Row r = new Row();
        r.top = yt; r.bottom = yb; r.left = L; r.right = Rr; r.n = n;
        r.slotX = new int[n]; r.green = new boolean[n]; r.filled = new boolean[n]; r.color = new float[n][];
        float sw = (Rr - L + 1) / (float) n;
        int half = (int) (sw * 0.25f);
        int jarH = (int) (W * 0.10f);
        for (int i = 0; i < n; i++) {
            int cx = (int) (L + sw * (i + 0.5f));
            r.slotX[i] = cx;
            int g = 0, t = 0;
            for (int x = cx - half; x < cx + half; x += 2) { t++; if (isGreen(p(x, yf))) g++; }
            r.green[i] = g > t * 0.5f;
            // 格子上方：瓶子（有玻璃亮邊）或碎掉的果醬
            int glass = 0;
            for (int y = yt - jarH; y < yt; y += 2) for (int x = cx - half; x < cx + half; x += 3) if (y >= 0 && isGlass(p(x, y))) glass++;
            float[] c = sampleColor(cx - half, cx + half, yt - (int) (jarH * 0.75f), yt - (int) (jarH * 0.1f), true);
            r.color[i] = c;
            r.filled[i] = c != null && glass >= 6;
        }
        return r;
    }
}
