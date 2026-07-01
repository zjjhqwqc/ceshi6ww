package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.view.ViewTreeObserver;
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
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed Hook 主类
 * 悬浮窗实现方式：Activity视图注入（暴力注入版）
 * 
 * 策略：
 * 1. 不过滤Activity，所有微信Activity都尝试注入
 * 2. 尝试多种注入方式：contentView / decorView / addContentView
 * 3. 多个Hook点：onCreate / onResume / onWindowFocusChanged / setContentView
 * 4. 先用最简单的红色测试View，确保可见性
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "HookTest";
    private static final String PREFS_NAME = "hookdata";
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    // 全局配置
    private static boolean locationEnabled = false;
    private static String customLat = "";
    private static String customLng = "";

    private static Activity currentActivity = null;
    private static Context appContext = null;
    private static ClassLoader targetClassLoader = null;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 每个Activity的悬浮球
    private static final Map<Activity, View> floatBallMap = new WeakHashMap<>();
    private static final Map<Activity, Boolean> injectedMap = new WeakHashMap<>();

    // 设置面板
    private static View panelView = null;
    private static boolean isPanelShowing = false;

    // 位置记录
    private static int savedBallX = -1;
    private static int savedBallY = -1;
    private static int statusBarHeight = 0;

    private static EditText latEdit;
    private static EditText lngEdit;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.e(TAG, "========== 模块加载成功 ==========");
        Log.e(TAG, "目标包名: " + lpparam.packageName);
        targetClassLoader = lpparam.classLoader;

        // 1. 获取Application Context
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        appContext = (Context) param.args[0];
                        Log.e(TAG, "获取到Application Context");
                        loadPrefs();
                        hookAMapLocation();
                        hookTencentLocation();
                        registerActivityLifecycleCallbacks((Application) appContext);
                    }
                });

        // 2. Hook Activity.onCreate - 最早的时机
        hookActivityOnCreate(lpparam);

        // 3. Hook Activity.onResume - 常用时机
        hookActivityOnResume(lpparam);

        // 4. Hook onWindowFocusChanged - 最可靠时机
        hookOnWindowFocusChanged(lpparam);

        // 5. Hook setContentView - 布局加载后立即注入
        hookSetContentView(lpparam);

        // 6. Hook addContentView
        hookAddContentView(lpparam);

        Log.e(TAG, "========== 所有Hook点注册完成 ==========");
    }

    // ==========================================
    // Hook Activity.onCreate
    // ==========================================
    private void hookActivityOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();
                            
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[onCreate] " + className);
                            currentActivity = activity;
                            
                            // 延迟注入
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    tryInject(activity, "onCreate-delay");
                                }
                            }, 300);
                        }
                    });
            Log.e(TAG, "Hook Activity.onCreate 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onCreate 失败", t);
        }
    }

    // ==========================================
    // Hook Activity.onResume
    // ==========================================
    private void hookActivityOnResume(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();
                            
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[onResume] " + className);
                            currentActivity = activity;
                            
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    tryInject(activity, "onResume");
                                }
                            }, 100);
                        }
                    });
            Log.e(TAG, "Hook Activity.onResume 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Activity.onResume 失败", t);
        }
    }

    // ==========================================
    // Hook onWindowFocusChanged
    // ==========================================
    private void hookOnWindowFocusChanged(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            boolean hasFocus = (Boolean) param.args[0];
                            
                            if (!hasFocus) return;
                            
                            String className = activity.getClass().getName();
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[onWindowFocusChanged] " + className);
                            currentActivity = activity;
                            
                            if (Looper.myLooper() == Looper.getMainLooper()) {
                                tryInject(activity, "onWindowFocusChanged");
                            } else {
                                uiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tryInject(activity, "onWindowFocusChanged");
                                    }
                                });
                            }
                        }
                    });
            Log.e(TAG, "Hook onWindowFocusChanged 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook onWindowFocusChanged 失败", t);
        }
    }

    // ==========================================
    // Hook setContentView - 布局加载后立即注入
    // ==========================================
    private void hookSetContentView(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook Activity.setContentView(int)
            XposedHelpers.findAndHookMethod(Activity.class, "setContentView",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();
                            
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[setContentView(int)] " + className);
                            
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tryInject(activity, "setContentView-int");
                                }
                            });
                        }
                    });
            
            // Hook Activity.setContentView(View)
            XposedHelpers.findAndHookMethod(Activity.class, "setContentView",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();
                            
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[setContentView(View)] " + className);
                            
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tryInject(activity, "setContentView-view");
                                }
                            });
                        }
                    });
            
            Log.e(TAG, "Hook setContentView 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook setContentView 失败", t);
        }
    }

    // ==========================================
    // Hook addContentView
    // ==========================================
    private void hookAddContentView(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "addContentView",
                    View.class, ViewGroup.LayoutParams.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            String className = activity.getClass().getName();
                            
                            if (!className.startsWith("com.tencent.mm")) {
                                return;
                            }
                            
                            Log.e(TAG, "[addContentView] " + className);
                            
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    tryInject(activity, "addContentView");
                                }
                            }, 100);
                        }
                    });
            Log.e(TAG, "Hook addContentView 成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook addContentView 失败", t);
        }
    }

    // ==========================================
    // 注册Activity生命周期回调
    // ==========================================
    private void registerActivityLifecycleCallbacks(Application app) {
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityResumed(Activity activity) {
                    if (activity.getClass().getName().startsWith("com.tencent.mm")) {
                        currentActivity = activity;
                        // 确保悬浮球在最上层
                        View ball = floatBallMap.get(activity);
                        if (ball != null && ball.getParent() != null) {
                            ball.bringToFront();
                        }
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
                    floatBallMap.remove(activity);
                    injectedMap.remove(activity);
                    if (panelView != null && panelView.getContext() == activity) {
                        hidePanel();
                    }
                    if (currentActivity == activity) {
                        currentActivity = null;
                    }
                    Log.e(TAG, "[onDestroyed] " + activity.getClass().getSimpleName());
                }
            });
            Log.e(TAG, "ActivityLifecycleCallbacks 注册成功");
        } catch (Throwable t) {
            Log.e(TAG, "注册ActivityLifecycleCallbacks失败", t);
        }
    }

    // ==========================================
    // 核心：尝试注入（尝试多种方式）
    // ==========================================
    private void tryInject(Activity activity, String from) {
        try {
            // 检查1: Activity是否有效
            if (activity == null || activity.isFinishing()) {
                return;
            }
            
            // 检查2: 是否已经注入成功
            if (injectedMap.containsKey(activity) && injectedMap.get(activity)) {
                // 已经注入过，确保在最上层
                View ball = floatBallMap.get(activity);
                if (ball != null && ball.getParent() != null) {
                    ball.bringToFront();
                }
                return;
            }
            
            Log.e(TAG, "尝试注入，来源: " + from + ", Activity: " + activity.getClass().getSimpleName());
            
            boolean success = false;
            
            // 方式1: contentView (android.R.id.content)
            if (!success) {
                success = injectToContentView(activity);
                if (success) Log.e(TAG, "✅ 注入成功: contentView方式");
            }
            
            // 方式2: decorView
            if (!success) {
                success = injectToDecorView(activity);
                if (success) Log.e(TAG, "✅ 注入成功: decorView方式");
            }
            
            // 方式3: addContentView
            if (!success) {
                success = injectByAddContentView(activity);
                if (success) Log.e(TAG, "✅ 注入成功: addContentView方式");
            }
            
            if (success) {
                injectedMap.put(activity, true);
                Log.e(TAG, "========== 悬浮球注入成功 ==========");
            } else {
                Log.e(TAG, "❌ 所有注入方式都失败了");
                // 延迟重试
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tryInject(activity, "retry");
                    }
                }, 500);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "tryInject异常", t);
        }
    }

    // ==========================================
    // 注入方式1: android.R.id.content
    // ==========================================
    private boolean injectToContentView(Activity activity) {
        try {
            View contentView = activity.findViewById(android.R.id.content);
            if (contentView == null) {
                Log.e(TAG, "  contentView方式失败: contentView为null");
                return false;
            }
            
            if (!(contentView instanceof ViewGroup)) {
                Log.e(TAG, "  contentView方式失败: contentView不是ViewGroup");
                return false;
            }
            
            ViewGroup container = (ViewGroup) contentView;
            
            // 检查布局是否完成
            if (container.getWidth() == 0 || container.getHeight() == 0) {
                Log.e(TAG, "  contentView方式: 布局未完成，等待OnGlobalLayout");
                waitForLayoutAndInject(container, activity, "contentView");
                return false; // 暂时返回false，等布局完成后会真正注入
            }
            
            // 执行注入
            View ball = createFloatBall(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            setupBallPosition(activity, params);
            
            container.addView(ball, params);
            ball.bringToFront();
            floatBallMap.put(activity, ball);
            
            Log.e(TAG, "  contentView方式: 添加成功，子View数=" + container.getChildCount());
            return true;
            
        } catch (Throwable t) {
            Log.e(TAG, "  contentView方式异常: " + t.getMessage());
            return false;
        }
    }

    // ==========================================
    // 注入方式2: DecorView
    // ==========================================
    private boolean injectToDecorView(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            if (decorView == null) {
                Log.e(TAG, "  decorView方式失败: decorView为null");
                return false;
            }
            
            if (!(decorView instanceof ViewGroup)) {
                Log.e(TAG, "  decorView方式失败: decorView不是ViewGroup");
                return false;
            }
            
            ViewGroup decorGroup = (ViewGroup) decorView;
            
            if (decorGroup.getWidth() == 0 || decorGroup.getHeight() == 0) {
                Log.e(TAG, "  decorView方式: 布局未完成");
                return false;
            }
            
            View ball = createFloatBall(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            setupBallPosition(activity, params);
            
            decorGroup.addView(ball, params);
            ball.bringToFront();
            floatBallMap.put(activity, ball);
            
            Log.e(TAG, "  decorView方式: 添加成功，子View数=" + decorGroup.getChildCount());
            return true;
            
        } catch (Throwable t) {
            Log.e(TAG, "  decorView方式异常: " + t.getMessage());
            return false;
        }
    }

    // ==========================================
    // 注入方式3: addContentView
    // ==========================================
    private boolean injectByAddContentView(Activity activity) {
        try {
            View ball = createFloatBall(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            setupBallPosition(activity, params);
            
            activity.addContentView(ball, params);
            ball.bringToFront();
            floatBallMap.put(activity, ball);
            
            Log.e(TAG, "  addContentView方式: 添加成功");
            return true;
            
        } catch (Throwable t) {
            Log.e(TAG, "  addContentView方式异常: " + t.getMessage());
            return false;
        }
    }

    // ==========================================
    // 等待布局完成后注入
    // ==========================================
    private void waitForLayoutAndInject(final ViewGroup container, final Activity activity, final String method) {
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (container.getWidth() > 0 && container.getHeight() > 0) {
                            container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            Log.e(TAG, "  OnGlobalLayout触发，重新尝试注入: " + method);
                            
                            boolean success = false;
                            if ("contentView".equals(method)) {
                                success = injectToContentView(activity);
                            }
                            
                            if (success) {
                                injectedMap.put(activity, true);
                                Log.e(TAG, "✅ 延迟注入成功: " + method);
                            }
                        }
                    }
                });
    }

    // ==========================================
    // 创建悬浮球View
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private View createFloatBall(final Activity activity) {
        // 计算状态栏高度
        if (statusBarHeight == 0) {
            int resourceId = activity.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
            }
            if (statusBarHeight == 0) {
                Rect visibleRect = new Rect();
                activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleRect);
                statusBarHeight = visibleRect.top;
            }
            Log.e(TAG, "状态栏高度: " + statusBarHeight);
        }
        
        final DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int screenWidth = dm.widthPixels;
        final int screenHeight = dm.heightPixels;
        
        // 创建悬浮球（用最显眼的红色确保能看到）
        final LinearLayout floatBall = new LinearLayout(activity);
        floatBall.setOrientation(LinearLayout.HORIZONTAL);
        floatBall.setGravity(Gravity.CENTER);
        floatBall.setBackgroundColor(0xFFE74C3C); // 鲜艳红色，确保可见
        floatBall.setPadding(dp2px(activity, 16), dp2px(activity, 12),
                dp2px(activity, 16), dp2px(activity, 12));
        floatBall.setElevation(dp2px(activity, 20)); // 高elevation确保在最上层
        
        TextView ballText = new TextView(activity);
        ballText.setText("📍定位");
        ballText.setTextColor(0xFFFFFFFF);
        ballText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ballText.setGravity(Gravity.CENTER);
        floatBall.addView(ballText);
        
        // 拖拽和点击
        final float[] touchRawX = new float[1];
        final float[] touchRawY = new float[1];
        final int[] ballStartLeft = new int[1];
        final int[] ballStartTop = new int[1];
        final long[] downTime = new long[1];
        final boolean[] isDragging = new boolean[1];
        
        floatBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchRawX[0] = event.getRawX();
                        touchRawY[0] = event.getRawY();
                        ballStartLeft[0] = params.leftMargin;
                        ballStartTop[0] = params.topMargin;
                        downTime[0] = System.currentTimeMillis();
                        isDragging[0] = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchRawX[0]);
                        int dy = (int) (event.getRawY() - touchRawY[0]);
                        
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging[0] = true;
                        }
                        
                        if (isDragging[0]) {
                            int newLeft = ballStartLeft[0] + dx;
                            int newTop = ballStartTop[0] + dy;
                            
                            int ballWidth = v.getWidth();
                            int ballHeight = v.getHeight();
                            
                            if (newLeft < 0) newLeft = 0;
                            if (newLeft + ballWidth > screenWidth) newLeft = screenWidth - ballWidth;
                            if (newTop < statusBarHeight) newTop = statusBarHeight;
                            if (newTop + ballHeight > screenHeight) newTop = screenHeight - ballHeight;
                            
                            params.leftMargin = newLeft;
                            params.topMargin = newTop;
                            params.gravity = Gravity.TOP | Gravity.START;
                            params.rightMargin = 0;
                            params.bottomMargin = 0;
                            
                            v.setLayoutParams(params);
                            
                            savedBallX = newLeft;
                            savedBallY = newTop;
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        if (!isDragging[0] && System.currentTimeMillis() - downTime[0] < 300) {
                            // 点击
                            Toast.makeText(activity, "点击悬浮球", Toast.LENGTH_SHORT).show();
                            togglePanel(activity);
                            return true;
                        }
                        return true;
                }
                return false;
            }
        });
        
        return floatBall;
    }

    // ==========================================
    // 设置悬浮球初始位置
    // ==========================================
    private void setupBallPosition(Activity activity, FrameLayout.LayoutParams params) {
        if (savedBallX >= 0 && savedBallY >= 0) {
            params.leftMargin = savedBallX;
            params.topMargin = savedBallY;
            params.gravity = Gravity.TOP | Gravity.START;
        } else {
            // 默认位置：左侧中间偏上（鲜红色确保能看到）
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = dp2px(activity, 20);
            params.topMargin = statusBarHeight + dp2px(activity, 200);
        }
    }

    // ==========================================
    // 设置面板
    // ==========================================
    private void togglePanel(Activity activity) {
        if (isPanelShowing) {
            hidePanel();
        } else {
            showPanel(activity);
        }
    }

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

    @SuppressLint("ClickableViewAccessibility")
    private void showPanel(final Activity activity) {
        if (isPanelShowing || activity == null) return;
        
        // 尝试获取contentView
        ViewGroup contentView = null;
        try {
            contentView = activity.findViewById(android.R.id.content);
        } catch (Throwable t) {
            Log.e(TAG, "获取contentView失败", t);
        }
        
        if (contentView == null) {
            showFallbackDialog(activity);
            return;
        }
        
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int panelWidth = Math.min(dp2px(activity, 360), (int) (dm.widthPixels * 0.9));
        int panelHeight = (int) (dm.heightPixels * 0.8);
        
        final LinearLayout panelContainer = new LinearLayout(activity);
        panelContainer.setOrientation(LinearLayout.VERTICAL);
        panelContainer.setBackgroundColor(0xF5FFFFFF);
        panelContainer.setPadding(dp2px(activity, 16), dp2px(activity, 16),
                dp2px(activity, 16), dp2px(activity, 16));
        panelContainer.setElevation(dp2px(activity, 25));
        
        final FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(panelWidth, panelHeight);
        panelParams.gravity = Gravity.CENTER;
        panelContainer.setLayoutParams(panelParams);
        
        // 标题栏
        LinearLayout titleBar = new LinearLayout(activity);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        
        TextView titleText = new TextView(activity);
        titleText.setText("📍 定位设置面板");
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
            public void onClick(View v) { hidePanel(); }
        });
        titleBar.addView(closeBtn);
        panelContainer.addView(titleBar);
        
        // 分割线
        View divider = new View(activity);
        divider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.topMargin = dp2px(activity, 8);
        divParams.bottomMargin = dp2px(activity, 8);
        divider.setLayoutParams(divParams);
        panelContainer.addView(divider);
        
        // 内容区
        ScrollView contentScroll = new ScrollView(activity);
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentLayout);
        
        // 定位开关
        addSwitchRow(activity, contentLayout, "🌏 模拟定位", locationEnabled,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        locationEnabled = isChecked;
                        if (appContext != null) {
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putBoolean("locationEnabled", isChecked).apply();
                        }
                    }
                });
        
        // 经度输入
        contentLayout.addView(createLabel(activity, "经度:"));
        EditText lngEditLocal = createEditText(activity, customLng, "例如: 121.808512");
        lngEditLocal.addTextChangedListener(new SimpleTextWatcher() {
            @Override void onTextChanged(String text) {
                customLng = text;
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString("lng", text).apply();
                }
            }
        });
        lngEdit = lngEditLocal;
        contentLayout.addView(lngEditLocal);
        
        // 纬度输入
        contentLayout.addView(createLabel(activity, "纬度:"));
        EditText latEditLocal = createEditText(activity, customLat, "例如: 31.141585");
        latEditLocal.addTextChangedListener(new SimpleTextWatcher() {
            @Override void onTextChanged(String text) {
                customLat = text;
                if (appContext != null) {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString("lat", text).apply();
                }
            }
        });
        latEdit = latEditLocal;
        contentLayout.addView(latEditLocal);
        
        // 地图选点
        addSmallDivider(activity, contentLayout);
        Button mapBtn = new Button(activity);
        mapBtn.setText("🗺 地图选点");
        mapBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMapPicker(activity); }
        });
        contentLayout.addView(mapBtn);
        
        // 获取当前位置
        Button curBtn = new Button(activity);
        curBtn.setText("📍 获取当前位置");
        curBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { getCurrentLocation(activity); }
        });
        contentLayout.addView(curBtn);
        
        panelContainer.addView(contentScroll);
        
        // 保存按钮
        Button saveBtn = new Button(activity);
        saveBtn.setText("💾 保存设置");
        saveBtn.setBackgroundColor(0xFF27AE60);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        saveBtn.setPadding(0, dp2px(activity, 12), 0, dp2px(activity, 12));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
        
        // 添加到contentView
        try {
            contentView.addView(panelContainer, panelParams);
            panelContainer.bringToFront();
            panelView = panelContainer;
            isPanelShowing = true;
        } catch (Throwable t) {
            Log.e(TAG, "添加面板失败", t);
            showFallbackDialog(activity);
        }
    }

    // ==========================================
    // 开关行
    // ==========================================
    private void addSwitchRow(Context ctx, LinearLayout parent, String title,
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
        toggleTv.setPadding(dp2px(ctx, 12), dp2px(ctx, 4), dp2px(ctx, 12), dp2px(ctx, 4));
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
        
        parent.addView(row);
    }

    // ==========================================
    // 地图选点
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
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        
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
                            Toast.makeText(ctx, "已选择: " + customLat + ", " + customLng,
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
            if (lm == null) { Toast.makeText(ctx, "无法获取位置服务", Toast.LENGTH_SHORT).show(); return; }
            
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
            
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
                            String latStr = sh.getString("lat", "");
                            if (!latStr.isEmpty()) {
                                try { param.setResult(Double.parseDouble(latStr)); } catch (Exception e) {}
                            }
                        }
                    });
            
            XposedHelpers.findAndHookMethod(aMapLocationClass, "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
                            String lngStr = sh.getString("lng", "");
                            if (!lngStr.isEmpty()) {
                                try { param.setResult(Double.parseDouble(lngStr)); } catch (Exception e) {}
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
            String[] classNames = {
                    "com.tencent.map.geolocation.sapp.TencentLocation",
                    "com.tencent.map.geolocation.TencentLocation",
                    "com.tencent.location.TencentLocation"
            };
            
            Class<?> locationClass = null;
            for (String cn : classNames) {
                try {
                    locationClass = targetClassLoader.loadClass(cn);
                    Log.e(TAG, "找到腾讯Location类: " + cn);
                    break;
                } catch (ClassNotFoundException e) {}
            }
            
            if (locationClass == null) {
                Log.e(TAG, "未找到腾讯Location类");
                return;
            }
            
            XposedHelpers.findAndHookMethod(locationClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled || appContext == null) return;
                            SharedPreferences sh = appContext.getSharedPreferences(
                                    PREFS_NAME, Context.MODE_PRIVATE);
                            String latStr = sh.getString("lat", "");
                            if (!latStr.isEmpty()) {
                                try { param.setResult(Double.parseDouble(latStr)); } catch (Exception e) {}
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
                            if (!lngStr.isEmpty()) {
                                try { param.setResult(Double.parseDouble(lngStr)); } catch (Exception e) {}
                            }
                        }
                    });
            
            Log.e(TAG, "腾讯定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "腾讯定位Hook失败", t);
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
        et.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
            builder.setTitle("定位设置")
                    .setMessage("定位功能: " + (locationEnabled ? "开启" : "关闭") +
                            "\n\n经度: " + customLng + "\n纬度: " + customLat)
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
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            onTextChanged(s.toString());
        }
        @Override public void afterTextChanged(android.text.Editable s) {}
    }
}
