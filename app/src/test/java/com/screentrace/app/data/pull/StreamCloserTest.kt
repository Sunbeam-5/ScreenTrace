package com.screentrace.app.data.pull

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下拉刷新「转圈不消失」的回归：看门狗必须能**硬打断**阻塞中的 read。
 *
 * `destroy()` 只在 Shizuku 服务端可达时才真的杀进程；服务端已死时它会静默失败，
 * 而 `ins.read()` 是阻塞 IO、不吃协程取消——没有「关流」这条路，`pull()` 会永远挂着。
 */
class StreamCloserTest {

    @Test
    fun `先 bind 后 close 关闭一次`() {
        val closer = StreamCloser()
        var closes = 0
        closer.bind { closes++ }
        closer.close()
        assertEquals(1, closes)
    }

    @Test
    fun `看门狗先开枪时 bind 立刻补一次关闭`() {
        val closer = StreamCloser()
        closer.close() // 超时先到（读线程还没拿到流）
        var closes = 0
        closer.bind { closes++ }
        assertEquals(1, closes)
    }
}
