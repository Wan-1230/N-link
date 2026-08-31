# N-Link ProGuard Rules

# Keep PTP protocol classes (reflection-free but keep for safety)
-keep class com.nikonlink.app.core.ptp.** { *; }

# 检查更新：Gson 按注解反射填充 Release 数据模型（ReleaseInfo / ReleaseAsset）。
# 字段名被混淆虽不影响 @SerializedName 映射，但注解本身可能被 R8 剥离导致映射失败，
# 该包代码量小、混淆收益近零，整体 keep（宁稳勿险）。
-keep class com.nikonlink.app.shared.update.** { *; }
-keepattributes *Annotation*, Signature

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Timber
-dontwarn org.jetbrains.annotations.**
