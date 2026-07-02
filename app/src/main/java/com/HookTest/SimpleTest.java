package com.HookTest;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 最简测试版 - 只打日志，验证是否能注入到微信进程
 */
public class SimpleTest implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "SimpleTest";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XposedBridge.log("[SimpleTest] initZygote 被调用");
        Log.e(TAG, "initZygote 被调用: " + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            XposedBridge.log("[SimpleTest] ===== handleLoadPackage =====");
            XposedBridge.log("[SimpleTest] packageName: " + lpparam.packageName);
            XposedBridge.log("[SimpleTest] processName: " + lpparam.processName);
            XposedBridge.log("[SimpleTest] classLoader: " + lpparam.classLoader);
            XposedBridge.log("[SimpleTest] ============================");

            Log.e(TAG, "===== handleLoadPackage =====");
            Log.e(TAG, "packageName: " + lpparam.packageName);
            Log.e(TAG, "processName: " + lpparam.processName);
            Log.e(TAG, "============================");

            // 尝试hook Application.attach
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application",
                        lpparam.classLoader,
                        "attach",
                        android.content.Context.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("[SimpleTest] Application.attach 已触发: " + lpparam.packageName);
                                Log.e(TAG, "Application.attach 已触发: " + lpparam.packageName);
                            }
                        }
                );
                XposedBridge.log("[SimpleTest] Hook Application.attach 成功: " + lpparam.packageName);
                Log.e(TAG, "Hook Application.attach 成功: " + lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log("[SimpleTest] Hook Application.attach 失败: " + t.getMessage());
                Log.e(TAG, "Hook Application.attach 失败", t);
            }

        } catch (Throwable t) {
            XposedBridge.log("[SimpleTest] handleLoadPackage 异常: " + t.getMessage());
            Log.e(TAG, "handleLoadPackage 异常", t);
        }
    }
}
