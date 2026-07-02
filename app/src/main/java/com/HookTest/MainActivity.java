package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 微信虚拟定位模块 - 主设置界面
 * 用户在此设置虚拟位置，设置后自动对微信生效
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "wx_location_prefs";
    private static final String KEY_LOCATION_ENABLED = "isLocation";
    private static final String KEY_XCX_ENABLED = "isX";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_GPS_PLACE = "gpsPlace";

    private boolean locationEnabled = false;
    private boolean xcxEnabled = false;
    private String latitude = "39.908823";
    private String longitude = "116.397470";
    private String gpsPlace = "北京市东城区";

    private TextView gpsTextView;
    private EditText latEdit;
    private EditText lngEdit;
    private Switch locationSwitch;
    private Switch xcxSwitch;

    // 验证状态
    private boolean isVerified = false;
    private TextView verifyStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化验证系统
        ShuanQVerifier.init(this);
        isVerified = ShuanQVerifier.isVerified();

        loadPrefs();
        setContentView(createMainView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到界面都刷新验证状态显示
        isVerified = ShuanQVerifier.isVerified();
        updateVerifyStatusText();
    }

    @SuppressLint("SetTextI18n")
    private View createMainView() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16));
        mainLayout.setBackgroundColor(0xFFF5F5F5);

        // 标题
        TextView titleView = new TextView(this);
        titleView.setText("微信虚拟定位");
        titleView.setTextColor(0xFF333333);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp2px(20);
        titleView.setLayoutParams(titleParams);
        mainLayout.addView(titleView);

        // ========== 验证状态卡片 ==========
        LinearLayout verifyCardLayout = new LinearLayout(this);
        verifyCardLayout.setOrientation(LinearLayout.VERTICAL);
        verifyCardLayout.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16));
        verifyCardLayout.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams verifyCardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        verifyCardParams.bottomMargin = dp2px(12);
        verifyCardLayout.setLayoutParams(verifyCardParams);

        // 验证状态标题行
        LinearLayout verifyTitleRow = new LinearLayout(this);
        verifyTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        verifyTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        verifyTitleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView verifyTitle = new TextView(this);
        verifyTitle.setText("卡密验证");
        verifyTitle.setTextColor(0xFF333333);
        verifyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        verifyTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams verifyTitleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        verifyTitle.setLayoutParams(verifyTitleParams);
        verifyTitleRow.addView(verifyTitle);

        verifyStatusText = new TextView(this);
        updateVerifyStatusText();
        verifyStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        verifyTitleRow.addView(verifyStatusText);
        verifyCardLayout.addView(verifyTitleRow);

        // 卡密输入框
        final EditText cardInput = new EditText(this);
        cardInput.setHint("请输入卡密");
        cardInput.setText(ShuanQVerifier.getCardCode());
        cardInput.setTextColor(0xFF333333);
        cardInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cardInput.setBackgroundResource(android.R.drawable.edit_text);
        cardInput.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        LinearLayout.LayoutParams cardInputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardInputParams.topMargin = dp2px(12);
        cardInput.setLayoutParams(cardInputParams);
        verifyCardLayout.addView(cardInput);

        // 验证按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.topMargin = dp2px(12);
        btnRow.setLayoutParams(btnRowParams);

        Button verifyBtn = new Button(this);
        verifyBtn.setText("验证卡密");
        verifyBtn.setTextColor(0xFFFFFFFF);
        verifyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        verifyBtn.setBackgroundColor(0xFF4CAF50);
        verifyBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verifyBtnParams = new LinearLayout.LayoutParams(
                0, dp2px(40), 1);
        verifyBtnParams.rightMargin = dp2px(8);
        verifyBtn.setLayoutParams(verifyBtnParams);
        verifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String card = cardInput.getText().toString().trim();
                if (card.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入卡密", Toast.LENGTH_SHORT).show();
                    return;
                }
                doVerify(card);
            }
        });
        btnRow.addView(verifyBtn);

        Button clearVerifyBtn = new Button(this);
        clearVerifyBtn.setText("清除验证");
        clearVerifyBtn.setTextColor(0xFFFFFFFF);
        clearVerifyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clearVerifyBtn.setBackgroundColor(0xFFF44336);
        clearVerifyBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clearBtnParams = new LinearLayout.LayoutParams(
                0, dp2px(40), 1);
        clearBtnParams.leftMargin = dp2px(8);
        clearVerifyBtn.setLayoutParams(clearBtnParams);
        clearVerifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShuanQVerifier.clearVerify(MainActivity.this);
                isVerified = false;
                updateVerifyStatusText();
                Toast.makeText(MainActivity.this, "验证已清除", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(clearVerifyBtn);
        verifyCardLayout.addView(btnRow);

        mainLayout.addView(verifyCardLayout);

        // 卡片容器
        LinearLayout cardLayout = new LinearLayout(this);
        cardLayout.setOrientation(LinearLayout.VERTICAL);
        cardLayout.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16));
        cardLayout.setBackgroundColor(0xFFFFFFFF);
        cardLayout.setBackgroundResource(android.R.color.white);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp2px(12);
        cardLayout.setLayoutParams(cardParams);

        // 定位开关
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView switchLabel = new TextView(this);
        switchLabel.setText("🌏 虚拟定位");
        switchLabel.setTextColor(0xFF333333);
        switchLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        switchLabel.setLayoutParams(labelParams);
        switchRow.addView(switchLabel);

        locationSwitch = new Switch(this);
        locationSwitch.setChecked(locationEnabled);
        locationSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                locationEnabled = isChecked;
                savePrefs();
                String status = isChecked ? "已开启" : "已关闭";
                Toast.makeText(MainActivity.this, "虚拟定位" + status, Toast.LENGTH_SHORT).show();
            }
        });
        switchRow.addView(locationSwitch);
        cardLayout.addView(switchRow);

        // 分隔线
        View divider1 = new View(this);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = dp2px(12);
        dividerParams.bottomMargin = dp2px(12);
        divider1.setLayoutParams(dividerParams);
        divider1.setBackgroundColor(0xFFEEEEEE);
        cardLayout.addView(divider1);

        // 小程序开关
        LinearLayout xcxRow = new LinearLayout(this);
        xcxRow.setOrientation(LinearLayout.HORIZONTAL);
        xcxRow.setGravity(Gravity.CENTER_VERTICAL);
        xcxRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView xcxLabel = new TextView(this);
        xcxLabel.setText("📱 小程序独立坐标(GCJ02)");
        xcxLabel.setTextColor(0xFF333333);
        xcxLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        xcxLabel.setLayoutParams(labelParams);
        xcxRow.addView(xcxLabel);

        xcxSwitch = new Switch(this);
        xcxSwitch.setChecked(xcxEnabled);
        xcxSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                xcxEnabled = isChecked;
                savePrefs();
            }
        });
        xcxRow.addView(xcxSwitch);
        cardLayout.addView(xcxRow);

        mainLayout.addView(cardLayout);

        // 位置信息卡片
        LinearLayout locCardLayout = new LinearLayout(this);
        locCardLayout.setOrientation(LinearLayout.VERTICAL);
        locCardLayout.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(16));
        locCardLayout.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams locCardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        locCardParams.bottomMargin = dp2px(12);
        locCardLayout.setLayoutParams(locCardParams);

        TextView locTitle = new TextView(this);
        locTitle.setText("📍 位置信息");
        locTitle.setTextColor(0xFF333333);
        locTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams locTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        locTitleParams.bottomMargin = dp2px(10);
        locTitle.setLayoutParams(locTitleParams);
        locCardLayout.addView(locTitle);

        gpsTextView = new TextView(this);
        gpsTextView.setText("纬度：" + latitude + "\n经度：" + longitude + "\n地点：" + gpsPlace);
        gpsTextView.setTextColor(0xFF666666);
        gpsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gpsTextView.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        gpsTextView.setBackgroundColor(0xFFF8F8F8);
        gpsTextView.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpsParams.bottomMargin = dp2px(12);
        gpsTextView.setLayoutParams(gpsParams);
        locCardLayout.addView(gpsTextView);

        // 纬度输入
        TextView latLabel = new TextView(this);
        latLabel.setText("纬度 (Latitude)");
        latLabel.setTextColor(0xFF666666);
        latLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        latLabel.setPadding(0, 0, 0, dp2px(4));
        locCardLayout.addView(latLabel);

        latEdit = new EditText(this);
        latEdit.setText(latitude);
        latEdit.setTextColor(0xFF333333);
        latEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        latEdit.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        latEdit.setBackgroundColor(0xFFF5F5F5);
        latEdit.setSingleLine(true);
        LinearLayout.LayoutParams latEditParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        latEditParams.bottomMargin = dp2px(10);
        latEdit.setLayoutParams(latEditParams);
        locCardLayout.addView(latEdit);

        // 经度输入
        TextView lngLabel = new TextView(this);
        lngLabel.setText("经度 (Longitude)");
        lngLabel.setTextColor(0xFF666666);
        lngLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lngLabel.setPadding(0, 0, 0, dp2px(4));
        locCardLayout.addView(lngLabel);

        lngEdit = new EditText(this);
        lngEdit.setText(longitude);
        lngEdit.setTextColor(0xFF333333);
        lngEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lngEdit.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        lngEdit.setBackgroundColor(0xFFF5F5F5);
        lngEdit.setSingleLine(true);
        LinearLayout.LayoutParams lngEditParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lngEditParams.bottomMargin = dp2px(12);
        lngEdit.setLayoutParams(lngEditParams);
        locCardLayout.addView(lngEdit);

        // 按钮行
        LinearLayout locBtnRow = new LinearLayout(this);
        locBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        locBtnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button mapBtn = new Button(this);
        mapBtn.setText("🗺 地图选点");
        mapBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mapBtn.setTextColor(0xFFFFFFFF);
        mapBtn.setBackgroundColor(0xFF2196F3);
        LinearLayout.LayoutParams mapBtnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mapBtnParams.rightMargin = dp2px(6);
        mapBtn.setLayoutParams(mapBtnParams);
        mapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMapPicker();
            }
        });
        locBtnRow.addView(mapBtn);

        Button curLocBtn = new Button(this);
        curLocBtn.setText("📍 当前位置");
        curLocBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        curLocBtn.setTextColor(0xFFFFFFFF);
        curLocBtn.setBackgroundColor(0xFFFF9800);
        LinearLayout.LayoutParams curLocBtnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        curLocBtnParams.leftMargin = dp2px(6);
        curLocBtn.setLayoutParams(curLocBtnParams);
        curLocBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getCurrentLocation();
            }
        });
        locBtnRow.addView(curLocBtn);

        locCardLayout.addView(locBtnRow);

        mainLayout.addView(locCardLayout);

        // 保存按钮
        Button saveBtn = new Button(this);
        saveBtn.setText("💾 保存设置");
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setPadding(0, dp2px(14), 0, dp2px(14));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp2px(8);
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                latitude = latEdit.getText().toString().trim();
                longitude = lngEdit.getText().toString().trim();
                savePrefs();
                updateGpsText();
                Toast.makeText(MainActivity.this, "设置已保存，重启微信后生效", Toast.LENGTH_SHORT).show();
            }
        });
        mainLayout.addView(saveBtn);

        // 提示文字
        TextView tipView = new TextView(this);
        tipView.setText("\n💡 使用说明：\n1. 在LSPosed中启用本模块，作用域选择微信\n2. 在此页面设置虚拟位置并保存\n3. 重启微信即可生效\n4. 微信启动时会显示「功能加载完成」提示");
        tipView.setTextColor(0xFF999999);
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tipView.setLineSpacing(0, 1.5f);
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipParams.topMargin = dp2px(16);
        tipView.setLayoutParams(tipParams);
        mainLayout.addView(tipView);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(mainLayout);
        return scrollView;
    }

    private void updateGpsText() {
        gpsTextView.setText("纬度：" + latitude + "\n经度：" + longitude + "\n地点：" + gpsPlace);
    }

    @SuppressLint("SetTextI18n")
    private void getCurrentLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                android.location.Location loc = null;
                try {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } catch (SecurityException e) {
                    Toast.makeText(this, "需要定位权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (loc == null) {
                    try {
                        loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    } catch (SecurityException e) {
                        // ignore
                    }
                }
                if (loc != null) {
                    latitude = String.valueOf(loc.getLatitude());
                    longitude = String.valueOf(loc.getLongitude());
                    gpsPlace = "当前位置";
                    latEdit.setText(latitude);
                    lngEdit.setText(longitude);
                    updateGpsText();
                    Toast.makeText(this, "已获取当前位置", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "无法获取当前位置", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showMapPicker() {
        final Dialog dialog = new Dialog(this);
        dialog.setTitle("地图选点");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // WebView显示腾讯地图
        final WebView webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);

        String mapUrl = "https://apis.map.qq.com/tools/locpicker?search=1&type=0&backurl=http://callback&key=Q3PBZ-TDH3R-4XEWO-WJUMV-YBAUJ-MQBAS&referer=myapp";
        webView.loadUrl(mapUrl);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (url.startsWith("http://callback")) {
                    try {
                        Uri uri = Uri.parse(url);
                        String latng = uri.getQueryParameter("latng");
                        String addr = uri.getQueryParameter("addr");
                        String poi = uri.getQueryParameter("poi");

                        if (latng != null && latng.contains(",")) {
                            String[] parts = latng.split(",");
                            if (parts.length >= 2) {
                                latitude = parts[0];
                                longitude = parts[1];
                                if (poi != null && !poi.isEmpty()) {
                                    gpsPlace = poi;
                                } else if (addr != null) {
                                    gpsPlace = addr;
                                }
                                latEdit.setText(latitude);
                                lngEdit.setText(longitude);
                                updateGpsText();
                                savePrefs();
                                Toast.makeText(MainActivity.this, "位置已选择: " + gpsPlace, Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                                return true;
                            }
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });

        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(webParams);
        layout.addView(webView);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("取消");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        layout.addView(cancelBtn);

        dialog.setContentView(layout);

        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);

        dialog.show();
    }

    // ==========================================
    // 网络验证相关方法
    // ==========================================

    /**
     * 更新验证状态显示文字
     */
    private void updateVerifyStatusText() {
        if (verifyStatusText == null) return;
        if (isVerified) {
            verifyStatusText.setText("已激活");
            verifyStatusText.setTextColor(0xFF4CAF50);
        } else {
            verifyStatusText.setText("未激活");
            verifyStatusText.setTextColor(0xFFF44336);
        }
    }

    /**
     * 执行卡密验证
     */
    private void doVerify(final String card) {
        Toast.makeText(this, "正在验证...", Toast.LENGTH_SHORT).show();

        ShuanQVerifier.verifyCard(this, card, new ShuanQVerifier.VerifyCallback() {
            @Override
            public void onSuccess(String cardInfo) {
                isVerified = true;
                updateVerifyStatusText();
                // 使用AlertDialog显示验证成功信息（比Toast更清晰，内容完整可见）
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("验证成功")
                        .setMessage(cardInfo)
                        .setPositiveButton("确定", null)
                        .show();
            }

            @Override
            public void onFailure(String errorMsg) {
                isVerified = false;
                updateVerifyStatusText();
                // 使用AlertDialog显示验证失败信息（比Toast更清晰，内容完整可见）
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("验证失败")
                        .setMessage(errorMsg)
                        .setPositiveButton("确定", null)
                        .show();
            }
        });
    }

    @SuppressLint("ApplySharedPref")
    private void savePrefs() {
        // 保存到本地SharedPreferences
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(KEY_LOCATION_ENABLED, locationEnabled);
        editor.putBoolean(KEY_XCX_ENABLED, xcxEnabled);
        editor.putString(KEY_LATITUDE, latitude);
        editor.putString(KEY_LONGITUDE, longitude);
        editor.putString(KEY_GPS_PLACE, gpsPlace);
        editor.commit();

        // 通过ContentProvider同步（供微信进程读取）
        try {
            ContentValues values = new ContentValues();
            values.put(KEY_LOCATION_ENABLED, locationEnabled ? 1 : 0);
            values.put(KEY_XCX_ENABLED, xcxEnabled ? 1 : 0);
            values.put(KEY_LATITUDE, latitude);
            values.put(KEY_LONGITUDE, longitude);
            values.put(KEY_GPS_PLACE, gpsPlace);
            ConfigProvider.writeConfig(this, values);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        locationEnabled = sp.getBoolean(KEY_LOCATION_ENABLED, false);
        xcxEnabled = sp.getBoolean(KEY_XCX_ENABLED, false);
        latitude = sp.getString(KEY_LATITUDE, latitude);
        longitude = sp.getString(KEY_LONGITUDE, longitude);
        gpsPlace = sp.getString(KEY_GPS_PLACE, gpsPlace);
    }

    private int dp2px(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
