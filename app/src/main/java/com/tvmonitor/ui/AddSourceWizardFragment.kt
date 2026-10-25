package com.tvmonitor.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvmonitor.R
import com.tvmonitor.TvMonitorApp
import com.tvmonitor.data.CredentialCrypto
import com.tvmonitor.data.JsonSourceStore
import com.tvmonitor.data.M3uParseReport
import com.tvmonitor.data.M3uParser
import com.tvmonitor.data.OnvifDevice
import com.tvmonitor.data.OnvifDeviceInfo
import com.tvmonitor.data.OnvifProfile
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.data.SourceStore
import com.tvmonitor.data.SourceType
import com.tvmonitor.data.StreamKind
import com.tvmonitor.data.VideoSource
import com.tvmonitor.onvif.OnvifClient
import com.tvmonitor.onvif.OnvifDiscovery
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 添加/编辑源向导 Fragment（T5 / US-03~06）。
 *
 * 步骤机：选择类型 → 类型表单（ONVIF 发现/手动、推流直链、M3U8/导入、本地文件）
 * → 命名 + 格位绑定 → 保存。
 *
 * 编辑模式：传入 sourceId，预填各字段；保存时按 id 更新。
 * 保存后格位绑定经 [SourceStore.bindSlot] 持久化，主界面经 Flow 自动起播。
 */
class AddSourceWizardFragment : Fragment() {

    /** 向导步骤。 */
    private enum class Step { TYPE, ONVIF, STREAM, M3U8, FILE, FINISH }

    private var _binding: com.tvmonitor.databinding.FragmentAddSourceBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var viewModel: AddSourceViewModel

    private var step: Step = Step.TYPE
    private var selectedType: SourceType? = null
    private var editSource: VideoSource? = null

    // ONVIF 中间态
    private var devices: List<OnvifDevice> = emptyList()
    private var profiles: List<Pair<OnvifProfile, String>> = emptyList()
    private var selectedProfileIndex: Int = -1
    private var deviceInfo: OnvifDeviceInfo? = null

    // 本地文件
    private var pickedFileUri: Uri? = null

    private val deviceAdapter = OnvifDeviceAdapter { index ->
        // 点击发现结果 → 预填 IP/端口
        devices.getOrNull(index)?.let { device ->
            binding.etIp.setText(device.ip)
            binding.etPort.setText(device.port.toString())
        }
    }

    private val profileAdapter = OnvifProfileAdapter { index ->
        selectedProfileIndex = index
    }

    /** m3u 文件选择器（SAF，支持 U 盘）。 */
    private val openM3uLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { importM3uFile(it) }
    }

    /** 视频文件选择器（SAF）。 */
    private val openVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { onVideoPicked(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = com.tvmonitor.databinding.FragmentAddSourceBinding.inflate(
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
            AddSourceViewModel.factory(),
        )[AddSourceViewModel::class.java]

        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
            isNestedScrollingEnabled = false
        }
        binding.rvProfiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = profileAdapter
            isNestedScrollingEnabled = false
        }

        // 返回键 = 回上一步（向导内不直接退出）
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (step != Step.TYPE) {
                showStep(Step.TYPE)
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        val editId = arguments?.getString(ARG_SOURCE_ID)
        if (editId != null) {
            lifecycleScope.launch {
                val source = viewModel.loadSource(editId)
                if (source != null) {
                    editSource = source
                    prefillForEdit(source)
                }
            }
        } else {
            showStep(Step.TYPE)
        }

        setupListeners()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ===================== 事件绑定 =====================

    private fun setupListeners() {
        binding.rowTypeOnvif.setOnClickListener { selectType(SourceType.ONVIF) }
        binding.rowTypeStream.setOnClickListener { selectType(SourceType.STREAM) }
        binding.rowTypeM3u8.setOnClickListener { selectType(SourceType.M3U8) }
        binding.rowTypeFile.setOnClickListener { selectType(SourceType.FILE) }

        binding.btnScan.setOnClickListener { scanDevices() }
        binding.btnConnect.setOnClickListener { connectOnvif() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnImport.setOnClickListener { openM3uLauncher.launch(M3U_MIME_TYPES) }
        binding.btnChooseFile.setOnClickListener { openVideoLauncher.launch(VIDEO_MIME_TYPES) }

        binding.btnPrev.setOnClickListener { showStep(Step.TYPE) }
        binding.btnNext.setOnClickListener { goToFinishStep() }
        binding.btnSave.setOnClickListener { save() }

        // TV 输入法：地址输入框回车 = 收起键盘
        listOf(binding.etAddress, binding.etUrl).forEach { field ->
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    field.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
    }

    // ===================== 步骤机 =====================

    private fun selectType(type: SourceType) {
        selectedType = type
        showStep(
            when (type) {
                SourceType.ONVIF -> Step.ONVIF
                SourceType.STREAM -> Step.STREAM
                SourceType.M3U8 -> Step.M3U8
                SourceType.FILE -> Step.FILE
            },
        )
    }

    private fun showStep(newStep: Step) {
        step = newStep
        val b = _binding ?: return
        b.stepType.isVisible = newStep == Step.TYPE
        b.stepOnvif.isVisible = newStep == Step.ONVIF
        b.stepStream.isVisible = newStep == Step.STREAM
        b.stepM3u8.isVisible = newStep == Step.M3U8
        b.stepFile.isVisible = newStep == Step.FILE
        b.stepFinish.isVisible = newStep == Step.FINISH

        val isEdit = editSource != null
        b.tvStepTitle.text = when {
            isEdit -> getString(R.string.wizard_title_edit)
            newStep == Step.TYPE -> getString(R.string.wizard_title_add)
            else -> getString(R.string.wizard_title_add)
        }
        b.tvStepHint.text = when (newStep) {
            Step.TYPE -> getString(R.string.wizard_step_select_type)
            Step.ONVIF -> getString(R.string.wizard_step_onvif)
            Step.STREAM -> getString(R.string.wizard_step_stream)
            Step.M3U8 -> getString(R.string.wizard_step_m3u8)
            Step.FILE -> getString(R.string.wizard_step_file)
            Step.FINISH -> getString(R.string.wizard_step_finish)
        }

        b.btnPrev.isVisible = newStep != Step.TYPE
        b.btnNext.isVisible = newStep != Step.TYPE && newStep != Step.FINISH
        b.btnSave.isVisible = newStep == Step.FINISH

        if (newStep == Step.FINISH) {
            prefillFinishStep()
        }
    }

    /** 下一步（类型表单 → 命名/格位）：带校验。 */
    private fun goToFinishStep() {
        when (step) {
            Step.ONVIF -> {
                val selected = profiles.getOrNull(selectedProfileIndex)
                val fallback = editSource?.address
                if (selected == null && fallback.isNullOrBlank()) {
                    toast(R.string.error_profile_not_selected)
                    return
                }
            }
            Step.STREAM -> {
                val address = binding.etAddress.text.toString().trim()
                if (address.isEmpty()) {
                    toast(R.string.error_address_empty)
                    return
                }
                if (!address.startsWith("rtsp://", true) &&
                    !address.startsWith("rtsps://", true) &&
                    !address.startsWith("http://", true) &&
                    !address.startsWith("https://", true)
                ) {
                    toast(R.string.error_address_protocol)
                    return
                }
            }
            Step.M3U8 -> {
                val url = binding.etUrl.text.toString().trim()
                if (url.isEmpty()) {
                    toast(R.string.error_address_empty)
                    return
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toast(R.string.error_url_invalid)
                    return
                }
            }
            Step.FILE -> {
                if (pickedFileUri == null && editSource == null) {
                    toast(R.string.error_file_not_selected)
                    return
                }
            }
            else -> Unit
        }
        showStep(Step.FINISH)
    }

    // ===================== 编辑模式预填 =====================

    private fun prefillForEdit(source: VideoSource) {
        selectedType = source.type
        binding.etName.setText(source.name)
        when (source.type) {
            SourceType.ONVIF -> {
                binding.etIp.setText(source.onvifIp)
                binding.etPort.setText(source.onvifPort.toString())
                binding.etUsername.setText(source.username)
                binding.cbSubstream.isChecked = source.preferSubStream
                binding.tvScanHint.text =
                    getString(R.string.file_picked_format, source.address)
                showStep(Step.ONVIF)
            }
            SourceType.STREAM -> {
                binding.etAddress.setText(source.address)
                showStep(Step.STREAM)
            }
            SourceType.M3U8 -> {
                binding.etUrl.setText(source.address)
                showStep(Step.M3U8)
            }
            SourceType.FILE -> {
                pickedFileUri = Uri.parse(source.address)
                binding.tvFilePath.isVisible = true
                binding.tvFilePath.text =
                    getString(R.string.file_picked_format, source.address)
                showStep(Step.FILE)
            }
        }
    }

    private fun prefillFinishStep() {
        if (editSource != null && binding.etName.text.isNullOrBlank()) {
            binding.etName.setText(editSource?.name)
        }
        // 格位单选文案
        binding.rbSlot0.text = getString(R.string.slot_label_format, 1)
        binding.rbSlot1.text = getString(R.string.slot_label_format, 2)
        binding.rbSlot2.text = getString(R.string.slot_label_format, 3)
        binding.rbSlot3.text = getString(R.string.slot_label_format, 4)
        if (binding.etName.text.isNullOrBlank()) {
            binding.etName.setText(defaultName())
        }
        binding.etName.requestFocus()
    }

    private fun defaultName(): String {
        val suffix = UUID.randomUUID().toString().take(4)
        return when (selectedType) {
            SourceType.ONVIF -> getString(R.string.default_name_onvif, suffix)
            SourceType.STREAM -> getString(R.string.default_name_stream, suffix)
            SourceType.M3U8 -> getString(R.string.default_name_m3u8, suffix)
            SourceType.FILE -> getString(R.string.default_name_file, suffix)
            null -> ""
        }
    }

    // ===================== ONVIF =====================

    private fun scanDevices() {
        binding.tvScanHint.setText(R.string.onvif_discovering)
        binding.rvDevices.isVisible = false
        lifecycleScope.launch {
            when (val result = viewModel.discover(requireContext())) {
                is AppResult.Failure -> {
                    binding.tvScanHint.text = result.error.toUserMessage(requireContext())
                }
                is AppResult.Success -> {
                    devices = result.data
                    deviceAdapter.submitList(result.data)
                    binding.rvDevices.isVisible = result.data.isNotEmpty()
                    binding.tvScanHint.text = if (result.data.isEmpty()) {
                        getString(R.string.onvif_discover_empty)
                    } else {
                        getString(R.string.onvif_discover_done_format, result.data.size)
                    }
                }
            }
        }
    }

    private fun connectOnvif() {
        val ip = binding.etIp.text.toString().trim()
        val portText = binding.etPort.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (!isValidIp(ip)) {
            toast(R.string.error_ip_invalid)
            return
        }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast(R.string.error_port_invalid)
            return
        }
        if (username.isEmpty()) {
            toast(R.string.error_username_empty)
            return
        }

        binding.tvDeviceInfo.isVisible = true
        binding.tvDeviceInfo.setText(R.string.onvif_connecting)
        binding.tvProfilesTitle.isVisible = false
        binding.rvProfiles.isVisible = false

        lifecycleScope.launch {
            when (val result = viewModel.connectOnvif(ip, port, username, password)) {
                is AppResult.Failure -> {
                    binding.tvDeviceInfo.text = result.error.toUserMessage(requireContext())
                }
                is AppResult.Success -> {
                    val connection = result.data
                    deviceInfo = connection.deviceInfo
                    // 子码流优先（主理人裁决 4）：按分辨率升序，最小视为子码流
                    profiles = connection.profiles.sortedBy { it.first.height }
                    selectedProfileIndex = if (profiles.isNotEmpty()) 0 else -1
                    profileAdapter.submitList(profiles, selectedProfileIndex)
                    binding.tvDeviceInfo.isVisible = true
                    binding.tvDeviceInfo.text = getString(
                        R.string.onvif_device_info_format,
                        connection.deviceInfo?.vendor ?: "-",
                        connection.deviceInfo?.model ?: "-",
                    )
                    binding.tvProfilesTitle.isVisible = profiles.isNotEmpty()
                    binding.rvProfiles.isVisible = profiles.isNotEmpty()
                }
            }
        }
    }

    // ===================== 连通性测试 =====================

    private fun testConnection() {
        val address = when (step) {
            Step.STREAM -> binding.etAddress.text.toString().trim()
            Step.M3U8 -> binding.etUrl.text.toString().trim()
            else -> return
        }
        if (address.isEmpty()) {
            toast(R.string.error_address_empty)
            return
        }
        binding.tvTestResult.isVisible = true
        binding.tvTestResult.setText(R.string.test_connecting)
        lifecycleScope.launch {
            val result = viewModel.testAddress(address)
            binding.tvTestResult.text = when (result) {
                is AppResult.Success -> getString(R.string.test_success)
                is AppResult.Failure -> getString(
                    R.string.test_failed_format,
                    result.error.toUserMessage(requireContext()),
                )
            }
        }
    }

    // ===================== M3U 导入 =====================

    private fun importM3uFile(uri: Uri) {
        lifecycleScope.launch {
            binding.tvImportResult.isVisible = true
            binding.tvImportResult.setText(R.string.test_connecting)
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                binding.tvImportResult.text = getString(
                    R.string.test_failed_format,
                    getString(R.string.error_file_read_failed),
                )
                return@launch
            }
            val outcome = viewModel.importM3u(bytes)
            binding.tvImportResult.text = buildString {
                append(getString(R.string.m3u_import_result_format, outcome.added, outcome.skipped, outcome.failed))
                if (outcome.failed > 0) {
                    append("；")
                    append(getString(R.string.m3u_import_reason_parse))
                }
            }
        }
    }

    // ===================== 本地文件 =====================

    private fun onVideoPicked(uri: Uri) {
        pickedFileUri = uri
        binding.tvFilePath.isVisible = true
        binding.tvFilePath.text = getString(R.string.file_picked_format, uri.toString())
        // 持久化读取权限（重启后绑定仍可播放）
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // ===================== 保存 =====================

    private fun save() {
        val type = selectedType ?: editSource?.type
        if (type == null) {
            toast(R.string.error_unknown)
            return
        }
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            toast(R.string.error_name_empty)
            return
        }

        lifecycleScope.launch {
            val existing = editSource
            val source: VideoSource? = when (type) {
                SourceType.ONVIF -> buildOnvifSource(name, existing)
                SourceType.STREAM -> VideoSource(
                    id = existing?.id ?: "",
                    name = name,
                    type = SourceType.STREAM,
                    address = binding.etAddress.text.toString().trim(),
                )
                SourceType.M3U8 -> VideoSource(
                    id = existing?.id ?: "",
                    name = name,
                    type = SourceType.M3U8,
                    address = binding.etUrl.text.toString().trim(),
                )
                SourceType.FILE -> VideoSource(
                    id = existing?.id ?: "",
                    name = name,
                    type = SourceType.FILE,
                    address = (pickedFileUri ?: existing?.address?.let { Uri.parse(it) })?.toString()
                        ?: "",
                )
            }

            if (source == null || source.address.isEmpty()) {
                toast(R.string.error_unknown)
                return@launch
            }

            val result = if (existing != null) {
                viewModel.updateSource(source.copy(id = existing.id))
            } else {
                viewModel.addSource(source)
            }

            when (result) {
                is AppResult.Success -> {
                    val slotIndex = selectedSlotIndex()
                    if (slotIndex != null) {
                        viewModel.bindSlot(slotIndex, result.data.id)
                    }
                    Toast.makeText(
                        requireContext(),
                        R.string.source_saved,
                        Toast.LENGTH_SHORT,
                    ).show()
                    parentFragmentManager.popBackStack()
                }
                is AppResult.Failure -> {
                    Toast.makeText(
                        requireContext(),
                        result.error.toUserMessage(requireContext()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 构造 ONVIF 源（凭据加密后落库，NFR-05）。 */
    private suspend fun buildOnvifSource(name: String, existing: VideoSource?): VideoSource? {
        val selected = profiles.getOrNull(selectedProfileIndex)
        val address = selected?.second ?: existing?.address
        if (address.isNullOrBlank()) {
            toast(R.string.error_profile_not_selected)
            return null
        }
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val passwordEnc = when {
            password.isNotEmpty() -> viewModel.encrypt(password)
            existing != null -> existing.passwordEnc
            else -> ""
        }
        val profile = selected?.first
        return VideoSource(
            id = existing?.id ?: "",
            name = name,
            type = SourceType.ONVIF,
            address = address,
            username = username,
            passwordEnc = passwordEnc,
            preferSubStream = binding.cbSubstream.isChecked,
            profileToken = profile?.token ?: existing?.profileToken ?: "",
            streamKind = if ((profile?.height ?: 0) in 1..720) StreamKind.SUB else StreamKind.MAIN,
            onvifIp = binding.etIp.text.toString().trim(),
            onvifPort = binding.etPort.text.toString().trim().toIntOrNull() ?: 80,
            deviceVendor = deviceInfo?.vendor ?: existing?.deviceVendor ?: "",
            deviceModel = deviceInfo?.model ?: existing?.deviceModel ?: "",
        )
    }

    private fun selectedSlotIndex(): Int? {
        val id = binding.rgSlots.checkedRadioButtonId
        return when (id) {
            R.id.rb_slot_0 -> 0
            R.id.rb_slot_1 -> 1
            R.id.rb_slot_2 -> 2
            R.id.rb_slot_3 -> 3
            else -> null
        }
    }

    // ===================== 工具 =====================

    private fun toast(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val value = part.toIntOrNull() ?: return@all false
            value in 0..255 && (part == "0" || !part.startsWith("0") || part.length == 1)
        }
    }

    companion object {
        private const val ARG_SOURCE_ID = "source_id"
        private val M3U_MIME_TYPES = arrayOf(
            "audio/x-mpegurl",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "text/plain",
            "application/octet-stream",
        )
        private val VIDEO_MIME_TYPES = arrayOf("video/*")

        fun newInstance(sourceId: String? = null): AddSourceWizardFragment =
            AddSourceWizardFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE_ID, sourceId)
                }
            }
    }
}

// ===================== 列表适配器 =====================

/** ONVIF 发现结果适配器。 */
private class OnvifDeviceAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<OnvifDeviceAdapter.DeviceHolder>() {

    private var items: List<OnvifDevice> = emptyList()

    fun submitList(list: List<OnvifDevice>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceHolder =
        DeviceHolder(
            com.tvmonitor.databinding.ItemOnvifDeviceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: DeviceHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DeviceHolder(
        private val itemBinding: com.tvmonitor.databinding.ItemOnvifDeviceBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {
        init {
            itemBinding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onClick)
            }
        }

        fun bind(device: OnvifDevice) {
            itemBinding.tvDeviceName.text = device.name
            itemBinding.tvDeviceEndpoint.text = "${device.ip}:${device.port}"
        }
    }
}

/** ONVIF 码流单选适配器。 */
private class OnvifProfileAdapter(
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<OnvifProfileAdapter.ProfileHolder>() {

    private var items: List<Pair<OnvifProfile, String>> = emptyList()
    private var selectedIndex: Int = -1

    fun submitList(list: List<Pair<OnvifProfile, String>>, selected: Int) {
        items = list
        selectedIndex = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileHolder =
        ProfileHolder(
            com.tvmonitor.databinding.ItemOnvifProfileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: ProfileHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class ProfileHolder(
        private val itemBinding: com.tvmonitor.databinding.ItemOnvifProfileBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {
        init {
            itemBinding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    selectedIndex = position
                    notifyDataSetChanged()
                    onSelect(position)
                }
            }
        }

        fun bind(pair: Pair<OnvifProfile, String>, selected: Boolean) {
            val (profile, _) = pair
            itemBinding.rbProfile.isChecked = selected
            val kind = if (profile.height in 1..720) {
                itemBinding.root.context.getString(R.string.onvif_profile_sub)
            } else {
                itemBinding.root.context.getString(R.string.onvif_profile_main)
            }
            val desc = if (profile.width > 0 && profile.height > 0) {
                "${profile.width}x${profile.height}"
            } else {
                profile.name.ifBlank { itemBinding.root.context.getString(R.string.onvif_profile_unknown) }
            }
            itemBinding.tvProfileDesc.text =
                itemBinding.root.context.getString(R.string.onvif_profile_format, kind, desc)
        }
    }
}

// ===================== ViewModel =====================

/** ONVIF 连接结果（设备信息 + 码流/地址对）。 */
data class OnvifConnection(
    val deviceInfo: OnvifDeviceInfo?,
    val profiles: List<Pair<OnvifProfile, String>>,
)

/** M3U 导入结果统计。 */
data class M3uImportOutcome(
    val added: Int,
    val skipped: Int,
    val failed: Int,
)

/**
 * 添加源向导 ViewModel。
 *
 * 负责：编辑态加载、ONVIF 发现/连接、连通性测试、m3u 导入、源 CRUD、凭据加密。
 * 网络/IO 均在 viewModelScope + Dispatchers.IO 内执行。
 */
class AddSourceViewModel(
    private val sourceStore: SourceStore,
    private val settingsStore: SettingsStore,
    private val credentialCrypto: CredentialCrypto = CredentialCrypto(),
) : ViewModel() {

    suspend fun loadSource(id: String): VideoSource? = withContext(Dispatchers.IO) {
        sourceStore.ensureLoaded()
        sourceStore.findById(id)
    }

    suspend fun discover(context: android.content.Context): AppResult<List<OnvifDevice>> =
        withContext(Dispatchers.IO) {
            OnvifDiscovery(context).discover()
        }

    suspend fun connectOnvif(
        ip: String,
        port: Int,
        username: String,
        password: String,
    ): AppResult<OnvifConnection> = withContext(Dispatchers.IO) {
        val endpoint = "http://$ip:$port/onvif/device_service"
        val client = OnvifClient(endpoint, username, password)
        when (val infoResult = client.getDeviceInfo()) {
            is AppResult.Failure -> infoResult
            is AppResult.Success -> {
                when (val streamsResult = client.getProfilesWithStreamUris()) {
                    is AppResult.Failure -> streamsResult
                    is AppResult.Success -> AppResult.Success(
                        OnvifConnection(infoResult.data, streamsResult.data),
                    )
                }
            }
        }
    }

    /**
     * 地址连通性测试：
     * - rtsp/rtsps：TCP 端口可达即可（媒体握手由播放器完成）；
     * - http(s)：HEAD 请求，2xx/3xx 视为可达。
     */
    suspend fun testAddress(address: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val scheme = address.substringBefore("://", "").lowercase()
        try {
            when (scheme) {
                "rtsp", "rtsps" -> {
                    val uri = Uri.parse(address)
                    val host = uri.host
                    val port = if (uri.port > 0) uri.port else 554
                    if (host.isNullOrBlank()) {
                        AppResult.Failure(
                            AppError(ErrorCode.INVALID_ARGUMENT, "地址缺少主机名"),
                        )
                    } else {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), TEST_TIMEOUT_MS)
                        }
                        AppResult.Success(Unit)
                    }
                }
                "http", "https" -> {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder().url(address).head().build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code in 300..399) {
                            AppResult.Success(Unit)
                        } else {
                            AppResult.Failure(
                                AppError(ErrorCode.NETWORK, "HTTP ${response.code}"),
                            )
                        }
                    }
                }
                else -> AppResult.Failure(
                    AppError(ErrorCode.PROTOCOL_UNSUPPORTED, "暂不支持该协议（$scheme）"),
                )
            }
        } catch (e: Exception) {
            AppResult.Failure(
                AppError(ErrorCode.NETWORK, e.message ?: "连接失败", e),
            )
        }
    }

    /** 导入 m3u 字节流：解析 → 逐条入库 → 汇总。 */
    suspend fun importM3u(bytes: ByteArray): M3uImportOutcome = withContext(Dispatchers.IO) {
        sourceStore.ensureLoaded()
        val report: M3uParseReport = M3uParser.parseBytes(bytes)
        val existing = sourceStore.getSources().map { it.address }.toSet()
        var added = 0
        var skipped = 0
        var failed = report.parseErrorCount
        report.entries.forEach { entry ->
            val type = inferType(entry.url)
            if (type == null || entry.url in existing) {
                skipped++
                return@forEach
            }
            val result = sourceStore.add(
                VideoSource(
                    id = "",
                    name = entry.title,
                    type = type,
                    address = entry.url,
                    preferSubStream = settingsStore.snapshot().preferSubStream,
                ),
            )
            if (result is AppResult.Success) added++ else failed++
        }
        M3uImportOutcome(added = added, skipped = skipped, failed = failed)
    }

    /** URL → 源类型推断；无法识别返回 null（跳过）。 */
    private fun inferType(url: String): SourceType? {
        val lower = url.lowercase()
        return when {
            lower.startsWith("rtsp://") || lower.startsWith("rtsps://") -> SourceType.STREAM
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                if (lower.substringBefore('?').substringBefore('#').endsWith(".m3u8")) {
                    SourceType.M3U8
                } else {
                    SourceType.STREAM
                }
            }
            lower.startsWith("file://") || lower.startsWith("content://") -> SourceType.FILE
            else -> null
        }
    }

    suspend fun addSource(source: VideoSource): AppResult<VideoSource> =
        withContext(Dispatchers.IO) { sourceStore.add(source) }

    suspend fun updateSource(source: VideoSource): AppResult<VideoSource> =
        withContext(Dispatchers.IO) { sourceStore.update(source) }

    suspend fun bindSlot(slotIndex: Int, sourceId: String): AppResult<Unit> =
        withContext(Dispatchers.IO) { sourceStore.bindSlot(slotIndex, sourceId) }

    /** 密码加密（保存前调用，明文不落盘）。 */
    fun encrypt(password: String): String = credentialCrypto.encrypt(password)

    companion object {
        private const val TAG = "TvMonitor:AddSourceVM"
        private const val TEST_TIMEOUT_MS = 5_000

        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = TvMonitorApp.instance
                return AddSourceViewModel(
                    sourceStore = JsonSourceStore.get(app),
                    settingsStore = SettingsStore(app),
                ) as T
            }
        }
    }
}
