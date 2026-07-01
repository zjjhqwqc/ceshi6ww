package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed Hook 主类
 * 悬浮窗实现方式：Activity视图注入（View Injection）
 * 
 * 原理：
 * 1. Hook Activity.onCreate() 方法
 * 2. 在afterHookedMethod中获取Activity的content容器(android.R.id.content)
 * 3. 将悬浮球作为普通View通过addView()添加到容器中
 * 4. 无需SYSTEM_ALERT_WINDOW权限，兼容性更好
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HookTest";
    private static final String PREFS_NAME = "hookdata";
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    // 全局配置状态
    private static boolean locationEnabled = false;
    private static String customLat = "";
    private static String customLng = "";

    // 当前Activity引用（静态保存，跟随Activity生命周期）
    private static Activity currentActivity = null;
    private static Context appContext = null;
    private static ClassLoader targetClassLoader = null;

    // UI Handler
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 悬浮球相关（每个Activity独立实例）
    private static View floatBallView = null;
    private static ViewGroup floatBallContainer = null;
    private static int statusBarHeight = 0;

    // 设置面板相关
    private static View panelView = null;
    private static boolean isPanelShowing = false;

    // 悬浮球位置记录（用于跨Activity保持位置）
    private static int savedBallX = -1;
    private static int savedBallY = -1;

    // 面板中的EditText引用
    private static EditText latEdit;
    private static EditText lngEdit;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只处理目标包名
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.e(TAG, "目标包名匹配，开始Hook: " + lpparam.packageName);
        targetClassLoader = lpparam.classLoader;

        // ==========================================
        // 第一步：Hook Application.attach 获取Context
        // ==========================================
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        appContext = (Context) param.args[0];
                        Log.e(TAG, "获取到Application Context");
                        loadPrefs();
                        hookAMapLocation();
                        hookTencentLocation();
                    }
                });

        // ==========================================
        // 第二步：Hook Instrumentation.callApplicationOnCreate 备用
        // ==========================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Instrumentation",
                    lpparam.classLoader,
                    "callApplicationOnCreate",
                    Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (appContext == null) {
                                Application app = (Application) param.args[0];
                                appContext = app.getApplicationContext();
                                Log.e(TAG, "通过Instrumentation获取到Context");
                                loadPrefs();
                                hookAMapLocation();
                                hookTencentLocation();
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "Hook Instrumentation失败", t);
        }

        // ==========================================
        // 第三步：Hook Activity.onCreate - 核心悬浮窗注入点
        // ==========================================
        hookActivityOnCreate(lpparam);

        // ==========================================
        // 第四步：Hook LauncherUI.onCreate - 主界面特殊处理
        // ==========================================
        hookLauncherUI(lpparam);

        Log.e(TAG, "Hook初始化完成");
    }

    // ==========================================
    // Hook Activity.onCreate (通用)
    // ==========================================
    private void hookActivityOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();

                            // 只处理目标包名下的Activity
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }

                            Log.e(TAG, "Activity.onCreate: " + className);

                            // 保存当前Activity引用
                            currentActivity = activity;

                            // 延迟注入悬浮球，等待Activity布局完成
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        injectFloatBall(activity);
                                    } catch (Throwable t) {
                                        Log.e(TAG, "注入悬浮球失败: " + activity.getClass().getName(), t);
                                    }
                                }
                            }, 500); // 500ms延迟，确保布局已加载
                        }
                    });
            Log.e(TAG, "Hook Activity.onCreate 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onCreate 失败", t);
        }
    }

    // ==========================================
    // Hook LauncherUI.onCreate (微信主界面)
    // ==========================================
    private void hookLauncherUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.ui.LauncherUI",
                    lpparam.classLoader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            Log.e(TAG, "LauncherUI.onCreate 主界面创建");

                            // 主界面也注入悬浮球
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        injectFloatBall(activity);
                                    } catch (Throwable t) {
                                        Log.e(TAG, "LauncherUI注入悬浮球失败", t);
                                    }
                                }
                            }, 800);
                        }
                    });
            Log.e(TAG, "Hook LauncherUI.onCreate 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook LauncherUI.onCreate 失败", t);
        }
    }

    // ==========================================
    // 核心方法：注入悬浮球到Activity
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private void injectFloatBall(final Activity activity) {
        if (activity == null) {
            return;
        }

        // 获取content容器
        final ViewGroup contentView = activity.findViewById(android.R.id.content);
        if (contentView == null) {
            Log.e(TAG, "contentView为null，无法注入");
            return;
        }

        // 计算状态栏高度
        if (statusBarHeight == 0) {
            int resourceId = activity.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
            }
        }

        // 获取屏幕尺寸
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int screenWidth = dm.widthPixels;
        final int screenHeight = dm.heightPixels;

        // 获取可见区域（用于边界检测）
        final Rect visibleRect = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleRect);

        // ==========================================
        // 创建悬浮球容器（全屏FrameLayout，用于定位）
        // ==========================================
        final FrameLayout ballContainer = new FrameLayout(activity);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        ballContainer.setLayoutParams(containerParams);
        ballContainer.setClickable(false);
        ballContainer.setFocusable(false);

        // ==========================================
        // 创建悬浮球按钮
        // ==========================================
        final LinearLayout floatBall = new LinearLayout(activity);
        floatBall.setOrientation(LinearLayout.HORIZONTAL);
        floatBall.setGravity(Gravity.CENTER);
        floatBall.setBackgroundColor(0xE64A90E2); // 半透明蓝色
        floatBall.setPadding(dp2px(activity, 14), dp2px(activity, 10),
                dp2px(activity, 14), dp2px(activity, 10));

        // 设置圆角背景（通过代码实现简单圆角效果）
        floatBall.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
        floatBall.setBackgroundColor(0xE64A90E2);

        TextView ballText = new TextView(activity);
        ballText.setText("⚙ 设置");
        ballText.setTextColor(0xFFFFFFFF);
        ballText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        floatBall.addView(ballText);

        // 悬浮球初始位置参数
        final FrameLayout.LayoutParams ballParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );

        // 如果有保存的位置，使用保存的位置；否则默认在右上角
        if (savedBallX >= 0 && savedBallY >= 0) {
            ballParams.leftMargin = savedBallX;
            ballParams.topMargin = savedBallY;
            ballParams.gravity = Gravity.TOP | Gravity.START;
        } else {
            // 默认位置：右上角，状态栏下方
            ballParams.gravity = Gravity.TOP | Gravity.END;
            ballParams.rightMargin = dp2px(activity, 16);
            ballParams.topMargin = statusBarHeight + dp2px(activity, 80);
        }

        floatBall.setLayoutParams(ballParams);

        // ==========================================
        // 拖拽实现（OnTouchListener）
        // ==========================================
        final float[] touchRawX = new float[1];
        final float[] touchRawY = new float[1];
        final int[] ballStartLeft = new int[1];
        final int[] ballStartTop = new int[1];
        final long[] downTime = new long[1];
        final boolean[] isDragging = new boolean[1];

        floatBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 记录按下时的位置
                        touchRawX[0] = event.getRawX();
                        touchRawY[0] = event.getRawY();
                        ballStartLeft[0] = ballParams.leftMargin;
                        ballStartTop[0] = ballParams.topMargin;
                        downTime[0] = System.currentTimeMillis();
                        isDragging[0] = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchRawX[0]);
                        int dy = (int) (event.getRawY() - touchRawY[0]);

                        // 移动超过5px判定为拖拽
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging[0] = true;
                        }

                        if (isDragging[0]) {
                            // 计算新位置
                            int newLeft = ballStartLeft[0] + dx;
                            int newTop = ballStartTop[0] + dy;

                            // 边界检测 - 左边界
                            if (newLeft < 0) newLeft = 0;
                            // 边界检测 - 右边界
                            int ballWidth = v.getWidth();
                            if (newLeft + ballWidth > screenWidth) {
                                newLeft = screenWidth - ballWidth;
                            }
                            // 边界检测 - 上边界（状态栏下方）
                            if (newTop < statusBarHeight) {
                                newTop = statusBarHeight;
                            }
                            // 边界检测 - 下边界
                            int ballHeight = v.getHeight();
                            if (newTop + ballHeight > screenHeight) {
                                newTop = screenHeight - ballHeight;
                            }

                            // 更新位置
                            ballParams.leftMargin = newLeft;
                            ballParams.topMargin = newTop;
                            ballParams.gravity = Gravity.TOP | Gravity.START;
                            ballParams.rightMargin = 0;
                            ballParams.bottomMargin = 0;

                            // 应用新位置
                            v.setLayoutParams(ballParams);

                            // 保存位置
                            savedBallX = newLeft;
                            savedBallY = newTop;
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        // 判断是点击还是拖拽
                        if (!isDragging[0] && System.currentTimeMillis() - downTime[0] < 300) {
                            // 点击事件 - 打开/关闭设置面板
                            togglePanel(activity);
                            return true;
                        }
                        return true;
                }
                return false;
            }
        });

        // ==========================================
        // 将悬浮球添加到容器，再添加到Activity
        // ==========================================
        ballContainer.addView(floatBall);

        try {
            contentView.addView(ballContainer, containerParams);
            floatBallView = floatBall;
            floatBallContainer = ballContainer;
            Log.e(TAG, "悬浮球注入成功: " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.e(TAG, "添加悬浮球失败", t);
        }
    }

    // ==========================================
    // 设置面板：显示/隐藏切换
    // ==========================================
    private void togglePanel(Activity activity) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(activity);
        }
    }

    // ==========================================
    // 设置面板：隐藏
    // ==========================================
    private void hidePanel() {
        if (panelView != null && panelView.getParent() != null) {
            try {
                ((ViewGroup) panelView.getParent()).removeView(panelView);
            } catch (Throwable t) {
                Log.e(TAG, "移除面板失败", t);
            }
        }
        panelView = null;
        isPanelShowing = false;
    }

    // ==========================================
    // 设置面板：显示（视图注入方式）
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private void showPanel(final Activity activity) {
        if (isPanelShowing || activity == null) {
            return;
        }

        final ViewGroup contentView = activity.findViewById(android.R.id.content);
        if (contentView == null) {
            Log.e(TAG, "contentView为null，无法显示面板");
            showFallbackDialog(activity);
            return;
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        final int panelWidth = Math.min(dp2px(activity, 360), (int) (screenWidth * 0.9));
        final int panelHeight = (int) (screenHeight * 0.8);

        // ==========================================
        // 面板主容器
        // ==========================================
        final LinearLayout panelContainer = new LinearLayout(activity);
        panelContainer.setOrientation(LinearLayout.VERTICAL);
        panelContainer.setBackgroundColor(0xF5FFFFFF);
        panelContainer.setPadding(dp2px(activity, 16), dp2px(activity, 16),
                dp2px(activity, 16), dp2px(activity, 16));

        final FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth,
                panelHeight
        );
        panelParams.gravity = Gravity.CENTER;
        panelContainer.setLayoutParams(panelParams);

        // ==========================================
        // 标题栏
        // ==========================================
        LinearLayout titleBar = new LinearLayout(activity);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleBar.setLayoutParams(titleParams);

        TextView titleText = new TextView(activity);
        titleText.setText("⚙ Hook 设置面板");
        titleText.setTextColor(0xFF2C3E50);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleText.setGravity(Gravity.CENTER);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleText);

        Button closeBtn = new Button(activity);
        closeBtn.setText("✕");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setBackgroundColor(0x00000000);
        closeBtn.setTextColor(0xFF7F8C8D);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hidePanel();
            }
        });
        titleBar.addView(closeBtn);

        panelContainer.addView(titleBar);

        // ==========================================
        // 分割线
        // ==========================================
        View titleDivider = new View(activity);
        titleDivider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = dp2px(activity, 8);
        dividerParams.bottomMargin = dp2px(activity, 8);
        titleDivider.setLayoutParams(dividerParams);
        panelContainer.addView(titleDivider);

        // ==========================================
        // 滚动内容区
        // ==========================================
        ScrollView contentScroll = new ScrollView(activity);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);

        // ---- 模块1: 模拟定位 ----
        addCollapsibleSection(activity, contentLayout, "🌏 模拟定位", locationEnabled,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        locationEnabled = isChecked;
                        if (appContext != null) {
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("locationEnabled", isChecked)
                                    .apply();
                        }
                    }
                },
                new SectionContentBuilder() {
                    @Override
                    public void build(LinearLayout layout) {
                        addLocationContent(activity, layout);
                    }
                });

        panelContainer.addView(contentScroll);

        // ==========================================
        // 底部保存按钮
        // ==========================================
        Button saveBtn = new Button(activity);
        saveBtn.setText("💾 保存设置");
        saveBtn.setBackgroundColor(0xFF27AE60);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        saveBtn.setPadding(0, dp2px(activity, 12), 0, dp2px(activity, 12));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        saveParams.topMargin = dp2px(activity, 12);
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePrefs();
                Toast.makeText(activity, "设置已保存", Toast.LENGTH_SHORT).show();
                hidePanel();
            }
        });
        panelContainer.addView(saveBtn);

        // ==========================================
        // 标题栏拖拽（移动整个面板）
        // ==========================================
        final int[] panelStartLeft = new int[1];
        final int[] panelStartTop = new int[1];
        final float[] panelTouchStartX = new float[1];
        final float[] panelTouchStartY = new float[1];

        titleBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        panelStartLeft[0] = panelParams.leftMargin;
                        panelStartTop[0] = panelParams.topMargin;
                        panelTouchStartX[0] = event.getRawX();
                        panelTouchStartY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - panelTouchStartX[0]);
                        int dy = (int) (event.getRawY() - panelTouchStartY[0]);
                        panelParams.leftMargin = panelStartLeft[0] + dx;
                        panelParams.topMargin = panelStartTop[0] + dy;
                        panelParams.gravity = Gravity.TOP | Gravity.START;
                        try {
                            panelContainer.setLayoutParams(panelParams);
                        } catch (Throwable t) {
                            Log.e(TAG, "更新面板位置失败", t);
                        }
                        return true;
                }
                return false;
            }
        });

        // ==========================================
        // 添加面板到Activity
        // ==========================================
        try {
            contentView.addView(panelContainer, panelParams);
            panelView = panelContainer;
            isPanelShowing = true;
            Log.e(TAG, "设置面板显示成功");
        } catch (Throwable t) {
            Log.e(TAG, "添加设置面板失败", t);
            showFallbackDialog(activity);
        }
    }

    // ==========================================
    // 折叠模块构建接口
    // ==========================================
    private interface SectionContentBuilder {
        void build(LinearLayout layout);
    }

    // ==========================================
    // 添加可折叠的设置模块
    // ==========================================
    private void addCollapsibleSection(Context ctx, LinearLayout parentLayout, String title,
                                       boolean isChecked,
                                       CompoundButton.OnCheckedChangeListener toggleListener,
                                       SectionContentBuilder contentBuilder) {

        final boolean[] checked = {isChecked};
        final boolean[] isExpanded = {false};

        LinearLayout sectionLayout = new LinearLayout(ctx);
        sectionLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.topMargin = dp2px(ctx, 4);
        sectionParams.bottomMargin = dp2px(ctx, 4);
        sectionLayout.setLayoutParams(sectionParams);

        // 头部
        LinearLayout headerLayout = new LinearLayout(ctx);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(dp2px(ctx, 8), dp2px(ctx, 10),
                dp2px(ctx, 8), dp2px(ctx, 10));
        headerLayout.setBackgroundColor(0xFFF8F9FA);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerLayout.setLayoutParams(headerParams);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerLayout.addView(tv);

        // 开关状态文字
        final TextView statusToggle = new TextView(ctx);
        statusToggle.setText(checked[0] ? "开启" : "关闭");
        statusToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusToggle.setTextColor(checked[0] ? 0xFF27AE60 : 0xFFE74C3C);
        statusToggle.setGravity(Gravity.CENTER);
        statusToggle.setPadding(dp2px(ctx, 10), dp2px(ctx, 4),
                dp2px(ctx, 10), dp2px(ctx, 4));
        statusToggle.setBackgroundColor(0x10000000);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        toggleParams.leftMargin = dp2px(ctx, 8);
        statusToggle.setLayoutParams(toggleParams);
        headerLayout.addView(statusToggle);

        sectionLayout.addView(headerLayout);

        // 内容容器
        final LinearLayout contentContainer = new LinearLayout(ctx);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp2px(ctx, 8), 0, dp2px(ctx, 8), dp2px(ctx, 8));
        contentContainer.setVisibility(View.GONE);

        contentBuilder.build(contentContainer);
        sectionLayout.addView(contentContainer);

        // 底部分割线
        View bottomDivider = new View(ctx);
        bottomDivider.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        bottomDivider.setLayoutParams(divParams);
        sectionLayout.addView(bottomDivider);

        // 头部点击：展开/收起
        headerLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isExpanded[0] = !isExpanded[0];
                contentContainer.setVisibility(isExpanded[0] ? View.VISIBLE : View.GONE);
            }
        });

        // 状态文字点击：切换开关
        statusToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checked[0] = !checked[0];
                statusToggle.setText(checked[0] ? "开启" : "关闭");
                statusToggle.setTextColor(checked[0] ? 0xFF27AE60 : 0xFFE74C3C);
                toggleListener.onCheckedChanged(null, checked[0]);
            }
        });

        parentLayout.addView(sectionLayout);
    }

    // ==========================================
    // 定位模块内容
    // ==========================================
    private void addLocationContent(Context ctx, LinearLayout layout) {
        addSmallDivider(ctx, layout);

        layout.addView(createLabel(ctx, "经度 (Longitude):"));
        EditText lngEditLocal = createEditText(ctx, customLng, "例如: 121.808512");
        lngEditLocal.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            void onTextChanged(String text) {
                customLng = text;
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString("lng", text).apply();
                }
            }
        });
        lngEdit = lngEditLocal;
        layout.addView(lngEditLocal);

        layout.addView(createLabel(ctx, "纬度 (Latitude):"));
        EditText latEditLocal = createEditText(ctx, customLat, "例如: 31.141585");
        latEditLocal.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            void onTextChanged(String text) {
                customLat = text;
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString("lat", text).apply();
                }
            }
        });
        latEdit = latEditLocal;
        layout.addView(latEditLocal);

        addSmallDivider(ctx, layout);

        // 地图选点按钮
        Button mapPickerBtn = new Button(ctx);
        mapPickerBtn.setText("🗺 地图选点");
        mapPickerBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mapPickerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMapPicker(ctx);
            }
        });
        layout.addView(mapPickerBtn);

        addSmallDivider(ctx, layout);

        // 获取当前位置按钮
        Button curLocBtn = new Button(ctx);
        curLocBtn.setText("📍 获取当前位置");
        curLocBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        curLocBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getCurrentLocation(ctx);
            }
        });
        layout.addView(curLocBtn);
    }

    // ==========================================
    // 腾讯地图Web选点
    // ==========================================
    @SuppressLint("SetJavaScriptEnabled")
    private void showMapPicker(Context ctx) {
        final Activity activity = (ctx instanceof Activity) ? (Activity) ctx : currentActivity;
        if (activity == null) {
            Toast.makeText(ctx, "无法打开地图选点", Toast.LENGTH_SHORT).show();
            return;
        }

        final android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.setTitle("地图选点");

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        WebView webView = new WebView(activity);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(webParams);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleMapUrl(request.getUrl().toString(), dialog, activity);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMapUrl(url, dialog, activity);
            }

            private boolean handleMapUrl(String url, android.app.Dialog dialog, Context ctx) {
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
                            if (appContext != null) {
                                SharedPreferences.Editor ed = appContext.getSharedPreferences(
                                        PREFS_NAME, Context.MODE_PRIVATE).edit();
                                ed.putString("lat", customLat);
                                ed.putString("lng", customLng);
                                ed.apply();
                            }
                            Toast.makeText(ctx, "已选择位置: " + customLat + ", " + customLng,
                                    Toast.LENGTH_SHORT).show();
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

    // ==========================================
    // 获取当前位置
    // ==========================================
    private void getCurrentLocation(Context ctx) {
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

            android.location.Location loc = lm.getLastKnownLocation(
                    android.location.LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            }
            if (loc != null) {
                customLat = String.valueOf(loc.getLatitude());
                customLng = String.valueOf(loc.getLongitude());
                if (latEdit != null) latEdit.setText(customLat);
                if (lngEdit != null) lngEdit.setText(customLng);
                if (appContext != null) {
                    SharedPreferences.Editor ed = appContext.getSharedPreferences(
                            PREFS_NAME, Context.MODE_PRIVATE).edit();
                    ed.putString("lat", customLat);
                    ed.putString("lng", customLng);
                    ed.apply();
                }
                Toast.makeText(ctx, "已获取当前位置", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, "无法获取当前位置", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException se) {
            Toast.makeText(ctx, "位置权限被拒绝", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "获取位置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // Hook 高德定位
    // ==========================================
    private void hookAMapLocation() {
        if (targetClassLoader == null) return;
        try {
            Class<?> aMapLocationClass = targetClassLoader.loadClass(
                    "com.amap.api.location.AMapLocation");

            // Hook getLatitude
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
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

            // Hook getLongitude
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
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

            Log.e(TAG, "高德定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "高德定位Hook失败", t);
        }
    }

    // ==========================================
    // Hook 腾讯地图定位
    // ==========================================
    private void hookTencentLocation() {
        if (targetClassLoader == null) return;
        try {
            // 尝试多种类名
            String[] managerClassNames = {
                    "com.tencent.map.geolocation.sapp.TencentLocationManager",
                    "com.tencent.map.geolocation.TencentLocationManager"
            };

            Class<?> managerClass = null;
            for (String cn : managerClassNames) {
                try {
                    managerClass = targetClassLoader.loadClass(cn);
                    Log.e(TAG, "找到腾讯地图定位Manager: " + cn);
                    break;
                } catch (ClassNotFoundException e) {
                    // continue
                }
            }

            if (managerClass != null) {
                Method[] methods = managerClass.getDeclaredMethods();
                for (Method method : methods) {
                    String methodName = method.getName();
                    if ("requestSingleFreshLocation".equals(methodName)
                            || "requestLocationUpdates".equals(methodName)) {
                        XposedHelpers.findAndHookMethod(managerClass, methodName,
                                method.getParameterTypes(),
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        if (!locationEnabled) return;
                                        Object listener = findListenerInArgs(param.args);
                                        if (listener != null) {
                                            hookTencentLocationListener(listener);
                                        }
                                    }
                                });
                        Log.e(TAG, "腾讯地图 " + methodName + " Hook成功");
                    }
                }
            }

            // 备用方案：Hook TencentLocation 的get方法
            hookTencentLocationFallback();

            Log.e(TAG, "腾讯地图定位Hook初始化完成");
        } catch (Throwable t) {
            Log.e(TAG, "腾讯地图定位Hook失败", t);
        }
    }

    private Object findListenerInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            if (isTencentLocationListener(arg.getClass())) {
                return arg;
            }
        }
        return null;
    }

    private boolean isTencentLocationListener(Class<?> clazz) {
        if (clazz == null) return false;
        String name = clazz.getName();
        if (name.contains("TencentLocationListener")) return true;
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().contains("TencentLocationListener")) return true;
        }
        return false;
    }

    private void hookTencentLocationListener(final Object listener) {
        if (listener == null) return;
        final Class<?> listenerClass = listener.getClass();
        try {
            Method[] methods = listenerClass.getDeclaredMethods();
            for (Method method : methods) {
                if ("onLocationChanged".equals(method.getName())) {
                    final Class<?>[] paramTypes = method.getParameterTypes();
                    XposedHelpers.findAndHookMethod(listenerClass, "onLocationChanged",
                            paramTypes, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (!locationEnabled || appContext == null) return;
                                    SharedPreferences sh = appContext.getSharedPreferences(
                                            PREFS_NAME, Context.MODE_PRIVATE);
                                    String latStr = sh.getString("lat", "");
                                    String lngStr = sh.getString("lng", "");
                                    if (latStr.isEmpty() || lngStr.isEmpty()) return;
                                    try {
                                        double lat = Double.parseDouble(latStr);
                                        double lng = Double.parseDouble(lngStr);
                                        for (int i = 0; i < param.args.length; i++) {
                                            Object arg = param.args[i];
                                            if (arg != null && isTencentLocation(arg)) {
                                                modifyTencentLocation(arg, lat, lng);
                                            }
                                        }
                                    } catch (NumberFormatException e) {
                                        Log.e(TAG, "经纬度格式错误", e);
                                    }
                                }
                            });
                    Log.e(TAG, "腾讯地图 onLocationChanged Hook成功");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook onLocationChanged失败", t);
        }
    }

    private boolean isTencentLocation(Object obj) {
        if (obj == null) return false;
        String className = obj.getClass().getName();
        return className.contains("TencentLocation") || className.contains("Location");
    }

    private void modifyTencentLocation(Object locationObj, double lat, double lng) {
        if (locationObj == null) return;
        Class<?> clazz = locationObj.getClass();
        try {
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
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // continue
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getName())) {
            return findField(superClass, fieldNames);
        }
        return null;
    }

    private void hookTencentLocationFallback() {
        if (targetClassLoader == null) return;
        try {
            String[] classNames = {
                    "com.tencent.map.geolocation.sapp.TencentLocation",
                    "com.tencent.map.geolocation.TencentLocation",
                    "com.tencent.location.TencentLocation"
            };

            Class<?> locationClass = null;
            for (String cn : classNames) {
                try {
                    locationClass = targetClassLoader.loadClass(cn);
                    Log.e(TAG, "找到腾讯地图Location类: " + cn);
                    break;
                } catch (ClassNotFoundException e) {
                    // continue
                }
            }

            if (locationClass == null) return;

            XposedHelpers.findAndHookMethod(locationClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
                            String latStr = sh.getString("lat", "");
                            if (latStr.isEmpty()) return;
                            try {
                                param.setResult(Double.parseDouble(latStr));
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(locationClass, "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
                            String lngStr = sh.getString("lng", "");
                            if (lngStr.isEmpty()) return;
                            try {
                                param.setResult(Double.parseDouble(lngStr));
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    });

            Log.e(TAG, "腾讯地图备用Hook方案成功");
        } catch (Throwable t) {
            Log.e(TAG, "腾讯地图备用Hook方案失败", t);
        }
    }

    // ==========================================
    // 配置持久化
    // ==========================================
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
        SharedPreferences.Editor editor = appContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean("locationEnabled", locationEnabled);
        editor.putString("lat", customLat);
        editor.putString("lng", customLng);
        editor.apply();
        Log.e(TAG, "配置已保存");
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
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
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
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        et.setLayoutParams(params);
        return et;
    }

    private void addSmallDivider(Context ctx, LinearLayout layout) {
        View divider = new View(ctx);
        divider.setBackgroundColor(0xFFE8E8E8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = dp2px(ctx, 8);
        params.bottomMargin = dp2px(ctx, 8);
        divider.setLayoutParams(params);
        layout.addView(divider);
    }

    private void showFallbackDialog(Context ctx) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("Hook设置")
                    .setMessage("定位功能: " + (locationEnabled ? "开启" : "关闭") +
                            "\n\n经度: " + customLng +
                            "\n纬度: " + customLat)
                    .setCancelable(true)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "显示回退Dialog失败", t);
        }
    }

    // ==========================================
    // TextWatcher 简化类
    // ==========================================
    private static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        abstract void onTextChanged(String text);

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onTextChanged(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {}
    }
}
