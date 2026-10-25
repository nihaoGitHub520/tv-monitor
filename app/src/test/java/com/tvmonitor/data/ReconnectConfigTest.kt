package com.tvmonitor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReconnectConfig] 指数退避单元测试（US-07 / FR-P0-05 / ARCHITECTURE §6.4）。
 *
 * 验收基线：
 * - 退避序列 5s / 10s / 20s / 40s / 60s 封顶（US-07：5/10/20 起，上限 60s）；
 * - 默认 10 次后进入 ERROR 态并保留手动重试（maxAttempts 边界）；
 * - ERROR 态后台保活间隔 60s；首帧 watchdog RTSP 15s / HLS 25s。
 */
class ReconnectConfigTest {

    @Test
    fun `default backoff sequence is 5 10 20 40 60`() {
        val config = ReconnectConfig()

        assertEquals(listOf(5, 10, 20, 40, 60), config.delaysSec)
    }

    @Test
    fun `delayForAttempt follows exponential backoff and caps at last step`() {
        val config = ReconnectConfig()

        assertEquals(5, config.delayForAttempt(1))
        assertEquals(10, config.delayForAttempt(2))
        assertEquals(20, config.delayForAttempt(3))
        assertEquals(40, config.delayForAttempt(4))
        assertEquals(60, config.delayForAttempt(5))
    }

    @Test
    fun `delayForAttempt stays capped beyond sequence length`() {
        val config = ReconnectConfig()

        // 第 6 次及以后封顶 60s（US-07：上限 60s）
        assertEquals(60, config.delayForAttempt(6))
        assertEquals(60, config.delayForAttempt(10))
        assertEquals(60, config.delayForAttempt(100))
    }

    @Test
    fun `delayForAttempt coerces out-of-range attempt values`() {
        val config = ReconnectConfig()

        // attempt <= 0 归一到首档；负值不得抛异常（防御重试计数错乱）
        assertEquals(5, config.delayForAttempt(0))
        assertEquals(5, config.delayForAttempt(-5))
    }

    @Test
    fun `empty delays yields zero delay instead of crash`() {
        val config = ReconnectConfig(delaysSec = emptyList())

        assertEquals(0, config.delayForAttempt(1))
        assertEquals(0, config.delayForAttempt(3))
    }

    @Test
    fun `custom delays sequence is honored with cap`() {
        val config = ReconnectConfig(delaysSec = listOf(3))

        assertEquals(3, config.delayForAttempt(1))
        assertEquals(3, config.delayForAttempt(2))
        assertEquals(3, config.delayForAttempt(9))
    }

    @Test
    fun `default maxAttempts is ten`() {
        val config = ReconnectConfig()

        assertEquals(10, config.maxAttempts)
        assertEquals(ReconnectConfig.DEFAULT_MAX_ATTEMPTS, config.maxAttempts)
    }

    @Test
    fun `default keepalive and first-frame timeouts match architecture`() {
        val config = ReconnectConfig()

        assertEquals(60, config.keepaliveIntervalSec)
        assertEquals(15, config.firstFrameTimeoutRtspSec)
        assertEquals(25, config.firstFrameTimeoutHlsSec)
    }

    @Test
    fun `maxAttempts boundary value one is accepted`() {
        // 设置页允许 1..N；边界值 1 必须可用（SettingsFragment 校验 >= 1）
        val config = ReconnectConfig(maxAttempts = 1)

        assertEquals(1, config.maxAttempts)
        assertEquals(5, config.delayForAttempt(1))
    }
}
