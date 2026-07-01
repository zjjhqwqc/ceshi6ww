package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook入口接口 - 模块必须实现此接口
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
