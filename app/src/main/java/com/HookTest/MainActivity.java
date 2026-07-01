package com.HookTest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.util.TypedValue;

/**
 * 模块主界面 - 用于LSPosed模块检测和显示模块信息
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp2px(24), dp2px(24), dp2px(24), dp2px(24));

        TextView title = new TextView(this);
        title.setText("微信虚拟定位模块");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(0xFF333333);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp2px(16);
        title.setLayoutParams(titleParams);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("模块说明：\n\n1. 请在LSPosed中启用本模块\n2. 作用域选择微信\n3. 重启微信后生效\n4. 点击微信右上角+按钮，在菜单中选择「定位设置」\n\n支持安卓10-14");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setTextColor(0xFF666666);
        desc.setLineSpacing(0, 1.5f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp2px(8);
        desc.setLayoutParams(descParams);
        layout.addView(desc);

        setContentView(layout);
    }

    private int dp2px(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
