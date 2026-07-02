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
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块
 * 多层Hook策略：系统定位 + 腾讯地图定位 + 其他定位SDK
 * 设置界面在模块APP主活动中
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "WxLocationHook";
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    // 全局状态 - 默认开启，便于测试Hook是否生效
    private static boolean locationEnabled = true;
    private static boolean xcxEnabled = false;
    private static String latitude = "39.908823";
    private static String longitude = "116.397470";
    private static String gpsPlace = "北京市东城区";

    private static Context appContext = null;
    private static ClassLoader classLoader = null;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 已Hook的回调类（防重复）
    private static final Set<String> hookedClasses = new HashSet<>();

    // 是否已显示加载完成提示
    private static boolean hasShownLoadedToast = false;

    // 小程序坐标（GCJ02）
    private static double xcxLat = 0;
    private static double xcxLng = 0;

    // Hook统计
    private static int hookCount = 0;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.e(TAG, "========================================");
        Log.e(TAG, "handleLoadPackage 被调用!");
        Log.e(TAG, "包名: " + lpparam.packageName);
        Log.e(TAG, "进程名: " + lpparam.processName);
        Log.e(TAG, "========================================");

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            Log.e(TAG, "不是目标包，跳过");
            return;
        }

        classLoader = lpparam.classLoader;

        Log.e(TAG, "========== 微信定位模块开始加载 ==========");

        // Hook Application.attach 获取Context
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "Application.attach 被调用!");
                            appContext = (Context) param.args[0];
                            Log.e(TAG, "获取到Context: " + appContext.getPackageName());

                            // 尝试读取配置
                            tryLoadConfig();

                            // 显示加载完成提示
                            showLoadedToast();

                            // 开始Hook定位
                            startHooking();
                        }
                    });
            Log.e(TAG, "Application.attach Hook注册成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Application.attach失败", t);
        }

        Log.e(TAG, "========== 模块加载初始化完成 ==========");
    }

    private void tryLoadConfig() {
        try {
            ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
            if (data != null) {
                locationEnabled = data.locationEnabled;
                xcxEnabled = data.xcxEnabled;
                if (!isEmpty(data.latitude)) latitude = data.latitude;
                if (!isEmpty(data.longitude)) longitude = data.longitude;
                if (!isEmpty(data.gpsPlace)) gpsPlace = data.gpsPlace;
                Log.e(TAG, "配置读取成功: 定位开关=" + locationEnabled
                        + ", lat=" + latitude + ", lng=" + longitude);
            } else {
                Log.e(TAG, "配置读取返回null，使用默认值（默认开启定位）");
                locationEnabled = true; // 默认开启，便于测试
            }
            updateXcxCoordinates();
        } catch (Throwable t) {
            Log.e(TAG, "读取配置异常，使用默认值（默认开启定位）", t);
            locationEnabled = true; // 默认开启，便于测试
        }
    }

    private void startHooking() {
        Log.e(TAG, "========== 开始Hook定位 ==========");

        // 第1层：Hook系统LocationManager
        hookSystemLocationManager();

        // 第2层：Hook LocationListener
        hookLocationListener();

        // 第3层：Hook腾讯地图定位
        hookTencentLocation();

        // 第4层：Hook百度地图定位
        hookBaiduLocation();

        // 第5层：Hook高德地图定位
        hookAmapLocation();

        Log.e(TAG, "========== Hook完成，共Hook " + hookCount + " 个方法 ==========");
    }

    // 显示加载完成提示
    private void showLoadedToast() {
        if (hasShownLoadedToast) return;
        hasShownLoadedToast = true;

        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, "功能加载完成 - Hook数量: " + hookCount,
                            Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Toast已显示: 功能加载完成");
                } catch (Throwable t) {
                    Log.e(TAG, "显示Toast失败", t);
                }
            }
        }, 2000);
    }

    // ==========================================
    // 第1层：Hook系统LocationManager
    // ==========================================
    private void hookSystemLocationManager() {
        try {
            Class<?> lmClass = XposedHelpers.findClass("android.location.LocationManager", null);

            // Hook getLastKnownLocation
            try {
                XposedHelpers.findAndHookMethod(lmClass, "getLastKnownLocation", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "[系统定位] getLastKnownLocation 被调用, provider=" + param.args[0]);
                                if (!locationEnabled) return;

                                Location original = (Location) param.getResult();
                                Location fake = createFakeLocation(
                                        original != null ? original.getProvider() : (String) param.args[0]);
                                param.setResult(fake);
                                Log.e(TAG, "[系统定位] getLastKnownLocation 已修改: "
                                        + fake.getLatitude() + ", " + fake.getLongitude());
                            }
                        });
                hookCount++;
                Log.e(TAG, "[系统定位] getLastKnownLocation Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "[系统定位] Hook getLastKnownLocation失败", t);
            }

            // Hook requestLocationUpdates (多个重载)
            String[] requestMethods = {"requestLocationUpdates", "requestSingleUpdate"};
            for (String methodName : requestMethods) {
                try {
                    Method[] methods = lmClass.getDeclaredMethods();
                    for (final Method method : methods) {
                        if (!method.getName().equals(methodName)) continue;

                        // 查找LocationListener参数
                        boolean hasListener = false;
                        int listenerIndex = -1;
                        Class<?>[] params = method.getParameterTypes();
                        for (int i = 0; i < params.length; i++) {
                            if (LocationListener.class.isAssignableFrom(params[i])) {
                                hasListener = true;
                                listenerIndex = i;
                                break;
                            }
                        }

                        if (hasListener) {
                            final int idx = listenerIndex;
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "[系统定位] " + method.getName() + " 被调用");
                                    if (!locationEnabled) return;

                                    Object listener = param.args[idx];
                                    if (listener != null) {
                                        hookLocationListenerObject(listener);
                                    }
                                }
                            });
                            hookCount++;
                            Log.e(TAG, "[系统定位] " + method.getName() + " Hook成功");
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "[系统定位] Hook " + methodName + "失败", t);
                }
            }

            // Hook isProviderEnabled (让定位看起来总是开启的)
            try {
                XposedHelpers.findAndHookMethod(lmClass, "isProviderEnabled", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!locationEnabled) return;
                                param.setResult(true);
                            }
                        });
                hookCount++;
                Log.e(TAG, "[系统定位] isProviderEnabled Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "[系统定位] Hook isProviderEnabled失败", t);
            }

            // Hook getProviders
            try {
                XposedHelpers.findAndHookMethod(lmClass, "getProviders", boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "[系统定位] getProviders 被调用, 返回: " + param.getResult());
                            }
                        });
                hookCount++;
                Log.e(TAG, "[系统定位] getProviders Hook成功");
            } catch (Throwable t) {
                Log.e(TAG, "[系统定位] Hook getProviders失败", t);
            }

            Log.e(TAG, "[系统定位] LocationManager Hook完成");
        } catch (Throwable t) {
            Log.e(TAG, "[系统定位] Hook LocationManager失败", t);
        }
    }

    // ==========================================
    // 第2层：Hook LocationListener接口实现类
    // ==========================================
    private void hookLocationListener() {
        try {
            // 通过动态代理Hook所有LocationListener
            // 这里我们在requestLocationUpdates时已经Hook了监听器对象
            Log.e(TAG, "[定位监听器] 监听器Hook通过requestLocationUpdates实现");
        } catch (Throwable t) {
            Log.e(TAG, "[定位监听器] Hook失败", t);
        }
    }

    // Hook具体的LocationListener对象
    private void hookLocationListenerObject(final Object listener) {
        if (listener == null) return;

        String className = listener.getClass().getName();
        if (hookedClasses.contains(className)) {
            Log.e(TAG, "[定位监听器] 已Hook过: " + className);
            return;
        }
        hookedClasses.add(className);

        try {
            // 查找onLocationChanged方法
            Method[] methods = listener.getClass().getDeclaredMethods();
            for (final Method method : methods) {
                if ("onLocationChanged".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == Location.class) {

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "[定位监听器] onLocationChanged 被调用");
                            if (!locationEnabled) return;

                            Location original = (Location) param.args[0];
                            if (original != null) {
                                Location fake = createFakeLocation(original.getProvider());
                                param.args[0] = fake;
                                Log.e(TAG, "[定位监听器] onLocationChanged 已修改: "
                                        + fake.getLatitude() + ", " + fake.getLongitude());
                            }
                        }
                    });
                    hookCount++;
                    Log.e(TAG, "[定位监听器] Hook成功: " + className + "." + method.getName());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[定位监听器] Hook失败: " + className, t);
        }
    }

    // ==========================================
    // 第3层：Hook腾讯地图定位
    // ==========================================
    private void hookTencentLocation() {
        String[] tencentClasses = {
                "com.tencent.map.geolocation.TencentLocationManager",
                "com.tencent.location.TencentLocationManager",
                "com.tencent.map.geolocation.a",
                "com.tencent.location.a",
                "com.tencent.map.geolocation.TencentLocation",
                "com.tencent.location.TencentLocation"
        };

        for (String className : tencentClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                Log.e(TAG, "[腾讯定位] 找到类: " + className);

                Method[] methods = clazz.getDeclaredMethods();
                for (final Method method : methods) {
                    String name = method.getName();
                    Class<?>[] params = method.getParameterTypes();

                    // 查找包含listener/回调的方法
                    boolean hasCallback = false;
                    int callbackIndex = -1;
                    for (int i = 0; i < params.length; i++) {
                        String paramName = params[i].getName();
                        if (paramName.contains("Listener")
                                || paramName.contains("Callback")
                                || paramName.contains("listener")
                                || paramName.contains("callback")) {
                            hasCallback = true;
                            callbackIndex = i;
                            break;
                        }
                    }

                    if (hasCallback) {
                        final int idx = callbackIndex;
                        try {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "[腾讯定位] " + method.getName() + " 被调用");
                                    if (!locationEnabled) return;

                                    Object callback = param.args[idx];
                                    if (callback != null) {
                                        hookTencentCallback(callback);
                                    }
                                }
                            });
                            hookCount++;
                            Log.e(TAG, "[腾讯定位] Hook方法成功: " + name);
                        } catch (Throwable t) {
                            // 忽略单个方法Hook失败
                        }
                    }

                    // 查找返回位置对象的方法
                    if (name.contains("getLast") || name.contains("getLocation")
                            || name.contains("lastKnown") || name.contains("getCurrent")) {
                        try {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    Log.e(TAG, "[腾讯定位] " + method.getName() + " 返回结果");
                                    if (!locationEnabled) return;

                                    Object result = param.getResult();
                                    if (result != null) {
                                        fakeTencentLocation(result);
                                    }
                                }
                            });
                            hookCount++;
                            Log.e(TAG, "[腾讯定位] Hook返回方法成功: " + name);
                        } catch (Throwable t) {
                            // 忽略
                        }
                    }
                }
            } catch (Throwable t) {
                // 类不存在，继续下一个
            }
        }

        Log.e(TAG, "[腾讯定位] Hook完成");
    }

    // Hook腾讯定位回调
    private void hookTencentCallback(final Object callback) {
        if (callback == null) return;

        String className = callback.getClass().getName();
        if (hookedClasses.contains(className)) {
            return;
        }
        hookedClasses.add(className);

        try {
            Method[] methods = callback.getClass().getDeclaredMethods();
            for (final Method method : methods) {
                String name = method.getName();
                if (name.contains("Location") || name.contains("location")
                        || name.contains("Changed") || name.contains("changed")
                        || name.contains("onStatus") || name.contains("onUpdate")) {

                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Log.e(TAG, "[腾讯回调] " + method.getName() + " 被调用");
                                if (!locationEnabled) return;

                                // 修改所有位置对象参数
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] != null
                                            && param.args[i].getClass().getName().contains("Location")) {
                                        fakeTencentLocation(param.args[i]);
                                        Log.e(TAG, "[腾讯回调] 修改参数 " + i + " 的位置");
                                    }
                                }
                            }
                        });
                        hookCount++;
                        Log.e(TAG, "[腾讯回调] Hook成功: " + className + "." + name);
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[腾讯回调] Hook失败: " + className, t);
        }
    }

    // 修改腾讯定位对象
    private void fakeTencentLocation(Object location) {
        try {
            Class<?> clazz = location.getClass();
            double lat = getLat();
            double lng = getLng();

            // 遍历所有字段，修改经纬度相关的
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                Class<?> fieldType = field.getType();

                if (fieldType == double.class || fieldType == Double.class
                        || fieldType == float.class || fieldType == Float.class) {
                    field.setAccessible(true);

                    if (fieldName.contains("lat") || fieldName.contains("latitude")
                            || fieldName.equals("a") || fieldName.equals("mLatitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lat);
                        } else {
                            field.setFloat(location, (float) lat);
                        }
                        Log.e(TAG, "[腾讯定位对象] 修改字段 " + field.getName() + " = " + lat);
                    }

                    if (fieldName.contains("lng") || fieldName.contains("longitude")
                            || fieldName.equals("b") || fieldName.equals("mLongitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lng);
                        } else {
                            field.setFloat(location, (float) lng);
                        }
                        Log.e(TAG, "[腾讯定位对象] 修改字段 " + field.getName() + " = " + lng);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "[腾讯定位对象] 修改失败", t);
        }
    }

    // ==========================================
    // 第4层：Hook百度地图定位
    // ==========================================
    private void hookBaiduLocation() {
        String[] baiduClasses = {
                "com.baidu.location.BDLocation",
                "com.baidu.location.LocationClient",
                "com.baidu.location.f"
        };

        for (String className : baiduClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                Log.e(TAG, "[百度定位] 找到类: " + className);

                // Hook所有方法
                Method[] methods = clazz.getDeclaredMethods();
                for (final Method method : methods) {
                    if (method.getParameterTypes().length >= 1) {
                        // 查找位置对象参数
                        Class<?>[] params = method.getParameterTypes();
                        for (int i = 0; i < params.length; i++) {
                            if (params[i].getName().contains("BDLocation")
                                    || params[i].getName().contains("Location")) {
                                final int idx = i;
                                try {
                                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                            if (!locationEnabled) return;
                                            if (param.args[idx] != null) {
                                                fakeBaiduLocation(param.args[idx]);
                                            }
                                        }
                                    });
                                    hookCount++;
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // 类不存在
            }
        }

        Log.e(TAG, "[百度定位] Hook完成");
    }

    // 修改百度定位对象
    private void fakeBaiduLocation(Object location) {
        try {
            Class<?> clazz = location.getClass();
            double lat = getLat();
            double lng = getLng();

            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                Class<?> fieldType = field.getType();

                if (fieldType == double.class || fieldType == Double.class
                        || fieldType == float.class || fieldType == Float.class) {
                    field.setAccessible(true);

                    if (fieldName.contains("lat") || fieldName.contains("latitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lat);
                        } else {
                            field.setFloat(location, (float) lat);
                        }
                    }
                    if (fieldName.contains("lng") || fieldName.contains("longitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lng);
                        } else {
                            field.setFloat(location, (float) lng);
                        }
                    }
                }
            }

            // 尝试调用setter方法
            try {
                Method setLatitude = clazz.getMethod("setLatitude", double.class);
                setLatitude.invoke(location, lat);
                Method setLongitude = clazz.getMethod("setLongitude", double.class);
                setLongitude.invoke(location, lng);
            } catch (Throwable ignored) {}

            Log.e(TAG, "[百度定位对象] 修改成功");
        } catch (Throwable t) {
            Log.e(TAG, "[百度定位对象] 修改失败", t);
        }
    }

    // ==========================================
    // 第5层：Hook高德地图定位
    // ==========================================
    private void hookAmapLocation() {
        String[] amapClasses = {
                "com.amap.api.location.AMapLocation",
                "com.amap.api.location.AMapLocationClient",
                "com.amap.api.location.b"
        };

        for (String className : amapClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                Log.e(TAG, "[高德定位] 找到类: " + className);

                Method[] methods = clazz.getDeclaredMethods();
                for (final Method method : methods) {
                    Class<?>[] params = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (params[i].getName().contains("AMapLocation")
                                || params[i].getName().contains("Location")) {
                            final int idx = i;
                            try {
                                XposedBridge.hookMethod(method, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        if (!locationEnabled) return;
                                        if (param.args[idx] != null) {
                                            fakeAmapLocation(param.args[idx]);
                                        }
                                    }
                                });
                                hookCount++;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                // 类不存在
            }
        }

        Log.e(TAG, "[高德定位] Hook完成");
    }

    // 修改高德定位对象
    private void fakeAmapLocation(Object location) {
        try {
            Class<?> clazz = location.getClass();
            double lat = getLat();
            double lng = getLng();

            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                Class<?> fieldType = field.getType();

                if (fieldType == double.class || fieldType == Double.class
                        || fieldType == float.class || fieldType == Float.class) {
                    field.setAccessible(true);

                    if (fieldName.contains("lat") || fieldName.contains("latitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lat);
                        } else {
                            field.setFloat(location, (float) lat);
                        }
                    }
                    if (fieldName.contains("lng") || fieldName.contains("longitude")) {
                        if (fieldType == double.class || fieldType == Double.class) {
                            field.setDouble(location, lng);
                        } else {
                            field.setFloat(location, (float) lng);
                        }
                    }
                }
            }

            Log.e(TAG, "[高德定位对象] 修改成功");
        } catch (Throwable t) {
            Log.e(TAG, "[高德定位对象] 修改失败", t);
        }
    }

    // ==========================================
    // 工具方法
    // ==========================================

    private double getLat() {
        try {
            if (xcxEnabled && xcxLat != 0) return xcxLat;
            return Double.parseDouble(latitude);
        } catch (Throwable e) {
            return 39.908823;
        }
    }

    private double getLng() {
        try {
            if (xcxEnabled && xcxLng != 0) return xcxLng;
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

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
