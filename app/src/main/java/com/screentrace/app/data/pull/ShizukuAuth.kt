package com.screentrace.app.data.pull

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

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
     * @return true = 已发出请求（结果要等 ON_RESUME 或授权结果回调再检测一次）；false = 服务没在跑
     */
    fun request(): Boolean = runCatching {
        if (!isRunning() || hasPermission()) return false
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    /**
     * CR-30 阶段 E（§7-67）：注册 Shizuku binder 到达/断开与授权结果监听，
     * 回调统一切回主线程触发 [onChanged]；返回取消注册句柄。全链路 `runCatching` 兜底。
     */
    fun observeEvents(onChanged: () -> Unit): () -> Unit {
        val mainHandler = Handler(Looper.getMainLooper())
        val dispatch = { mainHandler.post(onChanged); Unit }
        val receivedListener = Shizuku.OnBinderReceivedListener { dispatch() }
        val deadListener = Shizuku.OnBinderDeadListener { dispatch() }
        val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_CODE) dispatch()
        }
        runCatching { Shizuku.addBinderReceivedListener(receivedListener) }
        runCatching { Shizuku.addBinderDeadListener(deadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
        return {
            runCatching { Shizuku.removeBinderReceivedListener(receivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(deadListener) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        }
    }

    /** CR-30 阶段 E：尝试拉起 Shizuku 管理应用以便用户重启服务。 */
    fun launchShizukuApp(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
