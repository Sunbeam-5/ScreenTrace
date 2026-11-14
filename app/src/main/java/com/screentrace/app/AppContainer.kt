package com.screentrace.app

import android.content.Context
import androidx.room.Room
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.AppLabelResolver
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.pull.PullCoordinator
import com.screentrace.app.data.pull.ShizukuUsageProvider
import com.screentrace.app.data.pull.UsageStatsUsageProvider
import com.screentrace.app.data.pull.asPullResult
import kotlinx.coroutines.flow.first

/**
 * 手工依赖容器（§6 依赖白名单不含 Hilt/Dagger，一个 Application 级单例足够）。
 *
 * 批 6b 的接线点：`repository` 从 `EmptyUsageRepository` 换成 [RoomUsageRepository]，
 * 页面不用改一行（UI 只依赖 `contract/UsageRepository`）。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val db: ScreenTraceDatabase = Room.databaseBuilder(
        appContext,
        ScreenTraceDatabase::class.java,
        "screentrace.db"
    )
        // v2：+ screen_session（会话区间真并集，批 6g 三轮）
        .addMigrations(
            com.screentrace.app.data.db.MIGRATION_1_2,
            com.screentrace.app.data.db.MIGRATION_2_3,
            com.screentrace.app.data.db.MIGRATION_3_4,
            // v5（批 8）：+ app_limit
            com.screentrace.app.data.db.MIGRATION_4_5
        )
        .build()

    val settings = SettingsStore(appContext)

    /**
     * §3-8 展示名：PackageManager 标签优先。同一个实例同时给入库管道与仓库读取层用，
     * 缓存共享——入库时算过的包，读取时不用再查一次 PackageManager。
     */
    private val labels = AppLabelResolver(appContext.packageManager)

    /** 入库时写库（之后列表/搜索/详情都用真实名字）。 */
    val pipeline = IngestPipeline(db, labels)

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

    /** 批 8：本地备份导出 / 恢复（JSON、CSV）。 */
    val backup = com.screentrace.app.data.backup.BackupRepository(db = db, settings = settings)

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
        com.screentrace.app.data.sync.SyncManager(provider, backup, settings)
    }

    val repository: UsageRepository = RoomUsageRepository(
        db = db,
        settings = settings,
        // 设置页「立即拉取」（批 7 接 UI）：走同一条协调器，不另建通道
        pull = { pullCoordinator.pull().asPullResult() },
        // 读取层兜底：只在库里没存到名字时才真正去查
        labelResolver = labels
    )
}
