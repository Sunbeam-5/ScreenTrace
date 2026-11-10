package com.screentrace.app.data

import android.content.pm.PackageManager

/**
 * §3-8 展示名解析：`PackageManager.getApplicationLabel` 优先，回退内置映射（[com.screentrace.app.data.parse.AppNames]）。
 *
 * 为什么需要：dump 里只有包名，映射表只覆盖对账用的 176 个包；真实 dump 还有十几个
 * 不在表内的（桌面、米家、联通、隐私空间组件等），不做这层回填，排行页会直接显示
 * `com.miui.home` 这种字符串。
 *
 * Android 11+ 受包可见性限制：清单里声明了 `MAIN/LAUNCHER` 的 `<queries>`，
 * 因此「有桌面入口的应用」（屏幕使用时间统计的全是这类）可见；查不到时抛
 * `NameNotFoundException`，这里吞掉并回退，绝不因为求名失败拖垮入库。
 *
 * 结果带缓存：一次拉取要对上百个包求名，`getApplicationInfo` 不便宜。
 * 非线程安全（只在入库事务的同一协程里用），刻意不加锁。
 */
class AppLabelResolver(private val pm: PackageManager) : (String) -> String? {

    private val cache = HashMap<String, String?>()

    /** 拿不到真实标签时返回 null（调用方回退映射表再回退包名）。 */
    override fun invoke(pkg: String): String? = cache.getOrPut(pkg) { load(pkg) }

    private fun load(pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
}
