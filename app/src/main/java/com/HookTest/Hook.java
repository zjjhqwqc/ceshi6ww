package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
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
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HookTest";
    private static final String PREFS_NAME = "hookdata";

    // 全局状态
    private static boolean locationEnabled = false;
    private static String customLat = "";
    private static String customLng = "";

    // 面板中的EditText引用
    private static EditText latEdit;
    private static EditText lngEdit;

    // 悬浮窗相关
    private static Activity hostActivity;
    private static ViewGroup contentParent;
    private static View floatView;
    private static View panelView;
    private static boolean isPanelShowing = false;
    private static int statusBarHeight = 0;
    private static WindowManager windowManager;
    private static WindowManager.LayoutParams floatWindowParams;
    private static boolean isFloatWindowShowing = false;

    private static Context appContext;
    private static Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.tencent.mm")) {
            return;
        }

        Log.e(TAG, "包名匹配，开始Hook");

        // 直接在 handleLoadPackage 中初始化并执行 Hook
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "handleLoadPackage 直接获取到Context");
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleLoadPackage 获取Context失败", t);
        }

        // 使用 Application.attach 获取 Context，同时注册定位Hook
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                appContext = (Context) param.args[0];
                Log.e(TAG, "获取到Context: " + appContext);
                loadPrefs();
                hookAMapLocation(lpparam);
                hookTencentLocation(lpparam);
            }
        });

        // 如果 Application.attach 已经执行过（FPA框架延迟加载），直接用 classLoader 获取 Context
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app != null && appContext == null) {
                appContext = app.getApplicationContext();
                Log.e(TAG, "通过 currentApplication 获取到Context: " + appContext);
                loadPrefs();
                hookAMapLocation(lpparam);
                hookTencentLocation(lpparam);
            }
        } catch (Throwable t) {
            Log.e(TAG, "currentApplication 获取失败", t);
        }

        // 方式一：Hook HomeUI$PlusActionView 构造函数（微信加号菜单 - 最可靠）
        try {
            hookWeChatPlusMenu(lpparam);
            Log.e(TAG, "Hook 微信加号菜单成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook 微信加号菜单失败", t);
        }

        // 方式二：Hook LauncherUI 显示悬浮窗
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;
                    Log.e(TAG, "LauncherUI onCreate");
                    uiHandler.postDelayed(() -> {
                        try {
                            hostActivity = activity;
                            // 尝试使用WindowManager全局悬浮窗
                            showGlobalFloatWindow(activity);
                        } catch (Throwable t) {
                            Log.e(TAG, "显示全局悬浮窗失败，尝试视图注入", t);
                            // 降级到视图注入
                            try {
                                contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                                if (floatView == null) {
                                    showFloatWindow(activity);
                                }
                            } catch (Throwable t2) {
                                Log.e(TAG, "视图注入也失败", t2);
                            }
                        }
                    }, 1000);
                }
            });
            Log.e(TAG, "Hook LauncherUI 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook LauncherUI 失败，尝试 Hook 任意 Activity", t);
            // 兜底 Hook Activity.onResume
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                private boolean shown = false;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (shown) return;
                    Activity activity = (Activity) param.thisObject;
                    if (activity.getClass().getName().contains("tencent.mm")) {
                        shown = true;
                        Log.e(TAG, "Activity onResume 兜底显示悬浮窗: " + activity.getClass().getName());
                        uiHandler.postDelayed(() -> {
                            try {
                                hostActivity = activity;
                                showGlobalFloatWindow(activity);
                            } catch (Throwable t2) {
                                Log.e(TAG, "兜底显示悬浮窗失败", t2);
                                try {
                                    contentParent = (ViewGroup) activity.findViewById(android.R.id.content);
                                    if (floatView == null) {
                                        showFloatWindow(activity);
                                    }
                                } catch (Throwable t3) {
                                    Log.e(TAG, "视图注入兜底也失败", t3);
                                }
                            }
                        }, 1000);
                    }
                }
            });
        }
    }

    // ======================== 微信加号菜单Hook ========================

    private void hookWeChatPlusMenu(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试不同版本的类名
        String[] possibleClassNames = {
            "com.tencent.mm.ui.HomeUI$PlusActionView",
            "com.tencent.mm.ui.HomeUI$b",
            "com.tencent.mm.ui.home.HomeUI$PlusActionView",
            "com.tencent.mm.ui.home.HomeUI$b"
        };

        Class<?> plusActionViewClass = null;
        for (String className : possibleClassNames) {
            try {
                plusActionViewClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
                if (plusActionViewClass != null) {
                    Log.e(TAG, "找到PlusActionView类: " + className);
                    break;
                }
            } catch (Throwable t) {
                Log.e(TAG, "尝试类失败: " + className, t);
            }
        }

        if (plusActionViewClass == null) {
            Log.e(TAG, "未找到PlusActionView类，跳过加号菜单Hook");
            return;
        }

        // 查找getActionView方法
        Method getActionViewMethod = null;
        Method[] methods = plusActionViewClass.getDeclaredMethods();
        for (Method m : methods) {
            if (m.getReturnType() == View.class && m.getParameterTypes().length == 0) {
                getActionViewMethod = m;
                getActionViewMethod.setAccessible(true);
                Log.e(TAG, "找到getActionView方法: " + m.getName());
                break;
            }
        }

        final Method finalGetActionViewMethod = getActionViewMethod;

        // Hook构造函数 - 尝试常见的几种参数组合
        XC_MethodHook constructorHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Log.e(TAG, "PlusActionView 构造函数被调用");

                if (finalGetActionViewMethod != null) {
                    try {
                        View actionView = (View) finalGetActionViewMethod.invoke(param.thisObject);
                        if (actionView != null) {
                            // 设置点击监听器，点击打开设置面板
                            actionView.setOnClickListener(v -> {
                                Log.e(TAG, "PlusActionView 被点击");
                                try {
                                    Context context = v.getContext();
                                    if (context instanceof Activity) {
                                        hostActivity = (Activity) context;
                                    }
                                    showPanelDialog(v.getContext());
                                } catch (Throwable t) {
                                    Log.e(TAG, "显示设置面板失败", t);
                                }
                            });

                            // 设置长按监听器（备用触发方式）
                            actionView.setOnLongClickListener(v -> {
                                Log.e(TAG, "PlusActionView 被长按");
                                try {
                                    Context context = v.getContext();
                                    if (context instanceof Activity) {
                                        hostActivity = (Activity) context;
                                    }
                                    showPanelDialog(v.getContext());
                                    return true;
                                } catch (Throwable t) {
                                    Log.e(TAG, "长按显示设置面板失败", t);
                                    return false;
                                }
                            });

                            Log.e(TAG, "PlusActionView 监听器设置成功");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "获取ActionView失败", t);
                    }
                }
            }
        };

        // 尝试不同的构造函数签名
        String className = plusActionViewClass.getName();
        try {
            // 无参构造函数
            XposedHelpers.findAndHookConstructor(className, lpparam.classLoader, constructorHook);
            Log.e(TAG, "Hook无参构造函数成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook无参构造函数失败，尝试有参", t);
            try {
                // Context参数构造函数
                XposedHelpers.findAndHookConstructor(className, lpparam.classLoader,
                        Context.class, constructorHook);
                Log.e(TAG, "Hook Context构造函数成功");
            } catch (Throwable t2) {
                Log.e(TAG, "Hook Context构造函数失败", t2);
                try {
                    // View参数构造函数
                    XposedHelpers.findAndHookConstructor(className, lpparam.classLoader,
                            View.class, constructorHook);
                    Log.e(TAG, "Hook View构造函数成功");
                } catch (Throwable t3) {
                    Log.e(TAG, "Hook View构造函数失败", t3);
                }
            }
        }
    }

    // ======================== 全局悬浮窗（WindowManager） ========================

    @SuppressLint("ClickableViewAccessibility")
    private void showGlobalFloatWindow(Context context) {
        if (isFloatWindowShowing) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager 为 null");
            return;
        }

        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }

        // 创建悬浮按钮
        final LinearLayout floatBtn = new LinearLayout(context);
        floatBtn.setOrientation(LinearLayout.HORIZONTAL);
        floatBtn.setGravity(Gravity.CENTER);
        floatBtn.setBackgroundColor(0xFF4A90E2);
        floatBtn.setPadding(dp2px(context, 12), dp2px(context, 8), dp2px(context, 12), dp2px(context, 8));

        TextView btnText = new TextView(context);
        btnText.setText("定位");
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        floatBtn.addView(btnText);

        // 设置圆角背景
        floatBtn.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
        floatBtn.setBackgroundColor(0xFF4A90E2);

        // 悬浮窗参数
        floatWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        floatWindowParams.gravity = Gravity.TOP | Gravity.END;
        floatWindowParams.x = dp2px(context, 16);
        floatWindowParams.y = statusBarHeight + dp2px(context, 100);

        // 拖拽逻辑
        final int[] touchPosition = new int[2];
        final int[] initialPosition = new int[2];
        final boolean[] isDragging = {false};
        final long[] downTime = {0};

        floatBtn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downTime[0] = System.currentTimeMillis();
                    isDragging[0] = false;
                    touchPosition[0] = (int) event.getRawX();
                    touchPosition[1] = (int) event.getRawY();
                    initialPosition[0] = floatWindowParams.x;
                    initialPosition[1] = floatWindowParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - touchPosition[0];
                    int dy = (int) event.getRawY() - touchPosition[1];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging[0] = true;
                        floatWindowParams.x = initialPosition[0] - dx;
                        floatWindowParams.y = initialPosition[1] + dy;
                        try {
                            windowManager.updateViewLayout(floatBtn, floatWindowParams);
                        } catch (Exception e) {
                            Log.e(TAG, "更新悬浮窗位置失败", e);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging[0] && System.currentTimeMillis() - downTime[0] < 300) {
                        // 点击事件
                        try {
                            showPanelDialog(context);
                        } catch (Throwable t) {
                            Log.e(TAG, "显示面板失败", t);
                        }
                    }
                    return true;
            }
            return false;
        });

        try {
            windowManager.addView(floatBtn, floatWindowParams);
            floatView = floatBtn;
            isFloatWindowShowing = true;
            Log.e(TAG, "全局悬浮窗显示成功");
        } catch (Exception e) {
            Log.e(TAG, "添加全局悬浮窗失败", e);
            throw new RuntimeException(e);
        }
    }

    private int getWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    private int dp2px(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    // ======================== 对话框方式显示设置面板 ========================

    private void showPanelDialog(Context context) {
        final Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);

        LinearLayout mainContainer = new LinearLayout(context);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setBackgroundColor(0xF0FFFFFF);
        mainContainer.setPadding(dp2px(context, 16), dp2px(context, 16), dp2px(context, 16), dp2px(context, 16));

        // 标题栏
        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleBar.setLayoutParams(titleParams);

        TextView titleText = new TextView(context);
        titleText.setText("定位设置");
        titleText.setTextColor(0xFF000000);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleText);

        Button closeBtn = new Button(context);
        closeBtn.setText("✕");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setBackgroundColor(0x00000000);
        closeBtn.setTextColor(0xFF666666);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        titleBar.addView(closeBtn);
        mainContainer.addView(titleBar);

        // 滚动内容
        ScrollView contentScroll = new ScrollView(context);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);

        // 定位模块
        addLocationContent(context, contentLayout);

        mainContainer.addView(contentScroll);

        // 保存按钮
        Button saveBtn = new Button(context);
        saveBtn.setText("💾 保存设置");
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setPadding(0, dp2px(context, 12), 0, dp2px(context, 12));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp2px(context, 12);
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        mainContainer.addView(saveBtn);

        dialog.setContentView(mainContainer);
        dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.getWindow().setLayout(
                (int) (context.getResources().getDisplayMetrics().widthPixels * 0.9),
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.8)
        );
        dialog.show();
    }

    // ======================== 悬浮窗UI（视图注入方式 - 备用） ========================

    @SuppressLint("ClickableViewAccessibility")
    private void showFloatWindow(Activity activity) {
        if (floatView != null) return;

        hostActivity = activity;
        contentParent = (ViewGroup) activity.findViewById(android.R.id.content);

        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
        }

        // 创建全屏容器
        final LinearLayout floatContainer = new LinearLayout(activity);
        floatContainer.setOrientation(LinearLayout.VERTICAL);
        floatContainer.setGravity(Gravity.TOP | Gravity.END);
        floatContainer.setPadding(0, statusBarHeight + 50, 16, 0);
        floatContainer.setClickable(false);
        floatContainer.setFocusable(false);

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        // 创建悬浮按钮
        final LinearLayout floatBtn = new LinearLayout(activity);
        floatBtn.setOrientation(LinearLayout.HORIZONTAL);
        floatBtn.setBackgroundColor(0xCC000000);
        floatBtn.setPadding(16, 8, 16, 8);
        floatBtn.setGravity(Gravity.CENTER);
        floatBtn.setClickable(true);
        floatBtn.setFocusable(true);

        TextView btnText = new TextView(activity);
        btnText.setText("⚙ Hook");
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        floatBtn.addView(btnText);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        floatBtn.setLayoutParams(btnParams);

        // 点击事件
        floatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel(activity);
            }
        });

        // 触摸/拖拽事件
        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final long[] startTime = new long[1];
        final boolean[] isDragging = {false};

        floatBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX[0] = (int) v.getX();
                        initialY[0] = (int) v.getY();
                        touchX[0] = event.getRawX();
                        touchY[0] = event.getRawY();
                        startTime[0] = System.currentTimeMillis();
                        isDragging[0] = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX[0]);
                        int dy = (int) (event.getRawY() - touchY[0]);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging[0] = true;
                        }
                        v.setX(initialX[0] + dx);
                        v.setY(initialY[0] + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging[0] && System.currentTimeMillis() - startTime[0] < 300) {
                            return false;
                        }
                        return true;
                }
                return false;
            }
        });

        floatContainer.addView(floatBtn);

        try {
            contentParent.addView(floatContainer, containerParams);
            floatView = floatContainer;
            Log.e(TAG, "悬浮按钮显示成功（视图注入方式）");
        } catch (Throwable t) {
            Log.e(TAG, "悬浮按钮显示失败", t);
        }
    }

    // ======================== 面板控制 ========================

    private void togglePanel(Context ctx) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(ctx);
        }
    }

    private void hidePanel() {
        if (panelView != null && contentParent != null) {
            try {
                contentParent.removeView(panelView);
            } catch (Throwable t) {
                Log.e(TAG, "移除面板失败", t);
            }
            panelView = null;
            isPanelShowing = false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPanel(Context ctx) {
        if (isPanelShowing) return;

        if (contentParent == null && hostActivity != null) {
            contentParent = (ViewGroup) hostActivity.findViewById(android.R.id.content);
        }
        if (contentParent == null) {
            Log.e(TAG, "contentParent 为空，无法显示面板");
            showFallbackDialog(ctx);
            return;
        }

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int panelWidth = Math.min(900, (int) (screenWidth * 0.9));

        LinearLayout mainContainer = new LinearLayout(ctx);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setBackgroundColor(0xE0FFFFFF);
        mainContainer.setPadding(16, 16, 16, 16);

        LinearLayout titleBar = new LinearLayout(ctx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleBar.setLayoutParams(titleParams);

        TextView titleText = new TextView(ctx);
        titleText.setText("Hook 设置面板");
        titleText.setTextColor(0xFF000000);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleText);

        Button closeBtn = new Button(ctx);
        closeBtn.setText("✕");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setBackgroundColor(0x00000000);
        closeBtn.setTextColor(0xFF666666);
        closeBtn.setOnClickListener(v -> hidePanel());
        titleBar.addView(closeBtn);
        mainContainer.addView(titleBar);

        ScrollView contentScroll = new ScrollView(ctx);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout contentLayout = new LinearLayout(ctx);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);

        // ---- 模块1: 定位 ----
        addCollapsibleSection(ctx, contentLayout, "🌏 模拟定位", locationEnabled,
                (buttonView, isChecked) -> {
                    locationEnabled = isChecked;
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean("locationEnabled", isChecked).apply();
                },
                (layout) -> addLocationContent(ctx, layout));

        mainContainer.addView(contentScroll);

        Button saveBtn = new Button(ctx);
        saveBtn.setText("💾 保存所有设置");
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setPadding(0, 20, 0, 20);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = 16;
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show();
        });
        mainContainer.addView(saveBtn);

        FrameLayout.LayoutParams panelLayoutParams = new FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        panelLayoutParams.gravity = Gravity.CENTER;

        final int[] panelStartLeft = new int[1];
        final int[] panelStartTop = new int[1];
        final float[] touchStartX = new float[1];
        final float[] touchStartY = new float[1];

        titleBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        panelStartLeft[0] = panelLayoutParams.leftMargin;
                        panelStartTop[0] = panelLayoutParams.topMargin;
                        touchStartX[0] = event.getRawX();
                        touchStartY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLayoutParams.leftMargin = panelStartLeft[0] + (int) (event.getRawX() - touchStartX[0]);
                        panelLayoutParams.topMargin = panelStartTop[0] + (int) (event.getRawY() - touchStartY[0]);
                        panelLayoutParams.gravity = Gravity.TOP | Gravity.START;
                        if (contentParent != null && panelView != null) {
                            try {
                                contentParent.updateViewLayout(panelView, panelLayoutParams);
                            } catch (Throwable t) {
                                Log.e(TAG, "更新面板位置失败", t);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            contentParent.addView(mainContainer, panelLayoutParams);
            panelView = mainContainer;
            isPanelShowing = true;
            Log.e(TAG, "设置面板显示成功（视图注入方式）");
        } catch (Throwable t) {
            Log.e(TAG, "设置面板显示失败", t);
            showFallbackDialog(ctx);
        }
    }

    // ---- 折叠模块 ----
    private void addCollapsibleSection(Context ctx, LinearLayout parentLayout, String title,
            boolean isChecked,
            CompoundButton.OnCheckedChangeListener toggleListener,
            java.util.function.Consumer<LinearLayout> contentBuilder) {

        final boolean[] checked = {isChecked};

        LinearLayout sectionLayout = new LinearLayout(ctx);
        sectionLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionLayout.setLayoutParams(sectionParams);

        LinearLayout headerLayout = new LinearLayout(ctx);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(8, 12, 8, 12);
        headerLayout.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerLayout.setLayoutParams(headerParams);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerLayout.addView(tv);

        TextView statusToggle = new TextView(ctx);
        statusToggle.setText(checked[0] ? "开启" : "关闭");
        statusToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
        statusToggle.setGravity(Gravity.CENTER);
        statusToggle.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusToggle.setLayoutParams(toggleParams);
        headerLayout.addView(statusToggle);

        sectionLayout.addView(headerLayout);

        LinearLayout contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(8, 0, 8, 8);
        contentContainer.setVisibility(View.GONE);

        contentBuilder.accept(contentContainer);

        sectionLayout.addView(contentContainer);

        View divider = new View(ctx);
        divider.setBackgroundColor(0xFFDDDDDD);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divider.setLayoutParams(divParams);
        sectionLayout.addView(divider);

        final boolean[] isExpanded = {false};
        headerLayout.setOnClickListener(v -> {
            isExpanded[0] = !isExpanded[0];
            contentContainer.setVisibility(isExpanded[0] ? View.VISIBLE : View.GONE);
        });

        statusToggle.setOnClickListener(v -> {
            checked[0] = !checked[0];
            statusToggle.setText(checked[0] ? "开启" : "关闭");
            statusToggle.setTextColor(checked[0] ? 0xFF4CAF50 : 0xFFF44336);
            toggleListener.onCheckedChanged(null, checked[0]);
        });

        parentLayout.addView(sectionLayout);
    }

    // ---- 定位内容（含地图选点） ----
    private void addLocationContent(Context ctx, LinearLayout layout) {
        addDivider(layout);

        layout.addView(createLabel(ctx, "经度 (Longitude):"));
        EditText lngEditLocal = createEditText(ctx, customLng, "例如: 121.808512");
        lngEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLng = s;
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lng", s).apply();
        }));
        lngEdit = lngEditLocal;
        layout.addView(lngEditLocal);

        layout.addView(createLabel(ctx, "纬度 (Latitude):"));
        EditText latEditLocal = createEditText(ctx, customLat, "例如: 31.141585");
        latEditLocal.addTextChangedListener(new SimpleTextWatcher(s -> {
            customLat = s;
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("lat", s).apply();
        }));
        latEdit = latEditLocal;
        layout.addView(latEditLocal);

        addDivider(layout);

        // 地图选点
        Button mapPickerBtn = new Button(ctx);
        mapPickerBtn.setText("🗺 地图选点");
        mapPickerBtn.setOnClickListener(v -> showMapPicker(ctx));
        layout.addView(mapPickerBtn);

        addDivider(layout);

        Button curLocBtn = new Button(ctx);
        curLocBtn.setText("📍 获取当前位置");
        curLocBtn.setOnClickListener(v -> {
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    Toast.makeText(ctx, "无法获取位置服务", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                            && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(ctx, "缺少位置权限", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                if (loc == null) {
                    loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                }
                if (loc != null) {
                    customLat = String.valueOf(loc.getLatitude());
                    customLng = String.valueOf(loc.getLongitude());
                    lngEdit.setText(customLng);
                    latEdit.setText(customLat);
                    SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                    ed.putString("lat", customLat);
                    ed.putString("lng", customLng);
                    ed.apply();
                    Toast.makeText(ctx, "已获取当前位置", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "无法获取当前位置", Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException se) {
                Toast.makeText(ctx, "位置权限被拒绝", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(ctx, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(curLocBtn);
    }

    // ---- 腾讯地图Web选点 ----
    @SuppressLint("SetJavaScriptEnabled")
    private void showMapPicker(Context ctx) {
        Activity act = ctx instanceof Activity ? (Activity) ctx : hostActivity;
        if (act == null) {
            Toast.makeText(ctx, "无法打开地图选点", Toast.LENGTH_SHORT).show();
            return;
        }
        final Activity activity = act;
        Dialog dialog = new Dialog(activity);
        dialog.setTitle("地图选点");

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        WebView webView = new WebView(activity);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(webParams);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return handleMapUrl(url, dialog, activity);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMapUrl(url, dialog, activity);
            }

            private boolean handleMapUrl(String url, Dialog dialog, Context ctx) {
                if (url.startsWith("https://www.baidu.com")) {
                    Uri uri = Uri.parse(url);
                    String latng = uri.getQueryParameter("latng");
                    if (latng != null && !latng.isEmpty()) {
                        String[] parts = latng.split(",");
                        if (parts.length == 2) {
                            customLat = parts[0].trim();
                            customLng = parts[1].trim();
                            if (latEdit != null) latEdit.setText(customLat);
                            if (lngEdit != null) lngEdit.setText(customLng);
                            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                            ed.putString("lat", customLat);
                            ed.putString("lng", customLng);
                            ed.apply();
                            Toast.makeText(ctx, "已选择位置: " + customLat + ", " + customLng, Toast.LENGTH_SHORT).show();
                        }
                    }
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl("https://mapapi.qq.com/web/mapComponents/locationPicker/v/index.html?search=1&type=0&backurl=https://www.baidu.com&key=54NBZ-F3IWI-2DJGQ-UXGAY-YOY2F-MXFKE");

        container.addView(webView);
        dialog.setContentView(container);

        dialog.show();
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    // ======================== Hook 高德定位 ========================

    private void hookAMapLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = appContext.getClassLoader();
            Class<?> aMapLocationClass = cl.loadClass("com.amap.api.location.AMapLocation");
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String latStr = sh.getString("lat", "");
                    if (latStr.isEmpty()) return;
                    try {
                        double lat = Double.parseDouble(latStr);
                        Log.e(TAG, "Hook getLatitude: " + lat);
                        param.setResult(lat);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "纬度格式错误", e);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    boolean isGps = sh.getBoolean("locationEnabled", false);
                    if (!isGps) return;
                    String lngStr = sh.getString("lng", "");
                    if (lngStr.isEmpty()) return;
                    try {
                        double lng = Double.parseDouble(lngStr);
                        Log.e(TAG, "Hook getLongitude: " + lng);
                        param.setResult(lng);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "经度格式错误", e);
                    }
                }
            });
            Log.e(TAG, "高德定位Hook成功（实时配置模式）");
        } catch (Throwable t) {
            Log.e(TAG, "高德定位Hook失败", t);
        }
    }

    // ======================== Hook 腾讯地图定位 ========================

    private void hookTencentLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Hook TencentLocationManager 的 requestSingleFreshLocation 方法
            try {
                Class<?> tencentLocationManagerClass = cl.loadClass("com.tencent.map.geolocation.sapp.TencentLocationManager");
                Method[] methods = tencentLocationManagerClass.getDeclaredMethods();
                boolean foundRequestSingle = false;
                boolean foundRequestUpdates = false;

                for (Method method : methods) {
                    String methodName = method.getName();
                    // Hook requestSingleFreshLocation - 拦截单次定位请求
                    if ("requestSingleFreshLocation".equals(methodName) && !foundRequestSingle) {
                        XposedHelpers.findAndHookMethod(tencentLocationManagerClass, methodName,
                                method.getParameterTypes(), new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                                boolean isLoc = sh.getBoolean("locationEnabled", false);
                                if (!isLoc) return;
                                // 尝试Hook回调监听器
                                Object listener = findListenerInArgs(param.args);
                                if (listener != null) {
                                    hookTencentLocationListener(listener);
                                }
                            }
                        });
                        foundRequestSingle = true;
                        Log.e(TAG, "腾讯地图 requestSingleFreshLocation Hook成功");
                    }
                    // Hook requestLocationUpdates - 拦截连续定位请求
                    if ("requestLocationUpdates".equals(methodName) && !foundRequestUpdates) {
                        XposedHelpers.findAndHookMethod(tencentLocationManagerClass, methodName,
                                method.getParameterTypes(), new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                                boolean isLoc = sh.getBoolean("locationEnabled", false);
                                if (!isLoc) return;
                                Object listener = findListenerInArgs(param.args);
                                if (listener != null) {
                                    hookTencentLocationListener(listener);
                                }
                            }
                        });
                        foundRequestUpdates = true;
                        Log.e(TAG, "腾讯地图 requestLocationUpdates Hook成功");
                    }
                }

                if (!foundRequestSingle && !foundRequestUpdates) {
                    Log.e(TAG, "未找到腾讯地图定位方法，尝试备用方案");
                    hookTencentLocationFallback(cl);
                }
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "未找到 TencentLocationManager 类，尝试备用方案", e);
                hookTencentLocationFallback(cl);
            }

            Log.e(TAG, "腾讯地图定位Hook初始化完成");
        } catch (Throwable t) {
            Log.e(TAG, "腾讯地图定位Hook失败", t);
        }
    }

    /**
     * 从方法参数中查找定位监听器对象
     */
    private Object findListenerInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            Class<?> clazz = arg.getClass();
            // 检查是否实现了 TencentLocationListener 接口
            if (isTencentLocationListener(clazz)) {
                return arg;
            }
            // 检查接口
            Class<?>[] interfaces = clazz.getInterfaces();
            for (Class<?> iface : interfaces) {
                if (iface.getName().contains("TencentLocationListener")) {
                    return arg;
                }
            }
        }
        return null;
    }

    private boolean isTencentLocationListener(Class<?> clazz) {
        if (clazz == null) return false;
        String name = clazz.getName();
        if (name.contains("TencentLocationListener")) return true;
        Class<?>[] interfaces = clazz.getInterfaces();
        for (Class<?> iface : interfaces) {
            if (iface.getName().contains("TencentLocationListener")) return true;
        }
        return false;
    }

    /**
     * Hook 腾讯地图定位监听器的 onLocationChanged 回调
     */
    private void hookTencentLocationListener(final Object listener) {
        if (listener == null) return;
        final Class<?> listenerClass = listener.getClass();

        // 查找 onLocationChanged 方法并Hook
        try {
            Method[] methods = listenerClass.getDeclaredMethods();
            for (Method method : methods) {
                if ("onLocationChanged".equals(method.getName())) {
                    final Class<?>[] paramTypes = method.getParameterTypes();
                    XposedHelpers.findAndHookMethod(listenerClass, "onLocationChanged",
                            paramTypes, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                            boolean isLoc = sh.getBoolean("locationEnabled", false);
                            if (!isLoc) return;

                            String latStr = sh.getString("lat", "");
                            String lngStr = sh.getString("lng", "");
                            if (latStr.isEmpty() || lngStr.isEmpty()) return;

                            try {
                                double lat = Double.parseDouble(latStr);
                                double lng = Double.parseDouble(lngStr);

                                // 遍历参数，找到 TencentLocation 对象并修改经纬度
                                for (int i = 0; i < param.args.length; i++) {
                                    Object arg = param.args[i];
                                    if (arg == null) continue;
                                    if (isTencentLocation(arg)) {
                                        modifyTencentLocation(arg, lat, lng);
                                        Log.e(TAG, "腾讯地图 onLocationChanged Hook: lat=" + lat + ", lng=" + lng);
                                    }
                                }
                            } catch (NumberFormatException e) {
                                Log.e(TAG, "经纬度格式错误", e);
                            }
                        }
                    });
                    Log.e(TAG, "腾讯地图 onLocationChanged Hook成功: " + listenerClass.getName());
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook onLocationChanged 失败: " + listenerClass.getName(), t);
        }
    }

    private boolean isTencentLocation(Object obj) {
        if (obj == null) return false;
        String className = obj.getClass().getName();
        return className.contains("TencentLocation") || className.contains("Location");
    }

    /**
     * 修改 TencentLocation 对象的经纬度
     */
    private void modifyTencentLocation(Object locationObj, double lat, double lng) {
        if (locationObj == null) return;
        Class<?> clazz = locationObj.getClass();
        try {
            // 尝试通过反射设置 latitude 和 longitude 字段
            java.lang.reflect.Field latField = findField(clazz, "latitude", "mLatitude", "lat");
            if (latField != null) {
                latField.setAccessible(true);
                latField.setDouble(locationObj, lat);
            }
            java.lang.reflect.Field lngField = findField(clazz, "longitude", "mLongitude", "lng");
            if (lngField != null) {
                lngField.setAccessible(true);
                lngField.setDouble(locationObj, lng);
            }
        } catch (Throwable t) {
            Log.e(TAG, "修改TencentLocation失败", t);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                return field;
            } catch (NoSuchFieldException e) {
                // 继续查找下一个
            }
        }
        // 在父类中查找
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
            return findField(superClass, fieldNames);
        }
        // 遍历所有字段找相似名称
        try {
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                String fname = f.getName().toLowerCase();
                for (String name : fieldNames) {
                    if (fname.contains(name.toLowerCase())) {
                        return f;
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    /**
     * 备用方案：直接Hook TencentLocation 类的 getLatitude/getLongitude 方法
     */
    private void hookTencentLocationFallback(ClassLoader cl) {
        try {
            // 尝试多种类名
            String[] classNames = {
                "com.tencent.map.geolocation.sapp.TencentLocation",
                "com.tencent.map.geolocation.TencentLocation",
                "com.tencent.location.TencentLocation"
            };

            Class<?> locationClass = null;
            for (String cn : classNames) {
                try {
                    locationClass = cl.loadClass(cn);
                    Log.e(TAG, "找到腾讯地图定位类: " + cn);
                    break;
                } catch (ClassNotFoundException e) {
                    // 继续尝试
                }
            }

            if (locationClass == null) {
                Log.e(TAG, "未找到腾讯地图定位类");
                return;
            }

            // Hook getLatitude
            try {
                XposedHelpers.findAndHookMethod(locationClass, "getLatitude", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        boolean isLoc = sh.getBoolean("locationEnabled", false);
                        if (!isLoc) return;
                        String latStr = sh.getString("lat", "");
                        if (latStr.isEmpty()) return;
                        try {
                            double lat = Double.parseDouble(latStr);
                            param.setResult(lat);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "纬度格式错误", e);
                        }
                    }
                });
                Log.e(TAG, "腾讯地图 getLatitude Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook getLatitude失败", t);
            }

            // Hook getLongitude
            try {
                XposedHelpers.findAndHookMethod(locationClass, "getLongitude", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        boolean isLoc = sh.getBoolean("locationEnabled", false);
                        if (!isLoc) return;
                        String lngStr = sh.getString("lng", "");
                        if (lngStr.isEmpty()) return;
                        try {
                            double lng = Double.parseDouble(lngStr);
                            param.setResult(lng);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "经度格式错误", e);
                        }
                    }
                });
                Log.e(TAG, "腾讯地图 getLongitude Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook getLongitude失败", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "腾讯地图备用Hook方案失败", t);
        }
    }

    // ======================== 配置持久化 ========================

    private void loadPrefs() {
        if (appContext == null) return;
        SharedPreferences sh = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        locationEnabled = sh.getBoolean("locationEnabled", false);
        customLat = sh.getString("lat", "");
        customLng = sh.getString("lng", "");
        Log.e(TAG, "配置已加载: loc=" + locationEnabled);
    }

    private void savePrefs() {
        if (appContext == null) return;
        SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean("locationEnabled", locationEnabled);
        editor.putString("lat", customLat);
        editor.putString("lng", customLng);
        editor.apply();
        Log.e(TAG, "配置已保存");
    }

    // ---- UI辅助 ----

    private TextView createLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 12;
        params.bottomMargin = 4;
        tv.setLayoutParams(params);
        return tv;
    }

    private EditText createEditText(Context ctx, String text, String hint) {
        EditText et = new EditText(ctx);
        et.setText(text);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        et.setPadding(16, 12, 16, 12);
        et.setBackgroundColor(0xFFF0F0F0);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(params);
        return et;
    }

    private void addDivider(LinearLayout layout) {
        View divider = new View(layout.getContext());
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = 12;
        params.bottomMargin = 12;
        divider.setLayoutParams(params);
        layout.addView(divider);
    }

    private void showFallbackDialog(Context ctx) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("Hook设置")
                    .setMessage("悬浮窗权限未授予，请授予后重试。\n\n" +
                            "当前状态:\n" +
                            "定位: " + (locationEnabled ? "开启" : "关闭"))
                    .setCancelable(true)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "显示回退Dialog失败", t);
        }
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Callback callback;
        interface Callback {
            void onTextChanged(String text);
        }
        SimpleTextWatcher(Callback callback) {
            this.callback = callback;
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            callback.onTextChanged(s.toString());
        }
        @Override
        public void afterTextChanged(android.text.Editable s) {}
    }
}
