package com.tvmonitor.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvmonitor.data.AspectMode
import com.tvmonitor.data.PlaybackStats
import com.tvmonitor.data.PlaybackStatus
import com.tvmonitor.data.ReconnectConfig
import com.tvmonitor.data.VideoSource
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 单格 UI 状态（四分格角标 / 单预览 overlay 的唯一数据源）。
 *
 * @property status 粗粒度播放状态（角标着色依据）；
 * @property errorCode 失败错误码（UI 映射 strings.xml 文案，如 PROTOCOL_UNSUPPORTED）；
 * @property errorMessage 面向用户的失败详情；
 * @property reconnectAttempt 当前第几次重连（从 1 计）；
 * @property countdownSec 重连倒计时（秒），0 表示无倒计时；
 * @property stats 播放统计（info overlay）。
 */
data class SlotUiState(
    val slotIndex: Int,
    val source: VideoSource? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val reconnectAttempt: Int = 0,
    val countdownSec: Int = 0,
    val stats: PlaybackStats = PlaybackStats(),
)

/** 播放器构造参数（由设置页驱动，见 [AppSettings]）。 */
data class PlayerOptions(
    val bufferLimitMb: Int = 8,
    val maxResolutionHeight: Int = 1080,
)

/**
 * 单路播放槽位：状态机 + 重连 + 首帧 watchdog + 统计（T3 核心）。
 *
 * 隔离性承诺（NFR-01 / T3 验收 2）：
 * - 每槽独立 ExoPlayer、独立 [scope]、独立重连序列；单路失败不波及其他槽；
 * - 本类所有 player 操作均在调用方传入的 scope（主线程 immediate）内执行；
 * - 地址解析（可能网络）在 IO 线程完成后再回主线程操作 player。
 *
 * 重连策略（US-07 / §6.4）：
 * - 失败 → 指数退避 5/10/20/40/60s 封顶，倒计时对 UI 可见；
 * - 默认 10 次失败 → ERROR 态 + 手动重试入口 + 每 60s 后台保活重试；
 * - 播放成功（STATE_READY + 首帧）即重置计数。
 */
class PlaybackSlot(
    val slotIndex: Int,
    private val resolver: SourceUriResolver,
    private val configProvider: () -> ReconnectConfig,
    private val playerFactory: PlayerFactory,
    private val optionsProvider: () -> PlayerOptions,
    private val scope: CoroutineScope,
) {
    private val config: ReconnectConfig get() = configProvider()

    private val _uiState = MutableStateFlow(SlotUiState(slotIndex = slotIndex))

    /** 槽位 UI 状态流。 */
    val uiState: StateFlow<SlotUiState> = _uiState.asStateFlow()

    private var source: VideoSource? = null
    private var player: ExoPlayer? = null
    private var currentKind: MediaKind? = null
    private var attachedView: PlayerView? = null
    private var muted: Boolean = true

    private var reconnectAttempt: Int = 0
    private var firstFrameSeen: Boolean = false
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private var statsJob: Job? = null

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING ->
                    if (_uiState.value.status != PlaybackStatus.RECONNECTING) {
                        updateStatus(PlaybackStatus.LOADING)
                    }

                Player.STATE_READY -> onPlaybackReady()

                Player.STATE_ENDED ->
                    // 直播流不应自然结束；本地文件已置 REPEAT_MODE_ONE，此处按异常处理
                    handlePlaybackFailure(
                        AppError(ErrorCode.UNKNOWN, "播放意外结束"),
                    )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackFailure(error.toAppError())
        }

        override fun onRenderedFirstFrame() {
            firstFrameSeen = true
            watchdogJob?.cancel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startStatsJob() else stopStatsJob()
        }
    }

    // ===================== 对外 API =====================

    /**
     * 绑定源并开始播放。传入 null = 解绑（释放播放器，显示“未配置”）。
     * 已绑定同一源时忽略（避免重复起流）。
     */
    fun bind(newSource: VideoSource?) {
        val current = source
        if (current?.id == newSource?.id && newSource != null) return
        cancelAllJobs()
        source = newSource
        reconnectAttempt = 0
        firstFrameSeen = false
        if (newSource == null) {
            releasePlayer()
            _uiState.value = SlotUiState(slotIndex = slotIndex)
            TvLog.i(TAG, "slot=$slotIndex 解绑")
            return
        }
        _uiState.value = _uiState.value.copy(
            source = newSource,
            status = PlaybackStatus.LOADING,
            errorCode = null,
            errorMessage = null,
            reconnectAttempt = 0,
            countdownSec = 0,
            stats = PlaybackStats(),
        )
        TvLog.i(TAG, "slot=$slotIndex 绑定源：${newSource.name}（${newSource.type}）")
        prepareMedia()
    }

    /** 手动重试（ERROR 态入口）：重置计数并立即重连。 */
    fun retry() {
        if (source == null) return
        TvLog.i(TAG, "slot=$slotIndex 手动重试")
        cancelAllJobs()
        reconnectAttempt = 0
        firstFrameSeen = false
        updateStatus(PlaybackStatus.LOADING)
        _uiState.update { it.copy(errorCode = null, errorMessage = null, countdownSec = 0) }
        prepareMedia()
    }

    /** 挂载视图（四分格 / 单预览）。player 为 null 时先缓存视图，起流后自动挂载。 */
    fun attachView(view: PlayerView) {
        attachedView = view
        // 应用源的播放比例（2026-10-25：单预览与四分格共用同一 resizeMode 语义）
        view.resizeMode = resizeModeFor(source?.aspectMode ?: AspectMode.FIT)
        // surface 切换后强制重新布局，确保画面按当前视图尺寸重排（修复返回小格不适配）
        view.requestLayout()
        view.player = player
    }

    /** 卸载视图（切回四分格 / 释放）。仅当该视图正展示本槽 player 时摘除。 */
    fun detachView() {
        val view = attachedView ?: return
        if (view.player === player) {
            view.player = null
        }
        attachedView = null
    }

    /** 设置静音（US-09：仅作用于本槽）。 */
    fun setMuted(value: Boolean) {
        muted = value
        player?.volume = if (value) 0f else 1f
    }

    /** 暂停本槽（不释放）。 */
    fun pause() {
        player?.pause()
    }

    /** 恢复本槽播放。 */
    fun resume() {
        player?.play()
    }

    /** 释放播放器与全部协程任务（Activity onDestroy / 解绑场景）。 */
    fun release() {
        cancelAllJobs()
        detachView()
        releasePlayer()
        source = null
        _uiState.value = SlotUiState(slotIndex = slotIndex)
    }

    // ===================== 内部状态机 =====================

    /** 解析地址并起流；失败按错误类型进入重连或直接报错。 */
    private fun prepareMedia() {
        val src = source ?: return
        scope.launch {
            when (val result = resolver.resolve(src)) {
                is AppResult.Failure -> handleResolveFailure(result.error)
                is AppResult.Success -> withContext(Dispatchers.Main) {
                    startPlayback(result.data)
                }
            }
        }
    }

    /** 主线程：确保 player 存在且匹配媒体种类，装载媒体并开始播放。 */
    private fun startPlayback(media: ResolvedMedia) {
        val existing = player
        val needRecreate = existing == null || currentKind != media.kind
        if (needRecreate) {
            existing?.removeListener(playerListener)
            existing?.release()
            val options = optionsProvider()
            val created = playerFactory.create(media.kind, options)
            created.setWakeMode(C.WAKE_MODE_NETWORK)
            created.addListener(playerListener)
            created.repeatMode =
                if (media.repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            created.volume = if (muted) 0f else 1f
            player = created
            currentKind = media.kind
            // 新 player 立即挂回缓存视图（单预览切换 / 换源不断流场景）
            attachedView?.player = created
        }
        val active = player ?: return
        active.setMediaItem(MediaItem.fromUri(media.uri))
        active.prepare()
        active.playWhenReady = true
        firstFrameSeen = false
        updateStatus(PlaybackStatus.LOADING)
        startWatchdog(media.kind)
        TvLog.i(TAG, "slot=$slotIndex 起流：${media.uri}（${media.kind}）")
    }

    /**
     * 首帧 watchdog：起流后 [ReconnectConfig.firstFrameTimeoutRtspSec/HlsSec]
     * 内未渲染首帧即按失败处理（防止伪流地址永久 BUFFERING）。
     */
    private fun startWatchdog(kind: MediaKind) {
        watchdogJob?.cancel()
        val timeoutSec = when (kind) {
            MediaKind.RTSP -> config.firstFrameTimeoutRtspSec
            MediaKind.HLS -> config.firstFrameTimeoutHlsSec
            else -> 20
        }
        watchdogJob = scope.launch {
            delay(timeoutSec * 1000L)
            if (!firstFrameSeen && source != null) {
                TvLog.w(TAG, "slot=$slotIndex 首帧超时（${timeoutSec}s），转入重连")
                handlePlaybackFailure(
                    AppError(ErrorCode.TIMEOUT, "首帧超时（${timeoutSec}s）"),
                )
            }
        }
    }

    /** 播放成功：重置重连计数，进入在线态，开启统计。 */
    private fun onPlaybackReady() {
        firstFrameSeen = true
        watchdogJob?.cancel()
        reconnectAttempt = 0
        keepaliveJob?.cancel()
        keepaliveJob = null
        _uiState.update {
            it.copy(
                status = PlaybackStatus.ONLINE,
                errorCode = null,
                errorMessage = null,
                reconnectAttempt = 0,
                countdownSec = 0,
            )
        }
        startStatsJob()
    }

    /** 失败统一入口：解析失败（协议不支持直接报错）/ 播放失败（进重连序列）。 */
    private fun handleResolveFailure(error: AppError) {
        if (error.code == ErrorCode.PROTOCOL_UNSUPPORTED) {
            // 协议不支持重试无意义：直接 ERROR 态，UI 展示明确提示
            _uiState.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorCode = error.code,
                    errorMessage = error.message,
                    countdownSec = 0,
                )
            }
            TvLog.w(TAG, "slot=$slotIndex 协议不支持：${error.message}")
            return
        }
        handlePlaybackFailure(error)
    }

    /** 播放失败：指数退避重连；超限进 ERROR + 后台保活。 */
    private fun handlePlaybackFailure(error: AppError) {
        if (source == null) return
        watchdogJob?.cancel()
        reconnectAttempt++
        val max = config.maxAttempts
        if (reconnectAttempt > max) {
            _uiState.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorCode = error.code,
                    errorMessage = error.message,
                    reconnectAttempt = reconnectAttempt,
                    countdownSec = 0,
                )
            }
            startKeepalive()
            TvLog.w(TAG, "slot=$slotIndex 重连 $max 次失败，进入错误态 + 保活重试")
            return
        }
        val delaySec = config.delayForAttempt(reconnectAttempt)
        _uiState.update {
            it.copy(
                status = PlaybackStatus.RECONNECTING,
                errorCode = error.code,
                errorMessage = error.message,
                reconnectAttempt = reconnectAttempt,
                countdownSec = delaySec,
            )
        }
        TvLog.i(TAG, "slot=$slotIndex 第 $reconnectAttempt 次重连，${delaySec}s 后执行")
        scheduleReconnect(delaySec)
    }

    /** 倒计时 + 到期重连（可取消：手动重试/解绑时取消）。 */
    private fun scheduleReconnect(delaySec: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (remaining in delaySec downTo 1) {
                _uiState.update { it.copy(countdownSec = remaining) }
                delay(1000L)
            }
            _uiState.update { it.copy(countdownSec = 0) }
            prepareMedia()
        }
    }

    /** ERROR 态后台保活：每 [ReconnectConfig.keepaliveIntervalSec] 静默重试一次。 */
    private fun startKeepalive() {
        if (keepaliveJob?.isActive == true) return
        val intervalSec = config.keepaliveIntervalSec
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(intervalSec * 1000L)
                if (source == null) break
                TvLog.i(TAG, "slot=$slotIndex 保活重试")
                reconnectAttempt = 0
                firstFrameSeen = false
                updateStatus(PlaybackStatus.LOADING)
                prepareMedia()
            }
        }
    }

    /** 播放期间 500ms 粒度刷新统计（info overlay 数据源，US-10）。 */
    private fun startStatsJob() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (isActive) {
                updateStats()
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun stopStatsJob() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun updateStats() {
        val active = player ?: return
        val video = active.videoFormat
        val audio = active.audioFormat
        val bufferedMs = (active.bufferedPosition - active.currentPosition).coerceAtLeast(0L)
        _uiState.update { state ->
            state.copy(
                stats = PlaybackStats(
                    videoWidth = video?.width ?: 0,
                    videoHeight = video?.height ?: 0,
                    frameRate = video?.frameRate ?: 0f,
                    videoBitrateBps = video?.bitrate?.toLong() ?: 0L,
                    audioBitrateBps = audio?.bitrate?.toLong() ?: 0L,
                    bufferMs = bufferedMs,
                    droppedFrames = active.videoDecoderCounters?.droppedBufferCount ?: 0,
                ),
            )
        }
    }

    // ===================== 工具 =====================

    private fun updateStatus(status: PlaybackStatus) {
        _uiState.update { it.copy(status = status) }
    }

    private fun cancelAllJobs() {
        watchdogJob?.cancel(); watchdogJob = null
        reconnectJob?.cancel(); reconnectJob = null
        keepaliveJob?.cancel(); keepaliveJob = null
        statsJob?.cancel(); statsJob = null
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
        currentKind = null
    }

    private fun PlaybackException.toAppError(): AppError {
        val code = when (errorCode) {
            PlaybackException.ERROR_CODE_TIMEOUT -> ErrorCode.TIMEOUT
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> ErrorCode.NETWORK
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> ErrorCode.PARSE_FAILED
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            -> ErrorCode.PROTOCOL_UNSUPPORTED
            else -> ErrorCode.UNKNOWN
        }
        return AppError(
            code = code,
            message = message ?: errorCodeName,
        )
    }

    companion object {
        private const val TAG = "TvMonitor:PlaybackSlot"
        private const val STATS_INTERVAL_MS = 500L

        /** 播放比例 → media3 resizeMode（原比例 FIT / 16:9 裁切 ZOOM / 拉伸 FILL）。 */
        private fun resizeModeFor(mode: AspectMode): Int = when (mode) {
            AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }
}
