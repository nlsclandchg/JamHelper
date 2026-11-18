package com.jamhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Android 10 以下：用「螢幕錄影」權限取得畫面。
 * 需要前景服務（通知列會顯示「果醬解謎助手正在讀取畫面」）。
 */
public class ScreenCaptureService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "capture";

    static volatile ScreenCaptureService instance;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private int width, height;
    private byte[] bytes;
    private int[] last;
    private final Object lock = new Object();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent == null || projection != null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        thread = new HandlerThread("capture");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { release(); stopSelf(); }
        }, h);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels; height = dm.heightPixels;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        display = projection.createVirtualDisplay("jamhelper", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
        instance = this;
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "讀取畫面", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("果醬解謎助手")
                .setContentText("正在讀取遊戲畫面")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
    }

    /** 需要時才轉換影像（省電）；畫面沒變化時不會有新影像，所以保留最後一張 */
    private void readFrame() {
        Image img = null;
        try {
            ImageReader r = reader;
            if (r == null) return;
            img = r.acquireLatestImage();
            if (img == null) return;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            int need = buf.remaining();
            if (bytes == null || bytes.length < need) bytes = new byte[need];
            buf.get(bytes, 0, need);
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int row = y * rs, o = y * w;
                for (int x = 0; x < w; x++) {
                    int i = row + x * ps;
                    if (i + 2 >= need) break;
                    px[o + x] = 0xFF000000 | ((bytes[i] & 255) << 16) | ((bytes[i + 1] & 255) << 8) | (bytes[i + 2] & 255);
                }
            }
            last = px; width = w; height = h;
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /** 回傳最新畫面；wh[0]=寬, wh[1]=高。還沒有畫面時回傳 null */
    int[] grab(int[] wh) {
        synchronized (lock) {
            readFrame();
            if (last == null) return null;
            wh[0] = width; wh[1] = height;
            return last;
        }
    }

    private void release() {
        instance = null;
        synchronized (lock) { last = null; }
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        synchronized (lock) {
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
            display = null; reader = null;
        }
        if (thread != null) thread.quitSafely();
    }

    @Override
    public void onDestroy() {
        release();
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        projection = null;
        super.onDestroy();
    }
}
