package com.screentrace.app.uikit

import android.util.Log
import com.screentrace.app.BuildConfig

/**
 * CR-30 阶段 A-1：性能测量埋点（debug-only，release 零开销 no-op）。
 *
 * 只做计时打点（logcat tag `CR30Probe`），不参与任何业务逻辑。基线帧数据用
 * **干净构建**测得（dumpsys gfxinfo，不含本埋点）；本埋点仅用于内部耗时占比
 * （Room 查询 / 图表计算 / 壁纸解码 / 数据加载段），两类数据交叉印证。
 */
object PerfProbe {

    @PublishedApi
    internal fun logD(msg: String) {
        try {
            Log.d("CR30Probe", msg)
        } catch (_: RuntimeException) {
            // 纯 JVM 单测环境无 android.util.Log 实现；仅跳过日志输出，不影响业务与测试
        }
    }

    inline fun <T> measure(tag: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val t0 = System.nanoTime()
        val result = block()
        logD("$tag=${(System.nanoTime() - t0) / 1000}us")
        return result
    }

    private val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** 重组计数：埋在页面根组合函数体首行，每次组合/重组自增并打日志（tag 形如 `Compose/Today`）。 */
    fun tick(tag: String) {
        if (!BuildConfig.DEBUG) return
        val n = counters.getOrPut(tag) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        logD("$tag#$n")
    }

    /** 一次性事件打点（tag 形如 `Pager/scrollStart`），用日志时间戳算动画完成时间。 */
    fun event(tag: String) {
        if (!BuildConfig.DEBUG) return
        logD(tag)
    }

    // ---- A-5 因果实验探针（R2 修订：计数器与字符串构造全部限制在 Debug）----

    /** 任务序号源：lazy 分配，Release 下不创建、不计数，恒返回 0。 */
    private val a5Seq by lazy { java.util.concurrent.atomic.AtomicInteger(0) }

    fun nextSeq(): Int {
        if (!BuildConfig.DEBUG) return 0
        return a5Seq.incrementAndGet()
    }

    /** A5 事件：字符串拼接在 Debug 守卫之后，Release 零分配。 */
    fun a5(kind: String, target: Int, seq: Int) {
        if (!BuildConfig.DEBUG) return
        logD("A5/" + kind + "=" + target + "#" + seq)
    }

    /** A5 滚动收尾（含 TabScrollGuard.end 结果）。 */
    fun a5Finally(target: Int, seq: Int, aligned: Boolean) {
        if (!BuildConfig.DEBUG) return
        logD("A5/scrollFinally=" + target + "#" + seq + " aligned=" + aligned)
    }

    /** OBS-2：滚动取消（携带观测原因与实际动作，前缀与旧版兼容）。 */
    fun a5Cancel(target: Int, seq: Int, cause: String, act: String) {
        if (!BuildConfig.DEBUG) return
        logD("A5/scrollCancel=" + target + "#" + seq + ":" + cause + " act=" + act)
    }

    /** OBS-2：滚动收尾扩展打点（保持 aligned= 语义不变，追加 cause/act/stable/settledMatch）。 */
    fun a5Finally(
        target: Int,
        seq: Int,
        aligned: Boolean,
        cause: String,
        act: String,
        stable: Boolean,
        settledMatch: Boolean
    ) {
        if (!BuildConfig.DEBUG) return
        logD(
            "A5/scrollFinally=" + target + "#" + seq +
                " aligned=" + aligned +
                " owned=" + aligned +
                " cause=" + cause +
                " act=" + act +
                " stable=" + stable +
                " settledMatch=" + settledMatch
        )
    }

    /** OBS-2：对齐器/收尾跳过对齐原因。 */
    fun a5Skip(target: Int, seq: Int, reason: String) {
        if (!BuildConfig.DEBUG) return
        logD("A5/alignSkip=" + target + "#" + seq + ":" + reason)
    }
}
