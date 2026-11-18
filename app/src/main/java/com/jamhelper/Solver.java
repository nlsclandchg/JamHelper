package com.jamhelper;

/**
 * 依「已確定位置」與「已知錯誤位置」，在所有仍可能的排列中，
 * 選出預期命中數最高的一組放法（每次放瓶都要扣果醬，命中越多越省）。
 */
public final class Solver {
    private Solver() {}

    private static final long LIMIT = 300_000;

    /**
     * @param n        格數 = 顏色數
     * @param assigned assigned[slot] = 顏色編號（已確定或本輪已放下），-1 = 待決定
     * @param banned   banned[slot][color] = 該顏色放這格已確認錯誤
     * @return plan[slot] = 顏色；已指定的格子沿用 assigned；條件矛盾時回傳 null
     */
    public static int[] plan(int n, int[] assigned, boolean[][] banned) {
        boolean[] usedColor = new boolean[n];
        int free = 0;
        for (int s = 0; s < n; s++) { if (assigned[s] >= 0) usedColor[assigned[s]] = true; else free++; }
        int[] slots = new int[free];
        for (int s = 0, k = 0; s < n; s++) if (assigned[s] < 0) slots[k++] = s;
        int[] result = assigned.clone();
        if (free == 0) return result;

        long[][] count = new long[n][n];
        long[] total = {0};
        int[] cur = new int[n];
        enumerate(0, slots, usedColor.clone(), banned, cur, count, total, null, null);

        if (total[0] == 0) return null; // 條件互相矛盾

        int[] best = new int[n];
        double[] bestScore = {-1};
        enumerate(0, slots, usedColor.clone(), banned, cur, count, new long[]{0}, best, bestScore);
        for (int s : slots) result[s] = best[s];
        return result;
    }

    /** 回傳仍可能的排列數（顯示用） */
    public static long countPossible(int n, int[] assigned, boolean[][] banned) {
        boolean[] usedColor = new boolean[n];
        int free = 0;
        for (int s = 0; s < n; s++) { if (assigned[s] >= 0) usedColor[assigned[s]] = true; else free++; }
        int[] slots = new int[free];
        for (int s = 0, k = 0; s < n; s++) if (assigned[s] < 0) slots[k++] = s;
        long[] total = {0};
        enumerate(0, slots, usedColor, banned, new int[n], new long[n][n], total, null, null);
        return total[0];
    }

    /**
     * 第一輪（best == null）：統計每格每色在可能排列中的出現次數。
     * 第二輪（best != null）：找出 sum(count) 最大的排列。
     */
    private static void enumerate(int i, int[] slots, boolean[] used, boolean[][] banned, int[] cur,
                                  long[][] count, long[] total, int[] best, double[] bestScore) {
        if (total[0] >= LIMIT) return;
        if (i == slots.length) {
            total[0]++;
            if (best == null) {
                for (int s : slots) count[s][cur[s]]++;
            } else {
                double sc = 0;
                for (int s : slots) sc += count[s][cur[s]];
                if (sc > bestScore[0]) { bestScore[0] = sc; for (int s : slots) best[s] = cur[s]; }
            }
            return;
        }
        int s = slots[i];
        int n = used.length;
        for (int c = 0; c < n; c++) {
            if (used[c]) continue;
            if (banned != null && banned[s][c]) continue;
            used[c] = true; cur[s] = c;
            enumerate(i + 1, slots, used, banned, cur, count, total, best, bestScore);
            used[c] = false;
        }
    }
}
