package com.jamhelper;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.view.Display;

/** Android 11 以上：用無障礙服務內建的截圖功能（不需要錄影權限） */
@TargetApi(30)
final class Capture30 {
    private Capture30() {}

    interface FrameCallback { void onFrame(int[] px, int w, int h); }

    static void take(AccessibilityService svc, Handler worker, FrameCallback cb, Runnable onFail) {
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                worker.post(() -> {
                    int[] px = null; int w = 0, h = 0;
                    try {
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (sw != null) {
                                w = sw.getWidth(); h = sw.getHeight();
                                px = new int[w * h];
                                sw.getPixels(px, 0, w, 0, 0, w, h);
                                sw.recycle();
                            }
                        }
                    } finally {
                        hb.close();
                    }
                    if (px != null) cb.onFrame(px, w, h); else onFail.run();
                });
            }
            @Override public void onFailure(int errorCode) { worker.post(onFail); }
        });
    }
}
