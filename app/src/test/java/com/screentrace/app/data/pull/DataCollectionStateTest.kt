package com.screentrace.app.data.pull

import com.screentrace.app.data.db.CollectionStatusValues
import com.screentrace.app.data.prefs.PullFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30 阶段 E（§7-67）：数据采集八态纯函数仲裁器单测。
 * 含行为变异锚点 M-E1：空 dump（EMPTY）绝不得被归类为采集失败（FAILED）。
 */
class DataCollectionStateTest {

    private val now = 1_700_200_000_000L
    private val freshOkAt = now - 2L * 60 * 60 * 1000 // 2 小时前

    @Test
    fun `八态全覆盖_采集中优先级最高`() {
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                pulling = true,
                shizukuStatus = ProviderStatus.PERMISSION_DENIED,
                usageStatsStatus = ProviderStatus.USAGE_ACCESS_DENIED,
                latestPullOk = false,
                latestCollectionStatus = CollectionStatusValues.FAILED
            )
        )
        assertEquals(DataCollectionState.PULLING, verdict.state)
        assertTrue(verdict.showOnMainScreen)
        assertEquals(CollectionQuickAction.NONE, verdict.quickAction)
    }

    @Test
    fun `八态全覆盖_双通道均未授权判UNAUTHORIZED`() {
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.NOT_INSTALLED,
                usageStatsStatus = ProviderStatus.USAGE_ACCESS_DENIED,
                hasAnyData = false
            )
        )
        assertEquals(DataCollectionState.UNAUTHORIZED, verdict.state)
        assertTrue(verdict.showOnMainScreen)
        assertEquals(CollectionQuickAction.REQUEST_AUTHORIZATION, verdict.quickAction)
    }

    @Test
    fun `八态全覆盖_双通道服务均不可用判SERVICE_UNAVAILABLE`() {
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.NOT_INSTALLED,
                usageStatsStatus = ProviderStatus.TEMPORARILY_UNAVAILABLE,
                hasAnyData = true,
                hasDaySummary = true
            )
        )
        assertEquals(DataCollectionState.SERVICE_UNAVAILABLE, verdict.state)
        assertTrue(verdict.showOnMainScreen)
        assertEquals(CollectionQuickAction.OPEN_DIAGNOSTICS, verdict.quickAction)
    }

    @Test
    fun `八态全覆盖_拉取异常判FAILED且保留历史提示`() {
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = true,
                latestPullOk = false,
                latestCollectionStatus = CollectionStatusValues.FAILED,
                latestPullNote = "shell timeout"
            )
        )
        assertEquals(DataCollectionState.FAILED, verdict.state)
        assertTrue(verdict.showOnMainScreen)
        assertTrue(verdict.bannerText.contains("保留历史"))
        assertEquals(CollectionQuickAction.RETRY_PULL, verdict.quickAction)
    }

    @Test
    fun `M_E1_红线_空dump不得判为采集失败`() {
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = false,
                hasDaySummary = false,
                latestPullOk = false,
                latestCollectionStatus = CollectionStatusValues.EMPTY
            )
        )
        assertEquals(DataCollectionState.NO_RECORD, verdict.state)
        assertFalse("NO_RECORD 主页面由 DataEmptyState 承载，顶部状态条不重复显示", verdict.showOnMainScreen)
    }

    @Test
    fun `红线_零分钟有记录日不得判为NO_RECORD`() {
        // 真实 0 分钟有记录日：day_summary 已有该日行，hasAnyData = true 且 hasDaySummary = true
        val verdict = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = true,
                latestPullOk = true,
                latestCollectionStatus = CollectionStatusValues.OK,
                lastOkPullAtMs = freshOkAt,
                nowMs = now
            )
        )
        assertEquals(DataCollectionState.NORMAL, verdict.state)
        assertFalse(verdict.showOnMainScreen)
    }

    @Test
    fun `八态全覆盖_超过动态过期阈值判STALE_未超阈值保持NORMAL`() {
        val threshold = staleThresholdMsFor(PullFrequency.SIX_HOURS)
        assertEquals(18L * 60 * 60 * 1000, threshold)
        assertEquals(24L * 60 * 60 * 1000, staleThresholdMsFor(PullFrequency.TWELVE_HOURS))
        assertEquals(36L * 60 * 60 * 1000, staleThresholdMsFor(PullFrequency.DAILY))

        val atBoundary = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = true,
                latestPullOk = true,
                lastOkPullAtMs = now - threshold,
                nowMs = now,
                staleThresholdMs = threshold
            )
        )
        assertEquals(DataCollectionState.NORMAL, atBoundary.state)

        val overBoundary = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = true,
                latestPullOk = true,
                lastOkPullAtMs = now - threshold - 1L,
                nowMs = now,
                staleThresholdMs = threshold
            )
        )
        assertEquals(DataCollectionState.STALE, overBoundary.state)
        assertTrue(overBoundary.showOnMainScreen)
        assertEquals(CollectionQuickAction.RETRY_PULL, overBoundary.quickAction)
    }

    @Test
    fun `八态全覆盖_降级通道PARTIAL主屏显示_新装部分历史主屏隐藏`() {
        val degraded = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.NOT_INSTALLED,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = false,
                latestPullOk = true,
                lastOkPullAtMs = freshOkAt,
                nowMs = now
            )
        )
        assertEquals(DataCollectionState.PARTIAL, degraded.state)
        assertTrue(degraded.showOnMainScreen)
        assertTrue(degraded.bannerText.contains("降级"))

        val partialHistoryOnly = resolveDataCollectionState(
            DataCollectionSignal(
                shizukuStatus = ProviderStatus.AVAILABLE,
                usageStatsStatus = ProviderStatus.AVAILABLE,
                hasAnyData = true,
                hasDaySummary = true,
                latestPullOk = true,
                lastOkPullAtMs = freshOkAt,
                nowMs = now,
                isPartialHistory = true
            )
        )
        assertEquals(DataCollectionState.PARTIAL, partialHistoryOnly.state)
        assertFalse("新装前 9 天且主通道正常时主屏不常驻打扰", partialHistoryOnly.showOnMainScreen)
    }
}
