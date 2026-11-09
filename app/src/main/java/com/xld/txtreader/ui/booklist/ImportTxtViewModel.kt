package com.xld.txtreader.ui.booklist

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xld.txtreader.appRepositories
import com.xld.txtreader.core.ChapterParser
import com.xld.txtreader.core.EncodingReader
import com.xld.txtreader.core.RegexPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 扫描到的 txt 文件资源（仅包含未添加到书架的文件） */
data class TxtFileItem(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val parentLabel: String,
)

class ImportTxtViewModel(application: Application) : AndroidViewModel(application) {

    /** 扫描阶段：需要权限 / 扫描中 / 扫描完成 */
    sealed interface ScanPhase {
        data object NeedPermission : ScanPhase
        data class Scanning(val folderLabel: String, val found: Int) : ScanPhase
        data object Done : ScanPhase
    }

    data class UiState(
        val phase: ScanPhase = ScanPhase.NeedPermission,
        val files: List<TxtFileItem> = emptyList(),
        /** 导入界面分页展示，每次追加 LOAD_MORE_FILES 条 */
        val visibleFileCount: Int = LOAD_MORE_FILES,
        val checked: Set<String> = emptySet(),
        /** 导入进度（已导入数 / 总数），null 表示未在导入 */
        val importProgress: Pair<Int, Int>? = null,
        val imported: Boolean = false,
    ) {
        /** 当前展示的文件切片 */
        val visibleFiles: List<TxtFileItem>
            get() = files.take(visibleFileCount)
    }

    private val repo get() = getApplication<Application>().appRepositories

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 书架已存在的路径，用于置灰 */
    private val existingPaths = MutableStateFlow<Set<String>>(emptySet())

    private var scanJob: Job? = null
    private var importJob: Job? = null
    private var scanStarted = false

    init {
        viewModelScope.launch {
            repo.books.collect { records ->
                existingPaths.value = records.mapTo(HashSet()) { it.filePath }
                // 书架变化时（含导入过程中）把已添加的文件从列表移除
                if (_state.value.phase == ScanPhase.Done) removeAddedFiles()
            }
        }
    }

    /** 进入页面/从授权页返回时调用：检查权限并开始扫描（幂等） */
    fun startScan() {
        if (scanStarted) return
        if (!Environment.isExternalStorageManager()) {
            _state.update { it.copy(phase = ScanPhase.NeedPermission) }
            return
        }
        scanStarted = true
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    phase = ScanPhase.Scanning("准备中", 0),
                    files = emptyList(),
                    visibleFileCount = LOAD_MORE_FILES,
                    checked = emptySet(),
                )
            }
            val found = scanCommonFolders()
            if (found.isNotEmpty()) {
                _state.update { s ->
                    s.copy(
                        phase = ScanPhase.Done,
                        files = found.sortedByDescending { it.fileSize },
                    )
                }
            } else {
                _state.update { it.copy(phase = ScanPhase.Done) }
            }
        }
    }

    /** 递归扫描常用公共目录，只收集大于 1KB 且未添加到书架的 txt 文件 */
    private suspend fun scanCommonFolders(): List<TxtFileItem> = withContext(Dispatchers.IO) {
        val roots = commonFolders()
        val added = existingPaths.value
        val result = ArrayList<TxtFileItem>()
        for ((label, dir) in roots) {
            if (!dir.isDirectory) continue
            // 每进入一个顶层目录刷新一次进度
            _state.update { s ->
                if (s.phase is ScanPhase.Scanning) s.copy(phase = ScanPhase.Scanning(label, result.size)) else s
            }
            collectTxtFiles(dir, label, added, result, depth = 0)
        }
        result
    }

    /** 常用公共文件夹（含各自子目录） */
    private fun commonFolders(): List<Pair<String, File>> {
        val base = Environment.getExternalStorageDirectory()
        // label to Environment 目录常量（Books 等无系统常量，传 null 用同名子目录）
        val dirs: List<Pair<String, String?>> = listOf(
            "Download" to Environment.DIRECTORY_DOWNLOADS,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            // "DCIM" to Environment.DIRECTORY_DCIM,
            // "Pictures" to Environment.DIRECTORY_PICTURES,
            // "Music" to Environment.DIRECTORY_MUSIC,
            // "Movies" to Environment.DIRECTORY_MOVIES,
            // "Podcasts" to Environment.DIRECTORY_PODCASTS,
            // "Books" to null,
            // "Ringtones" to Environment.DIRECTORY_RINGTONES,
            // "Notifications" to Environment.DIRECTORY_NOTIFICATIONS,
            // "Alarms" to Environment.DIRECTORY_ALARMS,
        )
        return dirs.mapNotNull { (label, name) ->
            val dir = if (name == null) File(base, label) else Environment.getExternalStoragePublicDirectory(name)
            if (dir.isDirectory) label to dir else null
        }
    }

    private suspend fun collectTxtFiles(
        dir: File,
        parentLabel: String,
        added: Set<String>,
        out: MutableList<TxtFileItem>,
        depth: Int,
    ) {
        // 避免深层嵌套与循环符号链接拖慢扫描
        if (depth > 8) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                // 跳过 Android 系统目录
                if (child.name.equals("Android", ignoreCase = true)) continue
                collectTxtFiles(child, parentLabel, added, out, depth + 1)
            } else if (child.isFile &&
                child.name.endsWith(".txt", ignoreCase = true) &&
                child.length() > MIN_TXT_BYTES
            ) {
                val path = child.absolutePath
                // 已添加到书架的文件不展示
                if (path in added) continue
                out.add(
                    TxtFileItem(
                        filePath = path,
                        fileName = child.name,
                        fileSize = child.length(),
                        parentLabel = parentLabel,
                    ),
                )
                // 每发现 50 个刷新一次进度，避免频繁重组
                if (out.size % 50 == 0) {
                    _state.update { s ->
                        if (s.phase is ScanPhase.Scanning) s.copy(phase = ScanPhase.Scanning(parentLabel, out.size)) else s
                    }
                }
            }
        }
    }

    /** 书架变化时把已添加的文件从候选列表与勾选集中移除 */
    private suspend fun removeAddedFiles() {
        val added = existingPaths.value
        _state.update { s ->
            if (s.files.isEmpty()) s
            else s.copy(
                files = s.files.filter { it.filePath !in added },
                checked = s.checked - added,
            )
        }
    }

    fun toggleCheck(path: String) {
        _state.update { s ->
            if (s.files.none { it.filePath == path }) return@update s
            val next = if (path in s.checked) s.checked - path else s.checked + path
            s.copy(checked = next)
        }
    }

    /** 勾选全部：勾选列表中所有未导入的文件 */
    fun checkAll() {
        _state.update { s -> s.copy(checked = s.files.mapTo(HashSet()) { it.filePath }) }
    }

    /** 取消勾选全部 */
    fun uncheckAll() {
        _state.update { s -> s.copy(checked = emptySet()) }
    }

    /** 确认导入：按列表顺序依次解析并写入书架 */
    fun confirmImport() {
        val paths = _state.value.files
            .filter { it.filePath in _state.value.checked }
            .map { it.filePath }
        if (paths.isEmpty()) return
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(importProgress = 0 to paths.size) }
            var done = 0
            for (path in paths) {
                importOne(File(path))
                done++
                // 停止导入后不再回写进度（防止取消间隙覆盖 null）
                _state.update { s ->
                    if (s.importProgress == null) s else s.copy(importProgress = done to paths.size)
                }
            }
            if (!isActive) return@launch
            _state.update {
                it.copy(importProgress = null, imported = true, checked = emptySet())
            }
        }
    }

    /** 停止当前导入：取消协程并恢复界面可操作状态（已导入的文件保留） */
    fun stopImport() {
        val job = importJob ?: return
        if (!job.isActive) return
        job.cancel()
        importJob = null
        _state.update { it.copy(importProgress = null) }
    }

    /** 单个文件导入，逻辑与书架页手动添加一致 */
    private suspend fun importOne(file: File) {
        if (!file.exists()) return
        val path = file.absolutePath
        if (repo.get(path) != null) return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        if (bytes.isEmpty()) return
        val charset = EncodingReader.detectCharset(bytes)
        val text = EncodingReader.decode(bytes, charset)
        val preset = RegexPresets.default()
        val chapters = ChapterParser.parse(text, preset.value)
        val content = com.xld.txtreader.core.BookContent(
            filePath = path,
            fileName = file.name,
            text = text,
            chapters = chapters,
            encodeStr = charset,
            regexStr = preset.value,
            regexType = RegexPresets.list.indexOf(preset),
            fileSize = file.length(),
        )
        repo.addBook(content)
    }

    /** 扫描到的文件太多时分批展示，避免一次性重组 */
    fun loadMoreFiles() {
        _state.update { s ->
            if (s.files.size <= s.visibleFileCount) s
            else s.copy(visibleFileCount = s.visibleFileCount + LOAD_MORE_FILES)
        }
    }

    companion object {
        /** txt 文件最小字节数：必须大于 1KB */
        const val MIN_TXT_BYTES = 1024L

        /** 导入界面每次加载条数 */
        const val LOAD_MORE_FILES = 100
    }
}
