package com.tvmonitor.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据模型单元测试（T1 验收 / ARCHITECTURE §4.1）。
 *
 * 覆盖：
 * 1. [VideoSource] kotlinx.serialization 往返序列化（含默认值字段）；
 * 2. 计算属性 [VideoSource.hasCredentials] 不参与序列化；
 * 3. [SlotBinding] 往返（sourceId 可空）；
 * 4. [QuadLayoutState] schema 版本字段 + 4 格初始化校验；
 * 5. [AppSettings] / [PlaybackStats] 默认值与派生属性。
 */
class ModelsTest {

    private val json = Json { encodeDefaults = true }

    // ===================== VideoSource =====================

    @Test
    fun `VideoSource roundtrip serialization`() {
        val source = VideoSource(
            id = "uuid-001",
            name = "熔铸车间东",
            type = SourceType.ONVIF,
            address = "rtsp://192.168.1.61:554/Streaming/Channels/201",
            username = "admin",
            passwordEnc = "v1:abc:def",
            preferSubStream = true,
            profileToken = "profile_sub_1",
            streamKind = StreamKind.SUB,
            onvifIp = "192.168.1.61",
            onvifPort = 80,
            deviceVendor = "HIKVISION",
            deviceModel = "DS-2CD3T46",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_600_000L,
        )

        val encoded = json.encodeToString(VideoSource.serializer(), source)
        val decoded = json.decodeFromString(VideoSource.serializer(), encoded)

        assertEquals(source, decoded)
    }

    @Test
    fun `VideoSource defaults are applied when fields omitted`() {
        val minimal = """{"id":"x","name":"极简源","type":"STREAM","address":"rtsp://h/s"}"""

        val decoded = json.decodeFromString(VideoSource.serializer(), minimal)

        assertEquals("", decoded.username)
        assertEquals("", decoded.passwordEnc)
        assertTrue(decoded.preferSubStream)
        assertEquals(StreamKind.SUB, decoded.streamKind)
        assertEquals(80, decoded.onvifPort)
        assertEquals(0L, decoded.createdAt)
    }

    @Test
    fun `VideoSource serialization keeps passwordEnc field`() {
        val source = VideoSource(
            id = "x", name = "n", type = SourceType.ONVIF, address = "rtsp://h/s",
            username = "u", passwordEnc = "v1:iv:ct",
        )

        val encoded = json.encodeToString(VideoSource.serializer(), source)

        assertTrue(encoded.contains("\"passwordEnc\""))
        assertTrue(encoded.contains("v1:iv:ct"))
        // 计算属性不得落盘
        assertFalse(encoded.contains("hasCredentials"))
    }

    @Test
    fun `hasCredentials reflects username or passwordEnc`() {
        val noCred = VideoSource(id = "a", name = "n", type = SourceType.M3U8, address = "http://h/a.m3u8")
        val withUser = noCred.copy(username = "admin")
        val withPass = noCred.copy(passwordEnc = "v1:iv:ct")

        assertFalse(noCred.hasCredentials)
        assertTrue(withUser.hasCredentials)
        assertTrue(withPass.hasCredentials)
    }

    @Test
    fun `SourceType and StreamKind enum names serialize as declared`() {
        assertEquals("\"M3U8\"", json.encodeToString(SourceType.serializer(), SourceType.M3U8))
        assertEquals("\"MAIN\"", json.encodeToString(StreamKind.serializer(), StreamKind.MAIN))
        assertEquals(SourceType.FILE, json.decodeFromString(SourceType.serializer(), "\"FILE\""))
        assertEquals(StreamKind.SUB, json.decodeFromString(StreamKind.serializer(), "\"SUB\""))
    }

    // ===================== SlotBinding =====================

    @Test
    fun `SlotBinding roundtrip with null sourceId`() {
        val binding = SlotBinding(slotIndex = 2, sourceId = null)

        val encoded = json.encodeToString(SlotBinding.serializer(), binding)
        val decoded = json.decodeFromString(SlotBinding.serializer(), encoded)

        assertEquals(binding, decoded)
    }

    @Test
    fun `SlotBinding roundtrip with sourceId`() {
        val binding = SlotBinding(slotIndex = 0, sourceId = "uuid-xyz")

        val encoded = json.encodeToString(SlotBinding.serializer(), binding)
        val decoded = json.decodeFromString(SlotBinding.serializer(), encoded)

        assertEquals(binding, decoded)
    }

    // ===================== QuadLayoutState =====================

    @Test
    fun `QuadLayoutState default has four slots with sequential indices`() {
        val state = QuadLayoutState()

        assertEquals(QuadLayoutState.SLOT_COUNT, state.slots.size)
        assertEquals(listOf(0, 1, 2, 3), state.slots.map { it.slotIndex })
        assertTrue(state.slots.all { it.sourceId == null })
    }

    @Test
    fun `QuadLayoutState carries schema version`() {
        assertEquals(1, QuadLayoutState.SCHEMA_VERSION)

        val encoded = json.encodeToString(QuadLayoutState.serializer(), QuadLayoutState())

        assertTrue(encoded.contains("\"schema\":1"))
    }

    @Test
    fun `QuadLayoutState roundtrip preserves bindings`() {
        val state = QuadLayoutState(
            slots = listOf(
                SlotBinding(0, "src-a"),
                SlotBinding(1, null),
                SlotBinding(2, "src-c"),
                SlotBinding(3, "src-d"),
            ),
        )

        val decoded = json.decodeFromString(
            QuadLayoutState.serializer(),
            json.encodeToString(QuadLayoutState.serializer(), state),
        )

        assertEquals(state, decoded)
        assertEquals("src-a", decoded.slots[0].sourceId)
        assertEquals(null, decoded.slots[1].sourceId)
    }

    @Test
    fun `QuadLayoutState json without slots uses default four`() {
        val decoded = json.decodeFromString(QuadLayoutState.serializer(), """{"schema":1}""")

        assertEquals(4, decoded.slots.size)
    }

    // ===================== AppSettings / PlaybackStats =====================

    @Test
    fun `AppSettings defaults match architecture baseline`() {
        val settings = AppSettings()

        assertTrue(settings.defaultMute)
        assertTrue(settings.preferSubStream)
        assertEquals(8, settings.bufferLimitMb)
        assertEquals(1080, settings.maxResolutionHeight)
        assertTrue(settings.logEnabled)
        assertEquals(listOf(5, 10, 20, 40, 60), settings.reconnect.delaysSec)
    }

    @Test
    fun `PlaybackStats resolutionText`() {
        assertEquals("1920x1080", PlaybackStats(videoWidth = 1920, videoHeight = 1080).resolutionText)
        assertEquals("", PlaybackStats().resolutionText)
        assertEquals("", PlaybackStats(videoWidth = 0, videoHeight = 1080).resolutionText)
    }
}
