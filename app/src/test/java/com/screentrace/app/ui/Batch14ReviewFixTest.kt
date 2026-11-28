package com.screentrace.app.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.BackupSettings
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.export.PngExportModule
import com.screentrace.app.data.export.PngExportRange
import com.screentrace.app.data.export.PngPosterData
import com.screentrace.app.data.export.SharePosterGuard
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.ThemeMode
import com.screentrace.app.uikit.TrendSelectionMath
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Context.testThemeMigrationStore by preferencesDataStore(name = "test_theme_migration_prefs")

/**
 * GPT Review 退回项（P2-1 ~ P2-4）+ 切 Tab 震动修复回归测试，覆盖 M-R14-1 ~ M-R14-5。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Batch14ReviewFixTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ---------- M-R14-1（P2-1）：31 天可滚动柱图真实 12dp 槽宽 + 横向滚动偏移跟手命中 ----------

    @Test
    fun `M-R14-1_31天可滚动柱图使用实际12dp槽宽并叠加滚动偏移精确命中`() {
        // 31 天月趋势，3x 密度下实际槽宽 12dp = 36px（绝非 16dp = 48px）
        val slotPx = 36f
        val viewportPx = 900f
        val count = 31

        // 1) 未滚动（scrollOffsetPx = 0）：手指在 370px 处 -> floor(370 / 36) = 10（若误用 48px 会错算为 7）
        assertEquals(
            10,
            TrendSelectionMath.indexForDrag(
                localX = 370f,
                scrollOffsetPx = 0f,
                viewportWidthPx = viewportPx,
                scrollable = true,
                scrollableSlotPx = slotPx,
                count = count
            )
        )

        // 2) 横向向右滚动 216px（滚过 6 根柱子）：手指在视口左缘 18px 处应命中 index = 6（第 7 天），而非 index = 0
        assertEquals(
            6,
            TrendSelectionMath.indexForDrag(
                localX = 18f,
                scrollOffsetPx = 216f,
                viewportWidthPx = viewportPx,
                scrollable = true,
                scrollableSlotPx = slotPx,
                count = count
            )
        )

        // 3) 滚到底部附近（scrollOffsetPx = 216px，localX = 880px -> contentX = 1096 -> floor(1096/36) = 30）
        assertEquals(
            30,
            TrendSelectionMath.indexForDrag(
                localX = 880f,
                scrollOffsetPx = 216f,
                viewportWidthPx = viewportPx,
                scrollable = true,
                scrollableSlotPx = slotPx,
                count = count
            )
        )

        // 4) 不可滚动 7 天图：忽略 scrollOffsetPx，按 viewportWidthPx / 7 均分
        assertEquals(
            3,
            TrendSelectionMath.indexForDrag(
                localX = 350f,
                scrollOffsetPx = 999f,
                viewportWidthPx = 700f,
                scrollable = false,
                scrollableSlotPx = slotPx,
                count = 7
            )
        )
    }

    // ---------- M-R14-2（P2-2）：旧版 DataStore DARK_THEME=false 迁移为 LIGHT，而非 SYSTEM ----------

    @Test
    fun `M-R14-2_旧版DataStore迁移严格区分DARK_THEME不存在与显式false`() = runBlocking {
        val ds = context.testThemeMigrationStore
        val store = SettingsStore(ds)
        val darkKey = booleanPreferencesKey("dark_theme")

        // 场景 1：两个键均不存在（全新安装）-> SYSTEM
        ds.edit { it.clear() }
        assertEquals(ThemeMode.SYSTEM, store.themeMode.first())

        // 场景 2：没有 THEME_MODE，旧值 DARK_THEME = true -> DARK
        ds.edit {
            it.clear()
            it[darkKey] = true
        }
        assertEquals(ThemeMode.DARK, store.themeMode.first())

        // 场景 3：没有 THEME_MODE，旧值 DARK_THEME = false（用户曾明确选浅色）-> LIGHT（绝非 SYSTEM）
        ds.edit {
            it.clear()
            it[darkKey] = false
        }
        assertEquals(ThemeMode.LIGHT, store.themeMode.first())
    }

    // ---------- M-R14-3（P2-3）：备份导出与恢复完整保留 SYSTEM / LIGHT / DARK 及兼容旧备份 ----------

    @Test
    fun `M-R14-3_备份导出与恢复无损保留SYSTEM_LIGHT_DARK并兼容旧备份`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries()
            .setTransactionExecutor(executor)
            .setQueryExecutor(executor)
            .build()
        val ds = context.testThemeMigrationStore
        val store = SettingsStore(ds)
        val repo = BackupRepository(db = db, settings = store, sourceDeviceId = { "dev-a" })

        try {
            // 1) 三态往返：SYSTEM / LIGHT / DARK 导出后再恢复，必须与导出前完全一致
            for (mode in listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)) {
                ds.edit { it.clear() }
                store.setThemeMode(mode)
                val (_, json) = repo.exportJson(appVersion = "1.0.0")
                val decoded = BackupCodec.decode(json) as BackupCodec.DecodeResult.Ok
                assertEquals("导出备份必须携带精确 themeMode=$mode", mode.name, decoded.file.settings?.themeMode)

                // 先故意切成另一种模式，再恢复设置验证还原
                val other = if (mode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                store.setThemeMode(other)
                repo.restore(decoded.file, strategy = BackupRepository.Strategy.OVERWRITE, importSettings = true)
                assertEquals("恢复后 themeMode 必须还原为 $mode", mode, store.themeMode.first())
            }

            // 2) 旧版备份（无 themeMode，仅有 darkTheme = false）必须恢复为 LIGHT
            store.setThemeMode(ThemeMode.SYSTEM)
            val legacyBackup = BackupFile(
                schemaVersion = 7,
                exportedAt = "2026-11-27T12:00:00Z",
                appVersion = "0.9.0",
                device = BackupDevice(id = "old-dev", nickname = "Old", model = "Old"),
                settings = BackupSettings(darkTheme = false, themeMode = null)
            )
            repo.restore(legacyBackup, strategy = BackupRepository.Strategy.OVERWRITE, importSettings = true)
            assertEquals("旧备份 darkTheme=false 必须恢复为 LIGHT", ThemeMode.LIGHT, store.themeMode.first())
        } finally {
            db.close()
            executor.shutdownNow()
        }
    }

    // ---------- M-R14-4（P2-4）：PNG 预览切周期/加载中/失败时严禁展示或导出旧周期数据 ----------

    @Test
    fun `M-R14-4_PNG预览切换周期加载中与失败态均禁止导出旧周期数据`() {
        val day = LocalDate.of(2026, 11, 27)
        val data7 = PngPosterData(
            range = PngExportRange.LAST_7_DAYS,
            selection = DateSelection.lastDays(7, day),
            rangeLabel = "近7天",
            exportedAt = "2026-11-27 10:00",
            appVersion = "1.0.0",
            summary = Summary(DateSelection.lastDays(7, day), 7, 7, true, 1400, 200, 20, 60),
            dailyGoalMin = 240,
            trendDaily = emptyList(),
            trendTitle = "日均亮屏",
            trendAverageMin = 200,
            apps = emptyList(),
            topAppsPerDay = emptyMap(),
            hours = emptyList(),
            periods = listOf(50, 50, 50, 50),
            periodSubtitle = "日均",
            unlock = null,
            notification = null,
            yearMonth = YearMonth.from(day),
            monthLevels = emptyList(),
            today = day
        )
        val modules = PngExportRange.LAST_7_DAYS.defaultModules()

        // 1) 7 天加载完成：允许展示与导出
        assertNotNull(SharePosterGuard.matchedData(PngExportRange.LAST_7_DAYS, data7))
        assertTrue(
            SharePosterGuard.canExport(
                loading = false,
                exporting = false,
                activeRange = PngExportRange.LAST_7_DAYS,
                loadedData = data7,
                selectedModules = modules
            )
        )

        // 2) 快速切到 30 天（仍在加载或仍残留旧 7 天数据）：必须隐藏旧数据且禁止导出
        assertNull(
            "当前选 30 天但数据是 7 天时，不得返回旧数据",
            SharePosterGuard.matchedData(PngExportRange.LAST_30_DAYS, data7)
        )
        assertFalse(
            "切换周期后即使 loading=false，只要 range 不匹配就严禁导出旧数据",
            SharePosterGuard.canExport(
                loading = false,
                exporting = false,
                activeRange = PngExportRange.LAST_30_DAYS,
                loadedData = data7,
                selectedModules = modules
            )
        )
        assertFalse(
            "加载期间必须禁用保存和分享按钮",
            SharePosterGuard.canExport(
                loading = true,
                exporting = false,
                activeRange = PngExportRange.LAST_7_DAYS,
                loadedData = data7,
                selectedModules = modules
            )
        )

        // 3) 加载失败（loadedData == null）：严禁导出
        assertFalse(
            SharePosterGuard.canExport(
                loading = false,
                exporting = false,
                activeRange = PngExportRange.LAST_30_DAYS,
                loadedData = null,
                selectedModules = modules
            )
        )
    }

    // ---------- M-R14-5（P2-4）：buildPngPosterData 不得吞掉 CancellationException ----------

    @Test
    fun `M-R14-5_buildPngPosterData向上透传CancellationException绝不吞掉协程取消`() = runTest {
        val cancellingRepo = object : UsageRepository by EmptyUsageRepository() {
            override suspend fun summary(selection: DateSelection): Summary {
                throw CancellationException("simulated range switch cancel")
            }
        }
        val vm = AppViewModel(repository = cancellingRepo, todayProvider = { LocalDate.of(2026, 11, 27) })
        try {
            vm.buildPngPosterData(range = PngExportRange.LAST_30_DAYS, appVersion = "1.0.0")
            fail("CancellationException 必须向上抛出，不能被 runCatching 吞成 null")
        } catch (_: CancellationException) {
            // 预期行为
        }
    }
}
