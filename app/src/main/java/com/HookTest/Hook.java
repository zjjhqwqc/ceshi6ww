package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 * 通过Hook微信的定位相关类，实现虚拟定位功能
 * 设置界面在模块APP的主活动中
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
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 已Hook的回调类（防重复）
    private static final Set<String> hookedCallbacks = new HashSet<>();

    // 小程序坐标（GCJ02）
    private static double xcxLat = 0;
    private static double xcxLng = 0;

    // 是否已显示加载完成提示
    private static boolean hasShownLoadedToast = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.e(TAG, "========== handleLoadPackage 被调用 ==========");
        Log.e(TAG, "包名: " + lpparam.packageName);

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.e(TAG, "========== 微信定位模块开始加载 ==========");

        // Hook Application.attach 获取Context
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Log.e(TAG, "Application.attach 被调用");
                            appContext = (Context) param.args[0];
                            Log.e(TAG, "获取到Context: " + appContext.getPackageName());

                            // 加载配置
                            loadPrefs();
                            updateXcxCoordinates();

                            // 显示加载完成提示
                            showLoadedToast();
                        }
                    });
            Log.e(TAG, "Application.attach Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Application.attach失败", t);
        }

        // Hook 腾讯地图定位
        try {
            hookTencentLocation(lpparam);
        } catch (Throwable t) {
            Log.e(TAG, "Hook腾讯定位失败", t);
        }

        // Hook 系统定位（备用方案）
        try {
            hookSystemLocation();
        } catch (Throwable t) {
            Log.e(TAG, "Hook系统定位失败", t);
        }

        Log.e(TAG, "========== 模块加载完成 ==========");
    }

    // 显示加载完成提示
    private void showLoadedToast() {
        if (hasShownLoadedToast) return;
        hasShownLoadedToast = true;

        uiHandler.postDelayed(new Runnable() {
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
    // Hook 腾讯地图定位
    // ==========================================
    private void hookTencentLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试不同的腾讯定位类名
        String[] managerClasses = {
                "com.tencent.map.geolocation.TencentLocationManager",
                "com.tencent.location.TencentLocationManager",
                "com.tencent.map.geolocation.a",
                "com.tencent.location.a"
        };

        Class<?> managerClass = null;
        for (String className : managerClasses) {
            try {
                managerClass = XposedHelpers.findClass(className, lpparam.classLoader);
                Log.e(TAG, "找到腾讯定位类: " + className);
                break;
            } catch (Throwable t) {
                // 继续尝试
            }
        }

        if (managerClass == null) {
            Log.e(TAG, "未找到腾讯定位类，跳过");
            return;
        }

        // Hook所有方法，查找定位请求
        Method[] methods = managerClass.getDeclaredMethods();
        for (final Method method : methods) {
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();

            // 匹配定位请求方法（通常包含listener/回调参数）
            boolean isLocationMethod = false;
            for (Class<?> param : params) {
                String paramName = param.getName();
                if (paramName.contains("TencentLocationListener")
                        || paramName.contains("LocationListener")
                        || paramName.contains("Callback")
                        || paramName.contains("listener")) {
                    isLocationMethod = true;
                    break;
                }
            }

            if (isLocationMethod && params.length >= 1) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;

                            Log.e(TAG, "腾讯定位方法被调用: " + method.getName());

                            // 查找回调参数并Hook
                            for (Object arg : param.args) {
                                if (arg != null && isLocationCallback(arg)) {
                                    hookLocationCallback(arg);
                                }
                            }
                        }
                    });
                    Log.e(TAG, "Hook方法成功: " + name);
                } catch (Throwable t) {
                    Log.e(TAG, "Hook方法失败: " + name, t);
                }
            }
        }

        Log.e(TAG, "腾讯定位Hook完成");
    }

    // 判断是否是定位回调
    private boolean isLocationCallback(Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();

        // 检查类名
        String name = clazz.getName();
        if (name.contains("TencentLocationListener")
                || name.contains("LocationListener")
                || name.contains("LocationCallback")
                || name.contains("location")
                || name.contains("listener")) {
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

    // Hook定位回调
    private void hookLocationCallback(final Object callback) {
        try {
            String className = callback.getClass().getName();
            if (hookedCallbacks.contains(className)) {
                return;
            }
            hookedCallbacks.add(className);

            // 查找onLocationChanged方法
            Method[] methods = callback.getClass().getDeclaredMethods();
            for (final Method method : methods) {
                String name = method.getName();
                if (name.contains("onLocationChanged")
                        || name.contains("onLocation")
                        || name.contains("locationChanged")) {
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!locationEnabled) return;

                                Log.e(TAG, "定位回调被调用: " + method.getName());

                                // 修改第一个参数（位置对象）
                                if (param.args.length > 0 && param.args[0] != null) {
                                    fakeTencentLocation(param.args[0]);
                                }
                            }
                        });
                        Log.e(TAG, "Hook回调方法成功: " + className + "." + name);
                    } catch (Throwable t) {
                        Log.e(TAG, "Hook回调方法失败: " + name, t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook回调失败", t);
        }
    }

    // 修改腾讯定位对象
    private void fakeTencentLocation(Object location) {
        try {
            Class<?> clazz = location.getClass();
            double lat = Double.parseDouble(latitude);
            double lng = Double.parseDouble(longitude);

            // 小程序使用GCJ02坐标
            if (xcxEnabled && xcxLat != 0) {
                lat = xcxLat;
                lng = xcxLng;
            }

            Log.e(TAG, "准备修改定位: lat=" + lat + ", lng=" + lng);

            // 尝试通过字段设置
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                if (field.getType() == double.class || field.getType() == Double.class) {
                    field.setAccessible(true);
                    if (fieldName.contains("lat") || fieldName.contains("latitude")) {
                        field.setDouble(location, lat);
                        Log.e(TAG, "设置纬度成功: " + field.getName() + " = " + lat);
                    }
                    if (fieldName.contains("lng") || fieldName.contains("longitude")) {
                        field.setDouble(location, lng);
                        Log.e(TAG, "设置经度成功: " + field.getName() + " = " + lng);
                    }
                }
            }

            // 尝试通过setter方法设置
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods) {
                String name = method.getName();
                if (method.getParameterTypes().length == 1
                        && (method.getParameterTypes()[0] == double.class
                        || method.getParameterTypes()[0] == Double.class)) {
                    method.setAccessible(true);
                    if (name.equalsIgnoreCase("setLatitude")
                            || name.equalsIgnoreCase("setLat")
                            || name.equalsIgnoreCase("latitude")) {
                        method.invoke(location, lat);
                        Log.e(TAG, "通过setter设置纬度: " + name);
                    }
                    if (name.equalsIgnoreCase("setLongitude")
                            || name.equalsIgnoreCase("setLng")
                            || name.equalsIgnoreCase("longitude")) {
                        method.invoke(location, lng);
                        Log.e(TAG, "通过setter设置经度: " + name);
                    }
                }
            }

            Log.e(TAG, "定位修改完成");
        } catch (Throwable t) {
            Log.e(TAG, "修改定位对象失败", t);
        }
    }

    // ==========================================
    // Hook 系统定位（备用方案）
    // ==========================================
    private void hookSystemLocation() {
        try {
            // Hook LocationManager.getLastKnownLocation
            XposedHelpers.findAndHookMethod("android.location.LocationManager",
                    null, "getLastKnownLocation", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;

                            Location original = (Location) param.getResult();
                            if (original == null) {
                                original = new Location("fake");
                            }

                            double lat = Double.parseDouble(latitude);
                            double lng = Double.parseDouble(longitude);

                            if (xcxEnabled && xcxLat != 0) {
                                lat = xcxLat;
                                lng = xcxLng;
                            }

                            original.setLatitude(lat);
                            original.setLongitude(lng);
                            param.setResult(original);
                            Log.e(TAG, "系统定位getLastKnownLocation已Hook: " + lat + ", " + lng);
                        }
                    });

            // Hook LocationListener.onLocationChanged
            // （需要动态发现回调类）

            Log.e(TAG, "系统定位Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook系统定位失败", t);
        }
    }

    // ==========================================
    // 坐标转换
    // ==========================================
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
    // 通过ContentProvider读取配置
    // ==========================================
    private void loadPrefs() {
        try {
            if (appContext == null) return;

            // 通过ContentProvider读取模块APP的配置
            ConfigProvider.ConfigData data = ConfigProvider.readConfig(appContext);
            locationEnabled = data.locationEnabled;
            xcxEnabled = data.xcxEnabled;
            if (!isEmpty(data.latitude)) latitude = data.latitude;
            if (!isEmpty(data.longitude)) longitude = data.longitude;
            if (!isEmpty(data.gpsPlace)) gpsPlace = data.gpsPlace;

            Log.e(TAG, "配置已加载, 定位开关: " + locationEnabled
                    + ", lat: " + latitude + ", lng: " + longitude);
        } catch (Throwable t) {
            Log.e(TAG, "加载配置失败", t);
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
