package com.screentrace.app.data.pull

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screentrace.app.ScreenTraceApp
import java.util.concurrent.TimeUnit

/**
 * 每日后台拉取（§2.1）：系统只保留约 10 个滚动日，必须至少每 9 天拉一次，这里取每日。
 *
 * 约束为空是**故意的**：拉取是纯本地操作，不该被「充电中 / 有网络」挡住，
 * 否则用户隔天开机时可能一次都拉不到，历史窗口直接丢。
 */
class DailyPullWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ScreenTraceApp ?: return Result.success()
        return when (val outcome = app.container.pullCoordinator.pullIfStale()) {
            is PullOutcome.Success, PullOutcome.Throttled -> Result.success()
            PullOutcome.AlreadyRunning -> Result.retry()
            PullOutcome.EmptyDump -> Result.success() // 空 dump 重试也是空，等下次周期
            is PullOutcome.Failure -> when (outcome.status) {
                // 未授权/未安装靠用户动作，重试无意义，别白耗电
                ProviderStatus.NOT_INSTALLED,
                ProviderStatus.PERMISSION_DENIED,
                ProviderStatus.USAGE_ACCESS_DENIED -> Result.success()

                ProviderStatus.TEMPORARILY_UNAVAILABLE -> Result.retry()
                ProviderStatus.AVAILABLE -> Result.success()
            }
        }
    }

    companion object {
        const val WORK_NAME = "screentrace-daily-pull"
    }
}

/**
 * 排程入口（§5.8「目标与同步」的拉取频率）。
 *
 * 用 [ExistingPeriodicWorkPolicy.UPDATE] 而不是 KEEP：用户改成「每 6 小时」后必须
 * **立刻生效**，KEEP 会让旧任务一直跑到进程被杀为止，改设置像没改一样。
 * UPDATE 对同 interval 的重复调用是无操作（不会重置计时），冷启动调一遍也不会踩掉
 * 已有进度。
 */
object PullScheduler {

    fun ensureScheduled(
        context: Context,
        frequency: com.screentrace.app.data.prefs.PullFrequency = com.screentrace.app.data.prefs.PullFrequency.DAILY
    ) {
        val request = PeriodicWorkRequestBuilder<DailyPullWorker>(frequency.hours, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyPullWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
