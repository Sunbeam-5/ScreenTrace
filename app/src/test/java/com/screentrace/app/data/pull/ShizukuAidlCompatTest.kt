package com.screentrace.app.data.pull

import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TD-04：Shizuku AIDL 版本兼容隔离加固单测。
 * 覆盖行为变异锚点 M-TD8、M-TD9、M-TD10。
 */
class ShizukuAidlCompatTest {

    @Test
    fun `M_TD8_classifyAidlError准确区分链接期不兼容错误与普通Binder异常`() {
        assertEquals(
            ShizukuAidlCompatibility.INCOMPATIBLE_API,
            classifyAidlError(NoSuchMethodError("IShizukuService.newProcess"))
        )
        assertEquals(
            ShizukuAidlCompatibility.INCOMPATIBLE_API,
            classifyAidlError(NoClassDefFoundError("moe/shizuku/server/IRemoteProcess"))
        )
        assertEquals(
            ShizukuAidlCompatibility.INCOMPATIBLE_API,
            classifyAidlError(IncompatibleClassChangeError("interface changed"))
        )
        assertEquals(
            ShizukuAidlCompatibility.INCOMPATIBLE_API,
            classifyAidlError(ClassNotFoundException("moe.shizuku.server.IShizukuService"))
        )
        assertEquals(
            ShizukuAidlCompatibility.INCOMPATIBLE_API,
            classifyAidlError(RuntimeException("wrap", NoSuchMethodError("nested")))
        )
        assertEquals(
            ShizukuAidlCompatibility.BINDER_UNAVAILABLE,
            classifyAidlError(IOException("binder dead"))
        )
    }

    @Test
    fun `M_TD9_假桥接抛出NoSuchMethodError时ShizukuShell捕获并标记aidlIncompatible为true`() = runBlocking {
        val brokenBridge = object : ShizukuAidlBridge {
            override fun startProcess(command: Array<String>): RemoteProcessHandle {
                throw NoSuchMethodError("moe.shizuku.server.IShizukuService.newProcess")
            }
        }
        var caught: ShizukuExecException? = null
        try {
            ShizukuShell.execWithBridge(brokenBridge, ShizukuShell.PLAIN_CMD, 1000L)
        } catch (e: ShizukuExecException) {
            caught = e
        }
        assertTrue("链接期错误必须转换为 ShizukuExecException", caught != null)
        assertTrue("aidlIncompatible 标志必须为 true", caught!!.aidlIncompatible)
        assertTrue(caught.message!!.contains("不兼容"))

        val normalBroken = object : ShizukuAidlBridge {
            override fun startProcess(command: Array<String>): RemoteProcessHandle {
                throw IOException("binder disconnected")
            }
        }
        var normalCaught: ShizukuExecException? = null
        try {
            ShizukuShell.execWithBridge(normalBroken, ShizukuShell.PLAIN_CMD, 1000L)
        } catch (e: ShizukuExecException) {
            normalCaught = e
        }
        assertFalse("普通 IO 异常不得误标为 AIDL 不兼容", normalCaught!!.aidlIncompatible)
    }

    @Test
    fun `M_TD10_ShizukuUsageProvider遇到aidlIncompatible异常时返回明确不兼容提示以便平滑降级`() = runBlocking {
        var execCalls = 0
        val runner = object : ShellRunner {
            override suspend fun exec(command: String, timeoutMs: Long): String {
                execCalls++
                throw ShizukuExecException(
                    "Shizuku AIDL 接口不兼容（NoSuchMethodError），已自动降级",
                    NoSuchMethodError("newProcess"),
                    aidlIncompatible = true
                )
            }
        }
        val provider = ShizukuUsageProvider(shell = runner)
        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Failed)
        val failed = loaded as LoadedUsage.Failed
        assertEquals(ProviderStatus.TEMPORARILY_UNAVAILABLE, failed.status)
        assertEquals("AIDL 不兼容应立即快速失败，不再发起第二次 shell 拉取", 1, execCalls)
        assertFalse("不得退化为笼统的两路空数据报错", failed.message.startsWith("两路 dump"))
        assertTrue("诊断文案必须明确指出不兼容", failed.message.contains("不兼容"))
    }
}
