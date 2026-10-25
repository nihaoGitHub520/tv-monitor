package com.tvmonitor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.tvmonitor.databinding.ViewQuadGridBinding
import com.tvmonitor.playback.SlotUiState

/**
 * 四分格 2x2 网格容器（T4）。
 *
 * 焦点矩阵由 `view_quad_grid.xml` 的声明式 nextFocus 完成（越界不 wrap）；
 * 本类负责：
 * 1. 持有 4 个 [SlotView] 并注入下标；
 * 2. 批量渲染 [SlotUiState]；
 * 3. 点击/重试事件外抛给 MainActivity。
 */
class QuadGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewQuadGridBinding.inflate(LayoutInflater.from(context), this, true)

    /** 四个格位视图（下标顺序 = 格位顺序）。 */
    val slotViews: List<SlotView> = listOf(
        binding.slot0,
        binding.slot1,
        binding.slot2,
        binding.slot3,
    )

    /** 格位点击（确认键 → 进入单预览）。 */
    var onSlotClick: ((Int) -> Unit)? = null

    /** 格位手动重试。 */
    var onSlotRetry: ((Int) -> Unit)? = null

    init {
        slotViews.forEachIndexed { index, slot ->
            slot.bindIndex(index)
            slot.setOnClickListener { onSlotClick?.invoke(index) }
            slot.onRetryClick = { onSlotRetry?.invoke(index) }
        }
    }

    /** 批量渲染状态（长度不足时忽略多余槽位）。 */
    fun render(states: List<SlotUiState>) {
        slotViews.forEachIndexed { index, slot ->
            states.getOrNull(index)?.let { slot.render(it) }
        }
    }

    /** 聚焦指定格位。 */
    fun focusSlot(index: Int) {
        slotViews.getOrNull(index)?.requestFocus()
    }

    /** 首个已配置源的格位下标；全部未配置返回 null。 */
    fun firstConfiguredIndex(states: List<SlotUiState>): Int? =
        states.indexOfFirst { it.source != null }.takeIf { it >= 0 }

    /** 当前持有 D-pad 焦点的格位下标；无焦点返回 null。 */
    fun focusedSlotIndex(): Int? =
        slotViews.indexOfFirst { it.isFocused }.takeIf { it >= 0 }
}
