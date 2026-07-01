package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.util.Log;
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
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块
 * 功能：长按微信首页右上角+按钮弹出设置面板，支持虚拟定位
 * 支持Android 10-16
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "WxLocationHook";
    private static final String PREFS_NAME = "wx_location_prefs";
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    // 配置键名
    private static final String KEY_LOCATION_ENABLED = "isLocation";
    private static final String KEY_XCX_ENABLED = "isX";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_GPS_PLACE = "gpsPlace";

    // 全局状态
    private static boolean locationEnabled = false;
    private static boolean xcxEnabled = false;
    private static String latitude = "39.908823";
    private static String longitude = "116.397470";
    private static String gpsPlace = "北京市东城区";

    private static Context appContext = null;
    private static ClassLoader targetClassLoader = null;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 已Hook的类（防重复）
    private static final Set<String> hookedClasses = new HashSet<>();

    // 小程序坐标（GCJ02）
    private static double xcxLat = 0;
    private static double xcxLng = 0;

    // 当前Activity
    private static Activity currentActivity = null;

    // 设置面板
    private static android.app.Dialog settingDialog = null;
    private static TextView gpsTextView = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.e(TAG, "========== 微信定位模块加载成功 ==========");
        Log.e(TAG, "目标包名: " + lpparam.packageName);
        targetClassLoader = lpparam.classLoader;

        // Hook Application.attach 获取Context
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        appContext = (Context) param.args[0];
                        Log.e(TAG, "获取到Application Context");
                        loadPrefs();
                        updateXcxCoordinates();

                        // 注册Activity生命周期
                        registerActivityLifecycleCallbacks((Application) appContext);
                    }
                });

        // Hook 微信首页右上角+按钮
        hookPlusActionView(lpparam);

        // Hook 腾讯地图定位
        hookTencentLocation(lpparam);

        // Hook 系统定位服务（备用）
        hookSystemLocation(lpparam);

        Log.e(TAG, "========== 所有Hook点注册完成 ==========");
    }

    // ==========================================
    // Hook 微信右上角+按钮
    // ==========================================
    private void hookPlusActionView(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 尝试不同版本的类名
            String[] classNames = {
                    "com.tencent.mm.ui.HomeUI$PlusActionView",
                    "com.tencent.mm.ui.HomeUI$b",
                    "com.tencent.mm.ui.LauncherUI$PlusActionView",
                    "com.tencent.mm.ui.LauncherUI$b"
            };

            Class<?> plusActionViewClass = null;
            for (String className : classNames) {
                try {
                    plusActionViewClass = XposedHelpers.findClass(className, lpparam.classLoader);
                    Log.e(TAG, "找到PlusActionView类: " + className);
                    break;
                } catch (Throwable t) {
                    Log.e(TAG, "未找到类: " + className);
                }
            }

            if (plusActionViewClass == null) {
                Log.e(TAG, "未找到PlusActionView类，尝试Hook LauncherUI");
                hookLauncherUI(lpparam);
                return;
            }

            // 查找返回View的无参方法（getActionView）
            final Method getActionViewMethod = findGetActionViewMethod(plusActionViewClass);

            if (getActionViewMethod == null) {
                Log.e(TAG, "未找到getActionView方法");
                return;
            }

            Log.e(TAG, "找到getActionView方法: " + getActionViewMethod.getName());

            // Hook所有构造函数
            XposedBridge.hookAllConstructors(plusActionViewClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    uiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                View actionView = (View) getActionViewMethod.invoke(param.thisObject);
                                if (actionView != null) {
                                    setupLongClick(actionView);
                                    Log.e(TAG, "PlusActionView长按监听器设置成功");
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "设置PlusActionView长按失败", t);
                            }
                        }
                    }, 300);
                }
            });

            Log.e(TAG, "PlusActionView Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "PlusActionView Hook失败", t);
        }
    }

    // 查找getActionView方法
    private Method findGetActionViewMethod(Class<?> clazz) {
        try {
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getReturnType() == View.class && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            // 尝试父类
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && !superClass.equals(Object.class)) {
                Method parentMethod = findGetActionViewMethod(superClass);
                if (parentMethod != null) {
                    return parentMethod;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "查找getActionView方法异常", t);
        }
        return null;
    }

    // 设置长按监听器
    private void setupLongClick(View view) {
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Log.e(TAG, "右上角+按钮被长按");
                showSettingPanel(v.getContext());
                return true;
            }
        });
    }

    // Hook LauncherUI（备用方案）
    private void hookLauncherUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                    lpparam.classLoader, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            currentActivity = activity;
                            Log.e(TAG, "LauncherUI onCreate");

                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        // 尝试找到+按钮View
                                        View plusBtn = findPlusButton(activity);
                                        if (plusBtn != null) {
                                            setupLongClick(plusBtn);
                                            Log.e(TAG, "通过LauncherUI找到+按钮");
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG, "查找+按钮失败", t);
                                    }
                                }
                            }, 1000);
                        }
                    });
            Log.e(TAG, "LauncherUI Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "LauncherUI Hook失败", t);
        }
    }

    // 查找+按钮View
    private View findPlusButton(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            return findViewByContentDescription(decorView, "更多功能按钮");
        } catch (Throwable t) {
            Log.e(TAG, "查找+按钮异常", t);
            return null;
        }
    }

    // 递归查找View
    private View findViewByContentDescription(View view, String description) {
        if (view == null) return null;
        try {
            CharSequence cd = view.getContentDescription();
            if (cd != null && cd.toString().contains(description)) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View result = findViewByContentDescription(vg.getChildAt(i), description);
                    if (result != null) return result;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==========================================
    // 注册Activity生命周期
    // ==========================================
    private void registerActivityLifecycleCallbacks(Application app) {
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    if (activity.getClass().getName().startsWith("com.tencent.mm")) {
                        currentActivity = activity;
                    }
                }

                @Override
                public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityResumed(Activity activity) {
                    if (activity.getClass().getName().startsWith("com.tencent.mm")) {
                        currentActivity = activity;
                    }
                }

                @Override
                public void onActivityPaused(Activity activity) {}

                @Override
                public void onActivityStopped(Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (currentActivity == activity) {
                        currentActivity = null;
                    }
                    if (settingDialog != null && settingDialog.getContext() == activity) {
                        settingDialog.dismiss();
                        settingDialog = null;
                    }
                }
            });
            Log.e(TAG, "ActivityLifecycleCallbacks注册成功");
        } catch (Throwable t) {
            Log.e(TAG, "注册ActivityLifecycleCallbacks失败", t);
        }
    }

    // ==========================================
    // 设置面板
    // ==========================================
    @SuppressLint("SetTextI18n")
    private void showSettingPanel(Context context) {
        if (context == null) {
            context = currentActivity;
        }
        if (context == null) {
            Log.e(TAG, "Context为空，无法显示设置面板");
            return;
        }

        final Context ctx = context;

        // 创建Dialog
        final android.app.Dialog dialog = new android.app.Dialog(ctx);
        dialog.setTitle("定位设置");

        // 主容器
        LinearLayout mainLayout = new LinearLayout(ctx);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(dp2px(ctx, 16), dp2px(ctx, 16), dp2px(ctx, 16), dp2px(ctx, 16));

        // 定位开关
        LinearLayout switchRow = createSwitchRow(ctx, "🌏 虚拟定位", locationEnabled,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        locationEnabled = isChecked;
                        savePrefs();
                        updateSettingPanelVisibility();
                    }
                });
        mainLayout.addView(switchRow);

        // 小程序开关
        LinearLayout xcxSwitchRow = createSwitchRow(ctx, "📱 小程序独立坐标(GCJ02)", xcxEnabled,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        xcxEnabled = isChecked;
                        savePrefs();
                    }
                });
        xcxSwitchRow.setId(View.generateViewId());
        mainLayout.addView(xcxSwitchRow);

        // 位置信息显示
        gpsTextView = new TextView(ctx);
        gpsTextView.setText("纬度：" + latitude + "\n经度：" + longitude + "\n地点：" + gpsPlace);
        gpsTextView.setTextColor(0xFF333333);
        gpsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gpsTextView.setPadding(dp2px(ctx, 8), dp2px(ctx, 12), dp2px(ctx, 8), dp2px(ctx, 12));
        gpsTextView.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpsParams.topMargin = dp2px(ctx, 8);
        gpsParams.bottomMargin = dp2px(ctx, 8);
        gpsTextView.setLayoutParams(gpsParams);
        gpsTextView.setId(View.generateViewId());
        mainLayout.addView(gpsTextView);

        // 经纬度输入区域（可折叠）
        final LinearLayout inputContainer = new LinearLayout(ctx);
        inputContainer.setOrientation(LinearLayout.VERTICAL);
        inputContainer.setId(View.generateViewId());

        // 纬度输入
        inputContainer.addView(createLabel(ctx, "纬度 (Latitude):"));
        final EditText latEdit = createEditText(ctx, latitude, "例如: 39.908823");
        latEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            void onTextChanged(String text) {
                latitude = text;
            }
        });
        inputContainer.addView(latEdit);

        // 经度输入
        inputContainer.addView(createLabel(ctx, "经度 (Longitude):"));
        final EditText lngEdit = createEditText(ctx, longitude, "例如: 116.397470");
        lngEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            void onTextChanged(String text) {
                longitude = text;
            }
        });
        inputContainer.addView(lngEdit);

        mainLayout.addView(inputContainer);

        // 按钮区域
        LinearLayout btnLayout = new LinearLayout(ctx);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button mapBtn = new Button(ctx);
        mapBtn.setText("🗺 地图选点");
        mapBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams mapBtnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mapBtnParams.rightMargin = dp2px(ctx, 4);
        mapBtn.setLayoutParams(mapBtnParams);
        mapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMapPicker(ctx, dialog);
            }
        });
        btnLayout.addView(mapBtn);

        Button curLocBtn = new Button(ctx);
        curLocBtn.setText("📍 当前位置");
        curLocBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams curLocBtnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        curLocBtnParams.leftMargin = dp2px(ctx, 4);
        curLocBtn.setLayoutParams(curLocBtnParams);
        curLocBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getCurrentLocation(ctx);
                latEdit.setText(latitude);
                lngEdit.setText(longitude);
                updateGpsText();
            }
        });
        btnLayout.addView(curLocBtn);

        mainLayout.addView(btnLayout);

        // 保存按钮
        Button saveBtn = new Button(ctx);
        saveBtn.setText("💾 保存设置");
        saveBtn.setBackgroundColor(0xFF27AE60);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setPadding(0, dp2px(ctx, 14), 0, dp2px(ctx, 14));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp2px(ctx, 12);
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePrefs();
                updateXcxCoordinates();
                Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        mainLayout.addView(saveBtn);

        // 滚动包装
        ScrollView scrollView = new ScrollView(ctx);
        scrollView.addView(mainLayout);

        dialog.setContentView(scrollView);
        dialog.show();

        // 设置Dialog尺寸
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp.width = (int) (dm.widthPixels * 0.9);
        lp.height = (int) (dm.heightPixels * 0.75);
        dialog.getWindow().setAttributes(lp);

        settingDialog = dialog;

        // 初始化可见性
        updateSettingPanelVisibility();
    }

    // 更新设置面板可见性
    private void updateSettingPanelVisibility() {
        if (settingDialog == null || gpsTextView == null) return;
        try {
            View contentView = settingDialog.getWindow().getDecorView();
            // 简单处理：始终显示所有内容
        } catch (Throwable ignored) {}
    }

    // 更新GPS文本显示
    @SuppressLint("SetTextI18n")
    private void updateGpsText() {
        if (gpsTextView != null) {
            gpsTextView.setText("纬度：" + latitude + "\n经度：" + longitude + "\n地点：" + gpsPlace);
        }
    }

    // ==========================================
    // 地图选点
    // ==========================================
    @SuppressLint("SetJavaScriptEnabled")
    private void showMapPicker(Context ctx, final android.app.Dialog parentDialog) {
        final android.app.Dialog mapDialog = new android.app.Dialog(ctx);
        mapDialog.setTitle("地图选点");

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);

        WebView webView = new WebView(ctx);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleMapUrl(request.getUrl().toString(), mapDialog, ctx);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMapUrl(url, mapDialog, ctx);
            }
        });

        // 腾讯地图选点组件
        String mapUrl = "https://mapapi.qq.com/web/mapComponents/locationPicker/v/index.html"
                + "?search=1&type=0&backurl=https://www.baidu.com"
                + "&key=I6UBZ-RR23W-OIWRS-RGJ5T-XDIN3-FFB3C";
        webView.loadUrl(mapUrl);

        container.addView(webView);
        mapDialog.setContentView(container);
        mapDialog.show();

        WindowManager.LayoutParams lp = mapDialog.getWindow().getAttributes();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp.width = (int) (dm.widthPixels * 0.95);
        lp.height = (int) (dm.heightPixels * 0.9);
        mapDialog.getWindow().setAttributes(lp);

        mapDialog.setOnDismissListener(dialog -> {
            try {
                webView.destroy();
            } catch (Throwable ignored) {}
        });
    }

    // 处理地图选点URL回调
    private boolean handleMapUrl(String url, android.app.Dialog dialog, Context ctx) {
        if (url.startsWith("https://www.baidu.com")) {
            try {
                Uri uri = Uri.parse(url);
                String latng = uri.getQueryParameter("latng");
                String addr = uri.getQueryParameter("addr");
                String name = uri.getQueryParameter("name");

                if (latng != null && !latng.isEmpty()) {
                    String[] parts = latng.split(",");
                    if (parts.length >= 2) {
                        latitude = parts[0].trim();
                        longitude = parts[1].trim();
                        gpsPlace = (addr != null ? addr : "") + (name != null ? "," + name : "");
                        if (gpsPlace.startsWith(",")) gpsPlace = gpsPlace.substring(1);

                        savePrefs();
                        updateXcxCoordinates();
                        updateGpsText();

                        Toast.makeText(ctx, "已选择位置: " + gpsPlace, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "解析地图选点结果失败", t);
                Toast.makeText(ctx, "获取坐标失败", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            return true;
        }
        return false;
    }

    // ==========================================
    // 获取当前位置
    // ==========================================
    @SuppressLint("MissingPermission")
    private void getCurrentLocation(Context ctx) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                Toast.makeText(ctx, "无法获取位置服务", Toast.LENGTH_SHORT).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                        && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(ctx, "缺少位置权限", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            android.location.Location loc = null;
            try {
                loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {}
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                } catch (Exception ignored) {}
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER);
                } catch (Exception ignored) {}
            }

            if (loc != null) {
                latitude = String.valueOf(loc.getLatitude());
                longitude = String.valueOf(loc.getLongitude());
                gpsPlace = "当前位置";
                Toast.makeText(ctx, "已获取当前位置", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, "无法获取当前位置，请先开启定位", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException se) {
            Toast.makeText(ctx, "位置权限被拒绝", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "获取当前位置失败", t);
        }
    }

    // ==========================================
    // Hook 腾讯地图定位
    // ==========================================
    private void hookTencentLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            String[] managerClassNames = {
                    "com.tencent.map.geolocation.sapp.TencentLocationManager",
                    "com.tencent.map.geolocation.TencentLocationManager",
                    "com.tencent.location.TencentLocationManager"
            };

            Class<?> managerClass = null;
            for (String className : managerClassNames) {
                try {
                    managerClass = XposedHelpers.findClass(className, lpparam.classLoader);
                    Log.e(TAG, "找到腾讯定位Manager类: " + className);
                    break;
                } catch (Throwable ignored) {}
            }

            if (managerClass == null) {
                Log.e(TAG, "未找到腾讯定位Manager类");
                return;
            }

            // Hook单次定位
            try {
                XposedBridge.hookAllMethods(managerClass, "requestSingleFreshLocation",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                hookLocationCallback(param);
                            }
                        });
                Log.e(TAG, "Hook requestSingleFreshLocation成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook requestSingleFreshLocation失败", t);
            }

            // Hook持续定位
            try {
                XposedBridge.hookAllMethods(managerClass, "requestLocationUpdates",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                hookLocationCallback(param);
                            }
                        });
                Log.e(TAG, "Hook requestLocationUpdates成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook requestLocationUpdates失败", t);
            }

            // Hook requestLocation（某些版本）
            try {
                XposedBridge.hookAllMethods(managerClass, "requestLocation",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                hookLocationCallback(param);
                            }
                        });
                Log.e(TAG, "Hook requestLocation成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook requestLocation失败", t);
            }

            Log.e(TAG, "腾讯定位Hook注册完成");
        } catch (Throwable t) {
            Log.e(TAG, "腾讯定位Hook异常", t);
        }
    }

    // Hook定位回调
    private void hookLocationCallback(XC_MethodHook.MethodHookParam param) {
        try {
            // 从参数中找到回调对象
            Object callback = null;
            for (Object arg : param.args) {
                if (arg != null && isLocationCallback(arg)) {
                    callback = arg;
                    break;
                }
            }

            if (callback == null) {
                return;
            }

            Class<?> callbackClass = callback.getClass();
            String className = callbackClass.getName();

            // 防重复Hook
            synchronized (hookedClasses) {
                if (hookedClasses.contains(className)) {
                    return;
                }
                hookedClasses.add(className);
            }

            Log.e(TAG, "找到定位回调类: " + className);

            // 查找onLocationChanged方法
            Method onLocationChanged = findOnLocationChangedMethod(callbackClass);
            if (onLocationChanged == null) {
                Log.e(TAG, "未找到onLocationChanged方法");
                return;
            }

            final Method finalMethod = onLocationChanged;
            XposedBridge.hookMethod(onLocationChanged, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Hook定位结果对象
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        hookLocationResult(param.args[0]);
                    }
                }
            });

            Log.e(TAG, "Hook onLocationChanged成功: " + finalMethod.getName());
        } catch (Throwable t) {
            Log.e(TAG, "Hook定位回调失败", t);
        }
    }

    // 判断是否是定位回调
    private boolean isLocationCallback(Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        // 检查是否有onLocationChanged方法
        for (Method method : clazz.getDeclaredMethods()) {
            if ("onLocationChanged".equals(method.getName())) {
                return true;
            }
        }
        // 检查接口
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().contains("LocationListener") ||
                    iface.getName().contains("TencentLocationListener")) {
                return true;
            }
        }
        return false;
    }

    // 查找onLocationChanged方法
    private Method findOnLocationChangedMethod(Class<?> clazz) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if ("onLocationChanged".equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
            // 检查父类和接口
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && !superClass.equals(Object.class)) {
                Method parentMethod = findOnLocationChangedMethod(superClass);
                if (parentMethod != null) return parentMethod;
            }
            for (Class<?> iface : clazz.getInterfaces()) {
                Method ifaceMethod = findOnLocationChangedMethod(iface);
                if (ifaceMethod != null) return ifaceMethod;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // Hook定位结果对象
    private void hookLocationResult(Object locationResult) {
        if (locationResult == null) return;

        Class<?> resultClass = locationResult.getClass();
        String className = resultClass.getName();

        synchronized (hookedClasses) {
            if (hookedClasses.contains(className)) {
                return;
            }
            hookedClasses.add(className);
        }

        Log.e(TAG, "Hook定位结果类: " + className);

        // Hook getLatitude
        try {
            XposedHelpers.findAndHookMethod(resultClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            try {
                                double fakeLat = getFakeLatitude();
                                param.setResult(fakeLat);
                            } catch (Throwable t) {
                                Log.e(TAG, "替换latitude失败", t);
                            }
                        }
                    });
            Log.e(TAG, "Hook getLatitude成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook getLatitude失败", t);
        }

        // Hook getLongitude
        try {
            XposedHelpers.findAndHookMethod(resultClass, "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            try {
                                double fakeLng = getFakeLongitude();
                                param.setResult(fakeLng);
                            } catch (Throwable t) {
                                Log.e(TAG, "替换longitude失败", t);
                            }
                        }
                    });
            Log.e(TAG, "Hook getLongitude成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook getLongitude失败", t);
        }
    }

    // 获取伪造的纬度
    private double getFakeLatitude() {
        try {
            if (xcxEnabled) {
                return xcxLat;
            }
            return Double.parseDouble(latitude);
        } catch (Throwable t) {
            return 39.908823;
        }
    }

    // 获取伪造的经度
    private double getFakeLongitude() {
        try {
            if (xcxEnabled) {
                return xcxLng;
            }
            return Double.parseDouble(longitude);
        } catch (Throwable t) {
            return 116.397470;
        }
    }

    // ==========================================
    // Hook 系统定位服务（备用方案）
    // ==========================================
    private void hookSystemLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook LocationManager.getLastKnownLocation
            XposedHelpers.findAndHookMethod(
                    android.location.LocationManager.class,
                    "getLastKnownLocation",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;
                            try {
                                android.location.Location original = (android.location.Location) param.getResult();
                                if (original == null) {
                                    // 创建一个新的Location
                                    original = new android.location.Location("fake");
                                    original.setTime(System.currentTimeMillis());
                                }
                                android.location.Location fake = new android.location.Location(original);
                                fake.setLatitude(getFakeLatitude());
                                fake.setLongitude(getFakeLongitude());
                                param.setResult(fake);
                            } catch (Throwable t) {
                                Log.e(TAG, "替换系统定位失败", t);
                            }
                        }
                    });
            Log.e(TAG, "系统定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "系统定位Hook失败", t);
        }

        // Hook LocationListener.onLocationChanged
        try {
            XposedHelpers.findAndHookMethod(
                    android.location.LocationListener.class,
                    "onLocationChanged",
                    android.location.Location.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;
                            try {
                                android.location.Location original = (android.location.Location) param.args[0];
                                if (original != null) {
                                    original.setLatitude(getFakeLatitude());
                                    original.setLongitude(getFakeLongitude());
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "替换LocationListener定位失败", t);
                            }
                        }
                    });
            Log.e(TAG, "LocationListener Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "LocationListener Hook失败", t);
        }
    }

    // ==========================================
    // 配置持久化
    // ==========================================
    private void loadPrefs() {
        if (appContext == null) return;
        try {
            SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            locationEnabled = sh.getBoolean(KEY_LOCATION_ENABLED, false);
            xcxEnabled = sh.getBoolean(KEY_XCX_ENABLED, false);
            latitude = sh.getString(KEY_LATITUDE, "39.908823");
            longitude = sh.getString(KEY_LONGITUDE, "116.397470");
            gpsPlace = sh.getString(KEY_GPS_PLACE, "北京市东城区");
            Log.e(TAG, "配置已加载: loc=" + locationEnabled + ", xcx=" + xcxEnabled);
        } catch (Throwable t) {
            Log.e(TAG, "加载配置失败", t);
        }
    }

    private void savePrefs() {
        if (appContext == null) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE).edit();
            editor.putBoolean(KEY_LOCATION_ENABLED, locationEnabled);
            editor.putBoolean(KEY_XCX_ENABLED, xcxEnabled);
            editor.putString(KEY_LATITUDE, latitude);
            editor.putString(KEY_LONGITUDE, longitude);
            editor.putString(KEY_GPS_PLACE, gpsPlace);
            editor.apply();
            Log.e(TAG, "配置已保存");
        } catch (Throwable t) {
            Log.e(TAG, "保存配置失败", t);
        }
    }

    // 更新小程序坐标（GCJ02）
    private void updateXcxCoordinates() {
        try {
            double lat = Double.parseDouble(latitude);
            double lng = Double.parseDouble(longitude);
            double[] result = CoordinateTransform.wgs84ToGcj02(lat, lng);
            xcxLat = result[0];
            xcxLng = result[1];
            Log.e(TAG, "小程序坐标已更新: " + xcxLat + ", " + xcxLng);
        } catch (Throwable t) {
            Log.e(TAG, "更新小程序坐标失败", t);
        }
    }

    // ==========================================
    // UI 辅助方法
    // ==========================================
    private int dp2px(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    private TextView createLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF555555);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp2px(ctx, 10);
        params.bottomMargin = dp2px(ctx, 4);
        tv.setLayoutParams(params);
        return tv;
    }

    private EditText createEditText(Context ctx, String text, String hint) {
        EditText et = new EditText(ctx);
        et.setText(text);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        et.setPadding(dp2px(ctx, 12), dp2px(ctx, 10),
                dp2px(ctx, 12), dp2px(ctx, 10));
        et.setBackgroundColor(0xFFF0F0F0);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp2px(ctx, 4);
        et.setLayoutParams(params);
        return et;
    }

    // 创建开关行
    private LinearLayout createSwitchRow(Context ctx, String title,
                                         boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(ctx, 4), dp2px(ctx, 12), dp2px(ctx, 4), dp2px(ctx, 12));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        final TextView toggleTv = new TextView(ctx);
        toggleTv.setText(isChecked ? "开启" : "关闭");
        toggleTv.setTextColor(isChecked ? 0xFF27AE60 : 0xFFE74C3C);
        toggleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggleTv.setGravity(Gravity.CENTER);
        toggleTv.setPadding(dp2px(ctx, 16), dp2px(ctx, 6), dp2px(ctx, 16), dp2px(ctx, 6));
        toggleTv.setBackgroundColor(0x10000000);
        final boolean[] checked = {isChecked};
        final CompoundButton.OnCheckedChangeListener finalListener = listener;
        toggleTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checked[0] = !checked[0];
                toggleTv.setText(checked[0] ? "开启" : "关闭");
                toggleTv.setTextColor(checked[0] ? 0xFF27AE60 : 0xFFE74C3C);
                finalListener.onCheckedChanged(null, checked[0]);
            }
        });
        row.addView(toggleTv);

        return row;
    }

    // ==========================================
    // TextWatcher 简化类
    // ==========================================
    private static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        abstract void onTextChanged(String text);
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            onTextChanged(s.toString());
        }
        @Override public void afterTextChanged(android.text.Editable s) {}
    }
}
