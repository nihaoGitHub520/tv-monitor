package com.tvmonitor.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
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
 * 功能：源列表（名称/类型/地址/状态）、添加、编辑、删除、绑定格位、测试播放。
 * 交互：上下键移动焦点行；确认键/点击 = 进入编辑（2026-10-25 用户需求：
 * 已添加的源再次点击即进入编辑修改）；菜单键 = 操作菜单（测试播放/编辑/删除/绑定）。
 */
class SourceManageFragment : Fragment(), MenuKeyHandler {

    private var _binding: com.tvmonitor.databinding.FragmentSourceManageBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: SourceManageViewModel
    private lateinit var adapter: SourceAdapter

    /** 四分格实时状态快照（源列表状态点反映真实播放态，US-08）。 */
    private var slotStates: List<SlotUiState> = emptyList()

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
        }
        observeSlotStates()
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

        fun submitList(list: List<VideoSource>) {
            items = list
            notifyDataSetChanged()
        }

        /** 状态点刷新（播放状态变化；列表量小，整体重绑可接受）。 */
        fun refreshStatus() {
            notifyDataSetChanged()
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

            init {
                // 确认键 / 鼠标点击 = 进入编辑（2026-10-25 用户需求）
                itemBinding.root.setOnClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let { openWizard(it.id) }
                }
            }

            fun bind(source: VideoSource) {
                itemBinding.tvName.text = source.name
                itemBinding.tvAddress.text = source.address
                itemBinding.tvType.text = typeLabel(source.type)
                itemBinding.ivTypeIcon.setImageResource(typeIcon(source.type))
                itemBinding.ivStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    statusColorFor(source),
                )
            }
        }
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
