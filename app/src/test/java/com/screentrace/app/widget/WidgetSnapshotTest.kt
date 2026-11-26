package com.screentrace.app.widget

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetSnapshotTest {

    private val today = DateSelection.today(LocalDate.of(2026, 11, 26))

    private fun summary(hasAnyRecord: Boolean, minutes: Int): Summary = Summary(
        selection = today,
        totalDays = 1,
        recordedDays = if (hasAnyRecord) 1 else 0,
        hasAnyRecord = hasAnyRecord,
        totalScreenUnlockMin = minutes,
        averageScreenUnlockMin = minutes,
        averageUnlocks = 0,
        longestSessionMin = minutes
    )

    private fun detail(kind: MetricKind, hasEventSource: Boolean, total: Int): MetricDetail =
        MetricDetail(
            metric = kind,
            selection = today,
            hasEventSource = hasEventSource,
            total = total,
            dailyAverage = null,
            notification = null,
            points = listOf(
                com.screentrace.app.contract.MetricPoint(
                    date = today.date,
                    hour = 0,
                    value = total,
                    coverage = if (hasEventSource) Coverage.RECORDED else Coverage.NO_RECORD
                )
            ),
            appRanking = emptyList(),
            peakHour = null,
            hourlyAverage = null,
            busiestDate = null
        )

    @Test
    fun resolveWidgetLayoutSize_switchesAt220dp() {
        // M-W3: 2x2 与 4x2 响应式尺寸阈值
        assertEquals(WidgetLayoutSize.COMPACT_2X2, resolveWidgetLayoutSize(130f))
        assertEquals(WidgetLayoutSize.COMPACT_2X2, resolveWidgetLayoutSize(219.9f))
        assertEquals(WidgetLayoutSize.WIDE_4X2, resolveWidgetLayoutSize(220f))
        assertEquals(WidgetLayoutSize.WIDE_4X2, resolveWidgetLayoutSize(250f))
    }

    @Test
    fun noRecord_returnsEmptyGuideState() {
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = false, minutes = 0),
            unlockDetail = null,
            notificationDetail = null,
            dailyGoalMin = 240
        )
        assertFalse(snap.hasData)
        assertEquals("—", snap.usageText)
        assertEquals(0f, snap.goalProgress, 0.0001f)
        assertEquals("打开 App 拉取数据", snap.goalCaption)
        assertEquals("—", snap.unlockText)
        assertEquals("—", snap.notificationText)
    }

    @Test
    fun zeroMinutesWithRecord_isNotNoData() {
        // M-W1: 真实 0 分钟有记录日（hasAnyRecord = true）必须显示 0:00，不得退回无数据引导态
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 0),
            unlockDetail = detail(MetricKind.UNLOCK, hasEventSource = true, total = 0),
            notificationDetail = detail(MetricKind.NOTIFICATION, hasEventSource = true, total = 0),
            dailyGoalMin = 240
        )
        assertTrue(snap.hasData)
        assertEquals("0:00", snap.usageText)
        assertEquals(0f, snap.goalProgress, 0.0001f)
        assertEquals("目标 4:00", snap.goalCaption)
        assertEquals("0 次", snap.unlockText)
        assertEquals("0 次", snap.notificationText)
    }

    @Test
    fun missingEventSource_displaysDashNotZero() {
        // M-W2: 降级通道无事件源时（hasEventSource = false）解锁与通知必须显示 "—"，不得伪造 "0 次"
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 125),
            unlockDetail = detail(MetricKind.UNLOCK, hasEventSource = false, total = 0),
            notificationDetail = detail(MetricKind.NOTIFICATION, hasEventSource = false, total = 0),
            dailyGoalMin = 240
        )
        assertTrue(snap.hasData)
        assertEquals("2:05", snap.usageText)
        assertEquals("—", snap.unlockText)
        assertEquals("—", snap.notificationText)
    }

    @Test
    fun overDailyGoal_clampsProgressToOneAndMarksOverTarget() {
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 305),
            unlockDetail = detail(MetricKind.UNLOCK, hasEventSource = true, total = 42),
            notificationDetail = detail(MetricKind.NOTIFICATION, hasEventSource = true, total = 88),
            dailyGoalMin = 240
        )
        assertTrue(snap.hasData)
        assertTrue(snap.overTarget)
        assertEquals(1f, snap.goalProgress, 0.0001f)
        assertEquals("超目标 1小时5分钟", snap.goalCaption)
        assertEquals("42 次", snap.unlockText)
        assertEquals("88 次", snap.notificationText)
    }

    @Test
    fun unsetDailyGoal_showsUnsetCaption() {
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 90),
            unlockDetail = null,
            notificationDetail = null,
            dailyGoalMin = 0
        )
        assertTrue(snap.hasData)
        assertFalse(snap.overTarget)
        assertEquals(0f, snap.goalProgress, 0.0001f)
        assertEquals("未设置目标", snap.goalCaption)
    }

    @Test
    fun privacyRedline_snapshotModelContainsNoAppOrPackageFields() {
        val fieldNames = WidgetSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { it.contains("app") || it.contains("pkg") || it.contains("package") })
    }
}
