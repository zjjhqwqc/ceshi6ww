# Xposed模块入口类不被混淆
-keep class com.HookTest.Hook { *; }
-keep class com.HookTest.Hook$* { *; }

# 保留所有实现了IXposedHookLoadPackage接口的类
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# 保留Xposed API相关类
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# 保留ContentProvider
-keep class com.HookTest.ConfigProvider { *; }

# 保留坐标转换类
-keep class com.HookTest.CoordinateTransform { *; }
