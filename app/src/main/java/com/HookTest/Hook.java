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

        // 判断是否是微信进程：检查包名或进程名是否匹配
        // MIUI系统可能出现包名是com.miui.contentcatcher但进程名是com.tencent.mm的情况
        boolean isWeChat = TARGET_PACKAGE.equals(lpparam.packageName)
                || (lpparam.processName != null && lpparam.processName.startsWith(TARGET_PACKAGE));

        if (!isWeChat) {
            XposedBridge.log("[WxLocationHook] 非目标包，跳过: pkg=" + lpparam.packageName + ", proc=" + lpparam.processName);
            return;
        }

        XposedBridge.log("[WxLocationHook] ========== 微信定位模块开始加载 ==========");
        XposedBridge.log("[WxLocationHook] 包名: " + lpparam.packageName);
        XposedBridge.log("[WxLocationHook] 进程名: " + lpparam.processName);

        classLoader = lpparam.classLoader;

        Log.e(TAG, "========== 微信定位模块开始加载 ==========");

        // Hook Application.attach 获取Context
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[WxLocationHook] Application.attach 已触发!");
                            Log.e(TAG, "Application is already hook ! !");
                            appContext = (Context) param.args[0];
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
            XposedBridge.log("[WxLocationHook] Application.attach Hook注册成功");
            Log.e(TAG, "Application.attach Hook注册成功");
        } catch (Throwable t) {
            XposedBridge.log("[WxLocationHook] Hook Application.attach失败: " + t.getMessage());
            Log.e(TAG, "Hook Application.attach失败", t);
        }

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
    // Hook 腾讯地图定位
    // ==========================================
    private void startTencentMapHook() {
        XposedBridge.log("[WxLocationHook] start TencentMap...");
        Log.e(TAG, "start TencentMap...");

        try {
            // 原APK使用的类名：com.tencent.map.geolocation.sapp.TencentLocationManager
            String[] tencentClasses = {
                    "com.tencent.map.geolocation.sapp.TencentLocationManager",
                    "com.tencent.map.geolocation.TencentLocationManager",
                    "com.tencent.location.TencentLocationManager"
            };

            Class<?> tencentLocationClass = null;
            for (String className : tencentClasses) {
                try {
                    tencentLocationClass = XposedHelpers.findClass(className, classLoader);
                    XposedBridge.log("[WxLocationHook] 找到定位类: " + className);
                    Log.e(TAG, "(locationClass): " + className);
                    break;
                } catch (Throwable t) {
                    // 继续尝试
                }
            }

            if (tencentLocationClass == null) {
                XposedBridge.log("[WxLocationHook] TencentMap ClassNotFound --> 启用系统定位Hook");
                Log.e(TAG, "TencentMap ClassNotFound -->");
                // 备用：Hook系统定位
                hookSystemLocation();
                return;
            }

            // Hook requestSingleFreshLocation 方法
            try {
                Method[] methods = tencentLocationClass.getDeclaredMethods();
                for (final Method method : methods) {
                    String name = method.getName();

                    // Hook requestSingleFreshLocation
                    if ("requestSingleFreshLocation".equals(name)) {
                        try {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "requestSingleFreshLocation 被调用");
                                    if (!isLocation) return;

                                    // 查找listener参数并Hook
                                    for (Object arg : param.args) {
                                        if (arg != null && isLocationListener(arg)) {
                                            hookTencentLocationListener(arg);
                                        }
                                    }
                                }
                            });
                            Log.e(TAG, "Hook requestSingleFreshLocation 成功");
                        } catch (Throwable t) {
                            Log.e(TAG, "Hook requestSingleFreshLocation 失败", t);
                        }
                    }

                    // Hook requestLocationUpdates
                    if ("requestLocationUpdates".equals(name)) {
                        try {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "requestLocationUpdates 被调用");
                                    if (!isLocation) return;

                                    for (Object arg : param.args) {
                                        if (arg != null && isLocationListener(arg)) {
                                            hookTencentLocationListener(arg);
                                        }
                                    }
                                }
                            });
                            Log.e(TAG, "Hook requestLocationUpdates 成功");
                        } catch (Throwable t) {
                            Log.e(TAG, "Hook requestLocationUpdates 失败", t);
                        }
                    }
                }

                // 同时用hookAllMethods兜底
                try {
                    XposedBridge.hookAllMethods(tencentLocationClass, "requestSingleFreshLocation",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "[hookAll] requestSingleFreshLocation 被调用");
                                    if (!isLocation) return;
                                    for (Object arg : param.args) {
                                        if (arg != null && isLocationListener(arg)) {
                                            hookTencentLocationListener(arg);
                                        }
                                    }
                                }
                            });
                    Log.e(TAG, "hookAllMethods requestSingleFreshLocation 成功");
                } catch (Throwable t) {
                    Log.e(TAG, "hookAllMethods requestSingleFreshLocation 失败", t);
                }

                try {
                    XposedBridge.hookAllMethods(tencentLocationClass, "requestLocationUpdates",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "[hookAll] requestLocationUpdates 被调用");
                                    if (!isLocation) return;
                                    for (Object arg : param.args) {
                                        if (arg != null && isLocationListener(arg)) {
                                            hookTencentLocationListener(arg);
                                        }
                                    }
                                }
                            });
                    Log.e(TAG, "hookAllMethods requestLocationUpdates 成功");
                } catch (Throwable t) {
                    Log.e(TAG, "hookAllMethods requestLocationUpdates 失败", t);
                }

            } catch (Throwable t) {
                Log.e(TAG, "Hook腾讯定位方法失败", t);
            }

            // Hook腾讯定位对象的getLastKnownLocation等方法
            try {
                hookTencentLocationObject(tencentLocationClass);
            } catch (Throwable t) {
                Log.e(TAG, "Hook腾讯定位对象失败", t);
            }

            Log.e(TAG, "start TencentMap... 完成");
        } catch (Throwable t) {
            Log.e(TAG, "TencentMap ClassNotFound -->", t);
            hookSystemLocation();
        }
    }

    // 判断是否是定位监听器
    private boolean isLocationListener(Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        String name = clazz.getName();

        if (name.contains("TencentLocationListener")
                || name.contains("LocationListener")
                || name.contains("LocationCallback")
                || name.contains("listener")
                || name.contains("callback")) {
            return true;
        }

        // 检查接口
        Class<?>[] interfaces = clazz.getInterfaces();
        for (Class<?> iface : interfaces) {
            String ifaceName = iface.getName();
            if (ifaceName.contains("TencentLocationListener")
                    || ifaceName.contains("LocationListener")) {
                return true;
            }
        }

        return false;
    }

    // Hook腾讯定位监听器
    private void hookTencentLocationListener(final Object listener) {
        if (listener == null) return;

        String className = listener.getClass().getName();
        if (hookedClasses.contains(className)) {
            return;
        }
        hookedClasses.add(className);

        try {
            Method[] methods = listener.getClass().getDeclaredMethods();
            boolean found = false;

            for (final Method method : methods) {
                String name = method.getName();

                // 查找onLocationChanged方法
                if (name.contains("onLocationChanged")
                        || name.contains("onLocation")
                        || name.contains("locationChanged")) {

                    // 检查是否有Location参数
                    Class<?>[] params = method.getParameterTypes();
                    boolean hasLocationParam = false;
                    for (Class<?> p : params) {
                        if (p.getName().contains("Location")
                                || p.getName().contains("location")) {
                            hasLocationParam = true;
                            break;
                        }
                    }

                    if (hasLocationParam || params.length >= 1) {
                        try {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "onLocationChanged 被调用: " + method.getName());
                                    if (!isLocation) return;

                                    // 修改第一个位置参数
                                    for (int i = 0; i < param.args.length; i++) {
                                        if (param.args[i] != null
                                                && param.args[i].getClass().getName().contains("Location")) {
                                            fakeTencentLocation(param.args[i]);
                                            Log.e(TAG, "修改位置参数 " + i);
                                            break;
                                        }
                                    }
                                }
                            });
                            found = true;
                            Log.e(TAG, "Hook onLocationChanged 成功: " + className + "." + name);
                        } catch (Throwable t) {
                            Log.e(TAG, "Hook onLocationChanged 失败: " + name, t);
                        }
                    }
                }
            }

            if (!found) {
                Log.e(TAG, "onLocationChanged Method not exit ! - " + className);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook腾讯定位监听器失败", t);
        }
    }

    // 修改腾讯定位对象
    private void fakeTencentLocation(Object location) {
        try {
            Class<?> clazz = location.getClass();
            double lat = getLat();
            double lng = getLng();

            Log.e(TAG, "准备修改定位: lat=" + lat + ", lng=" + lng);

            // 遍历所有字段
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                Class<?> fieldType = field.getType();

                if (fieldType == double.class || fieldType == Double.class
                        || fieldType == float.class || fieldType == Float.class) {
                    field.setAccessible(true);

                    // 纬度
                    if (fieldName.contains("lat") || fieldName.contains("latitude")
                            || fieldName.equals("a") || fieldName.equals("mLatitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lat);
                        } else {
                            field.setFloat(location, (float) lat);
                        }
                        Log.e(TAG, "设置纬度: " + field.getName() + " = " + lat);
                    }

                    // 经度
                    if (fieldName.contains("lng") || fieldName.contains("longitude")
                            || fieldName.equals("b") || fieldName.equals("mLongitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lng);
                        } else {
                            field.setFloat(location, (float) lng);
                        }
                        Log.e(TAG, "设置经度: " + field.getName() + " = " + lng);
                    }
                }
            }

            // 尝试setter方法
            try {
                Method setLat = findMethod(clazz, "setLatitude", "setLat", "latitude");
                if (setLat != null) {
                    setLat.setAccessible(true);
                    setLat.invoke(location, lat);
                    Log.e(TAG, "通过setter设置纬度: " + setLat.getName());
                }

                Method setLng = findMethod(clazz, "setLongitude", "setLng", "longitude");
                if (setLng != null) {
                    setLng.setAccessible(true);
                    setLng.invoke(location, lng);
                    Log.e(TAG, "通过setter设置经度: " + setLng.getName());
                }
            } catch (Throwable ignored) {}

            Log.e(TAG, "定位修改完成");
        } catch (Throwable t) {
            Log.e(TAG, "修改定位对象失败", t);
        }
    }

    // 查找方法
    private Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Method[] methods = clazz.getDeclaredMethods();
                for (Method m : methods) {
                    if (m.getName().equalsIgnoreCase(name) && m.getParameterTypes().length == 1) {
                        Class<?> paramType = m.getParameterTypes()[0];
                        if (paramType == double.class || paramType == Double.class
                                || paramType == float.class || paramType == Float.class) {
                            return m;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // Hook腾讯定位类的getLast等方法
    private void hookTencentLocationObject(Class<?> managerClass) {
        try {
            // 查找返回位置对象的方法
            Method[] methods = managerClass.getDeclaredMethods();
            for (final Method method : methods) {
                String name = method.getName();
                Class<?> returnType = method.getReturnType();

                if (returnType != null && returnType.getName().contains("Location")) {
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "定位返回方法被调用: " + method.getName());
                                if (!isLocation) return;

                                Object result = param.getResult();
                                if (result != null) {
                                    fakeTencentLocation(result);
                                }
                            }
                        });
                        Log.e(TAG, "Hook返回方法成功: " + name);
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook腾讯定位对象方法失败", t);
        }
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
