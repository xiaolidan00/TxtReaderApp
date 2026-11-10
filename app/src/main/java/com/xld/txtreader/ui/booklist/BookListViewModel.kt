package com.xld.txtreader.ui.booklist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xld.txtreader.appRepositories
import com.xld.txtreader.appSettings
import com.xld.txtreader.data.db.BookGroup
import com.xld.txtreader.data.db.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BookListViewModel(application: Application) : AndroidViewModel(application) {

    enum class SortKey(val label: String) {
        TIME("时间"), NAME("名称"), SIZE("大小");
    }

    /** 分组视图下分组卡片的排序键 */
    enum class GroupSortKey(val label: String) {
        COUNT("文件数"), TIME("时间"), NAME("名称");
    }

    /** 书架视图模式 */
    enum class ViewMode { LIST, GROUP }

    /**
     * 分组卡片：[id] = 0 表示虚拟的"未分类"分组（不入库，
     * fileNum 由 books 表实时统计）。
     */
    data class GroupCard(
        val id: Int,
        val groupName: String,
        val fileNum: Int,
        val updateTime: Long,
    )

    data class UiState(
        /** 列表视图数据（已过滤排序） */
        val books: List<BookRecord> = emptyList(),
        /** 分组视图卡片（未分类固定首位，其余按 groupSortKey 排序） */
        val groupCards: List<GroupCard> = emptyList(),
        /** 分组详情页内文件（已过滤排序） */
        val groupBooks: List<BookRecord> = emptyList(),
        /** 原始分组列表（供弹窗选择，不参与搜索过滤） */
        val allGroups: List<BookGroup> = emptyList(),
        val keyword: String = "",
        val sortKey: SortKey = SortKey.TIME,
        val ascending: Boolean = false,
        val groupSortKey: GroupSortKey = GroupSortKey.COUNT,
        val groupAscending: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val viewMode: ViewMode = ViewMode.LIST,
        /** null = 分组列表页；非 null = 分组详情页 */
        val currentGroupId: Int? = null,
        /** 分组卡片分组懒加载：当前已展示条数 */
        val groupVisibleCount: Int = PAGE_SIZE,
        /** 分组详情页文件懒加载：当前已展示条数 */
        val groupBookVisibleCount: Int = PAGE_SIZE,
        /** 新增/重命名分组失败提示（如"分组已存在"） */
        val groupError: String? = null,
        /** 书架懒加载：当前已展示条数，每次追加 PAGE_SIZE 条 */
        val visibleCount: Int = PAGE_SIZE,
        /** 分组详情页标题显示的组内文件总数（不随搜索过滤） */
        val currentGroupTotal: Int = 0,
    ) {
        /** 当前展示的列表切片 */
        val visibleBooks: List<BookRecord>
            get() = books.take(visibleCount)

        /** 当前展示的分组卡片切片 */
        val visibleGroupCards: List<GroupCard>
            get() = groupCards.take(groupVisibleCount)

        /** 当前展示的分组内文件切片 */
        val visibleGroupBooks: List<BookRecord>
            get() = groupBooks.take(groupBookVisibleCount)

        /** 分组详情页标题显示的分组名 */
        val currentGroupName: String
            get() = allGroups.find { it.id == currentGroupId }?.groupName ?: UNCLASSIFIED_NAME
    }

    /** 输入快照：把所有可变输入聚合到一个 Flow，避免 combine 参数数量超限 */
    private data class Inputs(
        val records: List<BookRecord> = emptyList(),
        val groups: List<BookGroup> = emptyList(),
        val keyword: String = "",
        val sortKey: SortKey = SortKey.TIME,
        val ascending: Boolean = false,
        val groupSortKey: GroupSortKey = GroupSortKey.COUNT,
        val groupAscending: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val currentGroupId: Int? = null,
    )

    private val repo get() = getApplication<Application>().appRepositories
    private val settings get() = getApplication<Application>().appSettings

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val inputs = MutableStateFlow(
        Inputs(viewMode = if (settings.bookViewIsGroup) ViewMode.GROUP else ViewMode.LIST)
    )

    init {
        viewModelScope.launch {
            repo.books.collect { list -> inputs.update { it.copy(records = list) } }
        }
        viewModelScope.launch {
            repo.groups.collect { list -> inputs.update { it.copy(groups = list) } }
        }
        viewModelScope.launch {
            inputs.collect { s -> _state.update { prev -> compute(prev, s) } }
        }
    }

    // ------------------------------------------------------------ 输入设置

    fun setKeyword(value: String) {
        if (inputs.value.keyword == value) return
        inputs.update { it.copy(keyword = value) }
        // 关键字变化时重置各视图分页并回顶
        _state.update {
            it.copy(visibleCount = PAGE_SIZE, groupVisibleCount = PAGE_SIZE, groupBookVisibleCount = PAGE_SIZE)
        }
    }

    fun setSort(key: SortKey, asc: Boolean) {
        if (inputs.value.sortKey == key && inputs.value.ascending == asc) return
        inputs.update { it.copy(sortKey = key, ascending = asc) }
        _state.update { it.copy(visibleCount = PAGE_SIZE, groupBookVisibleCount = PAGE_SIZE) }
    }

    fun setGroupSort(key: GroupSortKey, asc: Boolean) {
        if (inputs.value.groupSortKey == key && inputs.value.groupAscending == asc) return
        inputs.update { it.copy(groupSortKey = key, groupAscending = asc) }
        _state.update { it.copy(groupVisibleCount = PAGE_SIZE) }
    }

    /** 切换列表视图 / 分组视图（持久化到 SharedPreferences） */
    fun setViewMode(mode: ViewMode) {
        if (inputs.value.viewMode == mode) return
        settings.bookViewIsGroup = mode == ViewMode.GROUP
        inputs.update { it.copy(viewMode = mode, currentGroupId = null) }
        _state.update {
            it.copy(
                selectedPaths = emptySet(),
                visibleCount = PAGE_SIZE,
                groupVisibleCount = PAGE_SIZE,
                groupBookVisibleCount = PAGE_SIZE,
            )
        }
    }

    /** 进入分组详情页（清除按组名输入的关键词，避免误过滤文件） */
    fun enterGroup(id: Int) {
        inputs.update { it.copy(currentGroupId = id, keyword = "") }
        _state.update {
            it.copy(
                selectedPaths = emptySet(),
                keyword = "",
                groupBookVisibleCount = PAGE_SIZE,
                groupVisibleCount = PAGE_SIZE,
            )
        }
    }

    /** 返回分组列表页（清除按文件名输入的关键词，避免误过滤分组） */
    fun exitGroup() {
        inputs.update { it.copy(currentGroupId = null, keyword = "") }
        _state.update {
            it.copy(
                selectedPaths = emptySet(),
                keyword = "",
                groupVisibleCount = PAGE_SIZE,
            )
        }
    }

    // ------------------------------------------------------------ 懒加载

    /** 滑动到底部附近时追加一页（列表视图） */
    fun loadMore() {
        _state.update { s ->
            if (s.visibleCount >= s.books.size) s
            else s.copy(visibleCount = s.visibleCount + PAGE_SIZE)
        }
    }

    /** 分组卡片列表追加一页 */
    fun loadMoreGroups() {
        _state.update { s ->
            if (s.groupVisibleCount >= s.groupCards.size) s
            else s.copy(groupVisibleCount = s.groupVisibleCount + PAGE_SIZE)
        }
    }

    /** 分组详情页文件列表追加一页 */
    fun loadMoreGroupBooks() {
        _state.update { s ->
            if (s.groupBookVisibleCount >= s.groupBooks.size) s
            else s.copy(groupBookVisibleCount = s.groupBookVisibleCount + PAGE_SIZE)
        }
    }

    // ------------------------------------------------------------ 分组操作

    /** 新增分组：名称为空或重复时返回失败；成功时返回新分组 ID */
    suspend fun addGroup(name: String): Result<Int> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("请输入分组名称"))
        return repo.addGroup(trimmed).map { it.toInt() }
    }

    /** 重命名分组：名称为空或与其它分组重复时返回失败 */
    suspend fun renameGroup(id: Int, name: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("请输入分组名称"))
        return repo.renameGroup(id, trimmed)
    }

    /** 设置分组操作错误提示（null 表示清除） */
    fun setGroupError(msg: String?) {
        if (_state.value.groupError != msg) _state.update { it.copy(groupError = msg) }
    }

    fun deleteGroup(id: Int) {
        if (id == UNCLASSIFIED_ID) return
        viewModelScope.launch(Dispatchers.IO) { repo.deleteGroup(id) }
        // 若正停留在被删除的分组内，返回分组列表页
        if (inputs.value.currentGroupId == id) exitGroup()
    }

    /** 单个文件移动到分组 */
    fun moveBookToGroup(path: String, groupId: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.moveBookToGroup(path, groupId) }
    }

    /** 批量移动已选文件到分组，完成后清空选择 */
    fun moveSelectedToGroup(groupId: Int) {
        val paths = _state.value.selectedPaths.toList()
        if (paths.isEmpty()) return
        _state.update { it.copy(selectedPaths = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) { repo.moveBooksToGroup(paths, groupId) }
    }

    fun clearGroupError() {
        if (_state.value.groupError != null) _state.update { it.copy(groupError = null) }
    }

    // ------------------------------------------------------------ 选择与删除

    fun deleteRecord(path: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteRecords(listOf(path)) }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteFilesAndRecords(listOf(path)) }
    }

    fun toggleSelect(path: String) {
        _state.update {
            val next = if (path in it.selectedPaths) it.selectedPaths - path else it.selectedPaths + path
            it.copy(selectedPaths = next)
        }
    }

    fun selectAll() {
        val target = if (_state.value.viewMode == ViewMode.GROUP && _state.value.currentGroupId != null) {
            _state.value.groupBooks
        } else {
            _state.value.books
        }
        _state.update { it.copy(selectedPaths = target.mapTo(HashSet()) { b -> b.filePath }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedPaths = emptySet()) }
    }

    fun deleteSelectedRecords() {
        val paths = _state.value.selectedPaths.toList()
        if (paths.isEmpty()) return
        _state.update { it.copy(selectedPaths = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) { repo.deleteRecords(paths) }
    }

    fun deleteSelectedFiles() {
        val paths = _state.value.selectedPaths.toList()
        if (paths.isEmpty()) return
        _state.update { it.copy(selectedPaths = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) { repo.deleteFilesAndRecords(paths) }
    }

    // ------------------------------------------------------------ 导入

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val path = resolveRealPath(context, uri) ?: continue
                val existing = repo.get(path)
                if (existing != null) continue
                val file = File(path)
                val name = file.name
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                val charset = com.xld.txtreader.core.EncodingReader.detectCharset(bytes)
                val text = com.xld.txtreader.core.EncodingReader.decode(bytes, charset)
                val preset = com.xld.txtreader.core.RegexPresets.default()
                val chapters = com.xld.txtreader.core.ChapterParser.parse(text, preset.value)
                val content = com.xld.txtreader.core.BookContent(
                    filePath = path,
                    fileName = name,
                    text = text,
                    chapters = chapters,
                    encodeStr = charset,
                    regexStr = preset.value,
                    regexType = com.xld.txtreader.core.RegexPresets.list.indexOf(preset),
                    fileSize = file.length(),
                )
                repo.addBook(content)
            }
        }
    }

    private fun resolveRealPath(context: android.content.Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return if (f.exists()) f.absolutePath else null
        }
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val displayName = c.getString(idx) ?: return null
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val candidate = File(downloads, displayName)
                    if (candidate.exists()) return candidate.absolutePath
                }
            }
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex("_data")
                    if (dataIdx >= 0) {
                        val dataPath = c.getString(dataIdx)
                        if (dataPath != null) {
                            val f = File(dataPath)
                            if (f.exists()) return f.absolutePath
                        }
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------ 计算

    /** 由输入快照推导全部 UI 状态 */
    private fun compute(prev: UiState, s: Inputs): UiState {
        // 分组被删除时自动退出详情页
        val currentGroupId = s.currentGroupId?.let { id ->
            if (id == UNCLASSIFIED_ID || s.groups.any { it.id == id }) id else null
        }

        val listBooks = applyFilterSort(s.records, s.keyword, s.sortKey, s.ascending)

        val groupCards = buildGroupCards(s)

        val groupBooks = if (currentGroupId == null) emptyList()
        else applyFilterSort(
            all = s.records.filter { it.groupId == currentGroupId },
            kw = s.keyword,
            key = s.sortKey,
            asc = s.ascending,
        )

        return prev.copy(
            books = listBooks,
            groupCards = groupCards,
            groupBooks = groupBooks,
            allGroups = s.groups,
            keyword = s.keyword,
            sortKey = s.sortKey,
            ascending = s.ascending,
            groupSortKey = s.groupSortKey,
            groupAscending = s.groupAscending,
            viewMode = s.viewMode,
            currentGroupId = currentGroupId,
            currentGroupTotal = if (currentGroupId == null) 0
            else s.records.count { it.groupId == currentGroupId },
        )
    }

    /** 分组卡片：未分类固定首位，其余按分组排序键排序，搜索框过滤组名 */
    private fun buildGroupCards(s: Inputs): List<GroupCard> {
        val unclassified = GroupCard(
            id = UNCLASSIFIED_ID,
            groupName = UNCLASSIFIED_NAME,
            fileNum = s.records.count { it.groupId == UNCLASSIFIED_ID },
            updateTime = s.records.filter { it.groupId == UNCLASSIFIED_ID }
                .maxOfOrNull { it.updateTime } ?: 0L,
        )
        val others = s.groups
            .filter { s.keyword.isBlank() || it.groupName.contains(s.keyword, ignoreCase = true) }
            .map { GroupCard(it.id, it.groupName, it.fileNum, it.updateTime) }
        val sorted = when (s.groupSortKey) {
            GroupSortKey.COUNT -> others.sortedBy { it.fileNum }
            GroupSortKey.TIME -> others.sortedBy { it.updateTime }
            GroupSortKey.NAME -> others.sortedBy { it.groupName }
        }
        val ordered = if (s.groupAscending) sorted else sorted.reversed()
        // 搜索时未分类仅在关键词命中组名或无关键词时展示
        val showUnclassified = s.keyword.isBlank() || UNCLASSIFIED_NAME.contains(s.keyword, ignoreCase = true)
        return (if (showUnclassified) listOf(unclassified) else emptyList()) + ordered
    }

    private fun applyFilterSort(
        all: List<BookRecord>,
        kw: String,
        key: SortKey,
        asc: Boolean,
    ): List<BookRecord> {
        val filtered = if (kw.isBlank()) all else all.filter { it.fileName.contains(kw, ignoreCase = true) }
        val sorted = when (key) {
            SortKey.TIME -> filtered.sortedBy { it.updateTime }
            SortKey.NAME -> filtered.sortedBy { it.fileName }
            SortKey.SIZE -> filtered.sortedBy { it.fileSize }
        }
        return if (asc) sorted else sorted.reversed()
    }

    companion object {
        /** 书架列表每页加载条数 */
        const val PAGE_SIZE = 20

        /** 未分类虚拟分组 ID */
        const val UNCLASSIFIED_ID = 0

        /** 未分类虚拟分组名称 */
        const val UNCLASSIFIED_NAME = "未分类"
    }
}
