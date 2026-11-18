package com.jamhelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** 透明的畫面，只用來跳出「開始錄影？」的系統確認視窗 */
public class CaptureActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenCaptureService.class);
            i.putExtra(ScreenCaptureService.EXTRA_CODE, resultCode);
            i.putExtra(ScreenCaptureService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } else {
            JamService.onCaptureDenied();
        }
        finish();
    }
}
