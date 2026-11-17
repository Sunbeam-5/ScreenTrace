package com.screentrace.app.data.db

import android.content.Context
import androidx.room.Room
import com.screentrace.app.data.prefs.DeviceIds

/**
 * §4 CR-21 ③：**每台设备一个独立 Room 库**。
 *
 * 需求是「不同设备的数据彼此独立，点切换设备之后整个 App 的数据就变成那台设备的数据」。
 * 两条路可选：
 *
 * 1. 给八张表加 `device_id` 列，所有查询都带上过滤条件；
 * 2. 每台设备一个独立的库文件，靠「当前库指针」决定读哪个。
 *
 * 选 2。八张表的查询散布在仓库层、领域计算器、详情页与排行页上百处，
 * 给每条 SQL 加 `device_id` 过滤意味着改上百条查询 + 写迁移 + 逐个补测试，
 * 出错面比收益大得多；而独立库文件是**物理隔离**——两台设备的数据根本不在一个文件里，
 * 不存在「漏加过滤条件就读串了」的可能。
 *
 * 布局：
 * - `screentrace.db` —— **本机采集库**。文件名沿用升级前的名字，承继全部历史数据；
 *   UsageStats 拉取（[com.screentrace.app.data.IngestPipeline]）**只写它**。
 * - `screentrace-dev-<设备ID>.db` —— **远端设备本地副本库**。只由云端备份恢复写入，
 *   本机采集永远碰不到它。
 *
 * 「当前库」由 AppContainer 用一个内存指针（[currentDeviceId]）+ DataStore 持久化
 * （`SettingsStore.currentDeviceId`）共同决定。
 */
class DeviceDatabases(context: Context) {

    private val appContext = context.applicationContext

    private val deviceDbs = mutableMapOf<String, ScreenTraceDatabase>()

    /** 本机采集库（升级前的历史数据都在这里）。 */
    val local: ScreenTraceDatabase by lazy { openDatabase(appContext, LOCAL_NAME) }

    /**
     * 某台远端设备的本地副本库。同一台设备反复切换时复用同一个实例，
     * 不重复开库（Room 的连接池开着很吃内存）。
     */
    fun forDevice(deviceId: String): ScreenTraceDatabase {
        val safe = DeviceIds.safe(deviceId)
        return deviceDbs.getOrPut(safe) { openDatabase(appContext, deviceName(safe)) }
    }

    /** 切换设备后关掉不再使用的副本库连接（保留本机采集库，它一直在用）。 */
    fun closeDeviceDbs(keep: Set<String> = emptySet()) {
        val kept = keep.map { DeviceIds.safe(it) }.toSet()
        deviceDbs.keys.toList().forEach { safe ->
            if (safe !in kept) {
                runCatching { deviceDbs.remove(safe)?.close() }
            }
        }
    }

    companion object {

        /** 本机采集库文件名（沿用升级前的名字，别改——改了用户历史数据就「消失」了）。 */
        const val LOCAL_NAME = "screentrace.db"

        /** 远端设备副本库文件名。 */
        fun deviceName(safeId: String): String = "screentrace-dev-$safeId.db"

        /** 建库（迁移注册集中在这里，本机库与副本库共用同一套迁移）。 */
        fun openDatabase(context: Context, name: String): ScreenTraceDatabase =
            Room.databaseBuilder(context, ScreenTraceDatabase::class.java, name)
                // v2：+ screen_session（会话区间真并集，批 6g 三轮）
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    // v5（批 8）：+ app_limit
                    MIGRATION_4_5,
                    MIGRATION_5_6
                )
                .build()
    }
}
