package com.screentrace.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScreenTraceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScreenTraceWidget()
}

/**
 * 小组件刷新入口：全 `runCatching` 保护，绝不影响拉取协程或主界面。
 */
object ScreenTraceWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun refreshAll(context: Context) {
        runCatching { ScreenTraceWidget().updateAll(context.applicationContext) }
    }

    fun refreshAllAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { refreshAll(appContext) }
    }
}
