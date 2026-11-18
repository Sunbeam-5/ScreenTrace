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

    inline fun <T> measure(tag: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val t0 = System.nanoTime()
        val result = block()
        Log.d("CR30Probe", "$tag=${(System.nanoTime() - t0) / 1000}us")
        return result
    }

    private val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** 重组计数：埋在页面根组合函数体首行，每次组合/重组自增并打日志（tag 形如 `Compose/Today`）。 */
    fun tick(tag: String) {
        if (!BuildConfig.DEBUG) return
        val n = counters.getOrPut(tag) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        Log.d("CR30Probe", "$tag#$n")
    }

    /** 一次性事件打点（tag 形如 `Pager/scrollStart`），用日志时间戳算动画完成时间。 */
    fun event(tag: String) {
        if (!BuildConfig.DEBUG) return
        Log.d("CR30Probe", tag)
    }
}
