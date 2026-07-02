package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块
 * 完全按照原APK逻辑重写
 * 支持微信8.0.74
 */
public class Hook implements IXposedHookLoadPackage {

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

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 【重要】首先输出到Xposed日志，确保LSPosed能捕获到
        XposedBridge.log("[WxLocationHook] ========================================");
        XposedBridge.log("[WxLocationHook] 【模块加载】handleLoadPackage 被调用!");
        XposedBridge.log("[WxLocationHook] 包名: " + lpparam.packageName);
        XposedBridge.log("[WxLocationHook] 进程名: " + lpparam.processName);
        XposedBridge.log("[WxLocationHook] =========================================");

        // 同时输出到logcat
        Log.e(TAG, "========================================");
        Log.e(TAG, "【LoadPackage】handleLoadPackage 被调用!");
        Log.e(TAG, "包名: " + lpparam.packageName);
        Log.e(TAG, "进程名: " + lpparam.processName);
        Log.e(TAG, "========================================");

        // 和原APK一致：不判断包名，只确保只执行一次
        if (hasHooked) {
            XposedBridge.log("[WxLocationHook] Application is already hook ! !");
            Log.e(TAG, "Application is already hook ! !");
            return;
        }

        hasHooked = true;

        XposedBridge.log("[WxLocationHook] load wechat Package success !");
        Log.e(TAG, "load wechat Package success !");

        // Hook Application.attach 获取Context（和原APK完全一致）
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("[WxLocationHook] Application.attach 已触发!");
                        Log.e(TAG, "Application is already hook ! !");
                        appContext = (Context) param.args[0];
                        // 从Context获取classLoader（和原APK一致：context.getClassLoader()）
                        classLoader = appContext.getClassLoader();
                        XposedBridge.log("[WxLocationHook] 获取到Context: " + appContext.getPackageName());
                        Log.e(TAG, "获取到Context: " + appContext.getPackageName());

                        // 加载配置
                        loadConfig();

                        // 显示加载完成提示
                        showLoadedToast();

                        // 开始Hook腾讯地图定位
                        startTencentMapHook();

                        // 开始Hook菜单
                        startMenuHook();

                        XposedBridge.log("[WxLocationHook] ========== 所有Hook注册完成 ==========");
                    }
                });

        XposedBridge.log("[WxLocationHook] ========== 模块加载初始化完成 ==========");
        Log.e(TAG, "========== 模块加载初始化完成 ==========");
    }

    // 显示加载完成提示
    private void showLoadedToast() {
        if (hasShownLoadedToast) return;
        hasShownLoadedToast = true;

        getUiHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, "功能加载完成", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Toast已显示: 功能加载完成");
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
    // Hook 腾讯地图定位（完全按照原APK方式实现）
    // ==========================================
    private void startTencentMapHook() {
        XposedBridge.log("[WxLocationHook] 【TencentMap】start TencentMap...");
        Log.e(TAG, "start TencentMap...");

        try {
            // 原APK使用的类名：com.tencent.map.geolocation.sapp.TencentLocationManager
            String tencentClassName = "com.tencent.map.geolocation.sapp.TencentLocationManager";

            // 用classLoader加载类（和原APK一致）
            Class<?> tencentLocationClass = classLoader.loadClass(tencentClassName);
            XposedBridge.log("[WxLocationHook] 【TencentMap】(locationClass): " + tencentClassName);
            Log.e(TAG, "(locationClass): " + tencentClassName);

            // Hook requestSingleFreshLocation - 用hookAllMethods（和原APK一致）
            try {
                XposedBridge.hookAllMethods(tencentLocationClass, "requestSingleFreshLocation",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[WxLocationHook] 【TencentMap】requestSingleFreshLocation 被调用");
                                Log.e(TAG, "requestSingleFreshLocation 被调用");
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

            XposedBridge.log("[WxLocationHook] 【TencentMap】start TencentMap... 完成");
            Log.e(TAG, "start TencentMap... 完成");
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
            // 和原APK完全一致：参数顺序是 [null, int.class, String.class]
            // 对应方法签名: onLocationChanged(TencentLocation location, int error, String reason)
            Object[] paramTypes = new Object[3];
            paramTypes[0] = null;           // 第一个参数：位置对象（任意引用类型）
            paramTypes[1] = int.class;      // 第二个参数：int
            paramTypes[2] = String.class;   // 第三个参数：String

            Method onLocationChangedMethod = XposedHelpers.findMethodBestMatch(
                    listenerClass, "onLocationChanged", paramTypes);

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
                    if (!isLocation) return;

                    // 第一个参数是位置对象（和原APK一致：args[0]）
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object locationObj = param.args[0];
                        // Hook位置对象的getLatitude和getLongitude
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
}
