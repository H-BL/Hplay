package com.hpplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志体系的关键约定：
 * - 会话文件名带协议前缀，上传包里能区分 CarPlay / CarLife / CarLink；
 * - 协议名做规范化，避免出现非法文件名字符。
 */
class SessionLogNamingTest {

    @Test
    fun `protocol tag is lowercased and stripped`() {
        assertEquals("carlife", protocolTag("CarLife"))
        assertEquals("carlink", protocolTag("CarLink"))
        assertEquals("carplay", protocolTag("CARPLAY"))
        assertEquals("carlife", protocolTag("car life"))
        assertEquals("carlife", protocolTag("car/life"))
    }

    @Test
    fun `protocol tag never empty`() {
        assertEquals("unknown", protocolTag(""))
        assertEquals("unknown", protocolTag("-/-"))
    }

    @Test
    fun `session file name carries protocol prefix`() {
        // 复刻 LogFileStore.newSessionFile 的命名规则，确保上传包里可识别来源
        val stamp = "20261103-081500"
        val prefix = "hplay-"
        val suffix = ".log"
        val name = prefix + protocolTag("carlife") + "-" + stamp + suffix
        assertEquals("hplay-carlife-20261103-081500.log", name)
        assertTrue(name.startsWith(prefix))
        assertTrue(name.endsWith(suffix))
    }

    @Test
    fun `uploader can tell protocol from file name`() {
        // 与 LogUploader.protocolSummary 的解析规则保持一致
        fun protocolOf(fileName: String): String {
            val base = fileName.removeSuffix(".log")
            val parts = base.split("-")
            // 4 段 = 带协议前缀；3 段 = 旧版 CarPlay 日志
            return if (parts.size >= 4) parts[1] else "carplay"
        }

        assertEquals("carplay", protocolOf("hplay-20261103-081500.log"))
        assertEquals("carlife", protocolOf("hplay-carlife-20261103-081500.log"))
        assertEquals("carlink", protocolOf("hplay-carlink-20261103-081600.log"))
        assertFalse(protocolOf("hplay-carlife-20261103-081500.log") == "carplay")
    }
}
