package com.screentrace.app.data.pull

import android.content.Context
import android.content.pm.PackageManager
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.UsageDumpParser
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 主通道（§2.1）：Shizuku 以 shell(uid 2000) 身份跑双 dump → 批 5 解析器。
 *
 * 授权状态机只依赖 [Shizuku] 静态方法，失败一律降级成
 * [ProviderStatus.TEMPORARILY_UNAVAILABLE]，**绝不抛异常到协调器**——
 * binder 在拉取中途死掉是常态（用户关掉 Shizuku），不能因此让 App 崩。
 *
 * [shell] 与 [parse] 可注入：真机走 [ShizukuShell] + [UsageDumpParser]，
 * JVM 单测用假实现验证「命令错 / 明文失败 / 空输出」三条分支。
 */
class ShizukuUsageProvider(
    private val shell: ShellRunner = ShizukuShell,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val context: Context? = null,
    private val parse: (String, String) -> ParseResult = { checkin, human ->
        UsageDumpParser.parse(checkin, human, zone)
    }
) : UsageProvider {

    override val channel: PullChannel = PullChannel.SHIZUKU

    override suspend fun status(): ProviderStatus = try {
        if (!Shizuku.pingBinder()) {
            ProviderStatus.NOT_INSTALLED
        } else if (Shizuku.isPreV11()) {
            // v11 之前没有 checkSelfPermission，权限是普通 runtime 权限，由 Context 查
            val granted = context?.checkSelfPermission(SHIZUKU_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) ProviderStatus.AVAILABLE else ProviderStatus.PERMISSION_DENIED
        } else {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ProviderStatus.AVAILABLE
            } else {
                ProviderStatus.PERMISSION_DENIED
            }
        }
    } catch (t: Throwable) {
        // binder 刚死 / 服务正在重启
        ProviderStatus.TEMPORARILY_UNAVAILABLE
    }

    override suspend fun load(): LoadedUsage {
        // 「抓取时刻」要在**开始 dump 之前**取（评审 P2-8）：它是 pull_log 的节流基准，
        // 也是 ForegroundBucketer 收尾开放区间的 endMs。解析要花约 1 秒，真机上看不出差别，
        // 但语义上「这次拉取代表的是哪一刻的数据」必须是起点而不是终点。
        val capturedAtMs = clock()

        // 两路 dump 都**不致命**：任何一路拿到能用的数据就够。单路失败先记下来，
        // 只有两路都没有可用数据时才按通道不可用处理（交给协调器降级）。
        val checkinAttempt = runCatching { shell.exec(ShizukuShell.CHECKIN_CMD) }
        val checkin = checkinAttempt.getOrDefault("")
        val human = runCatching { shell.exec(ShizukuShell.PLAIN_CMD) }.getOrDefault("")

        // 形态判定 + 解析都在 Default 上：4MB 文本的 `contains` 扫描与逐行解析都不该占用调用线程
        return withContext(Dispatchers.Default) {
            val checkinShape = DumpShape.of(checkin)
            val humanShape = DumpShape.of(human)

            // 真机实测（小米 2407FRK8EC / Android 16 / HyperOS 3）：
            //   `dumpsys usagestats`            → 4.17MB 完整明文（含事件流 + In-memory）
            //   `dumpsys usagestats --checkin`  → 561KB **只有元信息**，0 条 totalTimeUsed
            // 即这台 ROM 的 --checkin 不是「被忽略」，而是走了个只吐元信息的分支；
            // 真正可用的数据在**不带参数的明文**里。所以这里不是「验形态后失败」，
            // 而是「谁有数据谁当主输入」——明文主导时 checkinText 传空串，
            // 让解析器的 EV_HUMAN / PKG_HUMAN / In-memory 分支独立驱动。
            val useCheckin = checkinShape.looksLikeCheckin
            val usePlain = humanShape.usableAsPlain

            if (!useCheckin && !usePlain) {
                val reason = buildString {
                    append("两路 dump 都没有可用数据（checkin ")
                    if (checkinAttempt.isFailure) {
                        append("执行失败：")
                        append(checkinAttempt.exceptionOrNull()?.message ?: "未知错误")
                    } else {
                        append(checkinShape.summary)
                    }
                    append("；明文 ")
                    append(humanShape.summary)
                    append("）")
                }
                return@withContext LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, reason)
            }

            val warning = buildString {
                if (useCheckin) {
                    append("checkin ").append(checkinShape.summary)
                } else {
                    append("checkin 无数据（").append(checkinShape.summary).append("），本次明文主导")
                }
                append("；明文 ").append(if (humanShape.kind == DumpShape.Kind.EMPTY) "为空" else humanShape.summary)
            }

            try {
                LoadedUsage.Parsed(
                    // 不可用时传空串：绝不能把「不像 checkin 的文本」喂给 CSV 正则，
                    // 否则要么静默产出似真数据，要么把明文里的数字重复计一遍。
                    result = parse(if (useCheckin) checkin else "", human),
                    capturedAtMs = capturedAtMs,
                    warning = warning
                )
            } catch (e: Exception) {
                LoadedUsage.Failed(
                    ProviderStatus.TEMPORARILY_UNAVAILABLE,
                    "解析失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}
