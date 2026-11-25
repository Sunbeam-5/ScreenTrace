# =============================================================================
# ScreenTrace R8 / ProGuard 规则（TD-01）
# 原则：禁止整包 -keep com.screentrace.app.**，每条规则均按反射/IPC/序列化边界精确声明原因。
# =============================================================================

# 1. Shizuku AIDL IPC（TD-01 / TD-04）
# ShizukuShell 直接通过 IShizukuService.Stub.asInterface(binder) 调用底层 AIDL，
# 混淆或裁剪 Stub / Proxy / ShizukuProvider 会导致主通道静默失效。
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }

# 2. kotlinx.serialization（BackupModels / DeviceMeta / SourceRebuild）
# 备份 JSON 编解码与重建账本依赖 @Serializable 生成的 Companion.serializer() 与 $$serializer。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.screentrace.app.**$$serializer { *; }
-keepclassmembers class com.screentrace.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.screentrace.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.screentrace.app.** { *; }

# 3. Room 数据库（ScreenTraceDatabase_Impl 反射实例化）
# Room 通过类名拼接 _Impl 反射构造实现类。
-keep class * extends androidx.room.RoomDatabase
-keep class com.screentrace.app.data.db.ScreenTraceDatabase_Impl { *; }

# 4. WorkManager 后台任务（DailyPullWorker / AutoSyncWorker / PeriodReminderWorker）
# WorkManager 通过反射按 (Context, WorkerParameters) 构造函数实例化 Worker。
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 5. BuildConfig（PerfProbe 使用 BuildConfig.DEBUG 区分 debug/release 打点）
-keep class com.screentrace.app.BuildConfig { *; }

# 6. Tink（androidx.security:security-crypto 传递依赖）引用的 ErrorProne 编译期注解，运行时不进入 DEX
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
