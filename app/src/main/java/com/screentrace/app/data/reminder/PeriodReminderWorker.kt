package com.screentrace.app.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screentrace.app.MainActivity
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.UsageCalculator
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * CR-30 §7-30：周期总结提醒 WorkManager 非精确调度器。
 *
 * - 关闭即取消排期，无常驻服务、无精确闹钟；
 * - 触发前复查开关与有效统计数据，无有效数据不发误导通知；
 * - 通知点击直达「日历 → 周期总结」对应周期。
 */
object PeriodReminderScheduler {

    const val WORK_NAME = "screentrace_period_summary_reminder"
    const val CHANNEL_ID = "period_summary_reminders"
    const val EXTRA_PERIOD_TYPE = "open_period_type"

    fun sync(context: Context, config: ReminderConfig, now: LocalDateTime = LocalDateTime.now()) {
        val delayMs = PeriodReminderPolicy.nextTriggerDelayMillis(now, config)
        val wm = WorkManager.getInstance(context.applicationContext)
        if (delayMs == null) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = OneTimeWorkRequestBuilder<PeriodReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class PeriodReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ScreenTraceApp ?: return Result.success()
        val container = app.container
        val settings = container.settings

        val weekly = runCatching { settings.weeklyReminderEnabled.first() }.getOrDefault(false)
        val monthly = runCatching { settings.monthlyReminderEnabled.first() }.getOrDefault(false)
        val hour = runCatching { settings.reminderHour.first() }.getOrDefault(ReminderConfig.DEFAULT_HOUR)
        val config = ReminderConfig(weeklyEnabled = weekly, monthlyEnabled = monthly, hour = hour)

        if (!config.anyEnabled) {
            runCatching { PeriodReminderScheduler.sync(applicationContext, config) }
            return Result.success()
        }

        val lastWeekly = runCatching { settings.lastWeeklyReminderKey.first() }.getOrNull()
        val lastMonthly = runCatching { settings.lastMonthlyReminderKey.first() }.getOrNull()
        val now = LocalDateTime.now()
        val dueList = PeriodReminderPolicy.evaluateDueReminders(now, config, lastWeekly, lastMonthly)

        if (dueList.isNotEmpty() && PeriodReminderScheduler.hasNotificationPermission(applicationContext)) {
            ensureChannel(applicationContext)
            for (due in dueList) {
                val selection = DateSelection.Range(due.startDate, due.endDate)
                val summary = runCatching { container.repository.summary(selection) }.getOrNull()
                if (summary != null && summary.hasAnyRecord && summary.recordedDays > 0) {
                    postReminderNotification(applicationContext, due, summary.averageScreenUnlockMin)
                    when (due.periodType) {
                        PeriodType.WEEK -> settings.setLastWeeklyReminderKey(due.periodKey)
                        PeriodType.MONTH -> settings.setLastMonthlyReminderKey(due.periodKey)
                        PeriodType.YEAR -> Unit
                    }
                }
            }
        }

        // 排入下一次提醒
        runCatching { PeriodReminderScheduler.sync(applicationContext, config, now) }
        return Result.success()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(PeriodReminderScheduler.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            PeriodReminderScheduler.CHANNEL_ID,
            "周期总结提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "每周一与每月 1 日提醒查看上一完整周期的屏幕使用总结"
        }
        nm.createNotificationChannel(channel)
    }

    private fun postReminderNotification(context: Context, due: ReminderDue, avgMinutes: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PeriodReminderScheduler.EXTRA_PERIOD_TYPE, due.periodType.name)
        }
        val pi = PendingIntent.getActivity(
            context,
            due.periodType.ordinal + 100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (due.periodType) {
            PeriodType.WEEK -> "上周使用总结已生成"
            PeriodType.MONTH -> "上月使用总结已生成"
            PeriodType.YEAR -> "周期总结已生成"
        }
        val rangeText = "${due.startDate.monthValue}月${due.startDate.dayOfMonth}日–" +
            "${due.endDate.monthValue}月${due.endDate.dayOfMonth}日"
        val body = "$rangeText · 日均 ${UsageCalculator.formatDuration(avgMinutes)}"

        val notification = NotificationCompat.Builder(context, PeriodReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(2000 + due.periodType.ordinal, notification)
        }
    }
}
