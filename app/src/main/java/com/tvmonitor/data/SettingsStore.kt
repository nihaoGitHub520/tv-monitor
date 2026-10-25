package com.tvmonitor.data

import android.content.Context
import android.content.SharedPreferences
import com.tvmonitor.BuildConfig
import com.tvmonitor.util.TvLog

/**
 * 应用设置存储（SharedPreferences，T5 设置页数据源）。
 *
 * 与 [SourceStore] 的分工：
 * - 本类存“策略/偏好”类轻量配置（重连、缓冲、静音、日志开关）；
 * - [SourceStore] 存“业务数据”（源与格位绑定）。
 *
 * 写入口径：SettingsFragment 编辑后调 [save]，内部同步刷新快照并广播 Flow；
 * 播放内核启动时读 [snapshot] 构建 [ReconnectConfig]（不反向依赖播放层）。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 内存快照：读多写少，避免每次播放都走 SharedPreferences
    @Volatile
    private var cached: AppSettings = readFromPrefs()

    /** 设置变更流（设置页保存后发射）。 */
    val settingsFlow: kotlinx.coroutines.flow.MutableStateFlow<AppSettings> =
        kotlinx.coroutines.flow.MutableStateFlow(cached)

    /** 当前设置快照。 */
    fun snapshot(): AppSettings = cached

    /** 全量保存（设置页“保存”按钮）。 */
    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_RECONNECT_DELAYS, settings.reconnect.delaysSec.joinToString(","))
            .putInt(KEY_MAX_ATTEMPTS, settings.reconnect.maxAttempts)
            .putInt(KEY_KEEPALIVE_SEC, settings.reconnect.keepaliveIntervalSec)
            .putInt(KEY_FIRST_FRAME_RTSP, settings.reconnect.firstFrameTimeoutRtspSec)
            .putInt(KEY_FIRST_FRAME_HLS, settings.reconnect.firstFrameTimeoutHlsSec)
            .putBoolean(KEY_DEFAULT_MUTE, settings.defaultMute)
            .putBoolean(KEY_PREFER_SUBSTREAM, settings.preferSubStream)
            .putInt(KEY_BUFFER_MB, settings.bufferLimitMb)
            .putInt(KEY_MAX_RESOLUTION, settings.maxResolutionHeight)
            .putBoolean(KEY_LOG_ENABLED, settings.logEnabled)
            .apply()
        applySnapshot(settings)
    }

    /** 日志开关独立入口（设置页开关即时生效）。 */
    fun setLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
        applySnapshot(cached.copy(logEnabled = enabled))
    }

    private fun applySnapshot(settings: AppSettings) {
        cached = settings
        settingsFlow.value = settings
        TvLog.enabled = settings.logEnabled
    }

    private fun readFromPrefs(): AppSettings {
        val defaults = AppSettings()
        val delays = prefs.getString(KEY_RECONNECT_DELAYS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.takeIf { it.isNotEmpty() }
            ?: defaults.reconnect.delaysSec
        return AppSettings(
            reconnect = ReconnectConfig(
                delaysSec = delays,
                maxAttempts = prefs.getInt(
                    KEY_MAX_ATTEMPTS,
                    ReconnectConfig.DEFAULT_MAX_ATTEMPTS,
                ).coerceAtLeast(1),
                keepaliveIntervalSec = prefs.getInt(
                    KEY_KEEPALIVE_SEC,
                    ReconnectConfig.DEFAULT_KEEPALIVE_SEC,
                ).coerceAtLeast(5),
                firstFrameTimeoutRtspSec = prefs.getInt(
                    KEY_FIRST_FRAME_RTSP,
                    ReconnectConfig.DEFAULT_FIRST_FRAME_RTSP_SEC,
                ).coerceAtLeast(3),
                firstFrameTimeoutHlsSec = prefs.getInt(
                    KEY_FIRST_FRAME_HLS,
                    ReconnectConfig.DEFAULT_FIRST_FRAME_HLS_SEC,
                ).coerceAtLeast(3),
            ),
            defaultMute = prefs.getBoolean(KEY_DEFAULT_MUTE, true),
            preferSubStream = prefs.getBoolean(KEY_PREFER_SUBSTREAM, true),
            bufferLimitMb = prefs.getInt(
                KEY_BUFFER_MB,
                AppSettings.DEFAULT_BUFFER_LIMIT_MB,
            ).coerceIn(2, 64),
            maxResolutionHeight = prefs.getInt(
                KEY_MAX_RESOLUTION,
                AppSettings.DEFAULT_MAX_RESOLUTION_HEIGHT,
            ).coerceAtLeast(0),
            logEnabled = prefs.getBoolean(KEY_LOG_ENABLED, BuildConfig.DEBUG),
        )
    }

    companion object {
        private const val TAG = "TvMonitor:SettingsStore"
        private const val PREFS_NAME = "tvmonitor_settings"
        private const val KEY_RECONNECT_DELAYS = "reconnect_delays_sec"
        private const val KEY_MAX_ATTEMPTS = "reconnect_max_attempts"
        private const val KEY_KEEPALIVE_SEC = "reconnect_keepalive_sec"
        private const val KEY_FIRST_FRAME_RTSP = "first_frame_timeout_rtsp_sec"
        private const val KEY_FIRST_FRAME_HLS = "first_frame_timeout_hls_sec"
        private const val KEY_DEFAULT_MUTE = "default_mute"
        private const val KEY_PREFER_SUBSTREAM = "prefer_sub_stream"
        private const val KEY_BUFFER_MB = "buffer_limit_mb"
        private const val KEY_MAX_RESOLUTION = "max_resolution_height"
        private const val KEY_LOG_ENABLED = "log_enabled"
    }
}
