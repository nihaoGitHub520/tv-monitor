package com.tvmonitor.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.tvmonitor.data.QuadLayoutState
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.data.SourceStore
import com.tvmonitor.data.VideoSource
import com.tvmonitor.util.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 播放器工厂（T3：LoadControl / TrackSelector 按 §6.2/§6.3 配置并可调）。
 *
 * 隔离性：每路独立 [ExoPlayer] 实例，互不共享解码器与缓冲。
 */
interface PlayerFactory {

    /**
     * 创建播放器。
     *
     * @param kind 媒体种类（决定缓冲策略：RTSP 低延迟 / HLS 大缓冲）；
     * @param options 缓冲上限与分辨率上限（来自设置页）。
     */
    fun create(kind: MediaKind, options: PlayerOptions): ExoPlayer
}

/**
 * 默认播放器工厂。
 *
 * - **分协议 LoadControl**（§6.2 基线）：RTSP 小缓冲低延迟（起流快、延迟低）；
 *   HLS 大缓冲（抗抖动）；其他用默认值；四档参数与 §6.2 表逐项对齐，
 *   后续实测调优只改本类常量并同步文档，不得散落魔法数；
 * - **单路缓冲 8MB**（§6.2）：每 player 独立 DefaultAllocator，四路合计有界，
 *   保障 2GB 低端盒子内存基线（§6.5）；
 * - **分辨率上限 1080p**（§6.3）：TrackSelector 限制视频高度，低端盒不解 4K。
 */
class DefaultPlayerFactory(private val context: Context) : PlayerFactory {

    @OptIn(UnstableApi::class)
    override fun create(kind: MediaKind, options: PlayerOptions): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            // 注意：apply 的 receiver（DefaultTrackSelector）持有同名 @Nullable 字段 context，
            // 会遮蔽工厂构造参数，必须显式限定 this@DefaultPlayerFactory.context
            parameters = DefaultTrackSelector.ParametersBuilder(this@DefaultPlayerFactory.context)
                .apply {
                    if (options.maxResolutionHeight > 0) {
                        // 限制高度上限（宽度给足），低端盒子不解码 4K
                        setMaxVideoSize(MAX_WIDTH_CAP, options.maxResolutionHeight)
                    }
                }.build()
        }
        // 单路 8MB 总量上限：chunk 用默认 64KB，总量走 setTargetBufferBytes，
        // 四路合计缓冲有界，保障 2GB 低端盒子内存基线（§6.5）
        val allocator = DefaultAllocator(true, ALLOCATION_CHUNK_SIZE)
        val targetBufferBytes = options.bufferLimitMb * 1024 * 1024
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                minBufferMs(kind),
                maxBufferMs(kind),
                bufferForPlaybackMs(kind),
                bufferForPlaybackAfterRebufferMs(kind),
            )
            .setTargetBufferBytes(targetBufferBytes)
            .build()
        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
    }

    private fun minBufferMs(kind: MediaKind): Int = when (kind) {
        MediaKind.RTSP -> RTSP_MIN_BUFFER_MS
        MediaKind.HLS -> HLS_MIN_BUFFER_MS
        else -> DEFAULT_MIN_BUFFER_MS
    }

    private fun maxBufferMs(kind: MediaKind): Int = when (kind) {
        MediaKind.RTSP -> RTSP_MAX_BUFFER_MS
        MediaKind.HLS -> HLS_MAX_BUFFER_MS
        else -> DEFAULT_MAX_BUFFER_MS
    }

    private fun bufferForPlaybackMs(kind: MediaKind): Int = when (kind) {
        MediaKind.RTSP -> RTSP_PLAYBACK_MS
        MediaKind.HLS -> HLS_PLAYBACK_MS
        else -> DEFAULT_PLAYBACK_MS
    }

    private fun bufferForPlaybackAfterRebufferMs(kind: MediaKind): Int = when (kind) {
        MediaKind.RTSP -> RTSP_REBUFFER_MS
        MediaKind.HLS -> HLS_REBUFFER_MS
        else -> DEFAULT_REBUFFER_MS
    }

    companion object {
        private const val TAG = "TvMonitor:PlayerFactory"

        /** TrackSelector 宽度上限（配合高度上限 1080 使用，避免误伤宽屏）。 */
        private const val MAX_WIDTH_CAP = 4096

        /** DefaultAllocator 单块分配大小（字节）：与总量上限解耦。 */
        private const val ALLOCATION_CHUNK_SIZE = 64 * 1024

        // RTSP 低延迟档（对齐 ARCHITECTURE §6.2 基线：起流快、直播封顶防累积延迟）
        private const val RTSP_MIN_BUFFER_MS = 1_500
        private const val RTSP_MAX_BUFFER_MS = 8_000
        private const val RTSP_PLAYBACK_MS = 700
        private const val RTSP_REBUFFER_MS = 1_500

        // HLS/DASH/FILE 档（§6.2 基线第二列：抗抖动且起播不慢）
        private const val HLS_MIN_BUFFER_MS = 2_500
        private const val HLS_MAX_BUFFER_MS = 20_000
        private const val HLS_PLAYBACK_MS = 1_000
        private const val HLS_REBUFFER_MS = 2_500

        // 其他（本地文件 / 渐进式）：与 HLS 同档（§6.2 第二列覆盖 HLS/DASH/FILE）
        private const val DEFAULT_MIN_BUFFER_MS = 2_500
        private const val DEFAULT_MAX_BUFFER_MS = 20_000
        private const val DEFAULT_PLAYBACK_MS = 1_000
        private const val DEFAULT_REBUFFER_MS = 2_500
    }
}

/**
 * 四分格播放编排器（T3 门面 / T4 主界面唯一播放入口）。
 *
 * 职责：
 * 1. 创建并持有 4 个 [PlaybackSlot]（每路独立 scope，单路故障隔离）；
 * 2. 从 [SourceStore] 恢复格位绑定并驱动播放；
 * 3. 单预览 attach/detach 切换：player **不 stop、不重新 prepare**，
 *    返回四分格后四路播放不中断（FR-P0-02 / T3 验收 3）；
 * 4. 静音矩阵（US-09：默认全静音，仅当前路可取消静音，切格不串台）；
 * 5. 监听源库变更（外部删除/换绑）并同步到槽位。
 *
 * 线程：公开方法均假定主线程调用；内部 IO 由 slot 自行切换。
 */
class QuadPlaybackManager(
    private val context: Context,
    private val sourceStore: SourceStore,
    private val settingsStore: SettingsStore,
    private val resolver: SourceUriResolver = ResolverRegistry.createDefault(),
    playerFactory: PlayerFactory = DefaultPlayerFactory(context),
    private val managerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** 四个槽位（下标 0..3，2x2 从左到右、从上到下）。 */
    val slots: List<PlaybackSlot> = List(QuadLayoutState.SLOT_COUNT) { index ->
        PlaybackSlot(
            slotIndex = index,
            resolver = resolver,
            configProvider = { settingsStore.snapshot().reconnect },
            playerFactory = playerFactory,
            optionsProvider = {
                val settings = settingsStore.snapshot()
                PlayerOptions(
                    bufferLimitMb = settings.bufferLimitMb,
                    maxResolutionHeight = settings.maxResolutionHeight,
                )
            },
            // 每路独立 scope：单路协程崩溃/取消不波及其他路
            scope = CoroutineScope(managerScope.coroutineContext + SupervisorJob()),
        )
    }

    /** 槽位 UI 状态流列表（四分格 / 单预览 collect 用）。 */
    val slotStates: List<StateFlow<SlotUiState>> = slots.map { it.uiState }

    /** 四分格各 PlayerView（attach 后由 manager 托管切换）。 */
    private val quadViews = arrayOfNulls<PlayerView>(QuadLayoutState.SLOT_COUNT)

    /** 当前单预览槽位；null 表示四分格态。 */
    private var singlePreviewIndex: Int? = null

    /** 单预览 overlay 的 PlayerView（由 MainActivity 提供）。 */
    private var overlayView: PlayerView? = null

    /** 每槽静音状态（默认四路静音，US-09）。 */
    private val mutedStates = BooleanArray(QuadLayoutState.SLOT_COUNT) {
        settingsStore.snapshot().defaultMute
    }

    /** 当前生效的绑定 id（用于 diff 外部变更，避免重复起流）。 */
    private val boundSourceIds = arrayOfNulls<String>(QuadLayoutState.SLOT_COUNT)

    // ===================== 生命周期 =====================

    /**
     * 启动：加载源库 → 恢复格位绑定 → 起流；并监听源库变更。
     * 幂等保护由 [PlaybackSlot.bind] 的“同源忽略”保证。
     */
    suspend fun start() {
        sourceStore.ensureLoaded()
        restoreBindings()
        observeStoreChanges()
        TvLog.i(TAG, "QuadPlaybackManager 启动完成")
    }

    /** 全部释放（Activity onDestroy）。 */
    fun release() {
        slots.forEach { it.release() }
        quadViews.fill(null)
        overlayView = null
        singlePreviewIndex = null
        managerScope.cancel()
        TvLog.i(TAG, "QuadPlaybackManager 已释放")
    }

    // ===================== 槽位管理 =====================

    /** 绑定/换绑一格（不持久化；持久化由 UI 调 SourceStore.bindSlot 后经 flow 回流）。 */
    fun bindSource(slotIndex: Int, source: VideoSource?) {
        checkSlotIndex(slotIndex)
        boundSourceIds[slotIndex] = source?.id
        slots[slotIndex].bind(source)
        slots[slotIndex].setMuted(mutedStates[slotIndex])
    }

    /** 挂载四分格视图。 */
    fun attachSlotView(slotIndex: Int, view: PlayerView) {
        checkSlotIndex(slotIndex)
        quadViews[slotIndex] = view
        if (singlePreviewIndex != slotIndex) {
            slots[slotIndex].attachView(view)
        }
    }

    /** 摘除四分格视图（Activity 销毁 / 单预览切换时由内部调用）。 */
    fun detachSlotView(slotIndex: Int) {
        checkSlotIndex(slotIndex)
        quadViews[slotIndex] = null
        slots[slotIndex].detachView()
    }

    /** 手动重试（ERROR 态“重试”按钮）。 */
    fun retry(slotIndex: Int) {
        checkSlotIndex(slotIndex)
        slots[slotIndex].retry()
    }

    // ===================== 单预览 =====================

    /**
     * 进入单预览：把该槽 player attach 到 overlay 的 PlayerView。
     *
     * 关键约束（T3 验收 3 + 2026-10-25 surface 冻结修复）：
     * - 全程不 stop / 不 prepare，仅切换视频输出目标；
     * - 顺序“先挂 overlay 再摘格位”（见 [PlaybackSlot.attachView]）：
     *   surface 直切不经过“无 surface”中间态，摘格位时身份守卫命中为空操作，
     *   **不释放解码器**——与 media3 官方 switchTargetView 模式一致；
     * - 返回后由 [exitSinglePreview] 重新挂回四分格视图。
     *
     * @return true=进入成功；已在单预览态或槽位无源时返回 false。
     */
    fun enterSinglePreview(slotIndex: Int, overlay: PlayerView): Boolean {
        checkSlotIndex(slotIndex)
        if (singlePreviewIndex != null) return false
        val state = slots[slotIndex].uiState.value
        if (state.source == null) {
            TvLog.w(TAG, "slot=$slotIndex 未配置源，无法进入单预览")
            return false
        }
        // 挂到 overlay（attachView 内部：先 overlay 拿 surface 直切，再摘格位——no-op）
        slots[slotIndex].attachView(overlay)
        overlayView = overlay
        singlePreviewIndex = slotIndex
        slots[slotIndex].setMuted(mutedStates[slotIndex])
        TvLog.i(TAG, "进入单预览：slot=$slotIndex（${state.source.name}）")
        return true
    }

    /**
     * 退出单预览：恢复四分格视图挂载（四路不中断）。
     *
     * surface 顺序（2026-10-25 修复“返回后画面暂停/冻结”的整体 bug）：
     * **先挂格位再摘 overlay**——格位 surface 一直存在，player 的 video surface
     * 从 overlay 直切格位，不经过“无 surface”中间态；摘 overlay 时身份守卫命中
     * （当前 holder 已是格位的）为空操作，不释放解码器。
     * 旧实现先摘 overlay 再挂格位，触发“清 surface → 解码器释放 → 重建”，
     * 在模拟器/低端盒上重建挂起 → 画面冻结。
     */
    fun exitSinglePreview() {
        val index = singlePreviewIndex ?: return
        quadViews[index]?.let { slots[index].attachView(it) }
        overlayView = null
        singlePreviewIndex = null
        TvLog.i(TAG, "退出单预览，恢复四分格")
    }

    /**
     * 单预览内切换上一路/下一路（仅在有源槽位间轮转）。
     *
     * surface 顺序（同 [exitSinglePreview]）：先把当前路挂回它自己的格位
     * （surface 从 overlay 直切格位，不释放 codec），再把新路挂到 overlay——
     * 此时 setPlayer 内部对旧 player 的 clear 因 holder 已变而是 no-op。
     *
     * 静音语义（US-09）：切走后原槽恢复其自身静音状态，新槽应用其状态，不串台。
     */
    fun switchSinglePreview(delta: Int, overlay: PlayerView): Boolean {
        val current = singlePreviewIndex ?: return false
        val next = nextConfiguredSlot(current, delta)
        if (next == null || next == current) return false
        // 1) 当前路先挂回自己的格位（直切，不释放 codec）
        val currentQuad = quadViews[current] ?: return false
        slots[current].attachView(currentQuad)
        slots[current].setMuted(mutedStates[current])
        // 2) 新路再挂 overlay（对旧 player 的 clear 为 no-op）
        slots[next].attachView(overlay)
        slots[next].setMuted(mutedStates[next])
        singlePreviewIndex = next
        TvLog.i(TAG, "单预览切路：$current -> $next")
        return true
    }

    /** 当前是否单预览态。 */
    fun isSinglePreview(): Boolean = singlePreviewIndex != null

    /** 当前单预览槽位；四分格态返回 null。 */
    fun previewIndex(): Int? = singlePreviewIndex

    // ===================== 静音 =====================

    /**
     * 切换指定槽静音状态（US-09：仅作用于该路）。
     * @return 切换后的静音状态（true=静音）。
     */
    fun toggleMute(slotIndex: Int): Boolean {
        checkSlotIndex(slotIndex)
        mutedStates[slotIndex] = !mutedStates[slotIndex]
        slots[slotIndex].setMuted(mutedStates[slotIndex])
        return mutedStates[slotIndex]
    }

    /** 查询槽静音状态。 */
    fun isMuted(slotIndex: Int): Boolean = mutedStates[slotIndex]

    // ===================== 全局播放控制 =====================

    /** 全部暂停（退后台短时暂留，不释放）。 */
    fun pauseAll() {
        slots.forEach { it.pause() }
    }

    /** 全部恢复。 */
    fun resumeAll() {
        slots.forEach { it.resume() }
    }

    // ===================== 内部 =====================

    /** 从存储恢复绑定并起流。 */
    private suspend fun restoreBindings() {
        val layout = sourceStore.getLayoutState()
        val sources = sourceStore.getSources()
        layout.slots.forEach { binding ->
            val source = binding.sourceId
                ?.let { id -> sources.firstOrNull { it.id == id } }
            boundSourceIds[binding.slotIndex] = source?.id
            slots[binding.slotIndex].bind(source)
            slots[binding.slotIndex].setMuted(mutedStates[binding.slotIndex])
        }
    }

    /**
     * 监听源库布局变更（外部换绑/删除），同步到槽位。
     * 同源绑定被 [PlaybackSlot.bind] 忽略，不会重复起流。
     */
    private fun observeStoreChanges() {
        managerScope.launch {
            sourceStore.layoutState.collect { layout ->
                val sources = sourceStore.getSources()
                layout.slots.forEach { binding ->
                    val index = binding.slotIndex
                    if (boundSourceIds[index] == binding.sourceId) return@forEach
                    val source = binding.sourceId
                        ?.let { id -> sources.firstOrNull { it.id == id } }
                    boundSourceIds[index] = source?.id
                    slots[index].bind(source)
                    slots[index].setMuted(mutedStates[index])
                    TvLog.i(TAG, "外部绑定变更：slot=$index source=${source?.id ?: "空"}")
                }
            }
        }
    }

    /** 从 [from] 起按方向找下一个有源槽位（循环，仅轮转已配置源）。 */
    private fun nextConfiguredSlot(from: Int, delta: Int): Int? {
        if (delta == 0) return from
        val step = if (delta > 0) 1 else -1
        var candidate = from
        repeat(QuadLayoutState.SLOT_COUNT - 1) {
            candidate = (candidate + step + QuadLayoutState.SLOT_COUNT) %
                QuadLayoutState.SLOT_COUNT
            if (slots[candidate].uiState.value.source != null) return candidate
        }
        return null
    }

    private fun checkSlotIndex(slotIndex: Int) {
        require(slotIndex in 0 until QuadLayoutState.SLOT_COUNT) {
            "格位下标越界：$slotIndex"
        }
    }

    companion object {
        private const val TAG = "TvMonitor:QuadManager"
    }
}
