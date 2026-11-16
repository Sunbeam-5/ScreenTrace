package com.screentrace.app.data.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-51（CR-24）：壁纸模糊的**纯函数**部分。
 *
 * 背景：`Modifier.blur` 是每帧 RenderEffect，三页常驻（`beyondBoundsPageCount = 3`）
 * 把它乘以 3，是壁纸模式下唯一确定的 GPU 开销（vivo 实测约 1ms GPU/帧）。
 * 改成「只在壁纸或 `blurDp` 变化时算一次 CPU 模糊」后，模糊算法本身必须可单测打靶。
 */
class WallpaperBlurTest {

    private val eps = 1e-4f

    // ------------------------------------------------------------ 基本约定

    @Test
    fun `半径 0 原样返回`() {
        val src = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        val out = WallpaperTuning.blurArgb(2, 2, src, 0)
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun `半径 0 也是新数组 调用方可随意处置`() {
        val src = intArrayOf(1, 2, 3, 4)
        val out = WallpaperTuning.blurArgb(2, 2, src, 0)
        assertTrue("必须是新数组", out !== src)
    }

    @Test
    fun `像素数与尺寸不符要炸`() {
        assertThrows(IllegalArgumentException::class.java) {
            WallpaperTuning.blurArgb(2, 2, intArrayOf(1, 2, 3), 1)
        }
    }

    @Test
    fun `尺寸非法要炸`() {
        assertThrows(IllegalArgumentException::class.java) {
            WallpaperTuning.blurArgb(0, 2, IntArray(0), 1)
        }
    }

    // ------------------------------------------------------------ 模糊效果

    @Test
    fun `半径越大越平滑`() {
        // 一半黑一半白的横向条带：模糊后中间过渡带越宽 = 越平滑
        val w = 21
        val h = 1
        val src = IntArray(w * h) { i -> if (i < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        fun jump(radius: Int): Int {
            val out = WallpaperTuning.blurArgb(w, h, src, radius)
            // 统计亮度跳变次数：越平滑，单步跳变越小
            var maxStep = 0
            for (i in 1 until w) {
                val a = out[i - 1] and 0xFF
                val b = out[i] and 0xFF
                maxStep = maxOf(maxStep, kotlin.math.abs(a - b))
            }
            return maxStep
        }
        val stepSmall = jump(1)
        val stepLarge = jump(6)
        assertTrue("半径 6 的最大跳变应小于半径 1（$stepLarge >= $stepSmall）", stepLarge < stepSmall)
    }

    @Test
    fun `模糊后整体明暗不偏移`() {
        // 全灰图模糊后仍是同一灰度（盒式模糊是加权平均，均值守恒）
        val w = 8
        val h = 4
        val gray = 0xFF808080.toInt()
        val out = WallpaperTuning.blurArgb(w, h, IntArray(w * h) { gray }, 3)
        out.forEach { px ->
            assertEquals("每个像素都应仍是同一灰度", gray, px)
        }
    }

    @Test
    fun `边缘不越界 不发黑边`() {
        // 左边缘是白、其余黑：clamp 取样会让边缘仍是白的均值，不会被界外的 0 染黑
        val w = 5
        val h = 1
        val src = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt())
        val out = WallpaperTuning.blurArgb(w, h, src, 2)
        val left = out[0] and 0xFF
        assertTrue("左边缘亮度 $left 应 > 0（不被界外像素染黑）", left > 0)
    }

    @Test
    fun `四个通道都保留 不串色`() {
        // 单像素红 + 三像素透明黑，半径 0 时必须逐位不变（alpha/RGB 分别累加）
        val src = intArrayOf(0xFFFF0000.toInt(), 0, 0, 0)
        val out = WallpaperTuning.blurArgb(2, 2, src, 0)
        assertEquals(0xFFFF0000.toInt(), out[0])
    }

    @Test
    fun `三趟模糊的过渡带比一趟宽`() {
        // 阶跃边模糊后，中间过渡像素越多 = 越接近高斯（三趟可分离盒式）。
        // 一趟盒式半径 4 的过渡带只有 9px（8 个中间值）；三趟卷积支撑是 27px，
        // 中间值必须显著更多——这条专咬「少跑几趟」的变异。
        val w = 61
        val h = 1
        val src = IntArray(w) { i -> if (i < 20) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = WallpaperTuning.blurArgb(w, h, src, 4)
        val mid = out.count { px ->
            val v = px and 0xFF
            v in 1..254
        }
        assertTrue("过渡带中间值 $mid 应 > 18（一趟盒式只有 8 个）", mid > 18)
    }

    @Test
    fun `模糊是纯函数 同输入同输出 且不改写输入`() {
        // 不透明 ARGB（alpha 必须是 0xFF，否则四通道各自平均后全变透明，看不出效果）
        val src = IntArray(16) { 0xFF000000.toInt() or (it * 17) }
        val snapshot = src.copyOf()
        val a = WallpaperTuning.blurArgb(4, 4, src, 2)
        val b = WallpaperTuning.blurArgb(4, 4, src, 2)
        assertEquals("同输入必须同输出", a.toList(), b.toList())
        assertNotEquals("模糊后不该还是原图", snapshot.toList(), a.toList())
        assertEquals("输入数组不得被改写（纯函数）", snapshot.toList(), src.toList())
    }
}
