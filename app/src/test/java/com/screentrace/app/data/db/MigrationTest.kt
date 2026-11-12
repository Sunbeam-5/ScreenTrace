package com.screentrace.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v4 结构的测试专用 Database（= 批 8 之前的七张表，无 `app_limit`）。
 *
 * 不手写 DDL：由 Room 按 Entity 生成建表 SQL，与真实 v4 schema（`app/schemas/.../4.json`
 * 同源导出）零漂移。`exportSchema = false` 避免测试类污染 schema 导出目录。
 */
@Database(
    entities = [
        DaySummaryEntity::class,
        AppUsageEntity::class,
        HourUsageEntity::class,
        PullLogEntity::class,
        UnlockEventEntity::class,
        NotificationEventEntity::class,
        ScreenSessionEntity::class
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
 * [ScreenTraceDatabase]（version 5）+ [MIGRATION_4_5] 打开同一文件——
 * Room 执行迁移后会做**完整 schema 校验**（与 5.json 期望比对表/列/索引），
 * 迁移 SQL 差一个索引名都会在这里抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private val dbName = "migration-test.db"

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

        // ② v5 打开触发 MIGRATION_4_5；Room 同时校验迁移后结构与 5.json 一致
        val v5 = Room.databaseBuilder(context, ScreenTraceDatabase::class.java, dbName)
            .addMigrations(MIGRATION_4_5)
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

        v5.close()
    }
}
