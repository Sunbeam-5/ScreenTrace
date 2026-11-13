package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/** CR-09 验收 42：里程表式逐位滚动的方向判定契约。 */
class RollingNumberMathTest {

    @Test
    fun `数字增大向上滚`() {
        assertEquals(1, RollingNumberMath.rollDirection('1', '2'))
        assertEquals(1, RollingNumberMath.rollDirection('0', '9'))
    }

    @Test
    fun `数字减小向下滚`() {
        assertEquals(-1, RollingNumberMath.rollDirection('9', '0'))
        assertEquals(-1, RollingNumberMath.rollDirection('5', '3'))
    }

    @Test
    fun `相同与非数字字符交叉淡入淡出`() {
        assertEquals(0, RollingNumberMath.rollDirection('5', '5'))
        assertEquals(0, RollingNumberMath.rollDirection(':', ':'))
        assertEquals(0, RollingNumberMath.rollDirection(':', '1')) // 冒号 → 数字
        assertEquals(0, RollingNumberMath.rollDirection('1', ':')) // 数字 → 冒号
    }

    @Test
    fun `H-MM 场景示例——0 5 9 到 1 0 0 各位方向独立`() {
        // 0:59 → 1:00：'0'→'1' 向上，':' 不动，'5'→'0' 向下，'9'→'0' 向下
        assertEquals(1, RollingNumberMath.rollDirection('0', '1'))
        assertEquals(0, RollingNumberMath.rollDirection(':', ':'))
        assertEquals(-1, RollingNumberMath.rollDirection('5', '0'))
        assertEquals(-1, RollingNumberMath.rollDirection('9', '0'))
    }
}
