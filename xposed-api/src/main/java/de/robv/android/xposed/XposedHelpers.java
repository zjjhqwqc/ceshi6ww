package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Xposed辅助工具类 - 编译用stub
 */
public class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static void findAndHookMethod(Class<?> clazz, String methodName,
                                         Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static void findAndHookConstructor(String className, ClassLoader classLoader,
                                               Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static void findAndHookConstructor(Class<?> clazz,
                                               Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException();
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException();
    }

    public static long getLongField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        throw new UnsupportedOperationException();
    }

    public static float getFloatField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        throw new UnsupportedOperationException();
    }

    public static double getDoubleField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setDoubleField(Object obj, String fieldName, double value) {
        throw new UnsupportedOperationException();
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        throw new UnsupportedOperationException();
    }

    public static byte getByteField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setByteField(Object obj, String fieldName, byte value) {
        throw new UnsupportedOperationException();
    }

    public static short getShortField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setShortField(Object obj, String fieldName, short value) {
        throw new UnsupportedOperationException();
    }

    public static char getCharField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static void setCharField(Object obj, String fieldName, char value) {
        throw new UnsupportedOperationException();
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException();
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static Method findMethodExact(String className, ClassLoader classLoader,
                                         String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static Method findMethodExact(Class<?> clazz, String methodName,
                                         Class<?>... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName,
                                             Class<?>... parameterTypes) {
        throw new UnsupportedOperationException();
    }

    public static Class<?>[] getClassesAsArray(Class<?> clazz, String[] classNames) {
        throw new UnsupportedOperationException();
    }
}
