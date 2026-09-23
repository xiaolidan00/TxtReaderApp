package com.xld.txtreader.ui.reader

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xld.txtreader.appRepositories
import com.xld.txtreader.appSettings
import com.xld.txtreader.core.BookContent
import com.xld.txtreader.core.BookContentHolder
import com.xld.txtreader.core.BookSearcher
import com.xld.txtreader.core.ChapterParser
import com.xld.txtreader.core.EncodingReader
import com.xld.txtreader.core.Paginator
import com.xld.txtreader.core.RegexPresets
import com.xld.txtreader.core.SentenceSegmenter
import com.xld.txtreader.tts.TtsController
import com.xld.txtreader.OpenBookStore
import com.xld.txtreader.eventEmitter
import com.xld.txtreader.EVENT_TTS_DONE
import com.xld.txtreader.EVENT_TTS_SENTENCE_START
import com.xld.txtreader.EVENT_TTS_START
import com.xld.txtreader.EVENT_TTS_READY
import com.xld.txtreader.EVENT_TTS_STOPPED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@Immutable
data class ReaderUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val loadErrorMessage: String = "",
    val filePath: String = "",
    val fileName: String = "",
    val chapters: List<String> = emptyList(),
    val currentChapter: Int = 0,
    val currentPage: Int = 0,
    val totalChapter: Int = 0,
    val totalPages: Int = 0,
    val fontSp: Float = 16f,
    val lineSpacing: Float = 2f,
    val textColor: Long = 0xFF333333,
    val bgColor: Long = 0xFFFFF9F0,
    val regexType: Int = 0,
    val regexStr: String = "",
    val encodeStr: String = "UTF-8",
    val pagesEpoch: Int = 0,
    val scrollEpoch: Int = 0,
    val pendingScroll: Int? = null,
    val searchKeyword: String = "",
    val searchResults: List<BookSearcher.Hit> = emptyList(),
    val searching: Boolean = false,
    val highlightKeyword: String = "",
    val ttsPlaying: Boolean = false,
    val ttsAvailable: Boolean = false,
    val ttsChapter: Int = 0,
    val ttsPageInChapter: Int = 0,
    val ttsPagesInChapter: Int = 0,
    val ttsTotalChapter: Int = 0,
    val ttsBookTitle: String = "",
    val ttsChapterTitle: String = "",
    val ttsSpeed: Float = 1f,
    val ttsHighlightIndex: Int = -1,
    val ttsHighlightRanges: List<IntRange> = emptyList(),
) {
    val ttsProgressText: String
        get() = if (ttsPagesInChapter > 0) "${ttsPageInChapter + 1}/${ttsPagesInChapter}" else "0/0"
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo get() = getApplication<Application>().appRepositories
    private val settings get() = getApplication<Application>().appSettings
    private val controller = TtsController(getApplication())

    private val _state = MutableStateFlow(ReaderUiState(regexStr = RegexPresets.default().value))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var content: BookContent? = null
    private var pageList: Array<List<IntRange>> = emptyArray()
    private var offsets: IntArray = IntArray(0)
    private var currentChapter = 0
    private var currentPage = 0
    private var lastContentHash: String? = null
    private var lastPageListEpoch: Int = 0
    private var persistJob: Job? = null
    private var ttsSentenceIndex = 0

    init {
        viewModelScope.launch {
            eventEmitter.on(EVENT_TTS_START) { _ ->
                val c = content ?: return@on
                val g = currentGlobal()
                val ch = chapterOf(g)
                val local = g - (offsets.getOrNull(ch) ?: 0)
                _state.update {
                    it.copy(
                        ttsChapter = ch,
                        ttsPageInChapter = local,
                        ttsPagesInChapter = pageList.getOrNull(ch)?.size ?: 0
                    )
                }
            }
            eventEmitter.on(EVENT_TTS_READY) { _ ->
                _state.update { it.copy(ttsAvailable = true) }
            }
            eventEmitter.on(EVENT_TTS_STOPPED) { _ ->
                _state.update { it.copy(ttsPlaying = false) }
            }
            eventEmitter.on(EVENT_TTS_SENTENCE_START) { index ->
                val idx = index as? Int ?: return@on
                val c = content ?: return@on
                val text = pageTextAt(currentGlobal())
                val sentences = SentenceSegmenter.split(text)
                val ranges = sentences.map { it.start..it.end }
                _state.update {
                    it.copy(ttsHighlightIndex = idx, ttsHighlightRanges = ranges)
                }
            }
            eventEmitter.on(EVENT_TTS_DONE) { _ ->
                _state.update {
                    it.copy(ttsHighlightIndex = -1, ttsHighlightRanges = emptyList())
                }
                viewModelScope.launch {
                    ttsNextPage()
                }
            }
            // 监听 content 变化，自动更新 TTS 文本
            viewModelScope.launch {
                while (true) {
                    val newHash = content?.text?.hashCode().toString()
                    if (newHash != lastContentHash) {
                        lastContentHash = newHash
                         if (_state.value.ttsPlaying) {
                             val ch = currentChapter
                             val text = pageTextAt(currentGlobal())
                             controller.play(text)
                             _state.update {
                                 it.copy(
                                     ttsChapter = ch,
                                     ttsPageInChapter = currentPage,
                                     ttsPagesInChapter = pageList.getOrNull(ch)?.size ?: 0
                                 )
                             }
                         }
                     }
                     delay(500)
                 }
             }
             // 监听 pageList 变化，自动更新 TTS 文本
             viewModelScope.launch {
                 while (true) {
                     val newEpoch = _state.value.pagesEpoch
                     if (newEpoch != lastPageListEpoch) {
                         lastPageListEpoch = newEpoch
                         if (_state.value.ttsPlaying) {
                             val ch = currentChapter
                             val text = pageTextAt(currentGlobal())
                             controller.play(text)
                             _state.update {
                                 it.copy(
                                     ttsChapter = ch,
                                     ttsPageInChapter = currentPage,
                                     ttsPagesInChapter = pageList.getOrNull(ch)?.size ?: 0
                                 )
                             }
                         }
                     }
                     delay(500)
                 }
             }
        }
        load()
    }

    fun load() {
        val path = OpenBookStore.filePath
        if (path.isNullOrBlank()) {
            _state.update {
                it.copy(
                    loading = false,
                    loadError = true,
                    loadErrorMessage = "未指定文件路径"
                )
            }
            return
        }
        viewModelScope.launch(context = Dispatchers.IO) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = true,
                            loadErrorMessage = "文件不存在：$path"
                        )
                    }
                    return@launch
                }
                if (!file.canRead()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = true,
                            loadErrorMessage = "无法读取文件，请检查访问权限"
                        )
                    }
                    return@launch
                }
                val record = repo.get(path)
                val encode = record?.encodeStr ?: "UTF-8"
                val text = EncodingReader.readText(file, encode)
                if (text.isBlank()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = true,
                            loadErrorMessage = "文件内容为空"
                        )
                    }
                    return@launch
                }
                val garbledRatio =
                    text.count { it == '\uFFFD' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') }
                        .toFloat() / text.length
                if (garbledRatio > 0.01) {
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = true,
                            loadErrorMessage = "编码不匹配，文件内容显示为乱码\n当前编码：$encode\n请在设置中选择正确的编码方式"
                        )
                    }
                    return@launch
                }
                val fileName = record?.fileName ?: file.name
                val regexType = record?.regexType ?: 0
                val regexStr = record?.regexStr ?: RegexPresets.list[regexType].value
                val chapters = ChapterParser.parse(text, regexStr)
                val book = BookContent(
                    path,
                    fileName,
                    text,
                    chapters,
                    encode,
                    regexStr,
                    regexType,
                    record?.fileSize ?: 0L
                )
                content = book
                BookContentHolder = book

                val restoreChapter = (record?.currentChapter ?: 0)
                val restorePage = (record?.pageIndex ?: 0)

                _state.update {
                    it.copy(
                        loading = false,
                        loadError = false,
                        loadErrorMessage = "",
                        filePath = path,
                        fileName = fileName,
                        chapters = chapters.map { ch ->
                            ch.title.takeIf { t -> t.isNotBlank() } ?: ""
                        },
                        totalChapter = chapters.size,
                        fontSp = settings.fontSizeSp,
                        lineSpacing = settings.lineSpacingMult,
                        textColor = settings.textColorArgb,
                        bgColor = settings.bgColorArgb,
                        regexType = regexType,
                        regexStr = regexStr,
                        encodeStr = encode,
                    )
                }
                paginateAll()
                applyRestore(restoreChapter, restorePage)
            } catch (e: Exception) {
                val msg = when {
                    e is java.io.FileNotFoundException -> "文件不存在：$path"
                    e is java.io.IOException -> "读取文件失败：${e.message}"
                    else -> "加载失败：${e.message ?: e.javaClass.simpleName}"
                }
                _state.update { it.copy(loading = false, loadError = true, loadErrorMessage = msg) }
            }
        }
    }

    fun setViewport(widthPx: Int, heightPx: Int, density: Float) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return
        if (settings.contentWidthPx == widthPx && settings.contentHeightPx == heightPx && settings.density == density) {
            return
        }
        settings.contentWidthPx = widthPx
        settings.contentHeightPx = heightPx
        settings.density = density
        if (content != null) {
            repaginatePreservingPosition()
        }
    }

    fun pageTextAt(global: Int): String {
        val c = content ?: return ""
        if (offsets.isEmpty()) return ""
        val total = offsets.getOrNull(offsets.size - 1) ?: return ""
        if (global < 0 || global >= total) return ""
        val ch = chapterOf(global)
        val local = global - offsets[ch]
        val range = pageList.getOrNull(ch)?.getOrNull(local) ?: return ""
        return c.chapterText(ch).substring(range)
    }

    fun backPage() {
        val wasPlaying = _state.value.ttsPlaying
        if (wasPlaying) {
            controller.stop()
            _state.update { it.copy(ttsPlaying = false) }
        }
        persist()
    }

    fun currentGlobal(): Int {
        if (offsets.isEmpty()) return 0
        val total = offsets[offsets.size - 1]
        val base = offsets.getOrNull(currentChapter) ?: 0
        return (base + currentPage).coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    fun onPageChanged(global: Int) {
        val c = content ?: return
        if (offsets.isEmpty()) return
        val ch = chapterOf(global)
        val local = global - offsets[ch]
        if (ch != currentChapter || local != currentPage) {
            ttsSentenceIndex = 0
            val wasPlaying = _state.value.ttsPlaying
            if (wasPlaying) {
                controller.stop()
                _state.update { it.copy(ttsPlaying = false) }
            }
            currentChapter = ch
            currentPage = local
            _state.update { it.copy(currentChapter = ch, currentPage = local) }
            persist()
            if (wasPlaying) ttsPlayCurrent()
        }
    }

    fun jumpChapter(chapter: Int) {
        val c = content ?: return
        if (chapter !in c.chapters.indices) return
        ttsSentenceIndex = 0
        val wasPlaying = _state.value.ttsPlaying
        if (wasPlaying) {
            _state.update { it.copy(ttsChapter = chapter, ttsPagesInChapter = pageList.getOrNull(chapter)?.size ?: 0) }
        }
        val local = 0
        jumpTo(chapter, local)
        if (wasPlaying) ttsPlayCurrent()
    }

    fun jumpTo(chapter: Int, pageInChapter: Int, highlightKeyword: String = "") {
        val c = content ?: return
        if (chapter !in c.chapters.indices) return
        val clamped = pageInChapter.coerceIn(0, (pageList.getOrNull(chapter)?.size ?: 1) - 1)
        if (chapter == currentChapter && clamped == currentPage) {
            if (highlightKeyword.isNotBlank()) {
                _state.update { it.copy(highlightKeyword = highlightKeyword) }
            }
            return
        }
        ttsSentenceIndex = 0
        val wasPlaying = _state.value.ttsPlaying
        if (wasPlaying) {
            _state.update { it.copy(ttsChapter = chapter, ttsPagesInChapter = pageList.getOrNull(chapter)?.size ?: 0) }
        }
        currentChapter = chapter
        currentPage = clamped
        val g = currentGlobal()
        _state.update {
            it.copy(
                currentChapter = chapter,
                currentPage = clamped,
                scrollEpoch = it.scrollEpoch + 1,
                pendingScroll = g,
                highlightKeyword = highlightKeyword,
            )
        }
        persist()
        if (wasPlaying) ttsPlayCurrent()
    }

    fun jumpToHit(hit: BookSearcher.Hit, highlightKeyword: String) {
        val c = content ?: return
        val ch = hit.chapter
        if (ch !in c.chapters.indices) return
        val local = hit.offset
        if (local < 0) return
        val pages = pageList.getOrNull(ch).orEmpty()
        if (pages.isEmpty()) {
            jumpTo(ch, 0, highlightKeyword)
            return
        }
        var page = pages.indexOfFirst { local in it }
        if (page < 0) page = pages.indexOfLast { it.first <= local }.coerceAtLeast(0)
        jumpTo(ch, page, highlightKeyword)
    }

    fun seekToGlobal(global: Int) {
        if (offsets.isEmpty()) return
        val total = offsets[offsets.size - 1]
        val g = global.coerceIn(0, (total - 1).coerceAtLeast(0))
        val ch = chapterOf(g)
        val local = g - offsets[ch]
        ttsSentenceIndex = 0
        val wasPlaying = _state.value.ttsPlaying
        if (wasPlaying) {
            _state.update { it.copy(ttsChapter = ch, ttsPagesInChapter = pageList.getOrNull(ch)?.size ?: 0) }
        }
        currentChapter = ch
        currentPage = local
        _state.update {
            it.copy(
                currentChapter = ch,
                currentPage = local,
                scrollEpoch = it.scrollEpoch + 1,
                pendingScroll = g,
            )
        }
        if (wasPlaying) ttsPlayCurrent()
        persist()
    }

    fun consumeScroll() {
        _state.update { it.copy(pendingScroll = null) }
    }

    fun setFont(value: Float) {
        settings.fontSizeSp = value
        _state.update { it.copy(fontSp = value) }
        repaginateKeepPosition()
    }

    fun setLineSpacing(value: Float) {
        settings.lineSpacingMult = value
        _state.update { it.copy(lineSpacing = value) }
        repaginateKeepPosition()
    }

    fun setTextColor(value: Long) {
        settings.textColorArgb = value
        _state.update { it.copy(textColor = value) }
    }

    fun setBgColor(value: Long) {
        settings.bgColorArgb = value
        _state.update { it.copy(bgColor = value) }
    }

    fun setRegex(type: Int) {
        if (type !in RegexPresets.list.indices) return
        if (type == RegexPresets.CUSTOM_INDEX) {
            _state.update { it.copy(regexType = type) }
            return
        }
        val c = content ?: return
        val preset = RegexPresets.list[type]
        if (preset.value == c.regexStr) {
            _state.update { it.copy(regexType = type, regexStr = preset.value) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val chapters = ChapterParser.parse(c.text, preset.value)
            val updated = BookContent(
                c.filePath,
                c.fileName,
                c.text,
                chapters,
                c.encodeStr,
                preset.value,
                type
            )
            content = updated
            BookContentHolder = updated
            repo.updateRegex(c.filePath, type, preset.value)
            paginateAndRestoreChapterList(updated, type, preset.value)
        }
    }

    fun setRegexCustom(pattern: String) {
        if (pattern.isBlank()) return
        val c = content ?: return
        if (pattern == c.regexStr) {
            _state.update { it.copy(regexType = RegexPresets.CUSTOM_INDEX, regexStr = pattern) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val chapters = ChapterParser.parse(c.text, pattern)
            val updated = BookContent(
                c.filePath,
                c.fileName,
                c.text,
                chapters,
                c.encodeStr,
                pattern,
                RegexPresets.CUSTOM_INDEX
            )
            content = updated
            BookContentHolder = updated
            repo.updateRegex(c.filePath, RegexPresets.CUSTOM_INDEX, pattern)
            paginateAndRestoreChapterList(updated, RegexPresets.CUSTOM_INDEX, pattern)
        }
    }

    fun setEncode(encode: String) {
        val c = content ?: return
        if (encode == c.encodeStr) {
            _state.update { it.copy(encodeStr = encode) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val uri = runCatching { android.net.Uri.parse(c.filePath) }.getOrNull()
                val text = if (uri?.scheme == "content") {
                    EncodingReader.readText(getApplication(), uri, encode)
                } else {
                    EncodingReader.readText(java.io.File(c.filePath), encode)
                }
                val chapters = ChapterParser.parse(text, c.regexStr)
                val updated = BookContent(
                    c.filePath,
                    c.fileName,
                    text,
                    chapters,
                    encode,
                    c.regexStr,
                    c.regexType,
                    c.fileSize
                )
                content = updated
                BookContentHolder = updated
                repo.updateEncode(c.filePath, encode)
                paginateAndRestoreChapterList(updated, c.regexType, c.regexStr)
                _state.update { it.copy(encodeStr = encode) }
            }
        }
    }

    fun search(keyword: String) {
        _state.update { it.copy(searchKeyword = keyword, searching = true, highlightKeyword = "") }
        if (keyword.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val c = content ?: return@launch
            val results = BookSearcher().search(c, pageList.toList(), keyword)
            _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun ttsPlayCurrent(startIndex: Int = 0) {
        val c = content ?: return
        val text = pageTextAt(currentGlobal())
        if (text.isBlank()) return
        val ch = currentChapter
        val chTitle = c.chapterTitle(ch)
        ttsSentenceIndex = startIndex
        controller.play(text, startIndex)
        _state.update {
            it.copy(
                ttsPlaying = true,
                ttsChapter = ch,
                ttsPageInChapter = currentPage,
                ttsPagesInChapter = pageList.getOrNull(ch)?.size ?: 0,
                ttsBookTitle = c.fileName,
                ttsChapterTitle = chTitle,
                ttsTotalChapter = c.totalChapter
            )
        }
    }

    fun ttsToggle() {
        val cur = _state.value
        if (cur.ttsPlaying) {
            ttsSentenceIndex = cur.ttsHighlightIndex.coerceAtLeast(0)
            controller.pause()
            _state.update { it.copy(ttsPlaying = false) }
        } else {
            ttsPlayCurrent(ttsSentenceIndex)
        }
    }

    fun ttsPrevPage() {
        moveByPage(-1)
    }

    fun ttsNextPage() {
        moveByPage(1)
    }

    fun ttsPrevChapter() {
        jumpChapter(currentChapter - 1)
    }

    fun ttsNextChapter() {
        jumpChapter(currentChapter + 1)
    }

    fun ttsSpeed(value: Float) {
        settings.ttsSpeed = value
        controller.speed(value)
        _state.update { it.copy(ttsSpeed = value) }
    }

    fun stopTts() {
        controller.stop()
        _state.update { it.copy(ttsPlaying = false) }
    }

    private fun moveByPage(delta: Int) {
        if (offsets.isEmpty()) return
        seekToGlobal(currentGlobal() + delta)
    }

    private fun paginateAll() {
        val c = content ?: return
        val s = settings
        val w = s.contentWidthPx
        val h = s.contentHeightPx
        val density = s.density
        if (w <= 0 || h <= 0 || density <= 0f) return
        val fontPx = s.fontSizeSp * density
        val linePx = s.fontSizeSp * s.lineSpacingMult * density
        val paginator = Paginator(w, h, fontPx, linePx)
        pageList = Array(c.chapters.size) { i -> paginator.paginate(c.chapterText(i)) }
        offsets = IntArray(c.chapters.size + 1)
        var acc = 0
        for (i in c.chapters.indices) {
            offsets[i] = acc
            acc += pageList[i].size
        }
        offsets[c.chapters.size] = acc
        _state.update { it.copy(totalPages = acc, pagesEpoch = it.pagesEpoch + 1) }
    }

    private fun applyRestore(restoreChapter: Int, restorePage: Int) {
        val c = content ?: return
        val ch = restoreChapter.coerceIn(0, (c.totalChapter - 1).coerceAtLeast(0))
        val maxPage = (pageList.getOrNull(ch)?.size ?: 1) - 1
        currentChapter = ch
        currentPage = restorePage.coerceIn(0, maxPage.coerceAtLeast(0))
        val g = currentGlobal()
        _state.update {
            it.copy(
                currentChapter = ch,
                currentPage = currentPage,
                scrollEpoch = it.scrollEpoch + 1,
                pendingScroll = g,
            )
        }
        persist()
    }

    private fun repaginateKeepPosition() {
        repaginatePreservingPosition()
    }

    private fun repaginatePreservingPosition() {
        val anchor = pageAnchorOffset()
        paginateAll()
        placeAt(anchor)
    }

    private fun pageAnchorOffset(): Int {
        val c = content ?: return 0
        val range = pageList.getOrNull(currentChapter)?.getOrNull(currentPage) ?: return 0
        return offsets.getOrNull(currentChapter)?.plus(range.first) ?: 0
    }

    private fun placeAt(offset: Int) {
        val c = content ?: return
        if (c.totalChapter == 0) return
        val ch = chapterOf(offset)
        val pages = pageList.getOrNull(ch).orEmpty()
        val local = offset - offsets[ch]
        var page = pages.indexOfFirst { local in it }
        if (page < 0) page = pages.indexOfLast { it.first <= local }.coerceAtLeast(0)
        currentChapter = ch.coerceIn(0, c.totalChapter - 1)
        currentPage = page.coerceIn(0, (pageList.getOrNull(ch)?.size ?: 1) - 1)
        val g = currentGlobal()
        _state.update {
            it.copy(
                currentChapter = currentChapter,
                currentPage = currentPage,
                scrollEpoch = it.scrollEpoch + 1,
                pendingScroll = g,
            )
        }
    }

    private fun paginateAndRestoreChapterList(book: BookContent, type: Int, regex: String) {
        paginateAll()
        currentChapter = currentChapter.coerceIn(0, (book.totalChapter - 1).coerceAtLeast(0))
        currentPage = currentPage.coerceIn(0, (pageList.getOrNull(currentChapter)?.size ?: 1) - 1)
        val g = currentGlobal()
        _state.update {
            it.copy(
                chapters = book.chapters.map { ch ->
                    ch.title.takeIf { t -> t.isNotBlank() } ?: ""
                },
                totalChapter = book.totalChapter,
                regexType = type,
                regexStr = regex,
                currentChapter = currentChapter,
                currentPage = currentPage,
                scrollEpoch = it.scrollEpoch + 1,
                pendingScroll = g,
            )
        }
        persist()
    }

    private fun chapterOf(global: Int): Int {
        val c = content ?: return 0
        var ch = 0
        while (ch < c.totalChapter && offsets[ch + 1] <= global) ch++
        return (ch).coerceAtMost(c.totalChapter - 1)
    }


    fun persist() {
        val c = content ?: return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(400)
            repo.updateProgress(
                c.filePath,
                currentChapter,
                currentPage,
                c.totalChapter,
                c.text.length.toLong()
            )
        }
    }
}
