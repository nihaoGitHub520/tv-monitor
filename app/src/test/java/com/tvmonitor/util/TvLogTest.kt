package com.tvmonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TvLog] 日志脱敏单元测试（ARCHITECTURE §11.3 / NFR-05）。
 *
 * 约定：严禁打印密码 / Token / 完整 URL 凭据（脱敏为 rtsp://<掩码>@ip:554/...）。
 * 覆盖：
 * 1. key=value 形式敏感字段脱敏（password / pwd / token / secret ...）；
 * 2. **URL 内嵌凭据脱敏**（§11.3 明确要求，当前实现对裸 rtsp URL 不脱敏 → 失败用例，
 *    已登记 P1 问题并回传工程师修复，修复后本用例应转绿）；
 * 3. 正常文本不受影响；短值不误伤。
 */
class TvLogTest {

    @Before
    fun silenceLog() {
        TvLog.enabled = false
    }

    @Test
    fun `tagOf builds module tag`() {
        assertEquals("TvMonitor:Quad", TvLog.tagOf("Quad"))
        assertEquals("TvMonitor:Slot0", TvLog.tagOf("Slot0"))
    }

    @Test
    fun `maskCredentials masks key=value sensitive fields`() {
        assertEquals("password=ab****", TvLog.maskCredentials("password=abc123"))
        assertEquals("token: xy****", TvLog.maskCredentials("token: xyz123"))
        assertEquals("pwd=ab****", TvLog.maskCredentials("pwd=abcdef"))
        assertEquals("PASSWORD=ab****", TvLog.maskCredentials("PASSWORD=abc123"))
        assertEquals("\"secret\": \"ab****\"", TvLog.maskCredentials("\"secret\": \"abcdef\""))
    }

    @Test
    fun `maskCredentials keeps first two chars only`() {
        val masked = TvLog.maskCredentials("password=SuperSecretPwd")

        assertTrue(masked.startsWith("password=Su"))
        assertFalse(masked.contains("perSecretPwd"))
    }

    @Test
    fun `maskCredentials leaves normal text untouched`() {
        val normal = "解析到 3 个媒体配置 rtsp://192.168.1.61:554/live"

        assertEquals(normal, TvLog.maskCredentials(normal))
    }

    @Test
    fun `maskCredentials does not mask short values`() {
        // 长度 < 3 的值不脱敏（避免误伤短标识）
        assertEquals("pwd=ab", TvLog.maskCredentials("pwd=ab"))
    }

    @Test
    fun `maskCredentials handles empty message`() {
        assertEquals("", TvLog.maskCredentials(""))
    }

    @Test
    fun `maskCredentials masks credentials embedded in stream url`() {
        // ARCHITECTURE §11.3：完整 URL 凭据必须脱敏为 rtsp://***@ip:554/...
        val masked = TvLog.maskCredentials("rtsp://admin:123456@192.168.1.61:554/live")

        assertFalse("密码不得以明文出现在日志中：$masked", masked.contains("123456"))
        assertFalse("账号不得以明文出现在日志中：$masked", masked.contains("admin"))
        assertTrue(masked.contains("***"))
        assertTrue(masked.contains("@192.168.1.61"))
    }

    @Test
    fun `log calls are no-op when disabled`() {
        TvLog.enabled = false

        // 不得抛异常（android.util.Log 在单测环境不可用，关闭开关后不应触达）
        TvLog.v("TvMonitor:Test", "verbose")
        TvLog.d("TvMonitor:Test", "debug")
        TvLog.i("TvMonitor:Test", "info")
        TvLog.w("TvMonitor:Test", "warn")
        TvLog.e("TvMonitor:Test", "error", RuntimeException("boom"))
    }
}
