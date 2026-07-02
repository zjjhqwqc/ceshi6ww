package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块
 * 完全按照原APK逻辑重写
 * 支持微信8.0.74
 */
public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "WxLocationHook";
    private static final String TARGET_PACKAGE = "com.tencent.mm";
    private static final String PREFS_NAME = "sqwx";

    // 配置键名 - 与原APK一致
    private static final String KEY_LOCATION_ENABLED = "isLocation";
    private static final String KEY_XCX_ENABLED = "isX";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_GPS_PLACE = "gpsPlace";
    private static final String KEY_GPS_SWITCH = "gpsSwitch";
    private static final String KEY_GPS_TEXT = "gpsText";

    // 全局状态
    private static boolean isLocation = true;  // 默认开启，便于测试
    private static boolean isX = false;
    private static String latitude = "39.908823";
    private static String longitude = "116.397470";
    private static String gpsPlace = "北京市东城区";

    private static Context appContext = null;
    private static ClassLoader classLoader = null;
    private static Handler uiHandler = null;  // 延迟初始化，避免静态初始化失败

    // 已Hook的回调类
    private static final Set<String> hookedClasses = new HashSet<>();

    // 是否已显示加载完成提示
    private static boolean hasShownLoadedToast = false;

    // 小程序坐标（GCJ02）
    private static double xcxLat = 0;
    private static double xcxLng = 0;

    // 是否已执行过hook（和原APK一致，用静态变量确保只执行一次）
    private static boolean hasHooked = false;

    // hook初始化是否已完成（所有hook都已注册）
    private static boolean hooksInitialized = false;

    // ========== 网络验证状态 ==========
    private static boolean hasVerified = false;  // 是否已执行过验证检查
    private static boolean verifyPassed = false; // 验证是否通过

    // 重试计数
    private static int retryCount = 0;
    private static final int MAX_RETRY = 5;

    // Hook端心跳相关
    private static Timer heartbeatTimer = null;
    private static boolean heartbeatRunning = false;
    private static final int HEARTBEAT_INTERVAL = 50000; // 50秒一次，与原SDK一致
    private static String hookCardCode = "";
    private static String hookUserToken = "";
    private static long hookExpireTime = 0;

    // Zygote初始化（和原APK一致）
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XposedBridge.log("[WxLocationHook] 【Zygote】initZygote 被调用");
        Log.e(TAG, "initZygote 被调用: " + startupParam.modulePath);
        // 原APK中这里创建了XModuleResources，我们暂时不需要
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            // 【重要】首先输出到Xposed日志，确保LSPosed能捕获到
            XposedBridge.log("[WxLocationHook] ========================================");
            XposedBridge.log("[WxLocationHook] 【模块加载】handleLoadPackage 被调用!");
            XposedBridge.log("[WxLocationHook] 包名: " + lpparam.packageName);
            XposedBridge.log("[WxLocationHook] 进程名: " + lpparam.processName);
            XposedBridge.log("[WxLocationHook] classLoader: " + lpparam.classLoader);
            XposedBridge.log("[WxLocationHook] =========================================");

            // 同时输出到logcat
            Log.e(TAG, "========================================");
            Log.e(TAG, "【LoadPackage】handleLoadPackage 被调用!");
            Log.e(TAG, "包名: " + lpparam.packageName);
            Log.e(TAG, "进程名: " + lpparam.processName);
            Log.e(TAG, "========================================");

            // 判断是否是微信进程：同时检查包名和进程名
            // MIUI系统可能出现packageName是com.miui.contentcatcher但processName是com.tencent.mm的情况
            boolean isWeChat = TARGET_PACKAGE.equals(lpparam.packageName)
                    || (lpparam.processName != null && lpparam.processName.startsWith(TARGET_PACKAGE));

            if (!isWeChat) {
                return; // 非微信进程，直接跳过
            }

            // 和原APK一致：确保每个进程只执行一次
            if (hasHooked) {
                XposedBridge.log("[WxLocationHook] Application is already hook ! !");
                Log.e(TAG, "Application is already hook ! !");

                // 如果已经hook过但初始化未完成（可能是注入太晚的情况），尝试直接初始化
                if (!hooksInitialized && appContext != null) {
                    XposedBridge.log("[WxLocationHook] 检测到已hook但未初始化，尝试直接初始化...");
                    Log.e(TAG, "已hook但未初始化，尝试直接初始化");
                    initializeHooks();
                }
                return;
            }

            hasHooked = true;

            XposedBridge.log("[WxLocationHook] load wechat Package success !");
            Log.e(TAG, "load wechat Package success !");

            // 【关键修复】双保险机制：
            // 1. 先保存classLoader，用于hook类
            // 2. Hook Application.attach 作为主要入口（注入早时生效）
            // 3. 同时尝试直接获取Application Context，如果已存在则直接初始化（注入晚时生效）

            classLoader = lpparam.classLoader;

            // 方式一：Hook Application.attach（标准方式，注入时机早时生效）
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                XposedBridge.log("[WxLocationHook] Application.attach 已触发!");
                                Log.e(TAG, "Application.attach 已触发!");

                                if (hooksInitialized) {
                                    XposedBridge.log("[WxLocationHook] hooks已经初始化过，跳过");
                                    Log.e(TAG, "hooks已经初始化过，跳过");
                                    return;
                                }

                                appContext = (Context) param.args[0];
                                // 从Context获取classLoader（和原APK一致：context.getClassLoader()）
                                if (classLoader == null) {
                                    classLoader = appContext.getClassLoader();
                                }

                                XposedBridge.log("[WxLocationHook] 获取到Context: " + appContext.getPackageName());
                                Log.e(TAG, "获取到Context: " + appContext.getPackageName());

                                // 执行所有hook初始化
                                initializeHooks();

                                XposedBridge.log("[WxLocationHook] ========== 所有Hook注册完成(Application.attach方式) ==========");
                            } catch (Throwable t) {
                                XposedBridge.log("[WxLocationHook] Application.attach 回调异常: " + t.getMessage());
                                Log.e(TAG, "Application.attach 回调异常", t);
                            }
                        }
                    });

            // 方式二：尝试直接获取Application（注入时机晚时生效）
            // 很多时候LSPosed注入太晚，Application.attach已经执行过了
            // 这时候需要直接从lpparam中获取信息并初始化
            try {
                // 尝试通过反射获取当前Application
                Object activityThread = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentActivityThread");
                if (activityThread != null) {
                    Application app = (Application) XposedHelpers.callMethod(activityThread, "getApplication");
                    if (app != null) {
                        appContext = app;
                        if (classLoader == null) {
                            classLoader = app.getClassLoader();
                        }

                        XposedBridge.log("[WxLocationHook] 通过ActivityThread直接获取到Application: " + app.getPackageName());
                        Log.e(TAG, "通过ActivityThread直接获取到Application: " + app.getPackageName());

                        // 延迟一点执行初始化，确保Application完全就绪
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!hooksInitialized) {
                                    XposedBridge.log("[WxLocationHook] 使用直接获取方式初始化hooks...");
                                    Log.e(TAG, "使用直接获取方式初始化hooks...");
                                    initializeHooks();
                                }
                            }
                        }, 500);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 直接获取Application失败，将依赖Application.attach方式: " + t.getMessage());
                Log.e(TAG, "直接获取Application失败，将依赖Application.attach方式", t);
            }

            XposedBridge.log("[WxLocationHook] ========== 模块加载初始化完成（双保险模式） ==========");
            Log.e(TAG, "========== 模块加载初始化完成（双保险模式） ==========");
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] handleLoadPackage 异常: " + t.getMessage());
            Log.e(TAG, "handleLoadPackage 异常", t);
        }
    }

    /**
     * 初始化所有hook
     * 统一入口，确保无论通过哪种方式获取Context，都执行相同的初始化逻辑
     */
    private void initializeHooks() {
        if (hooksInitialized) {
            return;
        }

        try {
            XposedBridge.log("[WxLocationHook] 开始初始化所有hooks...");
            Log.e(TAG, "开始初始化所有hooks...");

            // ========== 网络验证检查 ==========
            // 深度集成：验证未通过时所有hook都失效
            checkNetworkVerify();

            // 加载配置
            loadConfig();

            // 注册配置变化观察者，实现实时生效
            registerConfigObserver();

            // 显示加载完成提示
            showLoadedToast();

            // 开始Hook腾讯地图定位
            startTencentMapHook();

            // 开始Hook菜单
            startMenuHook();

            hooksInitialized = true;

            XposedBridge.log("[WxLocationHook] ========== 所有Hook初始化完成 ==========");
            Log.e(TAG, "========== 所有Hook初始化完成 ==========");

        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] hooks初始化异常: " + t.getMessage());
            Log.e(TAG, "hooks初始化异常", t);

            // 失败重试机制
            if (retryCount < MAX_RETRY) {
                retryCount++;
                XposedBridge.log("[WxLocationHook] hooks初始化失败，第 " + retryCount + " 次重试...");
                Log.e(TAG, "hooks初始化失败，第 " + retryCount + " 次重试...");

                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initializeHooks();
                    }
                }, 1000 * retryCount); // 递增延迟重试
            }
        }
    }

    // ==========================================
    // 网络验证（深度集成，验证未通过时所有hook失效）
    // ==========================================
    private void checkNetworkVerify() {
        try {
            XposedBridge.log("[WxLocationHook] 【验证】开始检查网络验证状态...");
            Log.e(TAG, "【验证】开始检查网络验证状态...");

            // 优先通过ContentProvider读取验证状态（跨进程通信）
            boolean providerSuccess = false;
            try {
                ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
                if (data != null) {
                    verifyPassed = data.verifyPassed;
                    XposedBridge.log("[WxLocationHook] 【验证】从ContentProvider读取验证结果: " + verifyPassed);
                    Log.e(TAG, "【验证】从ContentProvider读取验证结果: " + verifyPassed);

                    if (verifyPassed && data.verifyExpire != null && !data.verifyExpire.isEmpty()) {
                        // 检查是否过期
                        try {
                            long expire = Long.parseLong(data.verifyExpire);
                            if (System.currentTimeMillis() > expire) {
                                verifyPassed = false;
                                XposedBridge.log("[WxLocationHook] 【验证】验证已过期");
                                Log.e(TAG, "【验证】验证已过期");
                            }
                        } catch (NumberFormatException e) {
                            // 时间格式不对，忽略
                        }
                    }

                    // 如果ContentProvider读取到了验证状态，且已验证，就信任它
                    if (verifyPassed) {
                        providerSuccess = true;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 【验证】ContentProvider读取失败: " + t.getMessage());
                Log.e(TAG, "【验证】ContentProvider读取失败", t);
            }

            // ContentProvider读取失败时，尝试直接读取模块的SharedPreferences
            // 注意：这只在sharedUserId相同或者模块进程已启动并暴露数据时可能有效
            if (!providerSuccess) {
                try {
                    // 尝试通过创建模块Context来读取SharedPreferences
                    Context moduleContext = appContext.createPackageContext(
                            "Square.box", Context.CONTEXT_IGNORE_SECURITY);
                    if (moduleContext != null) {
                        android.content.SharedPreferences sp = moduleContext.getSharedPreferences(
                                "shuanq_verify", Context.MODE_PRIVATE);
                        boolean savedVerified = sp.getBoolean("is_verified", false);
                        String savedCard = sp.getString("card_code", "");
                        long savedExpire = sp.getLong("expire_time", 0);

                        XposedBridge.log("[WxLocationHook] 【验证】从模块SP读取: verified=" + savedVerified + ", card=" + savedCard);
                        Log.e(TAG, "【验证】从模块SP读取: verified=" + savedVerified);

                        if (savedVerified) {
                            if (savedExpire > 0 && System.currentTimeMillis() > savedExpire) {
                                verifyPassed = false;
                                XposedBridge.log("[WxLocationHook] 【验证】SP中的验证已过期");
                            } else {
                                verifyPassed = true;
                                XposedBridge.log("[WxLocationHook] 【验证】使用SP缓存的验证状态");
                            }
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[WxLocationHook] 【验证】读取模块SP失败: " + t.getMessage());
                    Log.e(TAG, "【验证】读取模块SP失败", t);
                }
            }

            hasVerified = true;

            if (!verifyPassed) {
                XposedBridge.log("[WxLocationHook] 【验证】验证未通过，所有Hook将处于失效状态！");
                Log.e(TAG, "【验证】验证未通过，所有Hook将处于失效状态！");
                // 验证未通过时，强制关闭定位功能
                isLocation = false;
                // 停止心跳
                stopHookHeartbeat();
            } else {
                XposedBridge.log("[WxLocationHook] 【验证】验证通过，Hook功能正常运行");
                Log.e(TAG, "【验证】验证通过，Hook功能正常运行");

                // 验证通过后，启动Hook端独立心跳（不依赖模块端）
                startHookHeartbeat();
            }

        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 【验证】验证检查总异常: " + t.getMessage());
            Log.e(TAG, "【验证】验证检查总异常", t);
            verifyPassed = false;
            hasVerified = true;
        }
    }

    // 显示加载完成提示
    private void showLoadedToast() {
        if (hasShownLoadedToast) return;
        hasShownLoadedToast = true;

        getUiHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String msg;
                    if (verifyPassed) {
                        msg = "功能加载完成";
                    } else {
                        msg = "模块未激活，请先验证卡密";
                    }
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Toast已显示: " + msg);
                } catch (Throwable t) {
                    Log.e(TAG, "显示Toast失败", t);
                }
            }
        }, 2000);
    }

    // ==========================================
    // 加载配置
    // ==========================================
    @SuppressLint({"ApplySharedPref", "WorldReadableFiles"})
    private void loadConfig() {
        try {
            // 尝试通过ContentProvider读取
            try {
                ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
                if (data != null && data.latitude != null && !data.latitude.isEmpty()) {
                    isLocation = data.locationEnabled;
                    isX = data.xcxEnabled;
                    latitude = data.latitude;
                    longitude = data.longitude;
                    gpsPlace = data.gpsPlace;
                    XposedBridge.log("[WxLocationHook] 通过ContentProvider读取配置成功: lat=" + latitude + ", lng=" + longitude);
                    Log.e(TAG, "通过ContentProvider读取配置成功");
                    updateXcxCoordinates();
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] ContentProvider读取失败: " + t.getMessage());
                Log.e(TAG, "ContentProvider读取失败，尝试SharedPreferences");
            }

            // 备用：从微信自己的SharedPreferences读取（如果之前保存过）
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            isLocation = sp.getBoolean(KEY_LOCATION_ENABLED, true);
            isX = sp.getBoolean(KEY_XCX_ENABLED, false);
            latitude = sp.getString(KEY_LATITUDE, latitude);
            longitude = sp.getString(KEY_LONGITUDE, longitude);
            gpsPlace = sp.getString(KEY_GPS_PLACE, gpsPlace);
            updateXcxCoordinates();
            XposedBridge.log("[WxLocationHook] 配置已加载(SP), isLocation=" + isLocation + ", lat=" + latitude + ", lng=" + longitude);
            Log.e(TAG, "配置已加载, isLocation=" + isLocation + ", lat=" + latitude + ", lng=" + longitude);
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 加载配置失败，使用默认值: " + t.getMessage());
            Log.e(TAG, "加载配置失败，使用默认值（默认开启定位）", t);
            isLocation = true; // 默认开启，便于测试
        }
    }

    // ==========================================
    // 注册配置变化观察者（实时生效）
    // ==========================================
    private void registerConfigObserver() {
        try {
            Uri configUri = Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config");
            ContentObserver observer = new ContentObserver(getUiHandler()) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    XposedBridge.log("[WxLocationHook] 配置已变化，重新加载...");
                    Log.e(TAG, "配置已变化，重新加载...");
                    // 重新检查验证状态
                    checkNetworkVerify();
                    loadConfig();
                    XposedBridge.log("[WxLocationHook] 配置重新加载完成: isLocation=" + isLocation + ", lat=" + latitude + ", lng=" + longitude + ", verify=" + verifyPassed);
                    Log.e(TAG, "配置重新加载完成: isLocation=" + isLocation + ", lat=" + latitude + ", lng=" + longitude + ", verify=" + verifyPassed);
                }
            };
            appContext.getContentResolver().registerContentObserver(configUri, true, observer);
            XposedBridge.log("[WxLocationHook] 配置观察者已注册");
            Log.e(TAG, "配置观察者已注册");
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 注册配置观察者失败: " + t.getMessage());
            Log.e(TAG, "注册配置观察者失败", t);
        }
    }

    // ==========================================
    // Hook 腾讯地图定位（完全按照原APK方式实现）
    // ==========================================
    // 腾讯地图hook状态
    private static boolean tencentMapHooked = false;
    private static int tencentMapRetryCount = 0;
    private static final int MAX_TENCENT_MAP_RETRY = 10;

    private void startTencentMapHook() {
        XposedBridge.log("[WxLocationHook] 【TencentMap】start TencentMap...");
        Log.e(TAG, "start TencentMap...");

        // 如果已经hook成功，直接返回
        if (tencentMapHooked) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】已经hook过，跳过");
            Log.e(TAG, "【TencentMap】已经hook过，跳过");
            return;
        }

        try {
            // 原APK使用的类名：com.tencent.map.geolocation.sapp.TencentLocationManager
            String tencentClassName = "com.tencent.map.geolocation.sapp.TencentLocationManager";

            // 尝试加载类
            Class<?> tencentLocationClass = null;
            try {
                tencentLocationClass = classLoader.loadClass(tencentClassName);
                XposedBridge.log("[WxLocationHook] 【TencentMap】(locationClass): " + tencentClassName);
                Log.e(TAG, "(locationClass): " + tencentClassName);
            } catch (ClassNotFoundException e) {
                // 类还没加载，延迟重试
                if (tencentMapRetryCount < MAX_TENCENT_MAP_RETRY) {
                    tencentMapRetryCount++;
                    XposedBridge.log("[WxLocationHook] 【TencentMap】类未加载，第 " + tencentMapRetryCount + " 次延迟重试...");
                    Log.e(TAG, "【TencentMap】类未加载，第 " + tencentMapRetryCount + " 次延迟重试");

                    getUiHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startTencentMapHook();
                        }
                    }, 2000 * tencentMapRetryCount); // 递增延迟
                } else {
                    XposedBridge.log("[WxLocationHook] 【TencentMap】重试次数已达上限，放弃hook腾讯地图");
                    Log.e(TAG, "【TencentMap】重试次数已达上限，放弃hook腾讯地图");
                }
                return;
            }

            // Hook requestSingleFreshLocation - 用hookAllMethods（和原APK一致）
            try {
                XposedBridge.hookAllMethods(tencentLocationClass, "requestSingleFreshLocation",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】requestSingleFreshLocation 被调用");
                                Log.e(TAG, "requestSingleFreshLocation 被调用");
                                // 深度验证：验证未通过时直接返回，不执行任何hook
                                if (!verifyPassed) {
                                    XposedBridge.log("[WxLocationHook] 【验证】验证未通过，requestSingleFreshLocation hook失效");
                                    return;
                                }
                                if (!isLocation) return;

                                // 原APK的方式：args[1]是listener（和原APK一致）
                                if (param.args != null && param.args.length >= 2) {
                                    Object listener = param.args[1];
                                    if (listener != null) {
                                        hookLocationListener(listener);
                                    }
                                }
                            }
                        });
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook requestSingleFreshLocation 成功");
                Log.e(TAG, "Hook requestSingleFreshLocation 成功");
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook requestSingleFreshLocation 失败: " + t.getMessage());
                Log.e(TAG, "Hook requestSingleFreshLocation 失败", t);
            }

            // Hook requestLocationUpdates - 用hookAllMethods（和原APK一致）
            try {
                XposedBridge.hookAllMethods(tencentLocationClass, "requestLocationUpdates",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】requestLocationUpdates 被调用");
                                Log.e(TAG, "requestLocationUpdates 被调用");
                                // 深度验证：验证未通过时直接返回，不执行任何hook
                                if (!verifyPassed) {
                                    XposedBridge.log("[WxLocationHook] 【验证】验证未通过，requestLocationUpdates hook失效");
                                    return;
                                }
                                if (!isLocation) return;

                                if (param.args != null && param.args.length >= 2) {
                                    Object listener = param.args[1];
                                    if (listener != null) {
                                        hookLocationListener(listener);
                                    }
                                }
                            }
                        });
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook requestLocationUpdates 成功");
                Log.e(TAG, "Hook requestLocationUpdates 成功");
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook requestLocationUpdates 失败: " + t.getMessage());
                Log.e(TAG, "Hook requestLocationUpdates 失败", t);
            }

            // Hook getLastKnownLocation - 直接修改返回值
            try {
                XposedBridge.hookAllMethods(tencentLocationClass, "getLastKnownLocation",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】getLastKnownLocation 被调用");
                                Log.e(TAG, "getLastKnownLocation 被调用");
                                if (!verifyPassed || !isLocation) return;

                                Object result = param.getResult();
                                if (result != null) {
                                    // 修改返回的位置对象
                                    modifyLocationObject(result);
                                    XposedBridge.log("[WxLocationHook] 【TencentMap】getLastKnownLocation 已修改");
                                }
                            }
                        });
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getLastKnownLocation 成功");
                Log.e(TAG, "Hook getLastKnownLocation 成功");
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getLastKnownLocation 失败: " + t.getMessage());
                Log.e(TAG, "Hook getLastKnownLocation 失败", t);
            }

            // Hook getPoiList - 搜索附近POI时也返回假位置
            try {
                XposedBridge.hookAllMethods(tencentLocationClass, "getPoiList",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】getPoiList 被调用");
                                Log.e(TAG, "getPoiList 被调用");
                                if (!verifyPassed || !isLocation) return;

                                // 查找location参数并修改
                                if (param.args != null) {
                                    for (int i = 0; i < param.args.length; i++) {
                                        Object arg = param.args[i];
                                        if (arg != null && isLocationObject(arg)) {
                                            modifyLocationObject(arg);
                                            XposedBridge.log("[WxLocationHook] 【TencentMap】getPoiList 参数已修改");
                                        }
                                    }
                                }
                            }
                        });
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getPoiList 成功");
                Log.e(TAG, "Hook getPoiList 成功");
            } catch (Throwable t) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getPoiList 失败: " + t.getMessage());
                Log.e(TAG, "Hook getPoiList 失败", t);
            }

            XposedBridge.log("[WxLocationHook] 【TencentMap】start TencentMap... 完成");
            Log.e(TAG, "start TencentMap... 完成");

            // 标记腾讯地图hook成功
            tencentMapHooked = true;
            XposedBridge.log("[WxLocationHook] 【TencentMap】腾讯地图Hook全部注册完成");
            Log.e(TAG, "腾讯地图Hook全部注册完成");
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】TencentMap ClassNotFound --> " + t.getMessage());
            Log.e(TAG, "TencentMap ClassNotFound -->");
            // 备用：Hook系统定位
            hookSystemLocation();
        }
    }

    // ==========================================
    // Hook定位监听器（和原APK一致的方式）
    // ==========================================
    private void hookLocationListener(final Object listener) {
        if (listener == null) return;

        final Class<?> listenerClass = listener.getClass();
        final String className = listenerClass.getName();

        // 检查是否已经hook过这个类（和原APK一致）
        if (isClassHooked(className)) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】(mapClass): " + className + " is already hook !");
            Log.e(TAG, "(mapClass): " + className + " is already hook !");
            return;
        }

        // 标记为已hook
        markClassHooked(className);

        XposedBridge.log("[WxLocationHook] 【TencentMap】(mapClass): " + className + " hook 成功了！！");
        Log.e(TAG, "(mapClass): " + className + " hook 成功了！！");

        try {
            // 查找 onLocationChanged 方法 - 遍历所有方法（最可靠的方式）
            Method onLocationChangedMethod = null;
            Method[] methods = listenerClass.getDeclaredMethods();
            for (Method m : methods) {
                if ("onLocationChanged".equals(m.getName())) {
                    onLocationChangedMethod = m;
                    XposedBridge.log("[WxLocationHook] 【TencentMap】找到onLocationChanged方法: " + m);
                    Log.e(TAG, "找到onLocationChanged方法: " + m);
                    break;
                }
            }

            if (onLocationChangedMethod == null) {
                // 再试试父类和接口
                Class<?> clazz = listenerClass.getSuperclass();
                while (clazz != null && onLocationChangedMethod == null) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if ("onLocationChanged".equals(m.getName())) {
                            onLocationChangedMethod = m;
                            break;
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
                
                // 再试试接口
                if (onLocationChangedMethod == null) {
                    for (Class<?> iface : listenerClass.getInterfaces()) {
                        for (Method m : iface.getDeclaredMethods()) {
                            if ("onLocationChanged".equals(m.getName())) {
                                onLocationChangedMethod = m;
                                break;
                            }
                        }
                        if (onLocationChangedMethod != null) break;
                    }
                }
            }

            if (onLocationChangedMethod == null) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】onLocationChanged Method not exit !");
                Log.e(TAG, "onLocationChanged Method not exit ! - " + className);
                return;
            }

            // Hook onLocationChanged
            XposedBridge.hookMethod(onLocationChangedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("[WxLocationHook] 【TencentMap】onLocationChanged 被调用: " + className);
                    Log.e(TAG, "onLocationChanged 被调用: " + className);
                    // 深度验证：验证未通过时直接返回，不执行任何hook
                    if (!verifyPassed) {
                        XposedBridge.log("[WxLocationHook] 【验证】验证未通过，onLocationChanged hook失效");
                        return;
                    }
                    if (!isLocation) return;

                    // 第一个参数是位置对象（和原APK一致：args[0]）
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object locationObj = param.args[0];
                        // 直接修改位置对象的字段值（最可靠的方式）
                        modifyLocationObject(locationObj);
                        // Hook位置对象的getLatitude和getLongitude（双重保险）
                        hookLocationGetters(locationObj);
                    }
                }
            });

            XposedBridge.log("[WxLocationHook] 【TencentMap】Hook onLocationChanged 成功: " + className);
            Log.e(TAG, "Hook onLocationChanged 成功: " + className);
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】Hook onLocationChanged 失败: " + t.getMessage());
            Log.e(TAG, "Hook onLocationChanged 失败: " + className, t);
        }
    }

    // ==========================================
    // Hook位置对象的getLatitude和getLongitude（核心！和原APK一致）
    // ==========================================
    private void hookLocationGetters(final Object locationObj) {
        if (locationObj == null) return;

        final Class<?> locationClass = locationObj.getClass();
        final String className = locationClass.getName();

        // 检查是否已经hook过这个类的getter方法
        if (isClassHooked(className + "_getters")) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】(locationClass): " + className + " is already hook !");
            Log.e(TAG, "(locationClass): " + className + " is already hook !");
            return;
        }
        markClassHooked(className + "_getters");

        XposedBridge.log("[WxLocationHook] 【TencentMap】(locationClass): " + className + " hook 成功了！！");
        Log.e(TAG, "(locationClass): " + className + " hook 成功了！！");

        try {
            // Hook getLatitude（和原APK一致：afterHookedMethod中setResult）
            XposedHelpers.findAndHookMethod(locationClass, "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // 深度验证：验证未通过时直接返回，不修改结果
                            if (!verifyPassed) {
                                return;
                            }
                            if (!isLocation) return;

                            Object originalResult = param.getResult();
                            XposedBridge.log("[WxLocationHook] 【TencentMap】Latitude_前-> " + originalResult);
                            Log.e(TAG, "Latitude_前-> " + originalResult);

                            double fakeLat;
                            if (isX && xcxLat != 0) {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】******小程序定位******");
                                Log.e(TAG, "******小程序定位******");
                                fakeLat = xcxLat;
                            } else {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】******微信******");
                                Log.e(TAG, "******微信******");
                                fakeLat = getLat();
                            }

                            XposedBridge.log("[WxLocationHook] 【TencentMap】Latitude_后-> " + fakeLat);
                            Log.e(TAG, "Latitude_后-> " + fakeLat);

                            // 通过setResult修改返回值（和原APK一致）
                            param.setResult(fakeLat);
                        }
                    });

            // Hook getLongitude（和原APK一致：afterHookedMethod中setResult）
            XposedHelpers.findAndHookMethod(locationClass, "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // 深度验证：验证未通过时直接返回，不修改结果
                            if (!verifyPassed) {
                                return;
                            }
                            if (!isLocation) return;

                            Object originalResult = param.getResult();
                            XposedBridge.log("[WxLocationHook] 【TencentMap】Longitude_前-> " + originalResult);
                            Log.e(TAG, "Longitude_前-> " + originalResult);

                            double fakeLng;
                            if (isX && xcxLng != 0) {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】******小程序定位******");
                                Log.e(TAG, "******小程序定位******");
                                fakeLng = xcxLng;
                            } else {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】******微信******");
                                Log.e(TAG, "******微信******");
                                fakeLng = getLng();
                            }

                            XposedBridge.log("[WxLocationHook] 【TencentMap】Longitude_后-> " + fakeLng);
                            Log.e(TAG, "Longitude_后-> " + fakeLng);

                            // 通过setResult修改返回值（和原APK一致）
                            param.setResult(fakeLng);
                        }
                    });

            XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getLatitude/getLongitude 成功: " + className);
            Log.e(TAG, "Hook getLatitude/getLongitude 成功: " + className);
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] 【TencentMap】Hook getLatitude/getLongitude 失败: " + t.getMessage());
            Log.e(TAG, "Hook getLatitude/getLongitude 失败: " + className, t);
        }
    }

    // 检查类是否已hook
    private boolean isClassHooked(String className) {
        return hookedClasses.contains(className);
    }

    // 标记类为已hook
    private void markClassHooked(String className) {
        hookedClasses.add(className);
    }

    // ==========================================
    // Hook系统定位（备用）
    // ==========================================
    private void hookSystemLocation() {
        try {
            Log.e(TAG, "开始Hook系统定位（备用方案）");

            // Hook getLastKnownLocation
            try {
                XposedHelpers.findAndHookMethod("android.location.LocationManager",
                        null, "getLastKnownLocation", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "[系统定位] getLastKnownLocation 被调用");
                                // 深度验证：验证未通过时直接返回
                                if (!verifyPassed) {
                                    Log.e(TAG, "[系统定位] 验证未通过，hook失效");
                                    return;
                                }
                                if (!isLocation) return;

                                Location fake = createFakeLocation((String) param.args[0]);
                                param.setResult(fake);
                                Log.e(TAG, "[系统定位] 已修改: " + fake.getLatitude() + ", " + fake.getLongitude());
                            }
                        });
                Log.e(TAG, "[系统定位] getLastKnownLocation Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "[系统定位] Hook getLastKnownLocation失败", t);
            }

            // Hook requestLocationUpdates
            try {
                XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "[系统定位] requestLocationUpdates 被调用");
                                // 深度验证：验证未通过时直接返回
                                if (!verifyPassed) {
                                    Log.e(TAG, "[系统定位] 验证未通过，hook失效");
                                    return;
                                }
                                if (!isLocation) return;

                                for (Object arg : param.args) {
                                    if (arg instanceof LocationListener) {
                                        hookSystemLocationListener((LocationListener) arg);
                                    }
                                }
                            }
                        });
                Log.e(TAG, "[系统定位] requestLocationUpdates Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "[系统定位] Hook requestLocationUpdates失败", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "Hook系统定位失败", t);
        }
    }

    // Hook系统定位监听器
    private void hookSystemLocationListener(final LocationListener listener) {
        if (listener == null) return;

        String className = listener.getClass().getName();
        if (hookedClasses.contains(className)) {
            return;
        }
        hookedClasses.add(className);

        try {
            XposedBridge.hookAllMethods(listener.getClass(), "onLocationChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "[系统定位] onLocationChanged 被调用");
                            if (!isLocation) return;

                            Location original = (Location) param.args[0];
                            if (original != null) {
                                Location fake = createFakeLocation(original.getProvider());
                                param.args[0] = fake;
                                Log.e(TAG, "[系统定位] 已修改位置");
                            }
                        }
                    });
            Log.e(TAG, "[系统定位] Hook onLocationChanged成功: " + className);
        } catch (Throwable t) {
            Log.e(TAG, "[系统定位] Hook onLocationChanged失败", t);
        }
    }

    // ==========================================
    // Hook菜单（长按+按钮）
    // ==========================================
    private void startMenuHook() {
        Log.e(TAG, "start Menu...");

        try {
            // Hook PlusActionView - 与原APK一致的类名
            String[] plusActionViewClasses = {
                    "com.tencent.mm.ui.HomeUI$PlusActionView",
                    "com.tencent.mm.ui.HomeUI$b",
                    "com.tencent.mm.ui.LauncherUI$PlusActionView"
            };

            Class<?> plusActionViewClass = null;
            for (String className : plusActionViewClasses) {
                try {
                    plusActionViewClass = XposedHelpers.findClass(className, classLoader);
                    Log.e(TAG, "找到PlusActionView类: " + className);
                    break;
                } catch (Throwable t) {
                    // 继续
                }
            }

            if (plusActionViewClass == null) {
                Log.e(TAG, "PlusActionView is null...");
                return;
            }

            // Hook构造函数，设置长按监听
            try {
                XposedBridge.hookAllConstructors(plusActionViewClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Log.e(TAG, "PlusActionView构造函数被调用");
                        try {
                            Object thisObj = param.thisObject;
                            View actionView = null;

                            // 尝试获取getActionView方法
                            try {
                                Method getActionView = thisObj.getClass().getMethod("getActionView");
                                actionView = (View) getActionView.invoke(thisObj);
                            } catch (Throwable t) {
                                // 尝试直接转换为View
                                if (thisObj instanceof View) {
                                    actionView = (View) thisObj;
                                }
                            }

                            if (actionView != null) {
                                final View finalView = actionView;
                                actionView.setOnLongClickListener(new View.OnLongClickListener() {
                                    @Override
                                    public boolean onLongClick(View v) {
                                        Log.e(TAG, "PlusActionView 被长按!");
                                        // 深度验证：验证未通过时不打开设置面板
                                        if (!verifyPassed) {
                                            Log.e(TAG, "【验证】验证未通过，长按功能失效");
                                            getUiHandler().post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    Toast.makeText(appContext,
                                                            "模块未激活，请先验证卡密",
                                                            Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                            return true;
                                        }
                                        // 打开设置面板
                                        openSettingsDialog();
                                        return true;
                                    }
                                });
                                Log.e(TAG, "PlusActionView 长按监听设置成功");
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "设置PlusActionView长按监听失败", t);
                        }
                    }
                });
                Log.e(TAG, "PlusActionView构造函数Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "Hook PlusActionView构造函数失败", t);
            }

            // 备用：Hook HomeUI/LauncherUI的onCreate，查找+按钮
            try {
                String[] uiClasses = {
                        "com.tencent.mm.ui.HomeUI",
                        "com.tencent.mm.ui.LauncherUI"
                };

                for (String uiClass : uiClasses) {
                    try {
                        Class<?> clazz = XposedHelpers.findClass(uiClass, classLoader);
                        XposedBridge.hookAllMethods(clazz, "onCreate", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, uiClass + " onCreate");
                            }
                        });
                        Log.e(TAG, "Hook " + uiClass + " onCreate成功");
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Hook UI类失败", t);
            }

            Log.e(TAG, "start Menu... 完成");
        } catch (Throwable t) {
            Log.e(TAG, "start Menu... 失败", t);
        }
    }

    // 打开设置对话框
    private void openSettingsDialog() {
        // 由于在微信进程中，这里简单显示一个提示
        // 完整的设置界面在模块APP中
        getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext,
                            "请打开模块APP设置虚拟位置", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.e(TAG, "显示提示失败", t);
                }
            }
        });
    }

    // ==========================================
    // 工具方法
    // ==========================================

    // 懒加载获取UI线程Handler
    private static Handler getUiHandler() {
        if (uiHandler == null) {
            synchronized (Hook.class) {
                if (uiHandler == null) {
                    uiHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return uiHandler;
    }

    private double getLat() {
        try {
            if (isX && xcxLat != 0) return xcxLat;
            return Double.parseDouble(latitude);
        } catch (Throwable e) {
            return 39.908823;
        }
    }

    private double getLng() {
        try {
            if (isX && xcxLng != 0) return xcxLng;
            return Double.parseDouble(longitude);
        } catch (Throwable e) {
            return 116.397470;
        }
    }

    private Location createFakeLocation(String provider) {
        Location fake = new Location(provider != null ? provider : "gps");
        fake.setLatitude(getLat());
        fake.setLongitude(getLng());
        fake.setAccuracy(1.0f);
        fake.setTime(System.currentTimeMillis());
        fake.setBearing(0);
        fake.setSpeed(0);
        fake.setAltitude(0);
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            fake.setElapsedRealtimeNanos(
                    android.os.SystemClock.elapsedRealtimeNanos());
        }
        return fake;
    }

    private void updateXcxCoordinates() {
        try {
            double lat = Double.parseDouble(latitude);
            double lng = Double.parseDouble(longitude);
            double[] gcj = CoordinateTransform.wgs84ToGcj02(lat, lng);
            xcxLat = gcj[0];
            xcxLng = gcj[1];
            Log.e(TAG, "小程序坐标已更新: " + xcxLat + ", " + xcxLng);
        } catch (Throwable t) {
            Log.e(TAG, "更新小程序坐标失败", t);
        }
    }

    // ==========================================
    // 位置对象修改工具方法
    // ==========================================

    /**
     * 判断对象是否是位置对象（有getLatitude/getLongitude方法）
     */
    private boolean isLocationObject(Object obj) {
        if (obj == null) return false;
        try {
            Class<?> clazz = obj.getClass();
            // 检查是否有getLatitude和getLongitude方法
            Method[] methods = clazz.getMethods();
            boolean hasLat = false;
            boolean hasLng = false;
            for (Method m : methods) {
                if ("getLatitude".equals(m.getName())) hasLat = true;
                if ("getLongitude".equals(m.getName())) hasLng = true;
            }
            return hasLat && hasLng;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 直接修改位置对象的字段值（最可靠的方式）
     * 同时修改double类型和float类型的字段
     */
    private void modifyLocationObject(Object locationObj) {
        if (locationObj == null) return;
        if (!verifyPassed || !isLocation) return;

        double fakeLat = getLat();
        double fakeLng = getLng();

        try {
            Class<?> clazz = locationObj.getClass();
            Field[] fields = clazz.getDeclaredFields();
            boolean modified = false;

            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                try {
                    field.setAccessible(true);
                    Class<?> fieldType = field.getType();

                    // 修改纬度字段
                    if (fieldName.contains("lat") || fieldName.contains("latitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(locationObj, fakeLat);
                            modified = true;
                            Log.e(TAG, "修改位置字段 " + field.getName() + " (double) -> " + fakeLat);
                        } else if (fieldType == float.class || fieldType == Float.class) {
                            field.setFloat(locationObj, (float) fakeLat);
                            modified = true;
                            Log.e(TAG, "修改位置字段 " + field.getName() + " (float) -> " + fakeLat);
                        }
                    }

                    // 修改经度字段
                    if (fieldName.contains("lng") || fieldName.contains("long") || fieldName.contains("longitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(locationObj, fakeLng);
                            modified = true;
                            Log.e(TAG, "修改位置字段 " + field.getName() + " (double) -> " + fakeLng);
                        } else if (fieldType == float.class || fieldType == Float.class) {
                            field.setFloat(locationObj, (float) fakeLng);
                            modified = true;
                            Log.e(TAG, "修改位置字段 " + field.getName() + " (float) -> " + fakeLng);
                        }
                    }
                } catch (Throwable t) {
                    // 单个字段修改失败不影响其他
                }
            }

            // 如果没有找到字段，尝试修改父类的字段
            if (!modified) {
                Class<?> superClass = clazz.getSuperclass();
                while (superClass != null && !modified) {
                    Field[] superFields = superClass.getDeclaredFields();
                    for (Field field : superFields) {
                        String fieldName = field.getName().toLowerCase();
                        try {
                            field.setAccessible(true);
                            Class<?> fieldType = field.getType();

                            if (fieldName.contains("lat") || fieldName.contains("latitude")) {
                                if (fieldType == double.class || fieldType == Double.class) {
                                    field.setDouble(locationObj, fakeLat);
                                    modified = true;
                                } else if (fieldType == float.class || fieldType == Float.class) {
                                    field.setFloat(locationObj, (float) fakeLat);
                                    modified = true;
                                }
                            }

                            if (fieldName.contains("lng") || fieldName.contains("long") || fieldName.contains("longitude")) {
                                if (fieldType == double.class || fieldType == Double.class) {
                                    field.setDouble(locationObj, fakeLng);
                                    modified = true;
                                } else if (fieldType == float.class || fieldType == Float.class) {
                                    field.setFloat(locationObj, (float) fakeLng);
                                    modified = true;
                                }
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                    superClass = superClass.getSuperclass();
                }
            }

            if (modified) {
                XposedBridge.log("[WxLocationHook] 【TencentMap】位置对象字段已修改: lat=" + fakeLat + ", lng=" + fakeLng);
            } else {
                XposedBridge.log("[WxLocationHook] 【TencentMap】位置对象字段修改失败，未找到经纬度字段");
                Log.e(TAG, "位置对象类: " + locationObj.getClass().getName());
                // 打印所有字段名便于调试
                for (Field field : clazz.getDeclaredFields()) {
                    Log.e(TAG, "  字段: " + field.getName() + " (" + field.getType().getName() + ")");
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "修改位置对象失败", t);
        }
    }

    // ==========================================
    // Hook端独立心跳验证（不依赖模块端）
    // ==========================================

    /**
     * 启动Hook端心跳验证
     * 微信进程中独立维护心跳，不依赖模块APP是否存活
     */
    private void startHookHeartbeat() {
        if (heartbeatRunning) {
            return;
        }

        // 从ContentProvider读取卡密和token信息
        try {
            ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
            if (data != null) {
                hookCardCode = data.verifyCard != null ? data.verifyCard : "";
                if (data.verifyExpire != null && !data.verifyExpire.isEmpty()) {
                    try {
                        hookExpireTime = Long.parseLong(data.verifyExpire);
                    } catch (NumberFormatException e) {
                        hookExpireTime = 0;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "【心跳】读取卡密信息失败", t);
        }

        if (hookCardCode == null || hookCardCode.isEmpty()) {
            XposedBridge.log("[WxLocationHook] 【心跳】没有卡密，不启动心跳");
            Log.e(TAG, "【心跳】没有卡密，不启动心跳");
            return;
        }

        heartbeatTimer = new Timer();
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                doHookHeartbeat();
            }
        }, 0, HEARTBEAT_INTERVAL);

        heartbeatRunning = true;
        XposedBridge.log("[WxLocationHook] 【心跳】Hook端心跳已启动，间隔: " + (HEARTBEAT_INTERVAL / 1000) + "秒");
        Log.e(TAG, "【心跳】Hook端心跳已启动，间隔: " + (HEARTBEAT_INTERVAL / 1000) + "秒");
    }

    /**
     * 停止Hook端心跳
     */
    private void stopHookHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        heartbeatRunning = false;
        XposedBridge.log("[WxLocationHook] 【心跳】Hook端心跳已停止");
        Log.e(TAG, "【心跳】Hook端心跳已停止");
    }

    /**
     * 执行Hook端心跳验证
     * 调用ShuanQVerifier的心跳逻辑（使用微信进程的Context）
     */
    private void doHookHeartbeat() {
        try {
            // 重新从ContentProvider读取最新的卡密和token
            try {
                ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
                if (data != null && data.verifyCard != null && !data.verifyCard.isEmpty()) {
                    hookCardCode = data.verifyCard;
                    if (data.verifyExpire != null && !data.verifyExpire.isEmpty()) {
                        try {
                            hookExpireTime = Long.parseLong(data.verifyExpire);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "【心跳】读取配置失败", t);
            }

            if (hookCardCode == null || hookCardCode.isEmpty()) {
                verifyPassed = false;
                isLocation = false;
                stopHookHeartbeat();
                return;
            }

            // 设置ShuanQVerifier的卡密和验证状态（微信进程中独立维护）
            ShuanQVerifier.setCardCode(hookCardCode);
            ShuanQVerifier.setVerified(verifyPassed, hookExpireTime);

            // 使用ShuanQVerifier的心跳验证
            ShuanQVerifier.heartbeatVerify(appContext);

            // 检查验证结果
            boolean passed = ShuanQVerifier.isVerified();
            if (passed != verifyPassed) {
                verifyPassed = passed;
                XposedBridge.log("[WxLocationHook] 【心跳】验证状态变化: " + verifyPassed);
                Log.e(TAG, "【心跳】验证状态变化: " + verifyPassed);

                if (!verifyPassed) {
                    isLocation = false;
                    stopHookHeartbeat();
                }
            }

            // 同步验证状态到ContentProvider（供其他进程使用）
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("verify_passed", verifyPassed);
                ConfigProvider.writeConfig(appContext, values);
            } catch (Throwable t) {
                // ignore
            }

        } catch (Throwable t) {
            Log.e(TAG, "【心跳】心跳验证异常", t);
            // 网络异常时不立即失效，保留当前状态
        }
    }
}
