package com.screentrace.app.uikit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-4-C 组合构建回归（E1 + E2 最小 diff 契约锁）：
 *
 * - E1（AppHeader）：tabProgress.value 读取必须下沉在 graphicsLayer 绘制期，
 *   组合期不得再消费高频状态、热路径不得恢复 TabNav 日志；
 * - E2（FreshNavBar）：三组备用动画只允许存在于 progress == null（Preview/备用）分支，
 *   跟手分支必须由 proximity/lerp 派生；TalkBack 语义与 reduceMotion 降级契约不变；
 * - navProximity / TabMotion.titleShiftDp 数学回归（位移量不变的锚点）。
 *
 * 源码契约断言沿用 BackupPolicyTest 的 src/main 读取先例。
 */
class A4cCombinedRegressionTest {

    private fun src(path: String): String =
        File(System.getProperty("user.dir"), "src/main/$path").readText(Charsets.UTF_8)

    // ---------- E1：AppHeader 读取下沉契约 ----------

    @Test
    fun e1_tabProgress读取位于graphicsLayer绘制期内() {
        val s = src("java/com/screentrace/app/uikit/ScreenScaffold.kt")
        val anchor = s.indexOf("// A-4-E1")
        assertTrue("A-4-E1 注释锚点未找到", anchor >= 0)
        val at = s.indexOf(".graphicsLayer {", anchor)
        assertTrue("A-4-E1 的 graphicsLayer 块未找到", at >= 0)
        val gl = s.substring(at, minOf(at + 400, s.length))
        assertTrue(
            "graphicsLayer 块内必须直接读取 tabProgress.value（E1 下沉）",
            gl.contains("tabProgress?.let { titleShiftDp(it.value) }")
        )
        assertTrue("graphicsLayer 块内必须写 translationX", gl.contains("translationX = shiftDp.dp.toPx()"))
    }

    @Test
    fun e1_组合期不得恢复高频状态读取() {
        val s = src("java/com/screentrace/app/uikit/ScreenScaffold.kt")
        assertFalse(
            "组合期不得重建 titleShiftPx（E1 已下沉，组合期消费高频状态即为回归）",
            s.contains("val titleShiftPx")
        )
        assertFalse(
            "热路径 TabNav 日志不得恢复（E1 已删除）",
            s.contains("Log.d(\"TabNav\"")
        )
    }

    @Test
    fun e1_签名与结构不变() {
        val s = src("java/com/screentrace/app/uikit/ScreenScaffold.kt")
        assertTrue("tabProgress 签名不变", s.contains("tabProgress: State<Float>? = null"))
        assertTrue("titleShiftDp 签名不变", s.contains("titleShiftDp: (progress: Float) -> Float = { 0f }"))
        assertTrue("标题 Crossfade 保留", s.contains("label = \"headerTitle\""))
    }

    // ---------- E2：备用动画条件化契约 ----------

    /** 切出块状 if (progress != null) { … } else { … } 的两个分支文本。 */
    private fun progressBranches(s: String): Pair<String, String> {
        val lines = s.lines()
        val start = lines.indexOfFirst { it.trim() == "if (progress != null) {" }
        assertTrue("未找到块状 if (progress != null)", start >= 0)
        val mid = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "} else {" }
            ?: throw AssertionError("未找到 else 分支")
        val end = (mid + 1 until lines.size).firstOrNull { lines[it].trim() == "}" }
            ?: throw AssertionError("else 分支未闭合")
        return lines.subList(start + 1, mid).joinToString("\n") to
            lines.subList(mid + 1, end).joinToString("\n")
    }

    @Test
    fun e2_备用动画仅存在于progressNull分支() {
        val s = src("java/com/screentrace/app/uikit/FreshNavBar.kt")
        val (then, els) = progressBranches(s)
        assertFalse("跟手分支不得创建备用动画", then.contains("animateFloatAsState") || then.contains("animateColorAsState"))
        assertTrue("备用分支须保留 animateFloatAsState", els.contains("animateFloatAsState"))
        assertEquals("备用分支须保留两组 animateColorAsState", 2, els.split("animateColorAsState").size - 1)
    }

    @Test
    fun e2_跟手分支由proximity派生且算法不变() {
        val s = src("java/com/screentrace/app/uikit/FreshNavBar.kt")
        val (then, _) = progressBranches(s)
        assertTrue("expand 必须取 proximity", then.contains("expand = proximity"))
        assertTrue("容器色算法不变", then.contains("lerp(Color.Transparent, palette.primary, proximity)"))
        assertTrue("内容色算法不变", then.contains("lerp(palette.primary, palette.onPrimary, proximity)"))
    }

    @Test
    fun e2_TalkBack语义与reduceMotion降级契约不变() {
        val s = src("java/com/screentrace/app/uikit/FreshNavBar.kt")
        assertTrue(
            "TalkBack contentDescription 语义不得改动",
            s.contains("contentDescription = if (selected) null else item.label")
        )
        assertTrue("widthSpec reduceMotion 降级保留", s.contains("if (reduceMotion) tween(0) else spring("))
        assertTrue("colorSpec reduceMotion 降级保留", s.contains("tween<Color>(if (reduceMotion) 0 else 200)"))
    }

    // ---------- 数学回归：位移/邻近度锚点 ----------

    @Test
    fun navProximity_数学回归() {
        assertEquals(1f, navProximity(1, 1f), 1e-6f)
        assertEquals(0f, navProximity(1, 2f), 1e-6f)
        assertEquals(0f, navProximity(1, 0f), 1e-6f)
        assertEquals(0.5f, navProximity(1, 1.5f), 1e-6f)
        assertEquals(0.25f, navProximity(1, 0.25f), 1e-6f)
        assertEquals(0f, navProximity(1, 9f), 1e-6f) // clamp
    }

    @Test
    fun titleShiftDp_数学回归() {
        assertEquals(0f, TabMotion.titleShiftDp(target = 1f, progress = 1f), 1e-6f)
        val perStep = TabMotion.titleShiftDp(target = 1f, progress = 0f, perStepDp = 36f)
        assertEquals(36f, perStep, 1e-6f)
        assertEquals(-36f, TabMotion.titleShiftDp(target = 0f, progress = 1f, perStepDp = 36f), 1e-6f)
        assertEquals(
            "线性：半程位移减半",
            18f,
            TabMotion.titleShiftDp(target = 1f, progress = 0.5f, perStepDp = 36f),
            1e-6f
        )
    }
}
