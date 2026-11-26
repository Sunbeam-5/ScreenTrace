package com.screentrace.app.widget

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.Summary
import com.screentrace.app.theme.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        // M-W3 & M-W5: 2x2 / 4x2 / 4x4 三档响应式尺寸阈值
        assertEquals(WidgetLayoutSize.COMPACT_2X2, resolveWidgetLayoutSize(130f))
        assertEquals(WidgetLayoutSize.COMPACT_2X2, resolveWidgetLayoutSize(219.9f))
        assertEquals(WidgetLayoutSize.WIDE_4X2, resolveWidgetLayoutSize(220f))
        assertEquals(WidgetLayoutSize.WIDE_4X2, resolveWidgetLayoutSize(250f, 110f))
        assertEquals(WidgetLayoutSize.LARGE_4X4, resolveWidgetLayoutSize(250f, 200f))
        assertEquals(WidgetLayoutSize.LARGE_4X4, resolveWidgetLayoutSize(250f, 220f))
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
        assertNull(snap.dayPercent)
        assertEquals("日/夜比 —", snap.dayNightCaption)
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

    @Test
    fun dayPercent_missingHourlyDenominator_staysNullAndShowsDash() {
        // M-W4: 缺小时分母时必须返回 null 且显示 "日/夜比 —"，严禁伪造 0% / 100%
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 90),
            unlockDetail = null,
            notificationDetail = null,
            dailyGoalMin = 240,
            hourlyBuckets = listOf(HourBucket(10, 0), HourBucket(23, 0))
        )
        assertNull(snap.dayPercent)
        assertEquals("日/夜比 —", snap.dayNightCaption)
    }

    @Test
    fun dayPercent_validHourlyBuckets_matchesAppBreakdownMath() {
        // M-W4: 日间 90m（10点+15点）+ 夜间 30m（23点）= 75% 日间 / 25% 夜间
        val snap = buildWidgetSnapshot(
            summary = summary(hasAnyRecord = true, minutes = 120),
            unlockDetail = null,
            notificationDetail = null,
            dailyGoalMin = 240,
            hourlyBuckets = listOf(
                HourBucket(10, 45),
                HourBucket(15, 45),
                HourBucket(23, 30)
            )
        )
        assertEquals(75, snap.dayPercent)
        assertEquals("日 75% · 夜 25%", snap.dayNightCaption)
    }

    @Test
    fun hourlyChartModel_normalizesEmptyAndOver60MinPeaksWithoutOverflow() {
        // M-W6: 空分布全 0；峰值 > 60m 按 30m 向上取整封顶且不越界
        val emptyModel = buildHourlyChartModel(emptyList())
        assertEquals(24, emptyModel.bars.size)
        assertEquals(60, emptyModel.ceilingMinutes)
        assertEquals(30, emptyModel.midMinutes)
        assertTrue(emptyModel.bars.all { it == 0f })

        val peakModel = buildHourlyChartModel(listOf(HourBucket(14, 75), HourBucket(15, 30)))
        assertEquals(90, peakModel.ceilingMinutes)
        assertEquals(45, peakModel.midMinutes)
        assertEquals(75f / 90f, peakModel.bars[14], 0.001f)
        assertEquals(30f / 90f, peakModel.bars[15], 0.001f)
        assertTrue(peakModel.bars.all { it in 0f..1f })
    }

    @Test
    fun resolveWidgetPalette_followsVariantDarkAndCustomSeedWithOpaqueCard() {
        // M-W6: 主题配色随预设/深浅/自定义种子变化，且卡片底色始终保持不透明
        val mintLight = resolveWidgetPalette(ThemeVariant.MINT, darkTheme = false)
        val coralLight = resolveWidgetPalette(ThemeVariant.CORAL, darkTheme = false)
        val mintDark = resolveWidgetPalette(ThemeVariant.MINT, darkTheme = true)
        val customSeed = resolveWidgetPalette(
            variant = ThemeVariant.MINT,
            darkTheme = false,
            customThemeSeed = 0xFF006D77L
        )

        assertNotEquals(mintLight.primary, coralLight.primary)
        assertNotEquals(mintLight.cardSurface, mintDark.cardSurface)
        assertNotEquals(mintLight.primary, customSeed.primary)
        assertEquals(0xFF, (mintLight.cardSurface ushr 24) and 0xFF)
        assertEquals(0xFF, (mintDark.cardSurface ushr 24) and 0xFF)
    }
}
