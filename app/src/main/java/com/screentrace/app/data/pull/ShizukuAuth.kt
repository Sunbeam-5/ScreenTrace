package com.screentrace.app.data.pull

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * §5.8「权限与数据」组：Shizuku 授权的 UI 侧入口。
 *
 * 为什么单独抽出来：`Shizuku.requestPermission` 必须在**有 Activity 的时候**调，
 * 而且 Shizuku 服务可能随时死掉（用户手动停服务、系统回收）。所有调用都吞异常并
 * 返回布尔值，UI 只按「成了 / 没成」给一句人话提示，绝不让 binder 异常冒到界面。
 */
object ShizukuAuth {

    /** 与 [ShizukuUsageProvider] 的授权请求码不冲突即可（同一个 App 内唯一）。 */
    private const val REQUEST_CODE = 4231

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        if (!isRunning()) return false
        if (Shizuku.isPreV11()) {
            // v11 之前走普通 runtime 权限，没有 checkSelfPermission，交给状态探测去查
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false)

    /**
     * 拉起 Shizuku 授权弹窗。
     * @return true = 已发出请求（结果要等 ON_RESUME 再检测一次）；false = 服务没在跑
     */
    fun request(): Boolean = runCatching {
        if (!isRunning() || hasPermission()) return false
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)
}
