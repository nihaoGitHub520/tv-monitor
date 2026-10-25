# tv-monitor ProGuard 规则
# 当前 release 未开启混淆（isMinifyEnabled=false），以下规则为后续开启混淆预留。

# ---- kotlinx.serialization：保留序列化注解与生成器 ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.tvmonitor.** {
    *** Companion;
}
-keepclasseswithmembers class com.tvmonitor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tvmonitor.**$$serializer { *; }
-keepclassmembers class com.tvmonitor.** {
    static <clinit>();
}

# ---- media3 / OkHttp 常见告警抑制 ----
-dontwarn androidx.media3.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- 保留源码行号，便于 logcat 崩溃定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
