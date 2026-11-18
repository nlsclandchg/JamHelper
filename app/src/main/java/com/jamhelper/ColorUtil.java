package com.jamhelper;

public final class ColorUtil {
    private ColorUtil() {}

    /** 回傳 {h(0-360), s(0-1), v(0-1)} */
    public static float[] hsv(int c) {
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        float h;
        if (d == 0) h = 0;
        else if (mx == r) h = 60 * (((g - b) / d) % 6);
        else if (mx == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new float[]{h, mx == 0 ? 0 : d / mx, mx};
    }

    public static float hue(float[] f) {
        float h = (float) Math.toDegrees(Math.atan2(f[1], f[0]));
        return h < 0 ? h + 360 : h;
    }

    public static float hueDiff(float[] a, float[] b) {
        float d = Math.abs(hue(a) - hue(b));
        return Math.min(d, 360 - d);
    }

    /**
     * 顏色距離：只看色相（每 30 度算 1）。
     * 實測同色瓶在托盤／平台／發光時亮度差很多，但色相差不到 7 度；
     * 紅與粉紅相差約 16~20 度，所以只用色相最穩定。
     */
    public static float dist(float[] a, float[] b) {
        return hueDiff(a, b) / 30f;
    }

    /** 只用來顯示與除錯的大致色名 */
    public static String name(float[] f) {
        float h = hue(f);
        String n;
        if (h < 12 || h >= 345) n = "紅";
        else if (h < 45) n = "橘";
        else if (h < 70) n = "黃";
        else if (h < 165) n = "綠";
        else if (h < 205) n = "青";
        else if (h < 250) n = "藍";
        else if (h < 300) n = "紫";
        else n = "粉";
        return n + Math.round(h);
    }
}
