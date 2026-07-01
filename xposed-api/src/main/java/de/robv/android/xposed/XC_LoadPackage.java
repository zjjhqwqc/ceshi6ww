package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 兼容旧版导入路径 - 实际类在callbacks包中
 */
public class XC_LoadPackage extends de.robv.android.xposed.callbacks.XC_LoadPackage {
    protected XC_LoadPackage() {
        super();
    }
}
