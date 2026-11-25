package com.screentrace.app.data.pull

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30 阶段 E（§7-67）：双通道断开降级与授权恢复回流单测。
 * 含行为变异锚点 M-E2：通道授权恢复后必须自动触发补拉（绕过 60s 失败冷却）。
 */
class ChannelRecoveryPolicyTest {

    @Test
    fun `Shizuku断开且UsageStats可用时识别为降级并提示暂停事件采集`() {
        val prev = listOf(
            PullChannel.SHIZUKU to ProviderStatus.AVAILABLE,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val curr = listOf(
            PullChannel.SHIZUKU to ProviderStatus.NOT_INSTALLED,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val transition = evaluateChannelTransition(prev, curr, DataCollectionState.NORMAL)
        assertTrue(transition.shizukuDisconnected)
        assertTrue(transition.degradedToUsageStats)
        assertFalse(transition.recoveredForAutoPull)
        assertNotNull(transition.noticeMessage)
        assertTrue(transition.noticeMessage!!.contains("降级"))
    }

    @Test
    fun `M_E2_Shizuku从未授权恢复为可用时触发自动补拉`() {
        val prev = listOf(
            PullChannel.SHIZUKU to ProviderStatus.PERMISSION_DENIED,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val curr = listOf(
            PullChannel.SHIZUKU to ProviderStatus.AVAILABLE,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val transition = evaluateChannelTransition(prev, curr, DataCollectionState.PARTIAL)
        assertFalse(transition.shizukuDisconnected)
        assertTrue(transition.recoveredForAutoPull)
        assertNull(transition.noticeMessage)
    }

    @Test
    fun `M_E2_UsageStats从未授权恢复为可用时触发自动补拉`() {
        val prev = listOf(
            PullChannel.SHIZUKU to ProviderStatus.NOT_INSTALLED,
            PullChannel.USAGE_STATS to ProviderStatus.USAGE_ACCESS_DENIED
        )
        val curr = listOf(
            PullChannel.SHIZUKU to ProviderStatus.NOT_INSTALLED,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val transition = evaluateChannelTransition(prev, curr, DataCollectionState.UNAUTHORIZED)
        assertTrue(transition.recoveredForAutoPull)
    }

    @Test
    fun `正常状态下重复探测不触发自动补拉防止循环`() {
        val same = listOf(
            PullChannel.SHIZUKU to ProviderStatus.AVAILABLE,
            PullChannel.USAGE_STATS to ProviderStatus.AVAILABLE
        )
        val transition = evaluateChannelTransition(same, same, DataCollectionState.NORMAL)
        assertFalse(transition.shizukuDisconnected)
        assertFalse(transition.degradedToUsageStats)
        assertFalse(transition.recoveredForAutoPull)
        assertNull(transition.noticeMessage)
    }
}
