package com.screentrace.app.data.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.4 CR-20 ②：壁纸主色提取（`dominantSeedOf`）。
 *
 * 主色决定卡片底色往哪个色相偏，所以「取哪一桶」必须稳定且可解释：
 * 按色相分 12 桶，取「像素数 × (1 + 平均饱和度)」最高的一桶。
 *
 * 注：带 alpha 的十六进制字面量超出 Int 正数范围，统一 `.toInt()`（位模式不变）。
 */
class WallpaperSeedTest {

    private fun redOf(argb: Int) = (argb shr 16) and 0xFF
    private fun greenOf(argb: Int) = (argb shr 8) and 0xFF
    private fun blueOf(argb: Int) = argb and 0xFF

    @Test
    fun `空输入退回中性灰种子`() {
        assertEquals(WallpaperTuning.DEFAULT_SEED, WallpaperTuning.dominantSeedOf(IntArray(0)))
    }

    @Test
    fun `蓝色为主的图取到蓝色种子`() {
        val pixels = IntArray(100) { 0xFF3060C0.toInt() }
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertTrue("蓝分量应最大", blueOf(seed) > redOf(seed) && blueOf(seed) > greenOf(seed))
    }

    @Test
    fun `少数高饱和像素不压过占多数的色相`() {
        // 90 蓝 + 10 红：红更饱和，但蓝占多数 → 取蓝
        val pixels = IntArray(90) { 0xFF3060C0.toInt() } + IntArray(10) { 0xFFFF2000.toInt() }
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertTrue("应取占多数的蓝", blueOf(seed) > redOf(seed))
    }

    @Test
    fun `饱和度加权生效 面积接近时更艳的色相胜出`() {
        // 50 低饱和蓝(sat≈0.33) + 40 纯红(sat=1.0)：
        // 纯像素数是蓝赢，但按「像素数 × (1 + 平均饱和度)」是红赢——
        // 这条用例专门钉住饱和度加权这一项（去掉它就变绿）。
        // 注意 alpha 必须是 FF：0x8090C0 的 alpha 是 0，会被当成全透明像素整片跳过
        val lowSatBlue = 0xFF8090C0.toInt()
        val pixels = IntArray(50) { lowSatBlue } + IntArray(40) { 0xFFFF0000.toInt() }
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertTrue("面积接近时应取更艳的红", redOf(seed) > blueOf(seed))
    }

    @Test
    fun `大面积灰底配少量彩色时取彩色主色`() {
        // 80 近灰 + 20 蓝：近灰像素不参与分桶，主色应是蓝而不是灰
        val pixels = IntArray(80) { 0xFF909090.toInt() } + IntArray(20) { 0xFF3060C0.toInt() }
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertTrue("应取彩色主色", blueOf(seed) > redOf(seed))
    }

    @Test
    fun `整张灰图退回平均灰`() {
        val pixels = intArrayOf(0xFF808080.toInt(), 0xFF909090.toInt())
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertEquals(0xFF, (seed ushr 24) and 0xFF)
        assertTrue("平均灰应在 0x80–0x90", greenOf(seed) in 0x80..0x90)
    }

    @Test
    fun `半透明像素不参与取色`() {
        // 一半不透明蓝、一半全透明蓝：有效样本只有蓝色那半，结果仍应是蓝
        val pixels = IntArray(50) { 0xFF3060C0.toInt() } + IntArray(50) { 0x003060C0 }
        val seed = WallpaperTuning.dominantSeedOf(pixels)
        assertTrue(blueOf(seed) > redOf(seed))
    }

    @Test
    fun `结果是不透明的 ARGB`() {
        val seed = WallpaperTuning.dominantSeedOf(IntArray(10) { 0xFF3060C0.toInt() })
        assertEquals(0xFF, (seed ushr 24) and 0xFF)
    }
}
