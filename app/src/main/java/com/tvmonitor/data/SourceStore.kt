package com.tvmonitor.data

import com.tvmonitor.util.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * 视频源库统一接口（ARCHITECTURE.md：源管理只依赖此接口，便于测试替换实现）。
 *
 * 职责：
 * 1. 视频源 CRUD（[VideoSource]）；
 * 2. 四分格格位绑定持久化（[QuadLayoutState]）；
 * 3. 通过 Flow 向 UI 广播变更（源列表 / 布局状态）。
 *
 * 线程约定：所有挂起函数内部切 IO；Flow 发射线程安全（StateFlow 语义）。
 * 密码约定：实现层只存 [VideoSource.passwordEnc]（密文），明文加解密由
 * [CredentialCrypto] 在调用方完成，本接口不接触明文密码。
 */
interface SourceStore {

    /** 视频源列表流（按名称排序，界面直接 collect）。 */
    val sources: Flow<List<VideoSource>>

    /** 格位绑定状态流。 */
    val layoutState: Flow<QuadLayoutState>

    /** 确保磁盘数据已加载（幂等；内部 IO 完成前 Flow 可能为空列表）。 */
    suspend fun ensureLoaded()

    /** 全量源列表快照。 */
    suspend fun getSources(): List<VideoSource>

    /** 按 id 查找；不存在返回 null。 */
    suspend fun findById(id: String): VideoSource?

    /**
     * 新增源。[source.id] 为空时自动生成 UUID；createdAt/updatedAt 自动填充。
     * 返回持久化后的完整实体（含 id）。
     */
    suspend fun add(source: VideoSource): AppResult<VideoSource>

    /**
     * 更新源（按 id 全量替换）。id 不存在时返回 [com.tvmonitor.util.ErrorCode.NOT_FOUND]。
     * updatedAt 自动刷新。
     */
    suspend fun update(source: VideoSource): AppResult<VideoSource>

    /** 删除源；同步解除所有格位对该源的引用（删除后对应格显示“未配置”）。 */
    suspend fun delete(id: String): AppResult<Unit>

    /** 布局状态快照。 */
    suspend fun getLayoutState(): QuadLayoutState

    /**
     * 绑定/解绑格位。[sourceId] 为 null 表示解绑。
     * @param slotIndex 0..3，越界返回 [com.tvmonitor.util.ErrorCode.INVALID_ARGUMENT]。
     */
    suspend fun bindSlot(slotIndex: Int, sourceId: String?): AppResult<Unit>

    /** 查询格位当前绑定的源；未绑定或源已删除返回 null。 */
    suspend fun getSourceAtSlot(slotIndex: Int): VideoSource?
}
