package com.HookTest;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信虚拟定位Xposed模块 - 测试版本
 * 最简版本，用于验证模块是否能被LSPosed正确加载
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "WxLocationHook";
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    private static Context appContext = null;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.e(TAG, "========== handleLoadPackage 被调用 ==========");
        Log.e(TAG, "包名: " + lpparam.packageName);
        Log.e(TAG, "进程名: " + lpparam.processName);

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            Log.e(TAG, "不是目标包，跳过");
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

                            // 显示Toast提示模块已加载
                            showLoadedToast();
                        }
                    });
            Log.e(TAG, "Application.attach Hook成功");
        } catch (Throwable t) {
            Log.e(TAG, "Hook Application.attach失败", t);
        }

        Log.e(TAG, "========== 模块加载完成 ==========");
    }

    private void showLoadedToast() {
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, "功能加载完成 - 测试版本", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Toast已显示: 功能加载完成");
                } catch (Throwable t) {
                    Log.e(TAG, "显示Toast失败", t);
                }
            }
        }, 2000);
    }
}
