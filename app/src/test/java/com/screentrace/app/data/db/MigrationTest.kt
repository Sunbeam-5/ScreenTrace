package com.screentrace.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.SourceArchive
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ---------------------------------------------------------------- v4 形态实体
//
// CR-28 后主实体带 `sourceDeviceId` / `seq`，直接拿来生成「v4 建表 DDL」会把迁移
// 测试变成「v7 形状拷贝到 v7 形状」的假绿。这里按真实 v4 schema（与
// `app/schemas/.../4.json` 同源）复刻**旧形态**实体，仅供 [MigrationTestV4Database]
// 生成建表 SQL；迁移本身仍走真实的 [MIGRATION_4_5] → [MIGRATION_6_7]。

@Entity(tableName = "day_summary")
data class V4DaySummaryEntity(
    @PrimaryKey val date: String,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long
)

@Entity(tableName = "app_usage", primaryKeys = ["date", "pkg"])
data class V4AppUsageEntity(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String
)

@Entity(tableName = "hour_usage", primaryKeys = ["date", "hour"])
data class V4HourUsageEntity(
    val date: String,
    val hour: Int,
    val minutes: Int
)

@Entity(tableName = "unlock_event")
data class V4UnlockEventEntity(
    @PrimaryKey val occurredAtMs: Long
)

@Entity(
    tableName = "notification_event",
    primaryKeys = ["occurredAtMs", "type", "pkg"],
    indices = [Index("pkg"), Index("occurredAtMs")]
)
data class V4NotificationEventEntity(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String?
)

@Entity(tableName = "screen_session", primaryKeys = ["startMs", "kind"], indices = [Index("endMs")])
data class V4ScreenSessionEntity(
    val startMs: Long,
    val endMs: Long,
    val kind: String
)

@Entity(tableName = "pull_log")
data class V4PullLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pulledAt: Long,
    val source: String,
    val ok: Boolean,
    val note: String?
)

/**
 * v4 结构的测试专用 Database（= 批 8 之前的七张表，无 `app_limit` / `device_merge_ledger`，
 * 六表均为 CR-28 之前的旧形态：无 `sourceDeviceId` / `seq`；pull_log 无 v9 `collectionStatus`）。
 */
@Database(
    entities = [
        V4DaySummaryEntity::class,
        V4AppUsageEntity::class,
        V4HourUsageEntity::class,
        V4PullLogEntity::class,
        V4UnlockEventEntity::class,
        V4NotificationEventEntity::class,
        V4ScreenSessionEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MigrationTestV4Database : RoomDatabase()

/**
 * 评审 P1-3：迁移零测试是危险路径无验收物——迁移写错时 Room 在用户升级后
 * 首次打开数据库就抛 `IllegalStateException`，App 直接起不来。
 *
 * 方式：先用 [MigrationTestV4Database] 建 v4 库并写入数据，再用
 * [ScreenTraceDatabase]（version 7）+ [MIGRATION_4_5]…[MIGRATION_6_7] 打开同一文件——
 * Room 执行迁移后会做**完整 schema 校验**（与 7.json 期望比对表/列/索引），
 * 迁移 SQL 差一个索引名都会在这里抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private val dbName = "migration-test.db"

    private fun msOf(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `MIGRATION_4_5 建 app_limit 表且旧数据完好`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getDatabasePath(dbName).delete()

        // ① 建 v4 库并写入一行日聚合
        val v4 = Room.databaseBuilder(context, MigrationTestV4Database::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        // 物理列名是 camelCase（v1 起的既成实现，CSV/JSON 对外格式均为 §4 的 snake_case；
        // 偏差登记见计划文档 R9）
        v4.openHelper.writableDatabase.execSQL(
            "INSERT INTO day_summary (date, screenMin, screenUnlockMin, unlocks, launches, longestSessionMin, pulledAt) " +
                "VALUES ('2026-11-10', 100, 90, 5, 5, 30, 1700000000000)"
        )
        v4.close()

        // ② v5 打开触发 MIGRATION_4_5；Room 同时校验迁移后结构与 7.json 一致
        val v5 = Room.databaseBuilder(context, ScreenTraceDatabase::class.java, dbName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        // ③ app_limit 可插可查
        v5.appLimitDao().upsert(AppLimitEntity("com.a", 60, true))
        val limits = v5.appLimitDao().getAll()
        assertEquals(1, limits.size)
        assertEquals("com.a", limits[0].pkg)
        assertEquals(60, limits[0].limitMin)

        // ④ v4 数据完好
        val day = v5.daySummaryDao().get("2026-11-10")
        assertEquals(90, day?.screenUnlockMin)
        assertEquals(5, day?.unlocks)

        // ⑤ app_limit 在 v5 里有主键约束：同 pkg upsert 不重复
        v5.appLimitDao().upsert(AppLimitEntity("com.a", 30, true))
        assertEquals(1, v5.appLimitDao().getAll().size)
        assertTrue(v5.appLimitDao().getAll()[0].limitMin == 30)

        // ⑥ CR-27：device_merge_ledger 在 v6 里可写可读，且联合主键去重
        v5.deviceMergeLedgerDao().insert(
            listOf(
                DeviceMergeLedgerEntity("dev-1", "2026-11-10"),
                DeviceMergeLedgerEntity("dev-1", "2026-11-10"),   // 同键重复
                DeviceMergeLedgerEntity("dev-1", "2026-11-11")
            )
        )
        assertEquals(2, v5.deviceMergeLedgerDao().count())
        assertEquals(listOf("2026-11-10", "2026-11-11"), v5.deviceMergeLedgerDao().datesOf("dev-1").sorted())

        v5.close()
    }

    /**
     * CR-28 T7：迁移保真——v4 → v7 **旧行一行不丢**，全部先落「未归档」（`''`），
     * 再由 [SourceArchive] 按「合并账本内 → `'__legacy__'`、纯本机日 → 本机 ID」归档；
     * 归档后逐来源合计 == 迁移前总量（不凭空增减）。
     */
    @Test
    fun `MIGRATION_6_7 旧数据一行不丢且归档正确`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getDatabasePath(dbName).delete()

        val dMerged = "2026-11-10"   // 历史混合日（合并账本内）
        val dPure = "2026-11-11"     // 纯本机日

        // ① v4 库：六表各写数据（含跨两日的事件/会话）
        val v4 = Room.databaseBuilder(context, MigrationTestV4Database::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val sql = v4.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO day_summary (date, screenMin, screenUnlockMin, unlocks, launches, longestSessionMin, pulledAt) VALUES " +
                "('$dMerged', 240, 200, 10, 20, 60, 1700000000000), " +
                "('$dPure', 120, 110, 6, 12, 40, 1700001000000)"
        )
        sql.execSQL(
            "INSERT INTO app_usage (date, pkg, name, minutes, launches, category) VALUES " +
                "('$dMerged', 'com.a', 'A', 100, 5, 'OTHER'), " +
                "('$dPure', 'com.a', 'A', 50, 3, 'OTHER')"
        )
        sql.execSQL(
            "INSERT INTO hour_usage (date, hour, minutes) VALUES " +
                "('$dMerged', 9, 30), ('$dPure', 21, 25)"
        )
        sql.execSQL(
            "INSERT INTO unlock_event (occurredAtMs) VALUES " +
                "(${msOf(LocalDate.parse(dMerged), 9)}), (${msOf(LocalDate.parse(dPure), 21)})"
        )
        sql.execSQL(
            "INSERT INTO notification_event (occurredAtMs, type, pkg, channelId) VALUES " +
                "(${msOf(LocalDate.parse(dMerged), 10)}, 'INTERRUPTION', 'com.a', 'ch'), " +
                "(${msOf(LocalDate.parse(dPure), 22)}, 'SEEN', 'com.a', 'ch')"
        )
        sql.execSQL(
            "INSERT INTO screen_session (startMs, endMs, kind) VALUES " +
                "(${msOf(LocalDate.parse(dMerged), 8)}, ${msOf(LocalDate.parse(dMerged), 9)}, 'SCREEN'), " +
                "(${msOf(LocalDate.parse(dPure), 20)}, ${msOf(LocalDate.parse(dPure), 21)}, 'SCREEN')"
        )
        v4.close()

        // ② v7 打开：触发 4_5 / 5_6 / 6_7，Room 对照 7.json 完整 schema 校验
        val v7 = Room.databaseBuilder(context, ScreenTraceDatabase::class.java, dbName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        // ②b 索引显式核对（真机踩坑补洞：Robolectric 的 schema 校验对索引不敏感——
        // 迁移建了索引而实体没声明时测试假绿、真机 App 起不来。这里直接查 sqlite_master。）
        val indexNames = mutableSetOf<String>()
        v7.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'")
            .use { c -> while (c.moveToNext()) indexNames.add(c.getString(0)) }
        assertEquals(
            setOf(
                "index_day_summary_sourceDeviceId",
                "index_app_usage_pkg",
                "index_app_usage_sourceDeviceId",
                "index_hour_usage_sourceDeviceId",
                "index_screen_session_endMs",
                "index_screen_session_sourceDeviceId",
                "index_notification_event_pkg",
                "index_notification_event_occurredAtMs",
                "index_screen_evidence_endMs",
                "index_screen_evidence_sourceDeviceId",
                "index_usage_window_pkg",
                "index_usage_window_sourceDeviceId"
            ),
            indexNames
        )

        // R11 v8->v9：迁移后旧行新质量列必须为 null（UNKNOWN 语义），不得回填成 EXACT
        v7.daySummaryDao().getAll().forEach { assertNull("旧行 screenQuality 必须保持 null", it.screenQuality) }
        v7.appUsageDao().getAll().forEach { assertNull("旧行 minutesQuality 必须保持 null", it.minutesQuality) }
        v7.hourUsageDao().getAll().forEach { assertNull("旧行 quality 必须保持 null", it.quality) }

        // R11 新表可写可读
        v7.screenEvidenceDao().insertIgnore(
            listOf(
                ScreenEvidenceEntity(
                    startMs = 1_700_000_000_000L,
                    endMs = null,
                    kind = "SCREEN",
                    evidenceType = EvidenceTypes.ANCHOR,
                    quality = DataQualityValues.ANCHOR_ONLY,
                    measuredMs = null,
                    sourceDumpAt = 1_700_000_000_500L,
                    sourceDeviceId = "dev-1"
                )
            )
        )
        assertEquals(1, v7.screenEvidenceDao().count())
        // M-FIX6（P2-1 v9->v10）：同窗口不同 sourceDumpAt 的后续 W1 采样不得被 INSERT IGNORE 静默丢弃
        v7.screenEvidenceDao().insertIgnore(
            listOf(
                ScreenEvidenceEntity(
                    startMs = 1_700_000_000_000L,
                    endMs = null,
                    kind = "SCREEN",
                    evidenceType = EvidenceTypes.ANCHOR,
                    quality = DataQualityValues.ANCHOR_ONLY,
                    measuredMs = null,
                    sourceDumpAt = 1_700_000_009_999L,
                    sourceDeviceId = "dev-1"
                )
            )
        )
        assertEquals(2, v7.screenEvidenceDao().count())
        v7.usageWindowDao().insert(
            listOf(
                UsageWindowEntity(
                    startMs = 1_700_000_000_000L,
                    endMs = 1_700_086_400_000L,
                    pkg = "com.a",
                    name = "A",
                    minutes = 60,
                    launches = 2,
                    category = "OTHER",
                    sourceDeviceId = "dev-1"
                )
            )
        )
        assertEquals(1, v7.usageWindowDao().count())

        // ③ 一行不丢 + 全部「未归档」（迁移是纯 SQL，归属判定留给首启归档）
        assertEquals(2, v7.daySummaryDao().getAll().size)
        assertEquals(2, v7.appUsageDao().getAll().size)
        assertEquals(2, v7.hourUsageDao().getAll().size)
        assertEquals(2, v7.unlockEventDao().getAll().size)
        assertEquals(2, v7.notificationEventDao().getAll().size)
        assertEquals(2, v7.screenSessionDao().getAll().size)
        v7.daySummaryDao().getAll().forEach { assertEquals(SourceIds.UNARCHIVED, it.sourceDeviceId) }
        v7.unlockEventDao().getAll().forEach {
            assertEquals(SourceIds.UNARCHIVED, it.sourceDeviceId)
            assertEquals(0, it.seq)
        }
        v7.notificationEventDao().getAll().forEach { assertEquals(SourceIds.UNARCHIVED, it.sourceDeviceId) }
        v7.screenSessionDao().getAll().forEach { assertEquals(SourceIds.UNARCHIVED, it.sourceDeviceId) }

        // ④ 归档：2026-11-10 在合并账本（历史混合）→ '__legacy__'；2026-11-11 → 本机 dev-1
        v7.deviceMergeLedgerDao().insert(listOf(DeviceMergeLedgerEntity("dev-x", dMerged)))
        val moved = SourceArchive.archive(v7, "dev-1", setOf(dMerged))
        assertTrue("应有行被归档", moved >= 12)

        val daysBySource = v7.daySummaryDao().getAll().associate { it.date to it.sourceDeviceId }
        assertEquals(SourceIds.LEGACY, daysBySource[dMerged])
        assertEquals("dev-1", daysBySource[dPure])

        val pureStart = msOf(LocalDate.parse(dPure), 0)
        v7.unlockEventDao().getAll().forEach { row ->
            assertEquals(
                if (row.occurredAtMs >= pureStart) "dev-1" else SourceIds.LEGACY,
                row.sourceDeviceId
            )
        }
        v7.notificationEventDao().getAll().forEach { row ->
            assertEquals(
                if (row.occurredAtMs >= pureStart) "dev-1" else SourceIds.LEGACY,
                row.sourceDeviceId
            )
        }
        v7.screenSessionDao().getAll().forEach { row ->
            assertEquals(
                if (row.startMs >= pureStart) "dev-1" else SourceIds.LEGACY,
                row.sourceDeviceId
            )
        }

        // ⑤ 总量保真：逐来源合计 == 迁移前总量（不凭空增减、不丢行）
        assertEquals(360, v7.daySummaryDao().getAll().sumOf { it.screenMin })
        assertEquals(150, v7.appUsageDao().getAll().sumOf { it.minutes })
        assertEquals(55, v7.hourUsageDao().getAll().sumOf { it.minutes })

        v7.close()
    }
}
