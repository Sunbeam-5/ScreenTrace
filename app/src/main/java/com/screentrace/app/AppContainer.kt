package com.screentrace.app

import android.content.Context
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.AppLabelResolver
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.DeviceDatabases
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.DeviceIds
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.pull.PullCoordinator
import com.screentrace.app.data.pull.ShizukuUsageProvider
import com.screentrace.app.data.pull.UsageStatsUsageProvider
import com.screentrace.app.data.pull.asPullResult
import com.screentrace.app.data.sync.DeviceOperations
import com.screentrace.app.data.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手工依赖容器（§6 依赖白名单不含 Hilt/Dagger，一个 Application 级单例足够）。
 *
 * 批 6b 的接线点：`repository` 从 `EmptyUsageRepository` 换成 [RoomUsageRepository]，
 * 页面不用改一行（UI 只依赖 `contract/UsageRepository`）。
 *
 * CR-21 ③：仓库层多了一根「**当前库**」指针——本机采集库与各远端设备副本库物理隔离，
 * 读哪台设备的数据由 [currentDb] 决定；切换设备时改指针 + 重新加载四页。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)

    /** CR-21 ③：每台设备一个独立 Room 库（本机采集库 + 远端设备副本库）。 */
    val databases = DeviceDatabases(appContext)

    /** 本机采集库：UsageStats 拉取**只写它**。 */
    val db: ScreenTraceDatabase get() = databases.local

    /** 本机 deviceId（首启生成，DataStore 持久化）。 */
    @Volatile
    var localDeviceId: String = ""
        private set

    /** 当前正在查看哪台设备；空串 = 本机。 */
    @Volatile
    var currentDeviceId: String = ""
        private set

    /** §5.4 批 E：自定义壁纸（私有 no_backup 目录，不进 Room / 备份 JSON）。 */
    val wallpaperStore = com.screentrace.app.data.wallpaper.WallpaperStore(appContext)

    /**
     * §3-8 展示名：PackageManager 标签优先。同一个实例同时给入库管道与仓库读取层用，
     * 缓存共享——入库时算过的包，读取时不用再查一次 PackageManager。
     */
    private val labels = AppLabelResolver(appContext.packageManager)

    /** 入库时写库（之后列表/搜索/详情都用真实名字）。CR-28：写行携带本机来源 ID。 */
    val pipeline = IngestPipeline(db, localDeviceId = { settings.getOrCreateDeviceId() }, labelOf = labels)

    /**
     * 通道优先级＝降级顺序（§2.1）：Shizuku 能拿事件流；拿不到（未装/未授权/临时故障）
     * 才退到 UsageStatsManager，且降级通道只提供应用级与时段数据。
     */
    val pullCoordinator = PullCoordinator(
        db = db,
        pipeline = pipeline,
        providers = listOf(
            ShizukuUsageProvider(context = appContext),
            UsageStatsUsageProvider(appContext)
        ),
        // 批 8b-2 自动同步生产接线（§5.10/§7-15）：任何拉取成功（下拉 / 手动重试 /
        // 打开节流拉取 / 每日 Worker）都走这一个钩子，按频率窗口排一次自动上传。
        // 钩子失败被 PullCoordinator.runCatching 吞掉，不影响拉取结果。
        onSuccess = {
            com.screentrace.app.data.sync.AutoSyncScheduler.onPullSuccess(
                context = appContext,
                sync = syncManager,
                settings = settings
            )
        }
    )

    /** 批 8：本地备份导出 / 恢复（JSON、CSV）；批 C：导出顶层带本机 `device`。 */
    val backup: BackupRepository = BackupRepository(
        db = databases.local,
        settings = settings,
        device = { settings.currentDevice() },
        // CR-28：本机库的恢复/合并行归属本机来源
        sourceDeviceId = { settings.getOrCreateDeviceId() }
    )

    /** 批 8b：云同步机密凭据（EncryptedSharedPreferences，§5.10 禁明文）。
     *  lazy：不碰云同步的测试路径不触发 AndroidKeyStore（Robolectric 无此 provider）。 */
    val syncCredentials by lazy { com.screentrace.app.data.sync.SyncCredentials(appContext) }

    /** 批 8b：云同步编排（WebDAV 首个 provider；Google Drive 8b-2）。 */
    val syncManager by lazy {
        val provider = com.screentrace.app.data.sync.WebDavProvider(config = {
            com.screentrace.app.data.sync.WebDavConfig(
                baseUrl = settings.webdavUrl.first(),
                username = settings.webdavUsername.first(),
                password = syncCredentials.webdavPassword ?: "",
                remoteDir = settings.webdavRemoteDir.first()
            )
        })
        com.screentrace.app.data.sync.SyncManager(
            provider = provider,
            backups = backup,
            settings = settings,
            device = { settings.currentDevice() }
        )
    }

    /**
     * CR-21 ②③：切换设备与跨设备合并的编排（两处 UI 入口共用这一份逻辑）。
     */
    val deviceOperations: DeviceOperations by lazy {
        DeviceOperations(
            sync = syncManager,
            localBackups = backup,
            deviceBackups = { id ->
                BackupRepository(
                    db = databases.forDevice(id),
                    settings = settings,
                    device = { settings.currentDevice() },
                    // CR-28：副本库的行归属该副本所属设备（不是本机）
                    sourceDeviceId = { id }
                )
            },
            copyHasData = { id -> copyHasData(id) },
            settings = settings,
            onSwitch = { id -> setCurrentDevice(id) },
            uploadAfterMerge = { uploadAfterMerge() }
        )
    }

    val repository: UsageRepository = RoomUsageRepository(
        // CR-21 ③：按「当前库」逐次解析——切换设备后读到的是那台设备自己的库
        dbProvider = { currentDb() },
        settings = settings,
        // 设置页「立即拉取」（批 7 接 UI）：走同一条协调器，不另建通道
        pull = { pullCoordinator.pull().asPullResult() },
        // 读取层兜底：只在库里没存到名字时才真正去查
        labelResolver = labels
    )

    init {
        // 指针落 DataStore：进程重启后仍停在上次查看的那台设备。
        // 读盘是异步的，期间一律按「本机」处理——先显示本机数据比空库更诚实。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { settings.getOrCreateDeviceId() }.getOrNull()?.let { localDeviceId = it }
            val saved = runCatching { settings.currentDeviceId.first() }.getOrDefault("")
            if (saved.isNotEmpty()) currentDeviceId = saved

            // CR-28 首启归档（幂等，只处理未归档行）：MIGRATION_6_7 留下的 '' 行按
            // 「合并账本内 → '__legacy__'（历史混合、只读）、纯本机日 → 本机 ID」归档；
            // 账本取 Room ⊕ 旧 DataStore 并集（CR-27 口径，不丢老账）。
            runCatching {
                val localId = localDeviceId.ifEmpty { settings.getOrCreateDeviceId() }
                val legacyDates = db.deviceMergeLedgerDao().allDates().toSet() +
                    settings.mergeLedger.first().values.flatten()
                com.screentrace.app.data.SourceArchive.archive(db, localId, legacyDates)
            }

            // CR-28 批次 F：超 24h 脏行自动清洗（幂等，无脏行不动；快照可回滚、
            // rebuild_log 留痕；按会话并集重算校正，见 SourceRebuild.rebuildOversizedDays）
            runCatching {
                com.screentrace.app.data.SourceRebuild(db)
                    .rebuildOversizedDays(java.io.File(appContext.filesDir, "rebuild_snapshots"))
            }
        }
    }

    /** 当前库：本机采集库，或当前查看的那台远端设备的副本库。 */
    fun currentDb(): ScreenTraceDatabase {
        val current = currentDeviceId
        if (current.isEmpty()) return databases.local
        val local = localDeviceId
        // 本机 ID 还没读到时宁可显示本机，也不要凭空开一个副本库（那会让页面显示错的库）
        if (local.isEmpty()) return databases.local
        if (DeviceIds.safe(current) == DeviceIds.safe(local)) return databases.local
        return databases.forDevice(current)
    }

    /**
     * 切到某台设备：改内存指针 + 落 DataStore。
     *
     * 切回本机时指针存**空串**（「空 = 本机」），避免以后本机 ID 变了还停在自己身上。
     */
    suspend fun setCurrentDevice(deviceId: String) {
        val local = runCatching { settings.getOrCreateDeviceId() }.getOrDefault(localDeviceId)
        if (local.isNotEmpty()) localDeviceId = local
        val isLocal = local.isNotEmpty() && DeviceIds.safe(deviceId) == DeviceIds.safe(local)
        currentDeviceId = if (isLocal) "" else deviceId
        runCatching { settings.setCurrentDeviceId(currentDeviceId) }
    }

    /** 某台设备的本地副本库里是否已经有数据（决定切换时要不要联网下载）。 */
    private suspend fun copyHasData(deviceId: String): Boolean {
        val copy = databases.forDevice(deviceId)
        return copy.daySummaryDao().count() > 0 ||
            copy.appUsageDao().count() > 0 ||
            copy.hourUsageDao().count() > 0
    }

    /**
     * CR-21 ②：合并成功后自动上传一次，让新机的云端备份立刻含旧数据。
     *
     * 上传内容源恒为**本机采集库**（与当前显示哪台设备无关）；失败只提示，
     * 不回滚已完成的本地合并（§5.10）。
     */
    private suspend fun uploadAfterMerge(): Boolean =
        syncManager.uploadManual(appVersion = "app") is SyncResult.Ok
}
