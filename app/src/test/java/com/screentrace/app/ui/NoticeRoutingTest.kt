package com.screentrace.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-17 ①：提示分流白名单回归。
 * 变异锚点：把 `isTransientNotice` 改成恒 true / 恒 false，下面两组用例必须变红。
 */
class NoticeRoutingTest {

    @Test
    fun `纯确认类文案走短时条`() {
        listOf(
            "已导出 screen-time-backup.json",
            "已导出 3 个 CSV 文件",
            "已恢复：新增 1 条、更新 2 条、跳过 0 条",
            "云端配置已保存",
            "连接成功",
            "已上传 screen-time-backup.json",
            "刚刚已更新",
            "已复制"
        ).forEach { message ->
            assertTrue("$message 应走 2.5s 短时条", isTransientNotice(message))
        }
    }

    @Test
    fun `失败与需引导类文案默认常驻`() {
        listOf(
            "导出失败，请换个位置再试",
            "CSV 导出未完成（1/3），请重试",
            "恢复失败，本地数据未改动",
            "测试失败：网络不可达",
            "上传失败：鉴权失败",
            "下载失败：远端备份不可用",
            "读取文件失败",
            "无法导入：格式不正确",
            "报告生成失败，请稍后再试",
            "该时段尚无数据，请先拉取",
            "Shizuku 服务未运行，请先安装并启动 Shizuku",
            "没有找到系统授权页，请手动到系统设置里开启"
        ).forEach { message ->
            assertFalse("$message 必须常驻，不能被几秒抹掉", isTransientNotice(message))
        }
    }

    @Test
    fun `空串与未知文案按安全默认常驻`() {
        assertFalse(isTransientNotice(""))
        assertFalse(isTransientNotice("随便一句未登记的提示"))
    }
}
