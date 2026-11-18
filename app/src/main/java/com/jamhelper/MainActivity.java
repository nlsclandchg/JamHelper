package com.jamhelper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView state;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("果醬解謎助手");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        state = new TextView(this);
        state.setTextSize(16);
        state.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(state);

        Button open = new Button(this);
        open.setText("開啟無障礙設定");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setTextColor(0xFF333333);
        help.setPadding(0, pad, 0, 0);
        help.setText(
            "使用方式\n" +
            "1. 按上方按鈕，在「已下載的應用程式」找到「果醬解謎助手」並開啟。\n" +
            "   若顯示「受限制的設定」：到 設定 → 應用程式 → 果醬解謎助手 → 右上角 ⋮ → 允許受限制的設定，再回來開啟。\n" +
            "2. 進入遊戲的刨冰訂單畫面（看得到下方托盤的果醬瓶）。\n" +
            "3. 按浮動視窗的「▶ 開始」。程式會自動數瓶子、記住每格放錯過的顏色，\n" +
            "   每一輪都挑「最可能對」的放法，盡量少用果醬。\n" +
            "4. 浮動視窗請放在畫面上半部（可拖曳），不要蓋住平台與托盤。\n\n" +
            "安全機制\n" +
            "• 點了瓶子畫面卻沒變化、或跳出其他畫面（例如果醬用完的購買頁），會立刻自動停止，不會亂點。\n" +
            "• 中途接手也可以：會讀取畫面上已經變綠的格子和上一排碎掉的顏色。\n" +
            "• 換訂單時會自動偵測；若記憶錯亂可按「清除記憶」。");
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceOn();
        state.setText(on ? "✅ 服務已開啟，回到遊戲使用浮動視窗" : "⚠️ 服務尚未開啟");
        state.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
    }

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = getPackageName() + "/" + JamService.class.getName();
        String meShort = getPackageName() + "/." + JamService.class.getSimpleName();
        for (String s : enabled.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        return false;
    }
}
