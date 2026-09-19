# N-Link ProGuard Rules

# Keep PTP protocol classes (reflection-free but keep for safety)
-keep class com.nikonlink.app.core.ptp.** { *; }

# 检查更新：Gson 按注解反射填充 Release 数据模型（ReleaseInfo / ReleaseAsset）。
# 字段名被混淆虽不影响 @SerializedName 映射，但注解本身可能被 R8 剥离导致映射失败，
# 该包代码量小、混淆收益近零，整体 keep（宁稳勿险）。
-keep class com.nikonlink.app.shared.update.** { *; }
-keepattributes *Annotation*, Signature

# 逐 ROM 兼容矩阵（assets/compat/rom_rules.json）的 Gson 数据模型。
# 这些类没有 @SerializedName，Gson 是**按字段名**反射填充的，字段一被改名就读成 null，
# 于是 release 包里整张表静默变空、退回内置文案（debug 不混淆，所以只有线上会犯）。
# 只 keep 这四个数据类：device.connect 里其余都是连接主干，混淆收益是实的，不能整包 keep。
-keep class com.nikonlink.app.device.connect.RomRulesFile { *; }
-keep class com.nikonlink.app.device.connect.RomRule { *; }
-keep class com.nikonlink.app.device.connect.RomRule$Match { *; }
-keep class com.nikonlink.app.device.connect.RomRule$Trick { *; }

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
