package com.screentrace.app

import android.app.Application
import android.util.Log
import com.screentrace.app.data.pull.PullScheduler

/**
 * 进程级入口：建容器 + 排每日拉取任务（§2.1）。
 * 不在这里做任何拉取——拉取时机由「App 打开节流」与 WorkManager 决定，
 * 冷启动就直接跑 dumpsys 会让首屏变慢且容易和 Shizuku 授权弹窗打架。
 */
class ScreenTraceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 排任务失败**不得拖垮冷启动**：WorkManager 在部分 ROM（禁用后台自启、被动升级
        // 后进程被掐）与测试环境下会 `IllegalStateException: WorkManager is not
        // initialized properly`，而「每天拉一次」只是兜底——真正的主力是打开 App 时的
        // 节流拉取，失败最坏也只是隔几天才补一次。让 Application.onCreate 抛异常
        // 意味着用户连界面都进不去，代价远大于收益。
        runCatching { PullScheduler.ensureScheduled(this) }
            .onFailure { Log.w(TAG, "每日拉取任务排程失败，已降级为「打开 App 时拉取」", it) }
    }

    private companion object {
        const val TAG = "ScreenTraceApp"
    }
}
