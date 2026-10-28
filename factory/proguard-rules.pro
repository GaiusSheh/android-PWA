# apksig 内部按算法名查找签名实现，保守起见整体保留，避免 R8 误删导致手机上签名失败
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# jsoup 的可选依赖（re2j 正则引擎），Android 上不使用
-dontwarn com.google.re2j.**
