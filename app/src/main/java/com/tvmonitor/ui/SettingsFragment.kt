package com.tvmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.tvmonitor.BuildConfig
import com.tvmonitor.R
import com.tvmonitor.data.AppSettings
import com.tvmonitor.data.ReconnectConfig
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.util.TvLog

/**
 * 设置 Fragment（T5 验收 5：重连策略 / 默认静音 / 子码流优先 / 日志开关生效）。
 *
 * 保存后经 [SettingsStore.save] 持久化并刷新快照；播放内核在下一次重连/起流时
 * 读取新参数（configProvider 动态读取，无需重启）。
 */
class SettingsFragment : Fragment() {

    private var _binding: com.tvmonitor.databinding.FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var settingsStore: SettingsStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = com.tvmonitor.databinding.FragmentSettingsBinding.inflate(
            inflater,
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsStore = SettingsStore(requireContext().applicationContext)
        prefill(settingsStore.snapshot())
        binding.tvVersion.text =
            getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)
        binding.btnSave.setOnClickListener { save() }
        binding.btnSave.requestFocus()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun prefill(settings: AppSettings) {
        binding.etDelays.setText(settings.reconnect.delaysSec.joinToString(","))
        binding.etMaxAttempts.setText(settings.reconnect.maxAttempts.toString())
        binding.etKeepalive.setText(settings.reconnect.keepaliveIntervalSec.toString())
        binding.etFfRtsp.setText(settings.reconnect.firstFrameTimeoutRtspSec.toString())
        binding.etFfHls.setText(settings.reconnect.firstFrameTimeoutHlsSec.toString())
        binding.switchMute.isChecked = settings.defaultMute
        binding.switchSubstream.isChecked = settings.preferSubStream
        binding.etBuffer.setText(settings.bufferLimitMb.toString())
        binding.rbRes1080p.isChecked = settings.maxResolutionHeight > 0
        binding.rbResUnlimited.isChecked = settings.maxResolutionHeight <= 0
        binding.switchLog.isChecked = settings.logEnabled
    }

    private fun save() {
        // ---- 重连策略 ----
        val delays = binding.etDelays.text.toString()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
        if (delays.isEmpty()) {
            toast(R.string.settings_invalid_delays)
            return
        }
        val maxAttempts = binding.etMaxAttempts.text.toString().toIntOrNull()
        if (maxAttempts == null || maxAttempts < 1) {
            toast(R.string.settings_invalid_number)
            return
        }
        val keepalive = binding.etKeepalive.text.toString().toIntOrNull()
        if (keepalive == null || keepalive < 5) {
            toast(R.string.settings_invalid_number)
            return
        }
        val ffRtsp = binding.etFfRtsp.text.toString().toIntOrNull()
        if (ffRtsp == null || ffRtsp < 3) {
            toast(R.string.settings_invalid_number)
            return
        }
        val ffHls = binding.etFfHls.text.toString().toIntOrNull()
        if (ffHls == null || ffHls < 3) {
            toast(R.string.settings_invalid_number)
            return
        }

        // ---- 播放 ----
        val bufferMb = binding.etBuffer.text.toString().toIntOrNull()
        if (bufferMb == null || bufferMb !in 2..64) {
            toast(R.string.settings_invalid_number)
            return
        }

        val settings = AppSettings(
            reconnect = ReconnectConfig(
                delaysSec = delays,
                maxAttempts = maxAttempts,
                keepaliveIntervalSec = keepalive,
                firstFrameTimeoutRtspSec = ffRtsp,
                firstFrameTimeoutHlsSec = ffHls,
            ),
            defaultMute = binding.switchMute.isChecked,
            preferSubStream = binding.switchSubstream.isChecked,
            bufferLimitMb = bufferMb,
            maxResolutionHeight = if (binding.rbRes1080p.isChecked) 1080 else 0,
            logEnabled = binding.switchLog.isChecked,
        )
        settingsStore.save(settings)
        TvLog.i(TAG, "设置已保存：$settings")
        toast(R.string.settings_saved)
        parentFragmentManager.popBackStack()
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "TvMonitor:SettingsFragment"

        fun newInstance(): SettingsFragment = SettingsFragment()
    }
}
