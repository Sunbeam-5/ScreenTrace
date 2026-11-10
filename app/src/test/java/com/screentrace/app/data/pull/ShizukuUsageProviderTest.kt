package com.screentrace.app.data.pull

import com.screentrace.app.data.parse.ParseResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主通道（Shizuku）的**读取与容错**分支，用假 [ShellRunner] 跑，不碰 binder。
 *
 * 这里钉住三件真机难复现、但线上一定会遇到的事：
 * 1. 命令形态必须是 `sh -c "dumpsys usagestats --checkin"`——`--checkin` 漏写只会
 *    拿到明文，解析器会静默产出空区间（最隐蔽的一类错）；
 * 2. checkin 失败 → **必须失败**（不许返回半份数据）；
 * 3. 明文 dump 失败 → **不许让整次拉取失败**，但必须带 warning 出去写进 `pull_log.note`
 *    （今天少一截是可接受的降级，静默少一截是不可接受的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuUsageProviderTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- 命令形态 ----------

    @Test
    fun `checkin 命令走 sh -c 且带 --checkin`() {
        val args = ShizukuShell.invocation(ShizukuShell.CHECKIN_CMD)
        assertEquals(3, args.size)
        assertEquals("sh", args[0])
        assertEquals("-c", args[1])
        assertTrue("漏掉 --checkin 会退化成明文 dump", args[2].contains("--checkin"))
        assertTrue(args[2].startsWith("dumpsys usagestats"))
    }

    @Test
    fun `明文命令不带 --checkin`() {
        val args = ShizukuShell.invocation(ShizukuShell.PLAIN_CMD)
        assertEquals("dumpsys usagestats", args[2])
        assertTrue(!args[2].contains("--checkin"))
    }

    // ---------- 读取分支 ----------

    @Test
    fun `checkin 执行失败时不产出任何数据`() = runBlocking {
        val shell = FakeShell(ShizukuShell.CHECKIN_CMD to Result.failure(ShizukuExecException("权限被拒")))
        val provider = provider(shell)

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Failed)
        loaded as LoadedUsage.Failed
        assertEquals(ProviderStatus.TEMPORARILY_UNAVAILABLE, loaded.status)
        assertTrue(loaded.message.contains("权限被拒"))
    }

    @Test
    fun `checkin 输出为空视为失败而不是空 dump`() = runBlocking {
        val shell = FakeShell(ShizukuShell.CHECKIN_CMD to Result.success(""))
        val loaded = provider(shell).load()

        assertTrue("空输出要按失败处理，让协调器去试下一条通道", loaded is LoadedUsage.Failed)
    }

    @Test
    fun `明文 dump 失败时仍成功但带 warning`() = runBlocking {
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success("checkin-body"),
            ShizukuShell.PLAIN_CMD to Result.failure(ShizukuExecException("明文超时"))
        )
        val provider = provider(shell)

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertNotNull("必须留下「今日数据可能缺失」的痕迹", loaded.warning)
        // 解析器收到空明文：证明没有把失败当成功硬塞
        assertEquals("", lastHuman)
        assertTrue(
            "明文确实尝试过（失败也要试，否则等于没接这条源）",
            shell.received(ShizukuShell.PLAIN_CMD)
        )
    }

    @Test
    fun `两段都正常时无 warning 且解析器收到完整两段`() = runBlocking {
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success("checkin-body"),
            ShizukuShell.PLAIN_CMD to Result.success("human-body")
        )
        val provider = provider(shell)

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        assertNull((loaded as LoadedUsage.Parsed).warning)
        assertEquals("checkin-body", lastCheckin)
        assertEquals("human-body", lastHuman)
    }

    @Test
    fun `解析器抛异常时返回失败并带原因`() = runBlocking {
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success("garbage"),
            ShizukuShell.PLAIN_CMD to Result.success("garbage")
        )
        val provider = ShizukuUsageProvider(
            shell = shell,
            zone = zone,
            clock = { 1_700_000_000_000 },
            parse = { _, _ -> throw IllegalStateException("格式不认识") }
        )

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Failed)
        assertTrue((loaded as LoadedUsage.Failed).message.contains("格式不认识"))
    }

    @Test
    fun `环境没有 Shizuku 时状态探测不抛异常`() = runBlocking {
        // Robolectric 环境下 binder 一定拿不到；红线是「绝不让异常冒到协调器」
        val status = provider(FakeShell()).status()
        assertTrue(
            "允许 NOT_INSTALLED 或 TEMPORARILY_UNAVAILABLE，但不许抛",
            status == ProviderStatus.NOT_INSTALLED || status == ProviderStatus.TEMPORARILY_UNAVAILABLE
        )
    }

    // ---------- helpers ----------

    private var lastCheckin: String? = null
    private var lastHuman: String? = null

    private fun provider(shell: ShellRunner): ShizukuUsageProvider {
        lastCheckin = null
        lastHuman = null
        return ShizukuUsageProvider(
            shell = shell,
            zone = zone,
            clock = { 1_700_000_000_000 },
            parse = { checkin, human ->
                lastCheckin = checkin
                lastHuman = human
                emptyResult()
            }
        )
    }

    private fun emptyResult(): ParseResult = ParseResult(
        days = emptyMap(),
        hourMinutes = emptyMap(),
        appUsage = emptyList(),
        unlockEvents = emptyList(),
        notificationEvents = emptyList()
    )

    /** 按命令分发的假 shell；未预期的命令直接断言失败，防止测试悄悄跑偏。 */
    private class FakeShell(vararg answers: Pair<String, Result<String>>) : ShellRunner {

        private val table: Map<String, Result<String>> = answers.toMap()
        val calls = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutMs: Long): String {
            calls += command
            val answer = table[command] ?: error("未预期的命令：$command")
            return answer.getOrThrow()
        }

        /** 该命令是否被尝试过（不关心成败）。 */
        fun received(command: String): Boolean = calls.contains(command)
    }
}
