package com.jamhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 決策邏輯（純 Java，可離線測試）。
 * 每次拿到「穩定」的畫面就呼叫 decide()，回傳要點哪裡或等待。
 */
public final class Brain {

    public static final class Action {
        public final int type; // 0 等待, 1 點擊
        public final int x, y;
        public final String msg;
        private Action(int type, int x, int y, String msg) { this.type = type; this.x = x; this.y = y; this.msg = msg; }
        static Action waitFor(String m) { return new Action(0, 0, 0, m); }
        static Action tap(int x, int y, String m) { return new Action(1, x, y, m); }
        public boolean isTap() { return type == 1; }
        @Override public String toString() { return isTap() ? "TAP(" + x + "," + y + ") " + msg : "WAIT " + msg; }
    }

    // 同色判斷門檻：色相差 < 10.5 度視為同色（實測同色 < 7 度、紅與粉紅 > 15 度）
    private static final float SAME = 0.35f;

    // ---- 目前訂單狀態 ----
    private int n = 0;
    private final List<float[]> colors = new ArrayList<>();
    private int[] fixed;          // fixed[slot] = 顏色編號
    private boolean[][] banned;   // banned[slot][color]：自己放錯記下的（最可靠）
    private boolean[][] bannedScreen; // 接手時從上一排碎果醬讀到的（較不可靠）
    private int[] pending;        // 已放滿一整排、等結果的放法
    private int[] roundPlan;      // 本輪預定放法
    private int round = 0;
    private int mismatch = 0;

    // ---- 統計 ----
    public int usedThisOrder = 0, usedTotal = 0, ordersDone = 0;
    public String lastInfo = "";

    public boolean hasOrder() { return n > 0; }

    public void reset() { n = 0; colors.clear(); fixed = null; banned = null; bannedScreen = null; pending = null; roundPlan = null; round = 0; usedThisOrder = 0; }

    /** 檢查畫面是否「完整」：托盤瓶數 + 平台上的瓶數 = 格數 */
    public static boolean consistent(Detector.Scene s) {
        if (s == null || s.active == null) return false;
        int onRow = 0;
        for (int i = 0; i < s.active.n; i++) if (s.active.filled[i] || s.active.green[i]) onRow++;
        return s.tray.size() + onRow == s.active.n;
    }

    public Action decide(Detector.Scene s) {
        Detector.Row row = s.active;
        int rn = row.n;
        int greens = 0;
        for (boolean g : row.green) if (g) greens++;

        // 收集畫面上所有瓶子的顏色
        List<float[]> sceneColors = new ArrayList<>();
        for (Detector.Jar j : s.tray) sceneColors.add(j.color);
        for (int i = 0; i < rn; i++) if ((row.filled[i] || row.green[i]) && row.color[i] != null) sceneColors.add(row.color[i]);
        boolean anyFilled = false;
        for (boolean f : row.filled) if (f) anyFilled = true;
        boolean fresh = greens == 0 && !anyFilled && s.previous == null && s.tray.size() == rn;

        // 1. 畫面跟記憶中的訂單對不上
        if (hasOrder() && (n != rn || (greens < rn && !sameColorSet(sceneColors)))) {
            if (!fresh && ++mismatch < 6) return Action.waitFor("畫面轉換中…");
            // 明顯是新訂單，或持續對不上 → 重新開始
            reset();
        }
        mismatch = 0;

        // 2. 整排全綠 → 訂單完成
        if (greens == rn) {
            if (hasOrder()) {
                ordersDone++;
                lastInfo = "第 " + ordersDone + " 單完成，本單用了 " + usedThisOrder + " 個果醬";
                reset();
            }
            return Action.waitFor(lastInfo.isEmpty() ? "等待下一張訂單" : lastInfo);
        }

        // 新訂單（或剛啟動、中途接手）
        if (!hasOrder()) {
            if (sceneColors.size() != rn) return Action.waitFor("讀取顏色中…");
            if (!distinct(sceneColors)) return Action.waitFor("顏色太接近，無法分辨");
            n = rn;
            colors.addAll(sceneColors);
            fixed = new int[n]; Arrays.fill(fixed, -1);
            banned = new boolean[n][n];
            bannedScreen = new boolean[n][n];
            round = 1;
            lastInfo = "新訂單：" + n + " 瓶";
            // 接手時：讀取已確定（綠色）與上一排碎掉的顏色
            absorbScreen(s, true);
        }

        // 3. 等待上一輪結果
        if (pending != null && s.previous == null && greens == 0 && s.tray.size() == rn) {
            // 沒有上一排、平台全空：這是新訂單（剛好同樣顏色），不是「整排全錯」
            reset();
            return Action.waitFor("偵測到新訂單");
        }
        if (pending != null) {
            boolean hasEmpty = false;
            for (int i = 0; i < rn; i++) if (!row.filled[i] && !row.green[i]) hasEmpty = true;
            if (!hasEmpty) return Action.waitFor("等待判定…");
            int hit = 0;
            for (int i = 0; i < n; i++) {
                if (fixed[i] >= 0 || pending[i] < 0) continue;
                if (row.green[i]) { fixed[i] = pending[i]; hit++; }
                else banned[i][pending[i]] = true;
            }
            lastInfo = "第 " + round + " 輪命中 " + hit + " 瓶";
            pending = null; roundPlan = null; round++;
        }
        absorbScreen(s, true);

        // 4. 目前這排：已放下（未判定）的瓶子
        int[] assigned = fixed.clone();
        for (int i = 0; i < n; i++) {
            if (assigned[i] >= 0) continue;
            if (row.filled[i] && row.color[i] != null) assigned[i] = nearest(row.color[i]);
        }
        // 下一個要放的格子：最左邊的空格（遊戲會依序填入）
        int next = -1;
        for (int i = 0; i < n; i++) if (assigned[i] < 0) { next = i; break; }
        if (next < 0) return Action.waitFor("等待判定…");

        if (roundPlan == null || !planStillValid(assigned)) {
            roundPlan = Solver.plan(n, assigned, merged());
            if (roundPlan == null) roundPlan = Solver.plan(n, assigned, banned);       // 畫面讀到的資訊有誤，只用自己的紀錄
            if (roundPlan == null) roundPlan = Solver.plan(n, assigned, new boolean[n][n]);
            if (roundPlan == null) return Action.waitFor("無法規劃（顏色辨識可能有誤）");
        }
        int want = roundPlan[next];
        // 找托盤中這個顏色的瓶子
        Detector.Jar target = null; float bd = 9;
        for (Detector.Jar j : s.tray) {
            float d = ColorUtil.dist(j.color, colors.get(want));
            if (d < bd) { bd = d; target = j; }
        }
        if (target == null || bd > SAME) { roundPlan = null; return Action.waitFor("托盤找不到需要的顏色"); }

        // 這是本排最後一個空格 → 放完就等結果
        int empties = 0;
        for (int i = 0; i < n; i++) if (assigned[i] < 0) empties++;
        if (empties == 1) {
            int[] full = assigned.clone();
            full[next] = want;
            for (int i = 0; i < n; i++) if (fixed[i] >= 0) full[i] = -1; // 已確定的不算
            pending = full;
        }
        usedThisOrder++; usedTotal++;
        return Action.tap(target.x, target.y, "第 " + round + " 輪：第 " + (next + 1) + " 格放" + ColorUtil.name(colors.get(want)));
    }

    /** 點擊沒有生效時呼叫，撤銷剛剛的記帳 */
    public void undoLastTap() {
        usedThisOrder--; usedTotal--;
        pending = null;
    }

    private boolean planStillValid(int[] assigned) {
        for (int i = 0; i < n; i++) {
            if (assigned[i] >= 0) { if (roundPlan[i] != assigned[i]) return false; }
            else if (banned[i][roundPlan[i]] || bannedScreen[i][roundPlan[i]]) return false;
        }
        return true;
    }

    /** 從畫面補充資訊：綠色格 = 確定；（接手時）上一排非綠色格的碎果醬顏色 = 錯誤 */
    private void absorbScreen(Detector.Scene s, boolean readSplats) {
        Detector.Row row = s.active;
        for (int i = 0; i < n; i++) if (row.green[i] && row.color[i] != null && fixed[i] < 0) fixed[i] = nearest(row.color[i]);
        Detector.Row prev = s.previous;
        if (readSplats && prev != null && prev.n == n) {
            for (int i = 0; i < n; i++) {
                if (prev.green[i] || prev.color[i] == null) continue;
                if (fixed[i] >= 0) continue;
                int c = nearestHue(prev.color[i]);
                if (c >= 0) bannedScreen[i][c] = true;
            }
        }
        // 已確定的顏色不會出現在別格
        for (int i = 0; i < n; i++) if (fixed[i] >= 0) for (int j = 0; j < n; j++) if (j != i) banned[j][fixed[i]] = true;
    }

    private boolean[][] merged() {
        boolean[][] m = new boolean[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) m[i][j] = banned[i][j] || bannedScreen[i][j];
        return m;
    }

    private int nearest(float[] c) {
        int bi = -1; float bd = 9;
        for (int i = 0; i < colors.size(); i++) { float d = ColorUtil.dist(c, colors.get(i)); if (d < bd) { bd = d; bi = i; } }
        return bi;
    }

    /** 碎掉的果醬顏色會偏一點，只比色相，而且要明顯比第二接近的顏色更近才採用 */
    private int nearestHue(float[] c) {
        int bi = -1; float bd = 999, second = 999;
        for (int i = 0; i < colors.size(); i++) {
            float d = ColorUtil.hueDiff(c, colors.get(i));
            if (d < bd) { second = bd; bd = d; bi = i; } else if (d < second) second = d;
        }
        return (bd < 10 && second - bd > 6) ? bi : -1;
    }

    private boolean sameColorSet(List<float[]> sc) {
        if (sc.size() != n) return false;
        boolean[] used = new boolean[n];
        for (float[] c : sc) {
            int bi = -1; float bd = SAME;
            for (int i = 0; i < n; i++) { if (used[i]) continue; float d = ColorUtil.dist(c, colors.get(i)); if (d < bd) { bd = d; bi = i; } }
            if (bi < 0) return false;
            used[bi] = true;
        }
        return true;
    }

    private static boolean distinct(List<float[]> sc) {
        for (int i = 0; i < sc.size(); i++) for (int j = i + 1; j < sc.size(); j++) if (ColorUtil.hueDiff(sc.get(i), sc.get(j)) < 6) return false;
        return true;
    }

    /** 給浮動視窗顯示的「已知錯誤位置」摘要 */
    public String knowledge() {
        if (!hasOrder()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i + 1).append(':');
            if (fixed[i] >= 0) sb.append('✓').append(short1(colors.get(fixed[i])));
            else {
                sb.append('✗');
                boolean any = false;
                for (int c = 0; c < n; c++) if ((banned[i][c] || bannedScreen[i][c]) && !isFixedSomewhere(c)) { sb.append(short1(colors.get(c))); any = true; }
                if (!any) sb.append('-');
            }
            if (i < n - 1) sb.append("  ");
        }
        return sb.toString();
    }

    private boolean isFixedSomewhere(int c) { for (int f : fixed) if (f == c) return true; return false; }
    private static String short1(float[] c) { return ColorUtil.name(c).substring(0, 1); }
}
