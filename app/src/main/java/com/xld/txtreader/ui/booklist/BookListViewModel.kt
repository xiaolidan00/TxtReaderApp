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

class BookListViewModel(application: Application) : AndroidViewModel(application) {

    enum class SortKey(val label: String) {
        TIME("时间"), NAME("名称"), SIZE("大小");
    }

    data class UiState(
        val books: List<BookRecord> = emptyList(),
        val keyword: String = "",
        val sortKey: SortKey = SortKey.TIME,
        val ascending: Boolean = false,
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

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val path = uri.toString()
                val existing = repo.get(path)
                if (existing != null) continue
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME).let { c.getString(it) }
                    }
                }.getOrNull() ?: "book_${System.currentTimeMillis()}.txt"
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: continue
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
                    fileSize = bytes.size.toLong(),
                )
                repo.addBook(content)
            }
        }
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
