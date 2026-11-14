package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-17 ②：导航条连续插值回归。
 * 变异锚点：把 `navProximity` 改回离散（只有 index == progress 才 1f），
 * 半程 / 偏移用例必须变红。
 */
class NavProximityTest {

    @Test
    fun `停在整页时只有该页全亮`() {
        assertEquals(1f, navProximity(1, 1f), 1e-4f)
        assertEquals(0f, navProximity(0, 1f), 1e-4f)
        assertEquals(0f, navProximity(2, 1f), 1e-4f)
    }

    @Test
    fun `拖到两页中间时各占一半`() {
        assertEquals(0.5f, navProximity(0, 0.5f), 1e-4f)
        assertEquals(0.5f, navProximity(1, 0.5f), 1e-4f)
    }

    @Test
    fun `带偏移的连续位置按比例插值`() {
        assertEquals(0.7f, navProximity(1, 1.3f), 1e-4f)
        assertEquals(0.3f, navProximity(2, 1.3f), 1e-4f)
    }

    @Test
    fun `超出范围钳制到 0`() {
        assertEquals(0f, navProximity(0, 2f), 1e-4f)
        assertEquals(0f, navProximity(2, -1f), 1e-4f)
    }
}
