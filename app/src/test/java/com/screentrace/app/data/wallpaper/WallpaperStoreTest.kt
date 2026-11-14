package com.screentrace.app.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.4 批 E：私有目录读写与失败保留（不依赖真 ContentProvider）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WallpaperStoreTest {

    private lateinit var context: Context
    private lateinit var store: WallpaperStore
    private lateinit var srcFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val src = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        src.eraseColor(android.graphics.Color.RED)
        srcFile = File(context.cacheDir, "wall-src-${System.nanoTime()}.png")
        FileOutputStream(srcFile).use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        store = WallpaperStore(context) { uri ->
            runCatching { FileInputStream(File(uri.path!!)) }.getOrNull()
        }
    }

    @After
    fun tearDown() {
        store.clear()
        srcFile.delete()
    }

    @Test
    fun `选图后写入私有目录且解码出来的长边不超过 1920`() {
        val brightness = store.save(Uri.fromFile(srcFile))
        assertNotNull("有效图片应返回亮度", brightness)
        assertTrue("私有 JPEG 应存在", store.exists())
        val loaded = store.loadBitmap()
        assertNotNull(loaded)
        assertTrue(
            "长边应 ≤1920，实际 ${loaded!!.width}×${loaded.height}",
            maxOf(loaded.width, loaded.height) <= WallpaperTuning.MAX_EDGE
        )
        assertTrue("文件应落在 no_backup 目录", store.file.absolutePath.contains("no_backup"))
    }

    @Test
    fun `恢复默认删除文件 重复调用也安全`() {
        store.save(Uri.fromFile(srcFile))
        assertTrue(store.clear())
        assertTrue(!store.exists())
        assertTrue(store.clear())
        assertNull(store.loadBitmap())
    }

    @Test
    fun `解码失败保留原壁纸且不清空参数`() {
        store.save(Uri.fromFile(srcFile))
        val missing = Uri.fromFile(File(context.cacheDir, "definitely-missing-${System.nanoTime()}.png"))
        assertNull("读不到流应返回 null", store.save(missing))
        assertTrue("原壁纸文件必须保留", store.exists())
    }
}
