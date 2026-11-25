package com.screentrace.app.data.pull

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** TD-04：Shizuku AIDL 兼容性分类（纯枚举，无 Android 依赖）。 */
enum class ShizukuAidlCompatibility {
    COMPATIBLE,
    INCOMPATIBLE_API,
    BINDER_UNAVAILABLE
}

/**
 * TD-04：将 Shizuku 启动/读取期抛出的异常分类为「AIDL 版本不兼容」或「普通服务/Binder 不可用」。
 * 链接期错误（LinkageError / ClassNotFoundException / NoSuchMethodException）必须单独识别，
 * 以便降级并在「关于与诊断」给出可定位的兼容提示，而非吞成笼统失败或让进程崩溃。
 */
fun classifyAidlError(error: Throwable): ShizukuAidlCompatibility = when {
    error is LinkageError ||
        error is ClassNotFoundException ||
        error is NoSuchMethodException -> ShizukuAidlCompatibility.INCOMPATIBLE_API
    error.cause != null && error.cause !== error &&
        classifyAidlError(error.cause!!) == ShizukuAidlCompatibility.INCOMPATIBLE_API ->
        ShizukuAidlCompatibility.INCOMPATIBLE_API
    else -> ShizukuAidlCompatibility.BINDER_UNAVAILABLE
}

/** 将 `moe.shizuku.server.IRemoteProcess` 隔离在接口后方，避免 `ShizukuShell` 签名耦合内部包。 */
internal interface RemoteProcessHandle {
    fun inputStreamPfd(): ParcelFileDescriptor?
    fun destroy()
}

internal interface ShizukuAidlBridge {
    fun startProcess(command: Array<String>): RemoteProcessHandle
}

/**
 * 唯一允许直接引用 `moe.shizuku.server.*` 的适配层。
 * 即使未来 Shizuku 升级移除或重命名该包，错误也会被局限在调用本适配层时触发并被上层捕获降级。
 */
internal object ShizukuV13AidlAdapter : ShizukuAidlBridge {
    override fun startProcess(command: Array<String>): RemoteProcessHandle {
        if (!Shizuku.pingBinder()) throw ShizukuExecException("Shizuku 服务未运行")
        val binder = Shizuku.getBinder() ?: throw ShizukuExecException("Shizuku binder 为空")
        val service = IShizukuService.Stub.asInterface(binder)
            ?: throw ShizukuExecException("无法连接 Shizuku 服务")
        val remote: IRemoteProcess = service.newProcess(command, null, null)
            ?: throw ShizukuExecException("Shizuku 未返回远端进程句柄")
        return object : RemoteProcessHandle {
            override fun inputStreamPfd(): ParcelFileDescriptor? = remote.getInputStream()
            override fun destroy() {
                remote.destroy()
            }
        }
    }
}
