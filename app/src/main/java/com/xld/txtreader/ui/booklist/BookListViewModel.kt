package com.xld.txtreader.ui.booklist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xld.txtreader.appRepositories
import com.xld.txtreader.data.db.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BookListViewModel(application: Application) : AndroidViewModel(application) {

    enum class SortKey(val label: String) {
        TIME("时间"), NAME("名称"), SIZE("大小");
    }

    data class UiState(
        val books: List<BookRecord> = emptyList(),
        val keyword: String = "",
        val sortKey: SortKey = SortKey.TIME,
        val ascending: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
    )

    private val repo get() = getApplication<Application>().appRepositories

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val records = MutableStateFlow<List<BookRecord>>(emptyList())
    private val keyword = MutableStateFlow("")
    private val sortKey = MutableStateFlow(SortKey.TIME)
    private val ascending = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            repo.books.collect { records.value = it }
        }
        viewModelScope.launch {
            combine(records, keyword, sortKey, ascending) { all, kw, key, asc -> applyFilterSort(all, kw, key, asc) }
                .collect { list ->
                    _state.update { s -> s.copy(books = list) }
                }
        }
    }

    fun setKeyword(value: String) {
        keyword.value = value
    }

    fun setSort(key: SortKey, asc: Boolean) {
        sortKey.value = key
        ascending.value = asc
    }

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
        _state.update { it.copy(selectedPaths = it.books.mapTo(HashSet()) { b -> b.filePath }) }
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
}
