package de.robv.android.xposed.callbacks;

/**
 * Xposed回调基类
 */
public abstract class XCallback {
    /**
     * 参数基类
     */
    public static abstract class Param {
    }

    /**
     * 调用回调
     */
    protected abstract void call(Param param) throws Throwable;
}
