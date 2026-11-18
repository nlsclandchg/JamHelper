package com.jamhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 無障礙服務：截圖（takeScreenshot）→ 辨識 → 決策 → 模擬點擊（dispatchGesture）。
 * 浮動視窗用 TYPE_ACCESSIBILITY_OVERLAY，不需要「顯示在其他應用程式上層」權限。
 */
public class JamService extends AccessibilityService {

    private static final long LOOP_DELAY_MS = 380;      // Android 截圖最短間隔約 333ms
    private static final long TAP_TIMEOUT_MS = 3500;

    private WindowManager wm;
    private LinearLayout panel;
    private Button toggle;
    private TextView status;
    private WindowManager.LayoutParams lp;

    private HandlerThread thread;
    private Handler worker;
    private Handler main;

    private volatile boolean running = false;
    private final Brain brain = new Brain();

    // 穩定判斷與點擊確認
    private String lastSig = null;
    private int stableCount = 0;
    private boolean awaiting = false;
    private String sigBeforeTap = null;
    private long tapAt = 0;
    private int missCount = 0;
    private long lastSceneAt = 0;
    private String lastAction = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        main = new Handler(Looper.getMainLooper());
        thread = new HandlerThread("jam-worker");
        thread.start();
        worker = new Handler(thread.getLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    // ---------------- 浮動視窗 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private void createOverlay() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float d = dm.density;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * d);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1E2230);
        bg.setCornerRadius(12 * d);
        panel.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        toggle = new Button(this);
        toggle.setText("▶ 開始");
        toggle.setTextSize(14);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> { if (running) stop("已停止"); else start(); });
        top.addView(toggle);

        TextView drag = new TextView(this);
        drag.setText("  ✥ 拖曳  ");
        drag.setTextColor(Color.LTGRAY);
        drag.setTextSize(13);
        top.addView(drag);

        Button reset = new Button(this);
        reset.setText("清除記憶");
        reset.setTextSize(12);
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> worker.post(() -> { brain.reset(); lastAction = "已清除本單記憶"; refresh(); }));
        top.addView(reset);

        panel.addView(top);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setMaxWidth((int) (dm.widthPixels * 0.8f));
        status.setText("果醬解謎助手：打開遊戲的訂單畫面後按「開始」");
        panel.addView(status);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (dm.widthPixels * 0.03f);
        lp.y = (int) (dm.heightPixels * 0.10f);

        View.OnTouchListener mover = new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + (int) (e.getRawX() - sx);
                        lp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, lp);
                        return true;
                }
                return false;
            }
        };
        drag.setOnTouchListener(mover);
        status.setOnTouchListener(mover);
        wm.addView(panel, lp);
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (status != null) status.setText(text);
            if (toggle != null) toggle.setText(running ? "■ 停止" : "▶ 開始");
        });
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append(running ? "執行中" : "已停止");
        if (!lastAction.isEmpty()) sb.append("｜").append(lastAction);
        String k = brain.knowledge();
        if (!k.isEmpty()) sb.append("\n格位：").append(k).append("（✓確定 ✗已知錯誤）");
        if (!brain.lastInfo.isEmpty()) sb.append("\n").append(brain.lastInfo);
        sb.append("\n本單用 ").append(Math.max(0, brain.usedThisOrder))
          .append("｜總共用 ").append(brain.usedTotal)
          .append("｜完成 ").append(brain.ordersDone).append(" 單");
        setStatus(sb.toString());
    }

    // ---------------- 主迴圈 ----------------

    private void start() {
        if (running) return;
        running = true;
        lastSig = null; stableCount = 0; awaiting = false; missCount = 0;
        lastSceneAt = SystemClock.uptimeMillis();
        lastAction = "開始";
        refresh();
        worker.post(this::step);
    }

    private void stop(String why) {
        running = false;
        awaiting = false;
        lastAction = why;
        refresh();
    }

    private void step() {
        if (!running) return;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer hb = result.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    worker.post(() -> {
                        try {
                            if (hw != null) {
                                Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                                hb.close();
                                if (sw != null) {
                                    int w = sw.getWidth(), h = sw.getHeight();
                                    int[] px = new int[w * h];
                                    sw.getPixels(px, 0, w, 0, 0, w, h);
                                    sw.recycle();
                                    handleFrame(px, w, h);
                                }
                            } else {
                                hb.close();
                            }
                        } catch (Throwable t) {
                            lastAction = "錯誤：" + t.getClass().getSimpleName();
                            refresh();
                        }
                        if (running) worker.postDelayed(JamService.this::step, LOOP_DELAY_MS);
                    });
                }
                @Override public void onFailure(int errorCode) {
                    if (running) worker.postDelayed(JamService.this::step, LOOP_DELAY_MS + 200);
                }
            });
        } catch (Throwable t) {
            lastAction = "截圖失敗：" + t.getClass().getSimpleName();
            refresh();
            if (running) worker.postDelayed(this::step, 1000);
        }
    }

    private void handleFrame(int[] px, int w, int h) {
        long now = SystemClock.uptimeMillis();
        Detector.Scene s = Detector.analyze(px, w, h);
        boolean ok = Brain.consistent(s);

        if (awaiting) {
            String sig = ok ? s.signature() : null;
            if (sig != null && !sig.equals(sigBeforeTap)) {
                awaiting = false; missCount = 0;   // 點擊生效
            } else if (now - tapAt > TAP_TIMEOUT_MS) {
                awaiting = false;
                brain.undoLastTap();
                if (!ok) {
                    // 點完畫面變成看不懂的東西（多半是購買彈窗），立刻停下不再點
                    stop("點擊後出現其他畫面（可能果醬用完），已自動停止");
                    return;
                }
                missCount++;
                if (missCount >= 2) { stop("連續點擊沒有反應（可能果醬用完），已自動停止"); return; }
                lastAction = "點擊沒反應，重試";
                refresh();
            } else {
                return;
            }
        }

        if (!ok) {
            stableCount = 0; lastSig = null;
            if (now - lastSceneAt > 1500) { lastAction = "等待訂單畫面…"; refresh(); }
            return;
        }
        lastSceneAt = now;

        String sig = s.signature();
        if (!sig.equals(lastSig)) { lastSig = sig; stableCount = 1; return; }
        stableCount++;
        if (stableCount < 2) return;   // 連續兩張一樣才動作，避開動畫

        Brain.Action a = brain.decide(s);
        lastAction = a.msg;
        refresh();
        if (a.isTap()) {
            sigBeforeTap = sig;
            tapAt = now;
            awaiting = true;
            lastSig = null; stableCount = 0;
            tap(a.x, a.y);
        }
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        main.post(() -> dispatchGesture(g, null, null));
    }
}
