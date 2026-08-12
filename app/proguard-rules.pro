# NikonLink ProGuard Rules

# Keep PTP protocol classes (reflection-free but keep for safety)
-keep class com.nikonlink.app.core.ptp.** { *; }

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

# LiteRT / TFLite（PRD-AI修图 8.2 端侧推理）
# GPU Delegate 可选后端类缺失告警抑制（R8 missing_rules 生成）
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
# TfLiteRuntime 通过反射创建 GPUDelegate，必须保留类与无参构造
-keep class org.tensorflow.lite.gpu.GPUDelegate { <init>(...); }
# 推理 API 入口保留
-keep class org.tensorflow.lite.Interpreter { *; }
-keep interface org.tensorflow.lite.Delegate { *; }
