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
        val checkin = try {
            shell.exec(ShizukuShell.CHECKIN_CMD)
        } catch (e: ShizukuExecException) {
            return LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, e.message ?: "checkin 执行失败")
        }
        if (checkin.isBlank()) {
            return LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, "checkin 输出为空")
        }

        // 明文 dump 只用来补「今天」的 In-memory 段（§2.2）：失败不该毁掉整次拉取，
        // 但必须把原因带出去写进 pull_log.note，让「今天少一截」这件事可追溯。
        val human = try {
            shell.exec(ShizukuShell.PLAIN_CMD)
        } catch (e: ShizukuExecException) {
            ""
        }
        val warning = if (human.isBlank()) "明文 dump 为空：今日 In-memory 数据可能缺失" else null

        return try {
            val result = withContext(Dispatchers.Default) { parse(checkin, human) }
            LoadedUsage.Parsed(result = result, capturedAtMs = clock(), warning = warning)
        } catch (e: Exception) {
            LoadedUsage.Failed(
                ProviderStatus.TEMPORARILY_UNAVAILABLE,
                "解析失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}
