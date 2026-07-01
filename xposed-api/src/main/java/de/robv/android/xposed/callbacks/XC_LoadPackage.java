package de.robv.android.xposed.callbacks;

/**
 * 加载包回调
 */
public class XC_LoadPackage extends XCallback {
    /**
     * 加载包参数
     */
    public static final class LoadPackageParam extends Param {
        /** 包名 */
        public String packageName;
        /** 进程名 */
        public String processName;
        /** 类加载器 */
        public ClassLoader classLoader;
        /** 是否是第一个应用 */
        public boolean isFirstApplication;
    }

    protected void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // 由子类实现
    }

    @Override
    protected void call(Param param) throws Throwable {
        handleLoadPackage((LoadPackageParam) param);
    }
}
