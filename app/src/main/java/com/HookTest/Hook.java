package com.HookTest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块
 * 功能：点击微信首页右上角+按钮，在弹出菜单中增加"定位设置"选项
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

    // 是否已显示功能加载完成提示
    private static boolean hasShownLoadedToast = false;

    // 自定义菜单项ID
    private static final int CUSTOM_MENU_ITEM_ID = 0x7F0F0001;
    private static final String CUSTOM_MENU_TITLE = "定位设置";

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

                        // 显示功能加载完成提示
                        showLoadedToast();
                    }
                });

        // 方案1：Hook MMListPopupWindow 注入菜单项（主要方案）
        hookMMListPopupWindow(lpparam);

        // 方案2：Hook PlusActionView 长按（备用方案）
        hookPlusActionView(lpparam);

        // 方案3：Hook LauncherUI查找+按钮（备用方案2）
        hookLauncherUI(lpparam);

        // Hook 腾讯地图定位
        hookTencentLocation(lpparam);

        // Hook 系统定位服务（备用）
        hookSystemLocation(lpparam);

        Log.e(TAG, "========== 所有Hook点注册完成 ==========");
    }

    // 显示功能加载完成提示
    private void showLoadedToast() {
        if (hasShownLoadedToast) return;
        hasShownLoadedToast = true;

        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, "功能加载完成", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "功能加载完成 - Toast已显示");
                } catch (Throwable t) {
                    Log.e(TAG, "显示加载完成Toast失败", t);
                }
            }
        }, 3000);
    }

    // ==========================================
    // 方案1：Hook MMListPopupWindow 注入菜单项
    // ==========================================
    private void hookMMListPopupWindow(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> popupWindowClass = XposedHelpers.findClass(
                    "com.tencent.mm.ui.base.MMListPopupWindow", lpparam.classLoader);
            Log.e(TAG, "找到MMListPopupWindow类");

            // Hook show方法
            XposedBridge.hookAllMethods(popupWindowClass, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object popupWindow = param.thisObject;
                        Log.e(TAG, "MMListPopupWindow.show() 被调用");

                        // 尝试获取ListView
                        ListView listView = getListViewFromPopup(popupWindow, popupWindowClass);
                        if (listView == null) {
                            Log.e(TAG, "无法获取ListView");
                            return;
                        }

                        // 获取adapter
                        ListAdapter adapter = listView.getAdapter();
                        if (adapter == null) {
                            Log.e(TAG, "Adapter为null");
                            return;
                        }

                        int count = adapter.getCount();
                        Log.e(TAG, "当前菜单项数量: " + count);

                        // 检查是否是加号菜单（通常4个选项：发起群聊、添加朋友、扫一扫、收付款）
                        if (count >= 3 && count <= 6) {
                            // 检查是否已经注入过
                            boolean alreadyInjected = false;
                            for (int i = 0; i < count; i++) {
                                Object item = adapter.getItem(i);
                                if (item != null && isCustomMenuItem(item)) {
                                    alreadyInjected = true;
                                    break;
                                }
                            }

                            if (!alreadyInjected) {
                                Log.e(TAG, "检测到加号菜单，准备注入定位设置项");
                                injectMenuItemToListView(listView, adapter);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Hook MMListPopupWindow.show异常", t);
                    }
                }
            });

            Log.e(TAG, "MMListPopupWindow Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "MMListPopupWindow Hook失败: " + t.getMessage());
            // 尝试Hook标准的ListPopupWindow
            hookStandardListPopupWindow(lpparam);
        }
    }

    // Hook标准ListPopupWindow（备用）
    private void hookStandardListPopupWindow(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> popupWindowClass = XposedHelpers.findClass(
                    "androidx.appcompat.widget.ListPopupWindow", lpparam.classLoader);
            Log.e(TAG, "找到ListPopupWindow类");

            XposedBridge.hookAllMethods(popupWindowClass, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object popupWindow = param.thisObject;
                        ListView listView = (ListView) XposedHelpers.callMethod(popupWindow, "getListView");
                        if (listView == null) return;

                        ListAdapter adapter = listView.getAdapter();
                        if (adapter == null) return;

                        int count = adapter.getCount();
                        if (count >= 3 && count <= 6) {
                            boolean alreadyInjected = false;
                            for (int i = 0; i < count; i++) {
                                Object item = adapter.getItem(i);
                                if (item != null && isCustomMenuItem(item)) {
                                    alreadyInjected = true;
                                    break;
                                }
                            }
                            if (!alreadyInjected) {
                                Log.e(TAG, "ListPopupWindow: 注入定位设置项");
                                injectMenuItemToListView(listView, adapter);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "ListPopupWindow.show hook异常", t);
                    }
                }
            });

            Log.e(TAG, "ListPopupWindow Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "ListPopupWindow Hook失败: " + t.getMessage());
        }
    }

    // 从PopupWindow获取ListView
    private ListView getListViewFromPopup(Object popupWindow, Class<?> popupClass) {
        try {
            // 尝试直接调用getListView方法
            Method getListViewMethod = null;
            for (Method m : popupClass.getDeclaredMethods()) {
                if (m.getReturnType() == ListView.class && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    getListViewMethod = m;
                    break;
                }
            }
            if (getListViewMethod != null) {
                return (ListView) getListViewMethod.invoke(popupWindow);
            }

            // 尝试通过字段获取
            for (Field f : popupClass.getDeclaredFields()) {
                if (ListView.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (ListView) f.get(popupWindow);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "获取ListView失败", t);
        }
        return null;
    }

    // 检查是否是我们的自定义菜单项
    private boolean isCustomMenuItem(Object item) {
        try {
            // 尝试通过toString判断
            String str = item.toString();
            if (str.contains(CUSTOM_MENU_TITLE)) {
                return true;
            }
            // 尝试获取title字段
            for (Field f : item.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object val = f.get(item);
                    if (val != null && val.toString().equals(CUSTOM_MENU_TITLE)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // 向ListView注入菜单项（使用FooterView方案，不影响原有菜单项）
    private void injectMenuItemToListView(ListView listView, final ListAdapter originalAdapter) {
        try {
            final Context context = listView.getContext();

            // 检查是否已经添加过FooterView
            int footerViewCount = listView.getFooterViewsCount();
            if (footerViewCount > 0) {
                Log.e(TAG, "已存在FooterView，跳过注入");
                return;
            }

            // 创建自定义菜单项View
            View customItemView = createCustomMenuItemView(context);

            // 设置点击事件
            customItemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.e(TAG, "点击了定位设置菜单项");
                    // 关闭弹窗
                    try {
                        // 向上查找PopupWindow并关闭
                        View parent = (View) v.getParent();
                        while (parent != null) {
                            Object lp = parent.getTag();
                            if (lp instanceof PopupWindow) {
                                ((PopupWindow) lp).dismiss();
                                break;
                            }
                            Object p = parent.getParent();
                            if (p instanceof View) {
                                parent = (View) p;
                            } else {
                                // 尝试通过context关闭
                                if (context instanceof Activity) {
                                    // 不关闭activity，只关闭弹窗
                                }
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "关闭弹窗失败", t);
                    }
                    // 显示设置面板
                    showSettingPanel(context);
                }
            });

            // 添加分隔线
            View divider = new View(context);
            divider.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFFE0E0E0);

            // 添加FooterView（分隔线 + 自定义项）
            listView.addFooterView(divider);
            listView.addFooterView(customItemView);

            Log.e(TAG, "菜单项注入成功（FooterView方案）");
        } catch (Throwable t) {
            Log.e(TAG, "注入菜单项失败，尝试备用方案", t);
            // 备用方案：直接在PopupWindow底部添加按钮
            injectMenuItemByAddingView(listView, originalAdapter);
        }
    }

    // 备用方案：直接在PopupWindow中添加View
    private void injectMenuItemByAddingView(ListView listView, ListAdapter originalAdapter) {
        try {
            final Context context = listView.getContext();
            View parent = (View) listView.getParent();

            if (parent instanceof ViewGroup) {
                ViewGroup parentGroup = (ViewGroup) parent;

                // 创建自定义按钮
                LinearLayout btnLayout = new LinearLayout(context);
                btnLayout.setOrientation(LinearLayout.HORIZONTAL);
                btnLayout.setGravity(Gravity.CENTER_VERTICAL);
                btnLayout.setPadding(dp2px(context, 16), dp2px(context, 12),
                        dp2px(context, 16), dp2px(context, 12));
                btnLayout.setBackgroundColor(Color.WHITE);
                btnLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Log.e(TAG, "点击了定位设置（备用方案）");
                        showSettingPanel(context);
                    }
                });

                TextView iconView = new TextView(context);
                iconView.setText("📍");
                iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                iconParams.rightMargin = dp2px(context, 12);
                iconView.setLayoutParams(iconParams);
                btnLayout.addView(iconView);

                TextView textView = new TextView(context);
                textView.setText(CUSTOM_MENU_TITLE);
                textView.setTextColor(0xFF333333);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                btnLayout.addView(textView);

                // 添加到父布局底部
                ViewGroup.LayoutParams btnParams = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                parentGroup.addView(btnLayout, btnParams);

                Log.e(TAG, "备用方案注入成功");
            }
        } catch (Throwable t) {
            Log.e(TAG, "备用方案也失败", t);
        }
    }

    // 创建自定义菜单项View
    private View createCustomMenuItemView(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp2px(context, 16), dp2px(context, 12), dp2px(context, 16), dp2px(context, 12));
        layout.setBackgroundColor(Color.WHITE);

        // 图标（用文字代替）
        TextView iconView = new TextView(context);
        iconView.setText("📍");
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconParams.rightMargin = dp2px(context, 12);
        iconView.setLayoutParams(iconParams);
        layout.addView(iconView);

        // 文字
        TextView textView = new TextView(context);
        textView.setText(CUSTOM_MENU_TITLE);
        textView.setTextColor(0xFF333333);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        layout.addView(textView);

        return layout;
    }

    // 关闭PopupWindow
    private void dismissPopupWindow(AdapterView<?> parent) {
        try {
            // 向上查找PopupWindow
            View v = (View) parent.getParent();
            while (v != null) {
                if (v.getTag() != null && v.getTag() instanceof PopupWindow) {
                    ((PopupWindow) v.getTag()).dismiss();
                    return;
                }
                Object parentObj = v.getParent();
                if (parentObj instanceof View) {
                    v = (View) parentObj;
                } else {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "关闭PopupWindow失败", t);
        }
    }

    // ==========================================
    // 方案2：Hook 微信右上角+按钮（长按备用）
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
                Log.e(TAG, "未找到PlusActionView类");
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

    // ==========================================
    // 方案3：Hook LauncherUI（备用方案2）
    // ==========================================
    private void hookLauncherUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                    lpparam.classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            final Activity activity = (Activity) param.thisObject;
                            currentActivity = activity;

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

        // 如果已有dialog且正在显示，先关闭
        if (settingDialog != null && settingDialog.isShowing()) {
            settingDialog.dismiss();
            settingDialog = null;
        }

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

        // 设置窗口大小
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        dialog.getWindow().setAttributes(lp);

        settingDialog = dialog;
        dialog.show();

        Log.e(TAG, "设置面板已显示");
    }

    // 更新设置面板可见性
    private void updateSettingPanelVisibility() {
        // 这里可以控制某些控件的显示隐藏
        updateGpsText();
    }

    // 更新GPS文字显示
    private void updateGpsText() {
        if (gpsTextView != null) {
            gpsTextView.setText("纬度：" + latitude + "\n经度：" + longitude + "\n地点：" + gpsPlace);
        }
    }

    // 创建开关行
    private LinearLayout createSwitchRow(Context context, String title, boolean checked,
                                          CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(0xFF333333);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tvParams);
        layout.addView(tv);

        android.widget.Switch sw = new android.widget.Switch(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        layout.addView(sw);

        return layout;
    }

    // 创建标签
    private TextView createLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFF666666);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp2px(context, 8), 0, dp2px(context, 4));
        return tv;
    }

    // 创建输入框
    private EditText createEditText(Context context, String text, String hint) {
        EditText et = new EditText(context);
        et.setText(text);
        et.setHint(hint);
        et.setTextColor(0xFF333333);
        et.setHintTextColor(0xFFAAAAAA);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setPadding(dp2px(context, 10), dp2px(context, 8), dp2px(context, 10), dp2px(context, 8));
        et.setBackgroundColor(0xFFF0F0F0);
        et.setSingleLine(true);
        return et;
    }

    // ==========================================
    // 地图选点
    // ==========================================
    private void showMapPicker(final Context context, final android.app.Dialog parentDialog) {
        final android.app.Dialog mapDialog = new android.app.Dialog(context);
        mapDialog.setTitle("地图选点");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        // 搜索栏
        final EditText searchEdit = new EditText(context);
        searchEdit.setHint("搜索地点");
        searchEdit.setPadding(dp2px(context, 12), dp2px(context, 10), dp2px(context, 12), dp2px(context, 10));
        searchEdit.setBackgroundColor(0xFFF5F5F5);
        layout.addView(searchEdit);

        // WebView显示腾讯地图
        final WebView webView = new WebView(context);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);

        // 加载腾讯地图选点页面
        String mapUrl = "https://apis.map.qq.com/tools/locpicker?search=1&type=0&backurl=http://callback&key=OB4BZ-D4W3U-B7VVO-4PJWW-6TKDJ-WPB77&referer=myapp";
        webView.loadUrl(mapUrl);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.e(TAG, "URL: " + url);

                // 检测选点回调
                if (url.startsWith("http://callback")) {
                    try {
                        Uri uri = Uri.parse(url);
                        String latStr = uri.getQueryParameter("latng");
                        String addr = uri.getQueryParameter("addr");
                        String poi = uri.getQueryParameter("poi");

                        if (latStr != null && latStr.contains(",")) {
                            String[] parts = latStr.split(",");
                            if (parts.length >= 2) {
                                latitude = parts[0];
                                longitude = parts[1];
                                if (poi != null && !poi.isEmpty()) {
                                    gpsPlace = poi;
                                } else if (addr != null) {
                                    gpsPlace = addr;
                                }
                                savePrefs();
                                updateXcxCoordinates();
                                updateGpsText();
                                Toast.makeText(context, "位置已选择: " + gpsPlace, Toast.LENGTH_SHORT).show();
                                mapDialog.dismiss();
                                return true;
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "解析选点结果失败", t);
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

        // 取消按钮
        Button cancelBtn = new Button(context);
        cancelBtn.setText("取消");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mapDialog.dismiss();
            }
        });
        layout.addView(cancelBtn);

        mapDialog.setContentView(layout);

        WindowManager.LayoutParams lp = mapDialog.getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        mapDialog.getWindow().setAttributes(lp);

        mapDialog.show();
    }

    // ==========================================
    // 获取当前位置
    // ==========================================
    private void getCurrentLocation(Context context) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                android.location.Location loc = null;
                try {
                    loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                } catch (SecurityException e) {
                    Log.e(TAG, "无定位权限");
                }
                if (loc == null) {
                    try {
                        loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                    } catch (SecurityException e) {
                        // ignore
                    }
                }
                if (loc != null) {
                    latitude = String.valueOf(loc.getLatitude());
                    longitude = String.valueOf(loc.getLongitude());
                    gpsPlace = "当前位置";
                    Log.e(TAG, "获取当前位置成功: " + latitude + ", " + longitude);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "获取当前位置失败", t);
        }
    }

    // ==========================================
    // Hook 腾讯地图定位
    // ==========================================
    private void hookTencentLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 尝试Hook腾讯地图定位类
            String[] tencentLocationClasses = {
                    "com.tencent.map.geolocation.TencentLocationManager",
                    "com.tencent.location.TencentLocationManager"
            };

            Class<?> locationManagerClass = null;
            for (String className : tencentLocationClasses) {
                try {
                    locationManagerClass = XposedHelpers.findClass(className, lpparam.classLoader);
                    Log.e(TAG, "找到腾讯定位类: " + className);
                    break;
                } catch (Throwable t) {
                    // 继续尝试下一个
                }
            }

            if (locationManagerClass == null) {
                Log.e(TAG, "未找到腾讯定位类");
                return;
            }

            // Hook requestSingleFreshLocation (单次定位)
            try {
                Method[] methods = locationManagerClass.getDeclaredMethods();
                for (final Method method : methods) {
                    String name = method.getName();
                    if (name.contains("requestSingle") || name.contains("requestLocation")
                            || name.contains("getLocation") || name.contains("request")) {
                        if (method.getParameterTypes().length >= 2) {
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (!locationEnabled) return;

                                    Log.e(TAG, "腾讯定位方法被调用: " + method.getName());

                                    // 查找回调参数
                                    for (int i = 0; i < param.args.length; i++) {
                                        Object arg = param.args[i];
                                        if (arg != null && isLocationCallback(arg)) {
                                            Log.e(TAG, "找到定位回调，准备Hook回调类");
                                            hookLocationCallback(arg);
                                            break;
                                        }
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Hook腾讯定位方法失败", t);
            }

            Log.e(TAG, "腾讯地图定位Hook完成");
        } catch (Throwable t) {
            Log.e(TAG, "Hook腾讯地图定位失败", t);
        }
    }

    // 判断是否是定位回调
    private boolean isLocationCallback(Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        String name = clazz.getName();
        return name.contains("TencentLocationListener")
                || name.contains("LocationListener")
                || name.contains("Callback")
                || name.contains("listener");
    }

    // Hook定位回调类
    private void hookLocationCallback(final Object callback) {
        try {
            String className = callback.getClass().getName();
            if (hookedClasses.contains(className)) {
                return;
            }
            hookedClasses.add(className);

            XposedBridge.hookAllMethods(callback.getClass(),
                    "onLocationChanged", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;

                            Log.e(TAG, "定位回调onLocationChanged被调用");

                            // 修改位置参数
                            Object location = param.args[0];
                            if (location != null) {
                                fakeTencentLocation(location);
                            }
                        }
                    });

            Log.e(TAG, "定位回调Hook成功: " + className);
        } catch (Throwable t) {
            Log.e(TAG, "Hook定位回调失败", t);
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

            // 尝试设置纬度
            try {
                Field latField = findFieldByType(clazz, double.class, "lat");
                if (latField != null) {
                    latField.setAccessible(true);
                    latField.setDouble(location, lat);
                    Log.e(TAG, "设置纬度成功: " + lat);
                }
            } catch (Throwable t) {
                // 尝试其他方式
            }

            // 尝试设置经度
            try {
                Field lngField = findFieldByType(clazz, double.class, "lng");
                if (lngField != null) {
                    lngField.setAccessible(true);
                    lngField.setDouble(location, lng);
                    Log.e(TAG, "设置经度成功: " + lng);
                }
            } catch (Throwable t) {
                // 尝试其他方式
            }

            // 尝试通过setter方法
            try {
                Method[] methods = clazz.getDeclaredMethods();
                for (Method method : methods) {
                    String name = method.getName();
                    if ((name.equals("setLatitude") || name.equals("setLat"))
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == double.class) {
                        method.setAccessible(true);
                        method.invoke(location, lat);
                        Log.e(TAG, "通过setter设置纬度成功");
                    }
                    if ((name.equals("setLongitude") || name.equals("setLng"))
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == double.class) {
                        method.setAccessible(true);
                        method.invoke(location, lng);
                        Log.e(TAG, "通过setter设置经度成功");
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "通过setter设置位置失败", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "修改定位对象失败", t);
        }
    }

    // 按类型和名称关键字查找字段
    private Field findFieldByType(Class<?> clazz, Class<?> type, String keyword) {
        try {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == type) {
                    String name = field.getName().toLowerCase();
                    if (name.contains(keyword)) {
                        return field;
                    }
                }
            }
            // 尝试父类
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && !superClass.equals(Object.class)) {
                return findFieldByType(superClass, type, keyword);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==========================================
    // Hook 系统定位（备用方案）
    // ==========================================
    private void hookSystemLocation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook LocationManager.getLastKnownLocation
            XposedHelpers.findAndHookMethod("android.location.LocationManager",
                    lpparam.classLoader, "getLastKnownLocation", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!locationEnabled) return;

                            android.location.Location original = (android.location.Location) param.getResult();
                            if (original == null) {
                                // 创建一个新的Location
                                original = new android.location.Location("fake");
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
                            Log.e(TAG, "系统定位getLastKnownLocation已Hook");
                        }
                    });

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
    // 配置存取
    // ==========================================
    @SuppressLint("ApplySharedPref")
    private void savePrefs() {
        try {
            if (appContext == null) return;
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean(KEY_LOCATION_ENABLED, locationEnabled);
            editor.putBoolean(KEY_XCX_ENABLED, xcxEnabled);
            editor.putString(KEY_LATITUDE, latitude);
            editor.putString(KEY_LONGITUDE, longitude);
            editor.putString(KEY_GPS_PLACE, gpsPlace);
            editor.commit();
            Log.e(TAG, "配置已保存");
        } catch (Throwable t) {
            Log.e(TAG, "保存配置失败", t);
        }
    }

    private void loadPrefs() {
        try {
            if (appContext == null) return;
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            locationEnabled = sp.getBoolean(KEY_LOCATION_ENABLED, false);
            xcxEnabled = sp.getBoolean(KEY_XCX_ENABLED, false);
            latitude = sp.getString(KEY_LATITUDE, latitude);
            longitude = sp.getString(KEY_LONGITUDE, longitude);
            gpsPlace = sp.getString(KEY_GPS_PLACE, gpsPlace);
            Log.e(TAG, "配置已加载, 定位开关: " + locationEnabled);
        } catch (Throwable t) {
            Log.e(TAG, "加载配置失败", t);
        }
    }

    // ==========================================
    // 工具方法
    // ==========================================
    private int dp2px(Context context, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    // 简易TextWatcher
    private static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onTextChanged(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {}

        abstract void onTextChanged(String text);
    }
}
