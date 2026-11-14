package com.screentrace.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.SyncFrequency
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 自动同步的触发与上传任务（§5.10 / §6 / §7-15，批 8b-2）。
 *
 * 链条：任意**拉取成功**（[com.screentrace.app.data.pull.PullCoordinator] 的 onSuccess
 * 钩子——覆盖下拉刷新 / 设置页手动拉取 / 打开节流拉取 / 每日 Worker 四条路径）→
 * 按频率窗口判定（[SyncManager.shouldAutoSync]）→ 排一次带网络约束的 [AutoSyncWorker]
 * → 执行 [SyncManager.uploadAuto]（时间戳文件名 + 仅保留 5 份的清理在其内部）。
 * 失败静默写 SettingsStore（设置页展示，不弹窗，§7-15），不丢数据、不动拉取结果。
 *
 * **为什么上传走 WorkManager 而不是在钩子里直接调 uploadAuto**（§6 明文要求
 * 「任务约束为『网络可用』（开启仅 Wi-Fi 时 NetworkType.UNMETERED）、指数退避重试
 * 最多 3 次」——约束与退避正是 WorkManager 的原生语义）：
 * ① 约束/退避/去重交给系统，进程被杀不丢已排的上传；
 * ② 钩子跑在拉取协程里，内联上传会让下拉刷新的转圈多等最多 15 秒网络超时。
 *
 * CR-04 联动口径：手动下拉成功视为一次「成功拉取」——「每次成功拉取后」档随之触发；
 * 「每 1/3/7 天」按时间窗口判断，不因手动刷新提前或推迟（门限全在 [onPullSuccess]）。
 */
object AutoSyncScheduler {

    const val WORK_NAME = "screentrace-auto-sync"

    /** 「指数退避重试最多 3 次」= 首次失败后最多再试 3 次（共 4 次执行）。 */
    const val MAX_RETRIES = 3

    /** 退避基数（分钟）：指数退避序列 10 / 20 / 40 分钟。 */
    const val BACKOFF_MINUTES = 10L

    /**
     * 拉取成功钩子入口：频率窗口在这里判，OFF 与「每 1/3/7 天」未到期都不排任务。
     *
     * 窗口基准是 `syncLastAt`（上次同步**尝试**时间，成败都记，[SyncManager.record]）：
     * 失败过 3 次后本窗口内不再打扰，等下一窗口或下档频率再试——静默是 §7-15 的要求。
     *
     * [schedule] 默认即 [enqueue]；测试注入替身以钉住门限逻辑（不必初始化 WorkManager）。
     */
    suspend fun onPullSuccess(
        context: Context,
        sync: SyncManager,
        settings: SettingsStore,
        schedule: (wifiOnly: Boolean) -> Unit = { enqueue(context, it) }
    ) {
        if (!sync.shouldAutoSync(settings.syncLastAt.first())) return
        schedule(settings.syncWifiOnly.first())
    }

    /**
     * 排一次自动上传。REPLACE：窗口内重复拉取成功只保留最新一份待执行任务，
     * 约束也随最新的「仅 Wi-Fi」开关刷新。
     */
    fun enqueue(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(networkType(wifiOnly)).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** 约束映射（纯函数）：「仅 Wi-Fi」→ 不计费网络（UNMETERED），否则联网即可。 */
    fun networkType(wifiOnly: Boolean): NetworkType =
        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** 重试上限（纯函数）：第 [MAX_RETRIES] 次重试后放弃，等下一次拉取成功再触发。 */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
}

/**
 * 自动上传任务本体。执行时**重判**频率与「仅 Wi-Fi」——入队到执行之间设置可能已变
 * （用户改开关、手动上传过一次），执行时的口径才是 §7-15 说的「按所选频率」。
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ScreenTraceApp ?: return Result.success()
        val settings = app.container.settings
        if (settings.syncFrequency.first() == SyncFrequency.OFF) return Result.success()

        // 入队后才打开「仅 Wi-Fi」：按新约束重新排队（本任务约束可能还是 CONNECTED，
        // 直接跑会违反开关；直接放弃又会让这次触发彻底丢失）。静默换约束，不弹窗。
        if (settings.syncWifiOnly.first() && isMetered(applicationContext)) {
            AutoSyncScheduler.enqueue(applicationContext, wifiOnly = true)
            return Result.success()
        }

        val sync = app.container.syncManager
        // 执行窗口再判：入队后用户手动上传过 → 本窗口已同步，不重复传
        if (!sync.shouldAutoSync(settings.syncLastAt.first())) return Result.success()

        return when (sync.uploadAuto(appVersion())) {
            is SyncResult.Ok -> Result.success()
            is SyncResult.Err -> if (AutoSyncScheduler.shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                // 放弃不写假状态：uploadAuto 的 record() 已经留了失败原因，设置页可见
                Result.failure()
            }
        }
    }

    /** 计费网络判定：无网/未知按「计费」处理（宁可等 Wi-Fi，不在蜂窝上偷跑）。 */
    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 与设置页同一取法（AppNav）：BuildConfig 未开 versionName，直接问 PackageManager。 */
    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull() ?: "—"
}
