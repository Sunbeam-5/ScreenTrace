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
            ShizukuShell.CHECKIN_CMD to Result.success(checkinBody),
            ShizukuShell.PLAIN_CMD to Result.failure(ShizukuExecException("明文超时"))
        )
        val provider = provider(shell)

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        val warning = loaded.warning
        assertNotNull("必须留下「今日数据缺一块」的痕迹", warning)
        assertTrue("要写清明文那一路是空的", warning!!.contains("为空"))
        assertTrue("也要记下 checkin 那段的形态，便于事后追溯", warning.contains("checkin"))
        // 解析器收到空明文：证明没有把失败当成功硬塞
        assertEquals("", lastHuman)
        assertTrue(
            "明文确实尝试过（失败也要试，否则等于没接这条源）",
            shell.received(ShizukuShell.PLAIN_CMD)
        )
    }

    @Test
    fun `两段都正常时带上形态摘要且解析器收到完整两段`() = runBlocking {
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(checkinBody),
            ShizukuShell.PLAIN_CMD to Result.success(plainBody)
        )
        val provider = provider(shell)

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        // 成功也要留痕：用户报「数据不对」时，第一件事是看这次到底拿到了什么形态
        val warning = loaded.warning
        assertNotNull(warning)
        assertTrue(warning!!.contains("checkin"))
        assertTrue("明文那一段的形态也要记", warning.contains("明文"))
        assertEquals(checkinBody, lastCheckin)
        assertEquals(plainBody, lastHuman)
    }

    @Test
    fun `两路都只有元信息时必须失败而不是产出似真数据`() = runBlocking {
        // 真机实测：这台 ROM 的 `--checkin` 只吐 561KB 元信息（0 条 totalTimeUsed），
        // 若同时明文那一路也没拿到数据，就是真的没东西可解析——必须失败交给降级通道。
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(plainBody),
            ShizukuShell.PLAIN_CMD to Result.success(plainBody)
        )
        val loaded = provider(shell).load()

        assertTrue("两路都无数据 → 按通道失败处理", loaded is LoadedUsage.Failed)
        loaded as LoadedUsage.Failed
        assertEquals(ProviderStatus.TEMPORARILY_UNAVAILABLE, loaded.status)
        assertTrue("报错要能看出两路分别拿到了什么", loaded.message.contains("都没有可用数据"))
        assertTrue("要带上 checkin 那一路的形态", loaded.message.contains("plain"))
        assertNull("不许把不像 checkin 的文本塞给 CSV 分支", lastCheckin)
    }

    @Test
    fun `checkin 无数据但有明文时走明文主导且成功`() = runBlocking {
        // 这台 ROM 的正解：checkin 位置给元信息（无数据），明文给完整事件流。
        // 此时不该整次失败，而应让明文当主输入，checkin 分支传空串避免误解析。
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(plainBody),
            ShizukuShell.PLAIN_CMD to Result.success(richPlainBody)
        )
        val loaded = provider(shell).load()

        assertTrue("明文有数据就该成功", loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        val warning = loaded.warning
        assertNotNull(warning)
        assertTrue("要点明这次是明文主导", warning!!.contains("明文主导"))
        assertTrue("也要留下 checkin 无数据的证据", warning.contains("无数据"))
        assertEquals("checkin 分支必须收到空串，绝不能喂不像 checkin 的文本", "", lastCheckin)
        assertEquals(richPlainBody, lastHuman)
    }

    @Test
    fun `明文带上真机形态时解析器能产出日汇总`() = runBlocking {
        // 端到端一点：不只「调用到了解析器」，而是明文事件真的变成了屏幕时长。
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(plainBody),
            ShizukuShell.PLAIN_CMD to Result.success(richPlainBody)
        )
        // 这条必须用**真实解析器**：假 parse 只捕获入参、返回空结果，
        // 用它断言「产出日汇总」等于测自己写的桩。
        val loaded = realProvider(shell).load() as LoadedUsage.Parsed
        val days = loaded.result.days
        assertTrue("明文里的 SCREEN_* 区间必须产出日汇总（真机 765 分钟那天）", days.isNotEmpty())
        assertTrue(
            "屏幕时长应大于 0，否则说明事件没被认出来：\$days",
            days.values.any { it.screenMin > 0 }
        )
        assertTrue(
            "通知事件也应被认出来",
            loaded.result.notificationEvents.isNotEmpty()
        )
    }

    @Test
    fun `解析器抛异常时返回失败并带原因`() = runBlocking {
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(checkinBody),
            ShizukuShell.PLAIN_CMD to Result.success(checkinBody)
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

    // ---------- 输出判定（评审 P1-3） ----------

    @Test
    fun `非空输出但已被超时截断时必须抛而不是返回半份数据`() {
        // 这就是原来的 bug：13MB 被看门狗砍掉后半截，text 非空 → 直接被当成功入库，
        // 解析器逐行特征匹配所以照样能解析出前 N 天，pull_log.ok=true，
        // 「今天那段没了」和「今天没怎么用手机」长得一模一样。
        val truncatedBody = "days,2026-11-01,com.tencent.mm,1200"
        val error = runCatching {
            checkedOutput(truncatedBody, truncated = true, command = "x", timeoutMs = 120_000)
        }.exceptionOrNull()

        assertTrue("必须失败，交给协调器去降级/保留旧数据", error is ShizukuExecException)
        assertTrue("报错要能看出是超时截断", error!!.message!!.contains("截断"))
    }

    @Test
    fun `未截断的正常输出原样返回`() {
        assertEquals(
            "body",
            checkedOutput("body", truncated = false, command = "x", timeoutMs = 120_000)
        )
    }

    @Test
    fun `空输出仍然按失败处理`() {
        val error = runCatching {
            checkedOutput("", truncated = false, command = "x", timeoutMs = 120_000)
        }.exceptionOrNull()
        assertTrue(error is ShizukuExecException)
    }

    @Test
    fun `超时截断会让整个拉取失败而不是产出不完整数据`() = runBlocking {
        // 走完整条通道：shell 抛「截断」→ provider 必须返回 Failed，而不是 Parsed
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to
                Result.failure(ShizukuExecException("命令执行超时（120000ms），输出已截断"))
        )
        val loaded = provider(shell).load()

        assertTrue(loaded is LoadedUsage.Failed)
        assertNull("不许偷偷塞半份数据给解析器", lastCheckin)
    }

    // ---------- helpers ----------

    private var lastCheckin: String? = null
    private var lastHuman: String? = null

    /**
     * 真机 checkin 的最小可信形态。
     *
     * 必须是带 `Days:` / `totalTimeUsed=` 这类**checkin 独有**特征的真实形状：
     * 用 `"checkin-body"` 这种占位串的话，形态守卫会（正确地）把它判成 UNKNOWN，
     * 测试就成了在测守卫而不是测被测分支。
     */
    private val checkinBody = "Days:\n0,2026-11-10,765,0,3976,765\npackages:\n" +
        "0,com.xiaomi.smarthome,0,1386,0,1794300000000,totalTimeUsed=1386,appLaunchCount=0\n"

    /** 真机 `dumpsys usagestats` 的关键片段：事件流 + In-memory，确实带数据。 */
    private val richPlainBody = "Flags:\n    android.app.usage.user_interaction_type_api: true\n" +
        "user=0 \n" +
        "  Last 24 hour events (timeRange=\"2026/11/10 08:58 \u2013 2026/11/11 08:58\" )\n" +
        "    time=\"2026-11-10 11:15:10\" type=SCREEN_INTERACTIVE package=android flags=0x0 \n" +
        "    time=\"2026-11-11 00:12:44\" type=SCREEN_NON_INTERACTIVE package=android flags=0x0 \n" +
        "    time=\"2026-11-11 08:12:44\" type=NOTIFICATION_INTERRUPTION package=tv.danmaku.bili " +
        "channelId=mipush|tv.danmaku.bili|1 flags=0x0 \n" +
        "  In-memory daily stats\n" +
        "  timeRange=\"2026/11/10 09:15 \u2013 2026/11/11 08:55\" \n" +
        "    packages\n" +
        "      package=com.xiaomi.smarthome totalTimeUsed=\"00:30:00\" lastTimeUsed=\"2026-11-11 08:26:00\" appLaunchCount=3\n" +
        "    event aggregations\n" +
        "      screen-interactive: 1x for \"00:00\" (now running, started at )\n"

    private val plainBody = "Flags:\n    android.app.usage.use_parceled_list: true\n" +
        "Broadcast response stats:\n    notification:+2d22h13m13s724ms; event=<posted>, pkg=a, usr=0\n"

    /** 不解注入 parse：走真实的 [com.screentrace.app.data.parse.UsageDumpParser]。 */
    private fun realProvider(shell: ShellRunner): ShizukuUsageProvider = ShizukuUsageProvider(
        shell = shell,
        zone = zone,
        clock = { 1_700_000_000_000 }
    )

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
