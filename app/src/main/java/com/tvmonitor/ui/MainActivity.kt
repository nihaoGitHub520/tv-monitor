package com.tvmonitor.ui

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.media3.ui.PlayerView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvmonitor.R
import com.tvmonitor.data.JsonSourceStore
import com.tvmonitor.data.PlaybackStats
import com.tvmonitor.data.PlaybackStatus
import com.tvmonitor.data.QuadLayoutState
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.databinding.ActivityMainBinding
import com.tvmonitor.playback.QuadPlaybackManager
import com.tvmonitor.playback.SlotUiState
import com.tvmonitor.util.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 主界面（单 Activity）：四分格播放 + 单预览 Overlay + Fragment 宿主 + 全局按键分发。
 *
 * 关键机制落地：
 * 1. **单预览 attach/detach**（FR-P0-02）：进入/返回只切换 PlayerView 挂载目标，
 *    player 不 stop、不重新 prepare，四路播放不中断；
 * 2. **WakeLock 防休眠**（主理人裁决 5）：PARTIAL_WAKE_LOCK + FLAG_KEEP_SCREEN_ON
 *    + WifiLock（高版本 HIGH_PERF 模式，注意 API 兼容与 DEPRECATION）；
 * 3. **D-pad 全局语义**：四分格焦点矩阵声明式（view_quad_grid.xml nextFocus，越界不 wrap）；
 *    单预览沉浸式按键（左右切路 / info 3s 自动隐藏 / 静音仅当前路 / 返回 / 确认播放暂停）；
 * 4. **错误态入口**：每格重连倒计时浮层 + 手动重试（US-07）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sourceStore: JsonSourceStore
    private lateinit var settingsStore: SettingsStore

    /**
     * 播放编排器（internal：同模块的 SourceManageFragment 订阅其状态流，
     * 用于源列表状态点反映真实播放态，US-08）。
     */
    internal lateinit var playbackManager: QuadPlaybackManager
    private lateinit var overlay: SinglePreviewOverlay

    /** Activity 级协程作用域（持有播放管理器；onDestroy 取消）。 */
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ===================== WakeLock =====================

    // SDK 35 上 getSystemService(Class) 标注 @Nullable：显式非空类型 + !!
    // （平台类型推断在 @Nullable 注解下会推断为可空， WakeLock 使用处编译不过）
    private val powerManager: PowerManager by lazy {
        getSystemService(PowerManager::class.java)!!
    }
    private val wifiManager: WifiManager by lazy {
        applicationContext.getSystemService(WifiManager::class.java)!!
    }
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 是否已请求初始焦点（状态流首次到达时聚焦首个有源格）。 */
    private var initialFocusRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceStore = JsonSourceStore.get(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        TvLog.enabled = settingsStore.snapshot().logEnabled

        playbackManager = QuadPlaybackManager(
            context = this,
            sourceStore = sourceStore,
            settingsStore = settingsStore,
            managerScope = activityScope,
        )

        overlay = SinglePreviewOverlay(
            root = binding.singlePreviewRoot,
            playerView = binding.singlePreviewPlayer,
            header = binding.singlePreviewHeader,
            titleView = binding.singlePreviewTitle,
            infoPanel = binding.infoPanel,
            infoView = binding.singlePreviewInfo,
            muteButton = binding.btnMute,
            hintView = binding.singlePreviewHint,
        )
        overlay.onMuteToggle = { toggleMuteCurrentSlot() }
        overlay.onPlayPauseToggle = { togglePlayPause() }

        binding.quadGrid.onSlotClick = { index -> enterSinglePreview(index) }
        binding.quadGrid.onSlotRetry = { index -> playbackManager.retry(index) }

        // 屏幕常亮 + 防休眠
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireWakeLocks()

        supportFragmentManager.addOnBackStackChangedListener {
            binding.fragmentContainer.isVisible =
                supportFragmentManager.backStackEntryCount > 0
        }

        activityScope.launch {
            playbackManager.start()
            // 起流后挂载四分格视图
            binding.quadGrid.slotViews.forEachIndexed { index, slotView ->
                playbackManager.attachSlotView(index, slotView.playerView)
            }
            refreshQuadMuteIcons()
        }
        observeSlotStates()
        TvLog.i(TAG, "MainActivity 创建完成")
    }

    override fun onResume() {
        super.onResume()
        acquireWakeLocks()
    }

    override fun onDestroy() {
        releaseWakeLocks()
        playbackManager.release()
        activityScope.cancel()
        super.onDestroy()
        TvLog.i(TAG, "MainActivity 销毁")
    }

    // ===================== 状态订阅 =====================

    /** 合并四路状态流：刷新四分格与单预览 Overlay；首次有源时聚焦。 */
    private fun observeSlotStates() {
        activityScope.launch {
            combine(playbackManager.slotStates) { states -> states.toList() }
                .collect { states ->
                    binding.quadGrid.render(states)
                    if (playbackManager.isSinglePreview()) {
                        playbackManager.previewIndex()?.let { index ->
                            overlay.renderState(states[index])
                        }
                    }
                    if (!initialFocusRequested) {
                        val index = binding.quadGrid.firstConfiguredIndex(states)
                        if (index != null) {
                            binding.quadGrid.focusSlot(index)
                            initialFocusRequested = true
                        }
                    }
                }
        }
    }

    // ===================== 全局按键分发（D-pad 语义） =====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val inPreview = playbackManager.isSinglePreview()
        val inFragment = supportFragmentManager.backStackEntryCount > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBack()
                return true
            }

            // ---- 键盘鼠标扩展（2026-10-25）：模拟器 / 键鼠环境 ----
            // Esc / Del(退格) = 返回（键盘无 BACK 键时的等价键；注意 Android 退格键常量为 KEYCODE_DEL）
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> {
                handleBack()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                if (handleMenuKey()) return true
            }

            // M = 菜单（键盘直接打开源管理）
            KeyEvent.KEYCODE_M -> {
                if (!inPreview && handleMenuKey()) return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                if (!inPreview && !inFragment) {
                    openSettings()
                    return true
                }
            }

            // S = 设置（键盘快捷键）
            KeyEvent.KEYCODE_S -> {
                if (!inPreview && !inFragment) {
                    openSettings()
                    return true
                }
            }

            // 1~4 = 直达对应格：聚焦 + 有源即进单预览（快速监看某一路）
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4 -> {
                if (!inPreview && !inFragment) {
                    val index = event.keyCode - KeyEvent.KEYCODE_1
                    binding.quadGrid.focusSlot(index)
                    if (playbackManager.slotStates[index].value.source != null) {
                        enterSinglePreview(index)
                    }
                    return true
                }
            }

            // 空格 / 播放暂停键 = 单预览内播放暂停
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (inPreview) {
                    togglePlayPause()
                    return true
                }
            }

            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                if (inPreview) {
                    showInfoOverlay()
                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (inPreview) {
                    toggleMuteCurrentSlot()
                    return true
                }
                if (!inFragment) {
                    // 四分格态：仅切换焦点格（US-09：其余格保持，切格不串台）
                    toggleMuteFocusedSlot()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (inPreview) {
                    playbackManager.switchSinglePreview(-1, binding.singlePreviewPlayer)
                    refreshPreviewOverlay()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inPreview) {
                    playbackManager.switchSinglePreview(1, binding.singlePreviewPlayer)
                    refreshPreviewOverlay()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (inPreview) {
                    togglePlayPause()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 菜单键语义（MENU 键与键盘 M 共用）。
     * @return true=已消费；false=未处理（调用方落回 super 分发）。
     */
    private fun handleMenuKey(): Boolean {
        val inPreview = playbackManager.isSinglePreview()
        val inFragment = supportFragmentManager.backStackEntryCount > 0
        return when {
            // 单预览无菜单语义（沉浸观看）
            inPreview -> false
            inFragment -> {
                // 转发给当前 Fragment（源管理等列表页的上下文菜单）
                val handler = supportFragmentManager.fragments
                    .lastOrNull { it is MenuKeyHandler } as? MenuKeyHandler
                handler?.onMenuKey() == true
            }
            else -> {
                openSourceManage()
                true
            }
        }
    }

    // ===================== 鼠标滚轮（模拟器 / 键鼠环境） =====================

    /**
     * 鼠标滚轮映射：
     * - 单预览态：滚轮 = 上/下一路切换；
     * - 四分格态：滚轮 = 焦点格上/下移动（2x2，越界不动）；
     * - Fragment 态：透传（列表滚动由系统焦点导航处理）。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                val forward = vScroll < 0 // 向下滚 = 下一个
                when {
                    playbackManager.isSinglePreview() -> {
                        playbackManager.switchSinglePreview(
                            if (forward) 1 else -1,
                            binding.singlePreviewPlayer,
                        )
                        refreshPreviewOverlay()
                        return true
                    }

                    supportFragmentManager.backStackEntryCount == 0 -> {
                        val current = binding.quadGrid.focusedSlotIndex()
                        if (current != null) {
                            val target = current + if (forward) 2 else -2
                            if (target in 0 until QuadLayoutState.SLOT_COUNT) {
                                binding.quadGrid.focusSlot(target)
                            }
                        }
                        return true
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 返回键语义：单预览→四分格；Fragment→回退；主界面→退出确认。 */
    private fun handleBack() {
        when {
            playbackManager.isSinglePreview() -> exitSinglePreview()
            supportFragmentManager.backStackEntryCount > 0 ->
                supportFragmentManager.popBackStack()
            else -> showExitConfirmDialog()
        }
    }

    private fun showExitConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    // ===================== 单预览 =====================

    private fun enterSinglePreview(index: Int) {
        if (playbackManager.enterSinglePreview(index, binding.singlePreviewPlayer)) {
            overlay.show()
            refreshPreviewOverlay()
            TvLog.i(TAG, "进入单预览 slot=$index")
        }
    }

    private fun exitSinglePreview() {
        val index = playbackManager.previewIndex()
        playbackManager.exitSinglePreview()
        overlay.hide()
        // 返回四分格 = 恢复实时监看：单预览内的用户暂停不带回四分格
        // （2026-10-25 用户反馈：放大再返回后画面暂停）
        index?.let { playbackManager.slots[it].resume() }
        refreshQuadMuteIcons()
        TvLog.i(TAG, "返回四分格")
    }

    /** 切换/进入后刷新 Overlay 标题与错误态（切路时复现左上角 chrome 3s）。 */
    private fun refreshPreviewOverlay() {
        val index = playbackManager.previewIndex() ?: return
        overlay.renderState(playbackManager.slotStates[index].value)
        overlay.setMuted(playbackManager.isMuted(index))
        overlay.showChrome()
    }

    /** info 键：显示播放统计 overlay，3s 自动隐藏（US-10）。 */
    private fun showInfoOverlay() {
        val index = playbackManager.previewIndex() ?: return
        overlay.toggleInfo(playbackManager.slotStates[index].value.stats)
    }

    /** 静音键：仅切换当前路（US-09）。 */
    private fun toggleMuteCurrentSlot() {
        val index = playbackManager.previewIndex() ?: return
        val muted = playbackManager.toggleMute(index)
        overlay.setMuted(muted)
        // 静音图标随 chrome 复现 3s，让用户看到切换结果
        overlay.showChrome()
        // 单预览内的静音切换同步回四分格图标（返回后状态可见）
        refreshQuadMuteIcons()
        Toast.makeText(
            this,
            if (muted) R.string.muted_hint else R.string.unmuted_hint,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 四分格态静音键：仅切换当前焦点格（US-09）。
     * 无焦点格时不动作；其余格静音状态保持不变（不串台）。
     */
    private fun toggleMuteFocusedSlot() {
        val index = binding.quadGrid.focusedSlotIndex() ?: return
        val muted = playbackManager.toggleMute(index)
        binding.quadGrid.slotViews[index].setMuted(muted)
        Toast.makeText(
            this,
            if (muted) R.string.muted_hint else R.string.unmuted_hint,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 同步四分格各槽静音图标（起流后 / 单预览返回后刷新）。 */
    private fun refreshQuadMuteIcons() {
        binding.quadGrid.slotViews.forEachIndexed { index, slotView ->
            slotView.setMuted(playbackManager.isMuted(index))
        }
    }

    /** 确认键：播放/暂停当前路（暂停态在标题追加“已暂停”，chrome 复现 3s）。 */
    private fun togglePlayPause() {
        val index = playbackManager.previewIndex() ?: return
        if (overlay.isPaused) {
            playbackManager.slots[index].resume()
            overlay.setPaused(false)
        } else {
            playbackManager.slots[index].pause()
            overlay.setPaused(true)
        }
        overlay.showChrome()
    }

    // ===================== Fragment 导航 =====================

    private fun openSourceManage() {
        showFragment(SourceManageFragment.newInstance())
    }

    private fun openSettings() {
        showFragment(SettingsFragment.newInstance())
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ===================== WakeLock =====================

    /**
     * 获取 WakeLock：
     * - PARTIAL_WAKE_LOCK：CPU 持续运行，退后台/熄屏不断流；
     * - WifiLock：防止 WLAN 省电策略切断组播/长连接（API 29+ 用 HIGH_PERF，
     *   低版本用 FULL；两者均 deprecation，但监控长连接场景无公开替代 API）。
     */
    private fun acquireWakeLocks() {
        if (partialWakeLock?.isHeld != true) {
            partialWakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .also { it.acquire() }
        }
        acquireWifiLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        } else {
            WifiManager.WIFI_MODE_FULL
        }
        val lock = wifiManager.createWifiLock(mode, WAKE_LOCK_TAG)
        runCatching { lock.acquire() }
            .onFailure { TvLog.w(TAG, "WifiLock 获取失败：${it.message}") }
        wifiLock = lock
    }

    private fun releaseWakeLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { partialWakeLock?.takeIf { it.isHeld }?.release() }
        partialWakeLock = null
    }

    companion object {
        private const val TAG = "TvMonitor:MainActivity"
        private const val WAKE_LOCK_TAG = "tvmonitor:keepalive"
    }
}

/**
 * 单预览 Overlay（内嵌于 MainActivity.kt 的视图控制器）。
 *
 * 管理：左上角 chrome（标题 + 静音 + 信息 overlay）、底部操作提示、播放/暂停态。
 * 播放器挂载由 [QuadPlaybackManager.enterSinglePreview] 完成（attach 不重起流）。
 *
 * 自动隐藏（2026-10-25 用户需求）：
 * - 左上角流信息（标题）与静音图标：进入预览 3s 后自动消失；Info 键唤出统计时复现 3s；
 * - 底部操作方式：每次进入预览显示 5s 后自动消失。
 */
class SinglePreviewOverlay(
    private val root: View,
    private val playerView: PlayerView,
    private val header: View,
    private val titleView: TextView,
    private val infoPanel: View,
    private val infoView: TextView,
    private val muteButton: ImageButton,
    private val hintView: TextView,
) {
    var onMuteToggle: (() -> Unit)? = null
    var onPlayPauseToggle: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var chromeHideRunnable: Runnable? = null
    private var hintHideRunnable: Runnable? = null
    private var paused: Boolean = false

    /** 基础标题（源名）。 */
    private var baseTitle: String = ""

    /** 状态后缀（错误 / 重连中 / 已暂停），空表示无后缀。 */
    private var statusSuffix: String? = null

    /** 是否处于用户暂停态（ overlay 展示“已暂停”提示）。 */
    val isPaused: Boolean get() = paused

    init {
        muteButton.setOnClickListener { onMuteToggle?.invoke() }
        playerView.setOnClickListener { onPlayPauseToggle?.invoke() }
    }

    fun show() {
        root.isVisible = true
        paused = false
        // 左上角 chrome 3s 消失；底部操作提示 5s 消失
        showChrome()
        hintView.isVisible = true
        cancelHintHide()
        hintHideRunnable = Runnable { hintView.isVisible = false }.also {
            handler.postDelayed(it, HINT_AUTO_HIDE_MS)
        }
    }

    fun hide() {
        root.isVisible = false
        cancelAutoHide()
        infoPanel.isVisible = false
    }

    /** 显示左上角 chrome（标题 + 静音 + 信息面板）并排 3s 自动隐藏。 */
    fun showChrome() {
        header.isVisible = true
        cancelChromeHide()
        chromeHideRunnable = Runnable { header.isVisible = false }.also {
            handler.postDelayed(it, CHROME_AUTO_HIDE_MS)
        }
    }

    fun bindTitle(name: String) {
        baseTitle = name
        titleView.text = renderTitle()
    }

    /** 渲染当前路状态（标题 + 错误/重连提示）。 */
    fun renderState(state: SlotUiState) {
        baseTitle = state.source?.name.orEmpty()
        statusSuffix = when (state.status) {
            PlaybackStatus.ERROR ->
                errorCodeToUserMessage(root.context, state.errorCode, state.errorMessage)
            PlaybackStatus.RECONNECTING -> root.context.getString(
                R.string.reconnect_countdown_format,
                state.countdownSec,
                state.reconnectAttempt,
            )
            else -> null
        }
        titleView.text = renderTitle()
    }

    /** 标题 = 基础标题 + 可选状态后缀。 */
    private fun renderTitle(): String {
        val suffix = statusSuffix?.takeIf { it.isNotBlank() }
        return if (suffix.isNullOrEmpty()) baseTitle else "$baseTitle（$suffix）"
    }

    fun setMuted(muted: Boolean) {
        muteButton.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
        )
        muteButton.contentDescription = root.context.getString(
            if (muted) R.string.action_mute else R.string.action_unmute,
        )
    }

    /** 播放/暂停态切换（标题追加“已暂停”后缀）。 */
    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            val suffix = root.context.getString(R.string.action_paused)
            titleView.text = if (baseTitle.isEmpty()) suffix else "$baseTitle（$suffix）"
        } else {
            titleView.text = renderTitle()
        }
    }

    /** 切换信息 overlay（再按一次关闭；3s 无操作自动隐藏，US-10；唤出时连带标题复现）。 */
    fun toggleInfo(stats: PlaybackStats) {
        if (infoPanel.isVisible) {
            hideInfo()
        } else {
            showInfo(stats)
        }
    }

    fun showInfo(stats: PlaybackStats) {
        infoView.text = formatStats(infoView.context, stats)
        showChrome()
        infoPanel.isVisible = true
    }

    fun hideInfo() {
        infoPanel.isVisible = false
    }

    private fun cancelAutoHide() {
        cancelChromeHide()
        cancelHintHide()
    }

    private fun cancelChromeHide() {
        chromeHideRunnable?.let { handler.removeCallbacks(it) }
        chromeHideRunnable = null
    }

    private fun cancelHintHide() {
        hintHideRunnable?.let { handler.removeCallbacks(it) }
        hintHideRunnable = null
    }

    companion object {
        /** 左上角 chrome（流信息 + 静音图标）自动隐藏延时（3s）。 */
        const val CHROME_AUTO_HIDE_MS = 3_000L

        /** 底部操作提示自动隐藏延时（每次进入预览 5s）。 */
        const val HINT_AUTO_HIDE_MS = 5_000L

        /** info overlay 自动隐藏延时（US-10：3s）。 */
        const val INFO_AUTO_HIDE_MS = 3_000L
    }
}

/**
 * 播放统计格式化（US-10 info overlay 文本）。
 * 例：`分辨率：1920x1080  帧率：25  视频码率：4.0 Mbps  缓冲：380ms  丢帧：0`
 */
internal fun formatStats(context: android.content.Context, stats: PlaybackStats): String {
    val unknown = context.getString(R.string.info_unknown)
    val pairs = listOf(
        context.getString(R.string.info_resolution) to
            stats.resolutionText.ifEmpty { unknown },
        context.getString(R.string.info_framerate) to
            if (stats.frameRate > 0f) "%.0f".format(stats.frameRate) else unknown,
        context.getString(R.string.info_bitrate_video) to
            formatBitrate(stats.videoBitrateBps, unknown),
        context.getString(R.string.info_bitrate_audio) to
            formatBitrate(stats.audioBitrateBps, unknown),
        context.getString(R.string.info_buffer) to
            if (stats.bufferMs > 0) "${stats.bufferMs}ms" else unknown,
        context.getString(R.string.info_dropped_frames) to stats.droppedFrames.toString(),
    )
    return pairs.joinToString("  ") { (label, value) ->
        context.getString(R.string.info_value_format, label, value)
    }
}

/** 码率格式化：>=1Mbps 用 Mbps，否则 Kbps；0 值返回占位。 */
internal fun formatBitrate(bps: Long, unknown: String): String = when {
    bps <= 0L -> unknown
    bps >= 1_000_000L -> "%.1f Mbps".format(bps / 1_000_000.0)
    else -> "%.0f Kbps".format(bps / 1000.0)
}
