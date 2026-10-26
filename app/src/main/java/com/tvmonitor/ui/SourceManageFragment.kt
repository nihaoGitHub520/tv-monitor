package com.tvmonitor.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.liveData
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvmonitor.R
import com.tvmonitor.TvMonitorApp
import com.tvmonitor.data.JsonSourceStore
import com.tvmonitor.data.PlaybackStatus
import com.tvmonitor.data.QuadLayoutState
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.data.SourceStore
import com.tvmonitor.data.SourceType
import com.tvmonitor.data.VideoSource
import com.tvmonitor.playback.DefaultPlayerFactory
import com.tvmonitor.playback.PlayerFactory
import com.tvmonitor.playback.PlayerOptions
import com.tvmonitor.playback.ResolverRegistry
import com.tvmonitor.playback.SlotUiState
import com.tvmonitor.playback.SourceUriResolver
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.TvLog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 菜单键处理者（Fragment 实现，由 MainActivity 转发菜单键）。
 */
interface MenuKeyHandler {
    /** 处理菜单键；返回 true 表示已消费。 */
    fun onMenuKey(): Boolean
}

/**
 * 源管理 Fragment（T5 / US-08 / FR-P0-04）。
 *
 * 功能：源列表（通道号/名称/类型/地址/状态）、添加、编辑、删除、绑定格位、测试播放。
 * 交互：上下键移动焦点行；确认键/点击 = 进入编辑（2026-10-25 用户需求：
 * 已添加的源再次点击即进入编辑修改）；菜单键 = 操作菜单（测试播放/编辑/删除/绑定）。
 *
 * 2026-10-26 修复“点很多下才进编辑”三重诱因：
 * 1. 行布局移除 focusableInTouchMode（AOSP focusTaken 逻辑：未聚焦行首次点击只抢焦点）；
 * 2. 点击用 bind 时固化的源引用，不再依赖 bindingAdapterPosition（刷新窗口内为 NO_POSITION）；
 * 3. 状态点仅在变化时刷新，消除 500ms 无条件 notifyDataSetChanged 的点击丢失窗口。
 */
class SourceManageFragment : Fragment(), MenuKeyHandler {

    private var _binding: com.tvmonitor.databinding.FragmentSourceManageBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: SourceManageViewModel
    private lateinit var adapter: SourceAdapter

    /** 四分格实时状态快照（源列表状态点反映真实播放态，US-08）。 */
    private var slotStates: List<SlotUiState> = emptyList()

    /**
     * 首行自动聚焦待办（2026-10-26）。
     * true = 本次视图生命周期内还没聚焦过首行；布局完成后聚焦一次即置 false，
     * 之后不再抢焦点（用户主动移焦点到别处时不受干扰）。onDestroyView 重置，
     * 保证每次进入源管理页（含从向导返回）都重新聚焦首行。
     */
    private var firstRowFocusPending = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = com.tvmonitor.databinding.FragmentSourceManageBinding.inflate(
            inflater,
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            requireActivity(),
            SourceManageViewModel.factory(),
        )[SourceManageViewModel::class.java]

        adapter = SourceAdapter()
        binding.rvSources.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSources.adapter = adapter

        binding.btnAdd.setOnClickListener { openWizard(sourceId = null) }
        binding.btnAdd.requestFocus()

        viewModel.sources.observe(viewLifecycleOwner) { sources ->
            adapter.submitList(sources)
            binding.tvEmpty.isVisible = sources.isEmpty()
            binding.rvSources.isVisible = sources.isNotEmpty()
            // 2026-10-26：进入页面且列表非空时，聚焦首行（一次性，见 firstRowFocusPending）。
            // 原来焦点停在"添加源"按钮上，rvSources.focusedChild 恒为 null，导致：
            // 菜单键上下文菜单（测试播放/编辑/删除/绑定）无效；D-pad 焦点易穿透到
            // 四分格；确认键编辑入口只剩鼠标点击一条路。聚焦首行后三条路都通。
            if (sources.isNotEmpty() && firstRowFocusPending) {
                // b20→b21 修复要点：
                // 1. 首行聚焦用 doOnLayout——首次进入页面时 rv 尚未布局，doOnLayout 在“首次
                //    布局完成后”回调，此时首行 ViewHolder 已就绪（b20 实测 doOnLayout 能可靠
                //    把焦点设到 row0；更早的 post{} 写法在布局前执行会拿到 null）。
                // 2. 真正的焦点丢失根因：随后 setChannels/refreshStatus 的 notifyDataSetChanged
                //    把聚焦行 detach 重建，焦点回落到底层四分格 slot_0（uiautomator dump 实证）。
                //    已把两处刷新改为 notifyItemRangeChanged（保留 ViewHolder 实例，焦点不丢），
                //    配合此处一次性聚焦，菜单键/D-pad/确认键三条交互路径才真正通畅。
                val rv = binding.rvSources
                rv.doOnLayout {
                    val holder = rv.findViewHolderForAdapterPosition(0)
                    if (holder != null && firstRowFocusPending) {
                        holder.itemView.requestFocus()
                        firstRowFocusPending = false
                    }
                }
            }
        }
        observeSlotStates()
        observeChannelBindings()
    }

    /**
     * 订阅格位绑定流（2026-10-26 用户需求：源列表显示通道号）。
     * 通道号 = 绑定的格位（1~4）；未绑定显示“未绑定”。
     */
    private fun observeChannelBindings() {
        viewModel.layoutState.observe(viewLifecycleOwner) { state ->
            val map = state.slots.mapNotNull { slot ->
                slot.sourceId?.let { it to slot.slotIndex + 1 }
            }.toMap()
            adapter.setChannels(map)
        }
    }

    /**
     * 订阅四分格实时状态（宿主 Activity 的 QuadPlaybackManager，US-08）。
     * 状态点按“源当前绑定的格位播放状态”着色；未绑定格位的源用 idle 灰。
     */
    private fun observeSlotStates() {
        val manager = (requireActivity() as? MainActivity)?.playbackManager ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(manager.slotStates) { it.toList() }
                    .collect { states ->
                        slotStates = states
                        adapter.refreshStatus()
                    }
            }
        }
    }

    override fun onDestroyView() {
        // 重置首行聚焦待办：下次进入本页（视图重建）重新聚焦首行
        firstRowFocusPending = true
        _binding = null
        super.onDestroyView()
    }

    /** 菜单键：对当前焦点行弹出操作菜单。 */
    override fun onMenuKey(): Boolean {
        val binding = _binding ?: return false
        val focused = binding.rvSources.focusedChild ?: return false
        val holder = binding.rvSources.getChildViewHolder(focused) as? SourceAdapter.SourceHolder
        val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
        val source = adapter.currentList.getOrNull(position)
        if (source != null) {
            showSourceActions(source)
            return true
        }
        return false
    }

    private fun showSourceActions(source: VideoSource) {
        val actions = arrayOf(
            getString(R.string.action_test_play),
            getString(R.string.action_edit),
            getString(R.string.action_delete),
            getString(R.string.action_bind_slot),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(source.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openTestPlay(source)
                    1 -> openWizard(source.id)
                    2 -> confirmDelete(source)
                    3 -> showBindSlotDialog(source)
                }
            }
            .show()
    }

    private fun confirmDelete(source: VideoSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_source_confirm_format, source.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.delete(source.id)
                Toast.makeText(requireContext(), R.string.source_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showBindSlotDialog(source: VideoSource) {
        val labels = (0 until QuadLayoutState.SLOT_COUNT)
            .map { getString(R.string.slot_label_format, it + 1) }
            .toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_bind_slot)
            .setItems(labels) { _, index ->
                viewModel.bindSlot(index, source.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.source_bound_format, index + 1),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 测试播放（临时 slot）：全屏对话框 + 独立播放器，不影响四分格。 */
    private fun openTestPlay(source: VideoSource) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val playerView = PlayerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            useController = false
            setKeepContentOnPlayerReset(true)
        }
        dialog.setContentView(playerView)
        dialog.setOnDismissListener {
            (playerView.player as? ExoPlayer)?.release()
            playerView.player = null
        }
        dialog.show()
        viewModel.testPlay(source, playerView)
    }

    private fun openWizard(sourceId: String?) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, AddSourceWizardFragment.newInstance(sourceId))
            .addToBackStack(null)
            .commit()
    }

    private fun typeLabel(type: SourceType): String = when (type) {
        SourceType.ONVIF -> getString(R.string.type_onvif)
        SourceType.STREAM -> getString(R.string.type_stream)
        SourceType.M3U8 -> getString(R.string.type_m3u8)
        SourceType.FILE -> getString(R.string.type_file)
    }

    private fun typeIcon(type: SourceType): Int = when (type) {
        SourceType.ONVIF -> R.drawable.ic_camera
        SourceType.STREAM -> R.drawable.ic_link
        SourceType.M3U8 -> R.drawable.ic_video_file
        SourceType.FILE -> R.drawable.ic_folder
    }

    /**
     * 源 → 状态点颜色（US-08）：按该源当前绑定的格位播放状态着色；
     * 未绑定任何格位或状态未知时用 idle 灰。
     */
    private fun statusColorFor(source: VideoSource): Int {
        val state = slotStates.firstOrNull { it.source?.id == source.id }
        val colorRes = when (state?.status) {
            PlaybackStatus.ONLINE -> R.color.status_online
            PlaybackStatus.LOADING -> R.color.status_loading
            PlaybackStatus.RECONNECTING -> R.color.status_reconnecting
            PlaybackStatus.ERROR -> R.color.status_offline
            else -> R.color.status_idle
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    // ===================== 列表适配器 =====================

    private inner class SourceAdapter : RecyclerView.Adapter<SourceAdapter.SourceHolder>() {

        private var items: List<VideoSource> = emptyList()

        /** sourceId → 通道号（1-based；未绑定的源不在 map 中）。 */
        private var channels: Map<String, Int> = emptyMap()

        /** 上次状态快照（sourceId → 播放状态）：无变化不重绑，避免 500ms 全量刷新。 */
        private var lastStatuses: Map<String, PlaybackStatus?> = emptyMap()

        fun submitList(list: List<VideoSource>) {
            if (list == items) return
            // DiffUtil 增量更新：notifyDataSetChanged 会把已聚焦的行 detach 再重建，
            // 焦点丢失回落到底层四分格（b20 实测：首行聚焦、D-pad 导航全被打断）。
            // 增量更新只重绑变化项，未变化的行视图实例保留，焦点自然保住。
            val diff = DiffUtil.calculateDiff(SourceDiffCallback(items, list))
            items = list
            diff.dispatchUpdatesTo(this)
        }

        /** 通道号映射变化时整体重绑（绑定/解绑操作触发，频率低）。 */
        fun setChannels(map: Map<String, Int>) {
            if (map == channels) return
            channels = map
            // 同上：整表重绑而非 notifyDataSetChanged，保住焦点行
            if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
        }

        /**
         * 状态点刷新（播放状态变化；列表量小，整表重绑可接受）。
         *
         * 2026-10-26：仅在状态真的变化时刷新——原来每 500ms 无条件 notifyDataSetChanged，
         * 刷新窗口内 bindingAdapterPosition 为 NO_POSITION，落在窗口里的点击会静默丢失，
         * 是“点很多下才进编辑”的诱因之一。
         * 2026-10-26 晚：notifyDataSetChanged → notifyItemRangeChanged，
         * 否则会把聚焦行 detach 导致焦点回落到四分格（见 submitList 注释）。
         */
        fun refreshStatus() {
            val current = items.associate { source ->
                source.id to slotStates.firstOrNull { it.source?.id == source.id }?.status
            }
            if (current != lastStatuses) {
                lastStatuses = current
                if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
            }
        }

        val currentList: List<VideoSource> get() = items

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceHolder =
            SourceHolder(
                com.tvmonitor.databinding.ItemSourceBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )

        override fun onBindViewHolder(holder: SourceHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class SourceHolder(
            private val itemBinding: com.tvmonitor.databinding.ItemSourceBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            /**
             * bind 时固化的源引用。
             *
             * 2026-10-26：点击不再用 bindingAdapterPosition 现查——适配器刷新窗口内
             * 该位置为 NO_POSITION，getOrNull(-1) 静默丢失点击。固化引用后点击
             * 与刷新时序完全解耦，一次点击必进编辑。
             */
            private var boundSource: VideoSource? = null

            init {
                // 确认键 / 鼠标点击 = 进入编辑（2026-10-25 用户需求）
                itemBinding.root.setOnClickListener {
                    boundSource?.let { openWizard(it.id) }
                }
            }

            fun bind(source: VideoSource) {
                boundSource = source
                itemBinding.tvName.text = source.name
                itemBinding.tvAddress.text = source.address
                itemBinding.tvType.text = typeLabel(source.type)
                itemBinding.ivTypeIcon.setImageResource(typeIcon(source.type))
                // 通道号（2026-10-26 用户需求）
                val channel = channels[source.id]
                itemBinding.tvChannel.text = if (channel != null) {
                    itemBinding.root.context.getString(R.string.channel_format, channel)
                } else {
                    itemBinding.root.context.getString(R.string.channel_unbound)
                }
                itemBinding.ivStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    statusColorFor(source),
                )
            }
        }
    }

    /** 源列表 DiffUtil 比较（按 id 识别、按全字段判内容）。 */
    private class SourceDiffCallback(
        private val oldList: List<VideoSource>,
        private val newList: List<VideoSource>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition].id == newList[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    companion object {
        fun newInstance(): SourceManageFragment = SourceManageFragment()
    }
}

/**
 * 源管理 ViewModel。
 *
 * 持有 [SourceStore] 与测试播放所需的协议注册表 / 播放器工厂；
 * 测试播放在临时播放器中进行（T5 验收“临时 slot”），与四分格播放器隔离。
 */
class SourceManageViewModel(
    private val sourceStore: SourceStore,
    private val settingsStore: SettingsStore,
    private val resolver: SourceUriResolver = ResolverRegistry.createDefault(),
    private val playerFactory: PlayerFactory = DefaultPlayerFactory(TvMonitorApp.instance),
) : ViewModel() {

    /** 源列表（存储层 Flow 驱动）。 */
    val sources: LiveData<List<VideoSource>> = liveData {
        sourceStore.sources.collect { emit(it) }
    }

    /** 格位绑定状态流（源列表通道号：绑定的格位 1~4，2026-10-26 用户需求）。 */
    val layoutState: LiveData<QuadLayoutState> = liveData {
        sourceStore.layoutState.collect { emit(it) }
    }

    fun delete(sourceId: String) {
        viewModelScope.launch {
            sourceStore.delete(sourceId)
        }
    }

    fun bindSlot(slotIndex: Int, sourceId: String) {
        viewModelScope.launch {
            sourceStore.bindSlot(slotIndex, sourceId)
        }
    }

    /**
     * 测试播放：解析地址 → 临时 ExoPlayer 播放。
     * 失败时 Toast 明确原因（协议不支持 / 超时 / 连接失败等）。
     */
    fun testPlay(source: VideoSource, playerView: PlayerView) {
        viewModelScope.launch {
            val snapshot = settingsStore.snapshot()
            val options = PlayerOptions(
                bufferLimitMb = snapshot.bufferLimitMb,
                maxResolutionHeight = snapshot.maxResolutionHeight,
            )
            when (val result = resolver.resolve(source)) {
                is AppResult.Failure -> Toast.makeText(
                    TvMonitorApp.instance,
                    result.error.toUserMessage(TvMonitorApp.instance),
                    Toast.LENGTH_LONG,
                ).show()

                is AppResult.Success -> {
                    val player = playerFactory.create(result.data.kind, options)
                    player.setMediaItem(
                        androidx.media3.common.MediaItem.fromUri(result.data.uri),
                    )
                    player.prepare()
                    player.play()
                    playerView.player = player
                    TvLog.i(TAG, "测试播放：${source.name}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "TvMonitor:SourceManageVM"

        /** 默认工厂：从 Application 装配存储依赖。 */
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TvMonitorApp.instance
                return SourceManageViewModel(
                    sourceStore = JsonSourceStore.get(app),
                    settingsStore = SettingsStore(app),
                ) as T
            }
        }
    }
}
