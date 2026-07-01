package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Set;

/**
 * Xposed核心类 - 编译用stub
 */
public class XposedBridge {
    /** 日志 */
    public static void log(String text) {
        throw new UnsupportedOperationException();
    }
    public static void log(String text, Throwable t) {
        throw new UnsupportedOperationException();
    }

    /** Hook方法 */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }

    /** Hook所有同名方法 */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }

    /** Hook所有构造函数 */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }

    /** 取消Hook */
    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }

    /** 日志工具类 */
    public static final class Log {
        private Log() {}
        public static int e(String msg) { return 0; }
        public static int e(String msg, Throwable tr) { return 0; }
        public static int w(String msg) { return 0; }
        public static int w(String msg, Throwable tr) { return 0; }
        public static int i(String msg) { return 0; }
        public static int i(String msg, Throwable tr) { return 0; }
        public static int d(String msg) { return 0; }
        public static int d(String msg, Throwable tr) { return 0; }
    }
}
