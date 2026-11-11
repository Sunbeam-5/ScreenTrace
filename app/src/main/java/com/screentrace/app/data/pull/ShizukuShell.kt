package com.screentrace.app.data.pull

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** shell 执行失败的载体：**必须区别于「空输出」**，两者对应不同的界面状态（§5.8）。 */
class ShizukuExecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Shizuku shell 执行器（§2.1）。抽成接口是为了让 [ShizukuUsageProvider] 在 JVM 单测里
 * 用假实现跑通，不需要 binder 与真机。
 */
interface ShellRunner {
    suspend fun exec(command: String, timeoutMs: Long = ShizukuShell.DEFAULT_TIMEOUT_MS): String
}

/**
 * 以 shell(uid 2000) 身份执行命令。
 *
 * **为什么直接走 AIDL 的 `IShizukuService.newProcess` 而不是 `Shizuku.newProcess`**：
 * api 13.1.5 已把 `Shizuku.newProcess` 从公共 API 移除（实测 `javap` 无此方法，
 * 编译器报「it is private in 'Shizuku'」），公共面只保留 `Shizuku.getBinder()`。
 * 底层 `IShizukuService.newProcess(String[], String[], String)` 仍然存在，
 * 返回 `IRemoteProcess`——它的输入流是 **`ParcelFileDescriptor`**，这一点很关键：
 *
 * - 10 天 `dumpsys usagestats --checkin` 约 13MB 文本；
 * - binder 单次事务上限 1MB，走「UserService 用 AIDL 回传 String」必然被截断或抛
 *   `TransactionTooLargeException`；
 * - 而 `newProcess` 把 stdout 落成 fd，我们按块读，不受事务大小限制。
 *
 * 因此这不是图省事绕公共 API，而是**只有这条路能读完整 13MB**。代价是依赖
 * `moe.shizuku.server.*` 这个内部包：一旦 Shizuku 升级改了 AIDL，这里会在运行时抛
 * 异常 → [PullCoordinator] 自动降级到 UsageStats 通道，不会崩。
 */
object ShizukuShell : ShellRunner {

    /** 主数据源：机器可读格式，毫秒时间戳（§2.2）。 */
    const val CHECKIN_CMD = "dumpsys usagestats --checkin"

    /** 补当天「In-memory daily stats」（今天的数据还没写进磁盘日文件，§2.2）。 */
    const val PLAIN_CMD = "dumpsys usagestats"

    /** 10 天 checkin 约 13MB + 明文 2.4MB，真机实测整段读取在数秒内；给足余量。 */
    const val DEFAULT_TIMEOUT_MS = 120_000L

    /**
     * 以 shell(uid 2000) 身份执行。**纯函数**，单测直接断言命令形态，
     * 防止有人把 `--checkin` 写错（写错只会得到明文，解析器的区间/事件全部落空）。
     */
    fun invocation(command: String): Array<String> = arrayOf("sh", "-c", command)

    override suspend fun exec(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val remote = try {
            start(command)
        } catch (t: Throwable) {
            throw ShizukuExecException("启动 shell 失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        try {
            /**
             * 看门狗是否真的开过枪（评审 P1-3）。
             *
             * 以前这里没有任何痕迹：到点 `destroy()` → `readAll` 捕到异常当 EOF 返回 →
             * `text` 非空 → 不进「无输出」分支 → **半份 dump 被当成成功入库**。
             * 解析器是逐行特征匹配、不要求完整性，所以 13MB 被截断后仍能解析出前 N 天，
             * `pull_log.ok=true`、界面显示「已更新 N 天」，而今天那段数据就这么没了——
             * 并且长得和「今天没怎么用手机」一模一样，是最难查的一类静默丢失。
             *
             * 所以超时必须变成**可见的失败**：协调器会改走降级通道或保留旧数据，
             * 总比悄悄少半天的数据好。
             */
            val truncated = AtomicBoolean(false)
            val text = coroutineScope {
                // 读取是阻塞 IO，取消不掉；用看门狗 destroy 进程让 read 返回 EOF
                val watchdog = launch {
                    delay(timeoutMs)
                    // 先置位再杀进程：read 之后一定能看到这个 true
                    truncated.set(true)
                    runCatching { remote.destroy() }
                }
                val read = readAll(remote)
                watchdog.cancel()
                read
            }
            checkedOutput(text, truncated.get(), command, timeoutMs)
        } finally {
            runCatching { remote.destroy() }
        }
    }

    /** 拿到远端进程句柄。权限不足 / 服务未启动在这里就抛，交给调用方降级。 */
    private fun start(command: String): IRemoteProcess {
        if (!Shizuku.pingBinder()) throw ShizukuExecException("Shizuku 服务未运行")
        val binder = Shizuku.getBinder() ?: throw ShizukuExecException("Shizuku binder 为空")
        val service = IShizukuService.Stub.asInterface(binder)
            ?: throw ShizukuExecException("无法连接 Shizuku 服务")
        // env/dir 传 null = 继承 shell 默认环境与工作目录
        return service.newProcess(invocation(command), null, null)
    }

    /** 13MB 级输出，按块读，别用 readText（会二次拷贝）。 */
    private fun readAll(remote: IRemoteProcess): String {
        val pfd = try {
            remote.getInputStream()
        } catch (t: Throwable) {
            null
        } ?: return ""
        // AutoCloseInputStream 的 close 会连带关闭 pfd，避免 fd 泄漏（每次拉取两个 dump，泄漏很快耗尽）
        val buffer = ByteArrayOutputStream(1 shl 20)
        val chunk = ByteArray(64 * 1024)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
            while (true) {
                val read = try {
                    ins.read(chunk)
                } catch (_: Exception) {
                    -1 // destroy 后读取会抛，视作 EOF
                }
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toString("UTF-8")
    }

    /** Shizuku 服务是否可达（binder 存活）。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本进程 uid（shell 子进程提权前的对照，仅日志用）。 */
    fun appUid(): Int = Process.myUid()
}

/**
 * 「读到的输出能不能当成功」的判定（评审 P1-3）。
 *
 * 从 IO 里拆出来只为一件事：这个分支以前**没有测试也看不出来**，而它正好是
 * 「半份 dump 被当成功」的所在地——`text` 非空就返回，截断完全不留痕。
 * 抽成纯函数后，单测可以直接钉住「非空 + 已截断 ⇒ 必须抛」这条。
 *
 * @param truncated 看门狗是否到点杀过进程（真杀过 = 这次读到的只是前半截）
 */
internal fun checkedOutput(
    text: String,
    truncated: Boolean,
    command: String,
    timeoutMs: Long
): String {
    if (truncated) {
        throw ShizukuExecException("命令执行超时（${timeoutMs}ms），输出已截断：$command")
    }
    if (text.isEmpty()) {
        // 进程被看门狗销毁 / shell 直接退出，都落在这里
        throw ShizukuExecException("命令无输出（疑似超时或 shell 被拒）：$command")
    }
    return text
}
