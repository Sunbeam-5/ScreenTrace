package com.screentrace.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-48（CR-22 ②）：识别「来源备份是不是**本机自己**导出的」。
 *
 * 真机踩到：Redmi 上升级前的根目录旧备份其实就是这台机器自己在 11-12 导出的
 * （会话时间戳逐条一致），按累加口径并入后 11-10 的 765 分钟变成 1530 分钟。
 */
class SameDeviceFingerprintTest {

    private fun day(
        date: String,
        screenMin: Int = 60,
        unlockMin: Int = 50,
        unlocks: Int = 5,
        launches: Int = 8,
        longest: Int = 25
    ) = DaySummaryDto(date, screenMin, unlockMin, unlocks, launches, longest, 1_700_000_000_000L)

    private fun file(
        days: List<DaySummaryDto>,
        deviceId: String? = null
    ) = BackupFile(
        schemaVersion = 5,
        exportedAt = "2026-11-16 10:00",
        appVersion = "1.0",
        daySummary = days,
        device = deviceId?.let { BackupDevice(id = it, nickname = "旧机", model = "Redmi") }
    )

    @Test
    fun `设备 ID 与本机相同 判为同一台机器`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10")), deviceId = "local-uuid"),
            localDays = mapOf(
                "2026-11-10" to SameDeviceFingerprint.DayFingerprint(400, 360, 40, 60, 90)
            ),
            localDeviceId = "local-uuid"
        )
        assertTrue(verdict.isSameDevice)
        assertEquals(setOf("2026-11-10"), verdict.sameDeviceDates)
    }

    @Test
    fun `重叠日期数据逐项完全一致 判为同一台机器`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10"))),
            localDays = mapOf(
                "2026-11-10" to SameDeviceFingerprint.DayFingerprint(60, 50, 5, 8, 25)
            ),
            localDeviceId = "local-uuid"
        )
        assertTrue(verdict.isSameDevice)
        assertEquals(setOf("2026-11-10"), verdict.sameDeviceDates)
    }

    @Test
    fun `数据不一致 不判为同一台机器 照常累加`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10"))),
            localDays = mapOf(
                "2026-11-10" to SameDeviceFingerprint.DayFingerprint(400, 360, 40, 60, 90)
            ),
            localDeviceId = "local-uuid"
        )
        assertFalse(verdict.isSameDevice)
        assertTrue(verdict.sameDeviceDates.isEmpty())
    }

    @Test
    fun `只有一个字段不同也不算同一台机器`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10"))),
            localDays = mapOf(
                "2026-11-10" to SameDeviceFingerprint.DayFingerprint(60, 50, 5, 8, 26)
            ),
            localDeviceId = "local-uuid"
        )
        assertFalse("longest 不同就不能算同一份快照", verdict.isSameDevice)
    }

    @Test
    fun `没有重叠日期 不判为同一台机器`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10"))),
            localDays = emptyMap(),
            localDeviceId = "local-uuid"
        )
        assertFalse(verdict.isSameDevice)
    }

    @Test
    fun `部分日期一致时 只判那几天`() {
        val verdict = SameDeviceFingerprint.detect(
            file = file(listOf(day("2026-11-10"), day("2026-11-11", screenMin = 90))),
            localDays = mapOf(
                "2026-11-10" to SameDeviceFingerprint.DayFingerprint(60, 50, 5, 8, 25),
                "2026-11-11" to SameDeviceFingerprint.DayFingerprint(400, 360, 40, 60, 90)
            ),
            localDeviceId = "local-uuid"
        )
        assertEquals(setOf("2026-11-10"), verdict.sameDeviceDates)
    }

    @Test
    fun `同日多行取主指标最大的一行做指纹`() {
        val fp = SameDeviceFingerprint.fingerprintOf(
            file(listOf(day("2026-11-10", screenMin = 10), day("2026-11-10", screenMin = 90))),
            "2026-11-10"
        )
        assertEquals(90, fp!!.screenMin)
    }

    // ---- 信号③：事件主键子集（同机更早的快照）----

    private val zone: java.time.ZoneId = java.time.ZoneId.of("UTC")

    /** 2026-11-12 00:00:00Z 的毫秒时间戳。 */
    private fun msOnNov12(hour: Int = 1, minute: Int = 0): Long =
        1_794_441_600_000L + hour * 3_600_000L + minute * 60_000L

    private fun fileWithEvents(events: List<NotificationEventDto>) = BackupFile(
        schemaVersion = 5,
        exportedAt = "2026-11-16 10:00",
        appVersion = "1.0",
        daySummary = listOf(day("2026-11-12", screenMin = 658, unlockMin = 658)),
        notificationEvent = events
    )

    @Test
    fun `备份事件是本机事件子集 判为同一台机器的更早快照`() {
        val backup = fileWithEvents(
            listOf(
                NotificationEventDto(msOnNov12(1), "NOTIFICATION_INTERRUPTION", "com.a", "ch1"),
                NotificationEventDto(msOnNov12(2), "NOTIFICATION_SEEN", "com.b", "ch2")
            )
        )
        val local = mapOf(
            "2026-11-12" to SameDeviceFingerprint.DayEventKeys(
                setOf(
                    SameDeviceFingerprint.DayEventKeys.ofNotification(
                        msOnNov12(1), "NOTIFICATION_INTERRUPTION", "com.a"
                    ),
                    SameDeviceFingerprint.DayEventKeys.ofNotification(
                        msOnNov12(2), "NOTIFICATION_SEEN", "com.b"
                    ),
                    SameDeviceFingerprint.DayEventKeys.ofNotification(
                        msOnNov12(5), "NOTIFICATION_SEEN", "com.c"
                    )
                )
            )
        )
        val verdict = SameDeviceFingerprint.detect(
            file = backup,
            localDays = mapOf(
                "2026-11-12" to SameDeviceFingerprint.DayFingerprint(1347, 1347, 40, 415, 755)
            ),
            localDeviceId = "local-uuid",
            localEvents = local,
            zone = zone
        )
        assertTrue("更早快照也要判为同一台机器", verdict.isSameDevice)
        assertEquals(setOf("2026-11-12"), verdict.sameDeviceDates)
    }

    @Test
    fun `备份事件与本机不同 不判为同一台机器`() {
        val backup = fileWithEvents(
            listOf(NotificationEventDto(msOnNov12(1), "NOTIFICATION_INTERRUPTION", "com.a", "ch1"))
        )
        val local = mapOf(
            "2026-11-12" to SameDeviceFingerprint.DayEventKeys(
                setOf(
                    SameDeviceFingerprint.DayEventKeys.ofNotification(
                        msOnNov12(9), "NOTIFICATION_SEEN", "com.z"
                    )
                )
            )
        )
        val verdict = SameDeviceFingerprint.detect(
            file = backup,
            localDays = mapOf(
                "2026-11-12" to SameDeviceFingerprint.DayFingerprint(1347, 1347, 40, 415, 755)
            ),
            localDeviceId = "local-uuid",
            localEvents = local,
            zone = zone
        )
        assertFalse("不同手机的事件时间戳不可能重合", verdict.isSameDevice)
    }

    @Test
    fun `备份该日没有事件时 不靠事件判定`() {
        val backup = file(listOf(day("2026-11-12", screenMin = 658, unlockMin = 658)))
        val verdict = SameDeviceFingerprint.detect(
            file = backup,
            localDays = mapOf(
                "2026-11-12" to SameDeviceFingerprint.DayFingerprint(1347, 1347, 40, 415, 755)
            ),
            localDeviceId = "local-uuid",
            localEvents = mapOf(
                "2026-11-12" to SameDeviceFingerprint.DayEventKeys(setOf("n|1|x|y"))
            ),
            zone = zone
        )
        assertFalse(verdict.isSameDevice)
    }
}
