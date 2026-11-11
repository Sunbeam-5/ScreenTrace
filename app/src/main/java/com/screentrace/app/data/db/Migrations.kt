package com.screentrace.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移（v1 → v2，批 6g 三轮）：新增 `screen_session` 表（会话区间真并集）。
 * 只增表、不动任何 v1 结构——CR-01 红线（原六表 schema 不得删除或改义）满足。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `screen_session` (
                `startMs` INTEGER NOT NULL,
                `endMs` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                PRIMARY KEY(`startMs`, `kind`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_screen_session_endMs` ON `screen_session` (`endMs`)"
        )
    }
}
