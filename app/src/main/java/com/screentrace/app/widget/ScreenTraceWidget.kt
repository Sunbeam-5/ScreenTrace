package com.screentrace.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.screentrace.app.MainActivity
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricKind
import kotlinx.coroutines.flow.first

/**
 * §5.12 屏迹桌面小组件（纯离线只读 Room 最近一次拉取结果，不触发拉取、不发起网络、绝不展示应用名）。
 */
class ScreenTraceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(130.dp, 110.dp),
            DpSize(250.dp, 110.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadSnapshot(context)
        provideContent {
            GlanceTheme {
                WidgetContent(snapshot = snapshot)
            }
        }
    }

    private suspend fun loadSnapshot(context: Context): WidgetSnapshot {
        val app = context.applicationContext as? ScreenTraceApp
            ?: return emptySnapshot()
        return runCatching {
            val today = DateSelection.today()
            val repo = app.container.repository
            val summary = repo.summary(today)
            val unlock = repo.metricDetail(MetricKind.UNLOCK, today)
            val notification = repo.metricDetail(MetricKind.NOTIFICATION, today)
            val goalMin = app.container.settings.dailyGoalMin.first()
            buildWidgetSnapshot(
                summary = summary,
                unlockDetail = unlock,
                notificationDetail = notification,
                dailyGoalMin = goalMin
            )
        }.getOrElse { emptySnapshot() }
    }

    private fun emptySnapshot(): WidgetSnapshot = WidgetSnapshot(
        hasData = false,
        usageText = "—",
        goalProgress = 0f,
        goalCaption = "打开 App 拉取数据",
        overTarget = false,
        unlockText = "—",
        notificationText = "—"
    )
}

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val layoutSize = resolveWidgetLayoutSize(LocalSize.current.width.value)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "屏迹 · 今日",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp
            )
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = snapshot.usageText,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (snapshot.hasData) 26.sp else 24.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (snapshot.hasData) {
            LinearProgressIndicator(
                progress = snapshot.goalProgress,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .cornerRadius(3.dp),
                color = if (snapshot.overTarget) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
        }
        Text(
            text = snapshot.goalCaption,
            style = TextStyle(
                color = if (snapshot.overTarget) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp
            )
        )
        if (layoutSize == WidgetLayoutSize.WIDE_4X2) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "解锁 ${snapshot.unlockText}",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = "通知 ${snapshot.notificationText}",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}
