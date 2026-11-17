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

/**
 * v5 → v6（CR-27，2026-11-17）：新增 `device_merge_ledger`（跨设备合并的防重复记账）。
 *
 * 只建表结构 + 不搬旧数据：旧账本在 DataStore 里，**读取时两边取并集**即可
 * （`DeviceOperations`），不必在这里写 protobuf 解析；写入只写这张表、与数据同事务。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `device_merge_ledger` (
                `deviceId` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                PRIMARY KEY(`deviceId`, `date`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v6 → v7（CR-28，2026-11-17）：六表加来源维度 `sourceDeviceId`，事件表主键再加
 * 确定性 `seq`（同毫秒多条不丢 + 重复拉取幂等）。
 *
 * SQLite 不能 `ALTER TABLE` 改主键，走「建新表 + 搬行 + 换名」（需求 §4 CR-28）：
 * - **旧数据一行不丢**（铁律）：每张表的行原样搬入，只补新列；
 * - `sourceDeviceId` 全置 `''`（**未归档**）——迁移是纯 SQL、读不到 DataStore 的
 *   deviceId 与合并账本，归属判定留给首启的 [com.screentrace.app.data.SourceArchive]
 *   （账本内日期 → `'__legacy__'`，纯本机日 → 本机 ID）；
 * - 事件 `seq` 置 0（旧行同毫秒早已被旧 PK 折叠成一行，0 即该组第 0 条）；
 * - 新索引名必须与 Room 生成名逐字一致（`index_<表>_<列>`），否则 schema 校验不过。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- day_summary：PK (date, sourceDeviceId)
        db.execSQL(
            """
            CREATE TABLE `day_summary_v7` (
                `date` TEXT NOT NULL,
                `screenMin` INTEGER NOT NULL,
                `screenUnlockMin` INTEGER NOT NULL,
                `unlocks` INTEGER NOT NULL,
                `launches` INTEGER NOT NULL,
                `longestSessionMin` INTEGER NOT NULL,
                `pulledAt` INTEGER NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                PRIMARY KEY(`date`, `sourceDeviceId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `day_summary_v7`
                (`date`, `screenMin`, `screenUnlockMin`, `unlocks`, `launches`, `longestSessionMin`, `pulledAt`, `sourceDeviceId`)
            SELECT `date`, `screenMin`, `screenUnlockMin`, `unlocks`, `launches`, `longestSessionMin`, `pulledAt`, ''
            FROM `day_summary`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `day_summary`")
        db.execSQL("ALTER TABLE `day_summary_v7` RENAME TO `day_summary`")
        db.execSQL(
            "CREATE INDEX `index_day_summary_sourceDeviceId` ON `day_summary` (`sourceDeviceId`)"
        )

        // ---------------- app_usage：PK (date, pkg, sourceDeviceId)
        db.execSQL(
            """
            CREATE TABLE `app_usage_v7` (
                `date` TEXT NOT NULL,
                `pkg` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `minutes` INTEGER NOT NULL,
                `launches` INTEGER NOT NULL,
                `category` TEXT NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                PRIMARY KEY(`date`, `pkg`, `sourceDeviceId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `app_usage_v7`
                (`date`, `pkg`, `name`, `minutes`, `launches`, `category`, `sourceDeviceId`)
            SELECT `date`, `pkg`, `name`, `minutes`, `launches`, `category`, ''
            FROM `app_usage`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `app_usage`")
        db.execSQL("ALTER TABLE `app_usage_v7` RENAME TO `app_usage`")
        db.execSQL("CREATE INDEX `index_app_usage_pkg` ON `app_usage` (`pkg`)")
        db.execSQL(
            "CREATE INDEX `index_app_usage_sourceDeviceId` ON `app_usage` (`sourceDeviceId`)"
        )

        // ---------------- hour_usage：PK (date, hour, sourceDeviceId)
        db.execSQL(
            """
            CREATE TABLE `hour_usage_v7` (
                `date` TEXT NOT NULL,
                `hour` INTEGER NOT NULL,
                `minutes` INTEGER NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                PRIMARY KEY(`date`, `hour`, `sourceDeviceId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `hour_usage_v7` (`date`, `hour`, `minutes`, `sourceDeviceId`)
            SELECT `date`, `hour`, `minutes`, '' FROM `hour_usage`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `hour_usage`")
        db.execSQL("ALTER TABLE `hour_usage_v7` RENAME TO `hour_usage`")
        db.execSQL(
            "CREATE INDEX `index_hour_usage_sourceDeviceId` ON `hour_usage` (`sourceDeviceId`)"
        )

        // ---------------- screen_session：PK (startMs, kind, sourceDeviceId)
        db.execSQL(
            """
            CREATE TABLE `screen_session_v7` (
                `startMs` INTEGER NOT NULL,
                `endMs` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                PRIMARY KEY(`startMs`, `kind`, `sourceDeviceId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `screen_session_v7` (`startMs`, `endMs`, `kind`, `sourceDeviceId`)
            SELECT `startMs`, `endMs`, `kind`, '' FROM `screen_session`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `screen_session`")
        db.execSQL("ALTER TABLE `screen_session_v7` RENAME TO `screen_session`")
        db.execSQL("CREATE INDEX `index_screen_session_endMs` ON `screen_session` (`endMs`)")
        db.execSQL(
            "CREATE INDEX `index_screen_session_sourceDeviceId` ON `screen_session` (`sourceDeviceId`)"
        )

        // ---------------- unlock_event：PK (occurredAtMs, sourceDeviceId, seq)
        db.execSQL(
            """
            CREATE TABLE `unlock_event_v7` (
                `occurredAtMs` INTEGER NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                PRIMARY KEY(`occurredAtMs`, `sourceDeviceId`, `seq`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `unlock_event_v7` (`occurredAtMs`, `sourceDeviceId`, `seq`)
            SELECT `occurredAtMs`, '', 0 FROM `unlock_event`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `unlock_event`")
        db.execSQL("ALTER TABLE `unlock_event_v7` RENAME TO `unlock_event`")

        // ---------------- notification_event：PK (occurredAtMs, type, pkg, sourceDeviceId, seq)
        db.execSQL(
            """
            CREATE TABLE `notification_event_v7` (
                `occurredAtMs` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `pkg` TEXT NOT NULL,
                `channelId` TEXT,
                `sourceDeviceId` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                PRIMARY KEY(`occurredAtMs`, `type`, `pkg`, `sourceDeviceId`, `seq`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `notification_event_v7`
                (`occurredAtMs`, `type`, `pkg`, `channelId`, `sourceDeviceId`, `seq`)
            SELECT `occurredAtMs`, `type`, `pkg`, `channelId`, '', 0 FROM `notification_event`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `notification_event`")
        db.execSQL("ALTER TABLE `notification_event_v7` RENAME TO `notification_event`")
        db.execSQL("CREATE INDEX `index_notification_event_pkg` ON `notification_event` (`pkg`)")
        db.execSQL(
            "CREATE INDEX `index_notification_event_occurredAtMs` ON `notification_event` (`occurredAtMs`)"
        )
    }
}
