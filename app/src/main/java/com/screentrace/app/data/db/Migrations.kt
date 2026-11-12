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

/**
 * v2 → v3（批 6g，2026-11-11）：清空 `app_usage`。
 *
 * 起因是真机对账：米家 8h27 + 屏迹 4h36 + 桌面 28min = 13h31 超过了屏幕总时长 11h09。
 * 追下去是 MIUI 的 `totalTimeUsed` 把后台常驻、画中画、跨天累计都算进了日块（同一个包
 * 在不同区间里出现过 148h、914h），而入库合并策略是「同 (date,pkg) 取较大值」——
 * 旧的虚高值永远不会被新的正确值覆盖。
 *
 * 所以这里把历史应用行整体丢弃：下次拉取按新口径（事件流优先 + 总量约束）重建。
 * 代价是应用构成要等下一次拉取才有数（约 10 分钟节流窗口），换来的是不再显示一组
 * 加起来比屏幕还长的数字。屏幕时长/解锁/通知不受影响，它们走的是另一套事件。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `app_usage`")
    }
}

/**
 * v3 → v4（批 6g，2026-11-11）：清空 `hour_usage`。
 *
 * 同 MIGRATION_2_3 的处境：旧算法的时段分布只从 SCREEN 事件切分，而屏幕时长后来通过
 * 「应用前台区间并集」补全了，两套数字对不上（真机：7 天时段合计 30 小时、屏幕合计
 * 103 小时）。合并策略是逐桶取大，旧的低值会一直留着，只能整体重建。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `hour_usage`")
    }
}

/**
 * v4 → v5（批 8 第一步，2026-11-12）：新增 `app_limit`（§4 / §5.12 CR-04）。
 *
 * 只建表结构，限额业务（CRUD、到量提醒、排行页进度条）在批 11。先在本批建表的原因：
 * 备份 `schemaVersion=4` 必须包含八张表，要是等批 11 再建，备份结构又得涨一版，
 * 已经导出的文件全部要写兼容分支。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_limit` (
                `pkg` TEXT NOT NULL,
                `limitMin` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                PRIMARY KEY(`pkg`)
            )
            """.trimIndent()
        )
    }
}
