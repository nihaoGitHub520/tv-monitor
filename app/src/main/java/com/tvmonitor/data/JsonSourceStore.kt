package com.tvmonitor.data

import android.content.Context
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import com.tvmonitor.util.asSuccess
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 基于 JSON 文件的 [SourceStore] 实现。
 *
 * 存储布局（应用私有目录，rootless 设备同样可用）：
 * - `files/sources.json`：`{schema, sources[]}`，视频源全量；
 * - `files/state.json`：`QuadLayoutState`，格位绑定。
 *
 * 关键机制：
 * 1. **原子写**：先写 `.tmp` 并 fsync，再 rename 覆盖；中途 kill 不损坏原文件；
 * 2. **schema 版本**：读取时校验，低版本按默认值前向兼容并重写，高版本告警降级加载；
 * 3. **Flow 变更通知**：内存 StateFlow + 落盘成功后发射，UI 实时刷新；
 * 4. **删除联动**：删除源时解除格位引用，避免悬挂绑定。
 */
class JsonSourceStore(private val context: Context) : SourceStore {

    private val json = Json {
        ignoreUnknownKeys = true   // 前向兼容：新版本字段旧代码忽略
        encodeDefaults = true      // 默认值显式落盘，便于人工检查
        prettyPrint = false
    }

    private val sourcesFile: File get() = File(context.filesDir, FILE_SOURCES)
    private val stateFile: File get() = File(context.filesDir, FILE_STATE)

    private val mutex = Mutex()
    private var loaded = false

    private val _sources = MutableStateFlow<List<VideoSource>>(emptyList())
    private val _layoutState = MutableStateFlow(QuadLayoutState())

    override val sources: StateFlow<List<VideoSource>> = _sources.asStateFlow()
    override val layoutState: StateFlow<QuadLayoutState> = _layoutState.asStateFlow()

    /** 幂等加载：首次调用读盘（IO 线程），之后直接返回。 */
    override suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                loadSourcesLocked()
                loadStateLocked()
            }
            loaded = true
            TvLog.i(TAG, "源库加载完成：${_sources.value.size} 个源")
        }
    }

    override suspend fun getSources(): List<VideoSource> {
        ensureLoaded()
        return _sources.value
    }

    override suspend fun findById(id: String): VideoSource? {
        ensureLoaded()
        return _sources.value.firstOrNull { it.id == id }
    }

    override suspend fun add(source: VideoSource): AppResult<VideoSource> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val toSave = source.copy(
            id = source.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = if (source.createdAt > 0L) source.createdAt else now,
            updatedAt = now,
        )
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val next = _sources.value + toSave
                    persistSourcesLocked(next)
                    _sources.value = next
                    TvLog.i(TAG, "新增源：${toSave.name}（${toSave.type}）")
                    toSave.asSuccess()
                } catch (e: SerializationException) {
                    AppResult.Failure(AppError(ErrorCode.PARSE_FAILED, "源序列化失败", e))
                } catch (e: Exception) {
                    AppResult.Failure(AppError(ErrorCode.IO_FAILED, "源写入失败：${e.message}", e))
                }
            }
        }
    }

    override suspend fun update(source: VideoSource): AppResult<VideoSource> {
        ensureLoaded()
        if (source.id.isBlank()) {
            return AppResult.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, "更新源缺少 id"),
            )
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = _sources.value
                val index = current.indexOfFirst { it.id == source.id }
                if (index < 0) {
                    return@withLock AppResult.Failure(
                        AppError(ErrorCode.NOT_FOUND, "源不存在：${source.id}"),
                    )
                }
                try {
                    val toSave = source.copy(updatedAt = System.currentTimeMillis())
                    val next = current.toMutableList().apply { set(index, toSave) }
                    persistSourcesLocked(next)
                    _sources.value = next
                    TvLog.i(TAG, "更新源：${toSave.name}")
                    toSave.asSuccess()
                } catch (e: Exception) {
                    AppResult.Failure(
                        AppError(ErrorCode.IO_FAILED, "源写入失败：${e.message}", e),
                    )
                }
            }
        }
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = _sources.value
                if (current.none { it.id == id }) {
                    return@withLock AppResult.Failure(
                        AppError(ErrorCode.NOT_FOUND, "源不存在：$id"),
                    )
                }
                try {
                    val next = current.filterNot { it.id == id }
                    persistSourcesLocked(next)
                    _sources.value = next
                    // 联动解除格位引用：删除播放中的源后对应格显示“未配置”
                    val state = _layoutState.value
                    val newSlots = state.slots.map { binding ->
                        if (binding.sourceId == id) binding.copy(sourceId = null) else binding
                    }
                    if (newSlots != state.slots) {
                        persistStateLocked(state.copy(slots = newSlots))
                        _layoutState.value = state.copy(slots = newSlots)
                    }
                    TvLog.i(TAG, "删除源：$id")
                    Unit.asSuccess()
                } catch (e: Exception) {
                    AppResult.Failure(
                        AppError(ErrorCode.IO_FAILED, "源删除失败：${e.message}", e),
                    )
                }
            }
        }
    }

    override suspend fun getLayoutState(): QuadLayoutState {
        ensureLoaded()
        return _layoutState.value
    }

    override suspend fun bindSlot(slotIndex: Int, sourceId: String?): AppResult<Unit> {
        ensureLoaded()
        if (slotIndex !in 0 until QuadLayoutState.SLOT_COUNT) {
            return AppResult.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, "格位下标越界：$slotIndex"),
            )
        }
        if (sourceId != null && _sources.value.none { it.id == sourceId }) {
            return AppResult.Failure(
                AppError(ErrorCode.NOT_FOUND, "源不存在：$sourceId"),
            )
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val state = _layoutState.value
                    val newSlots = state.slots.map { binding ->
                        if (binding.slotIndex == slotIndex) binding.copy(sourceId = sourceId)
                        else binding
                    }
                    val newState = state.copy(slots = newSlots)
                    persistStateLocked(newState)
                    _layoutState.value = newState
                    TvLog.i(TAG, "格位绑定：slot=$slotIndex source=${sourceId ?: "空"}")
                    Unit.asSuccess()
                } catch (e: Exception) {
                    AppResult.Failure(
                        AppError(ErrorCode.IO_FAILED, "布局写入失败：${e.message}", e),
                    )
                }
            }
        }
    }

    override suspend fun getSourceAtSlot(slotIndex: Int): VideoSource? {
        ensureLoaded()
        if (slotIndex !in 0 until QuadLayoutState.SLOT_COUNT) return null
        val sourceId = _layoutState.value.slots[slotIndex].sourceId ?: return null
        return _sources.value.firstOrNull { it.id == sourceId }
    }

    // ===================== 内部实现 =====================

    /** sources.json 文件包装（带 schema 版本）。 */
    @Serializable
    private data class SourcesFile(
        val schema: Int = SCHEMA_VERSION,
        val sources: List<VideoSource> = emptyList(),
    )

    private fun loadSourcesLocked() {
        val file = sourcesFile
        if (!file.exists()) {
            // 首次启动：落一份空文件，便于人工检查与后续原子写
            persistSourcesLocked(emptyList())
            _sources.value = emptyList()
            return
        }
        runCatching {
            val text = file.readText()
            if (text.isBlank()) {
                emptyList()
            } else {
                val parsed = json.decodeFromString<SourcesFile>(text)
                when {
                    parsed.schema > SCHEMA_VERSION -> {
                        // 高版本数据：告警并降级加载（§11：新增字段给默认值）
                        TvLog.w(TAG, "sources.json schema=${parsed.schema} 高于当前 $SCHEMA_VERSION，按兼容模式加载")
                        parsed.sources
                    }
                    parsed.schema < SCHEMA_VERSION -> {
                        TvLog.i(TAG, "sources.json schema 升级 ${parsed.schema} -> $SCHEMA_VERSION")
                        parsed.sources
                    }
                    else -> parsed.sources
                }
            }
        }.onSuccess { list ->
            _sources.value = list.sortedBy { it.name }
        }.onFailure { e ->
            // 文件损坏：备份后按空库启动，绝不崩溃（NFR-01）
            TvLog.e(TAG, "sources.json 解析失败，按空库启动并备份原文件", e as? Throwable)
            runCatching { file.copyTo(File(file.parentFile, "$FILE_SOURCES.corrupt"), overwrite = true) }
            _sources.value = emptyList()
        }
    }

    private fun loadStateLocked() {
        val file = stateFile
        if (!file.exists()) {
            persistStateLocked(QuadLayoutState())
            _layoutState.value = QuadLayoutState()
            return
        }
        runCatching {
            val text = file.readText()
            if (text.isBlank()) QuadLayoutState()
            else json.decodeFromString<QuadLayoutState>(text)
        }.onSuccess { state ->
            _layoutState.value = normalizeState(state)
        }.onFailure { e ->
            TvLog.e(TAG, "state.json 解析失败，使用默认布局", e as? Throwable)
            runCatching { file.copyTo(File(file.parentFile, "$FILE_STATE.corrupt"), overwrite = true) }
            _layoutState.value = QuadLayoutState()
        }
    }

    /** 兜底：slots 数量异常时补齐/截断到 4 格。 */
    private fun normalizeState(state: QuadLayoutState): QuadLayoutState {
        val count = QuadLayoutState.SLOT_COUNT
        if (state.slots.size == count) return state
        val slots = (0 until count).map { i ->
            state.slots.getOrNull(i)?.copy(slotIndex = i) ?: SlotBinding(i)
        }
        return state.copy(slots = slots)
    }

    /** 原子写：tmp + fsync + rename；rename 失败退化为覆盖写。 */
    private fun persistSourcesLocked(list: List<VideoSource>) {
        val text = json.encodeToString(SourcesFile(SCHEMA_VERSION, list))
        atomicWrite(sourcesFile, text)
    }

    private fun persistStateLocked(state: QuadLayoutState) {
        val text = json.encodeToString(state)
        atomicWrite(stateFile, text)
    }

    private fun atomicWrite(target: File, content: String) {
        val dir = target.parentFile ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        val renamed = tmp.renameTo(target)
        if (!renamed) {
            // 极端文件系统 rename 语义差异：退化为直接覆盖，保证可用性
            TvLog.w(TAG, "rename 失败，退化为覆盖写：${target.name}")
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "TvMonitor:SourceStore"
        private const val FILE_SOURCES = "sources.json"
        private const val FILE_STATE = "state.json"
        private const val SCHEMA_VERSION = QuadLayoutState.SCHEMA_VERSION

        @Volatile
        private var instance: JsonSourceStore? = null

        /**
         * 进程级单例：保证 MainActivity / 各 ViewModel 共享同一份内存状态与 Flow，
         * 避免多实例各持一份 StateFlow 导致变更不同步（写入同文件但内存分叉）。
         */
        fun get(context: Context): JsonSourceStore =
            instance ?: synchronized(this) {
                instance ?: JsonSourceStore(context.applicationContext).also { instance = it }
            }
    }
}
