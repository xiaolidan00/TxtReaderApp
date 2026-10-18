package com.xld.txtreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.xld.txtreader.OpenBookStore
import com.xld.txtreader.R
import com.xld.txtreader.core.BookSearcher
import com.xld.txtreader.core.RegexPresets

private enum class SheetKind { CHAPTERS, TTS, SEARCH, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember {
        viewModelFactory {
            initializer { ReaderViewModel(ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY.let { this[it]!! }) }
        }
    }
    val vm: ReaderViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val fileChangeTrigger by OpenBookStore.fileChangeTrigger.collectAsStateWithLifecycle()

    LaunchedEffect(fileChangeTrigger) {
        vm.load()
    }

    // 系统亮/暗色切换时刷新正文默认配色（仅未手动选色时生效）
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) {
        vm.applySystemReadingColors(systemDark)
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.persist()
        }
    }

    var activeSheet by remember { mutableStateOf<SheetKind?>(null) }

    val densities = LocalDensity.current
    val padXPx = with(densities) { 20.dp.toPx() }
    val padYPx = with(densities) { 12.dp.toPx() }

    // 阅读页统一配色：顶栏/底栏/正文共用，跟随系统暗色（未手动选色时）
    val readingBg = Color(state.bgColor.toInt())
    val readingText = Color(state.textColor.toInt())

    Box(
        Modifier
            .fillMaxSize()
            .background(readingBg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .navigationBarsPadding(),
        ) {
            // Top bar slot
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                ReaderTopBar(
                    fileName = state.fileName,
                    progress = if (state.totalChapter > 0) "第${state.currentChapter + 1}/${state.totalChapter}章" else "",
                    onBack = {
                        vm.persist()
                        vm.backPage()
                        onBack()
                    },
                    modifier = Modifier.fillMaxSize(),
                    textColor = readingText,
                    bgColor = readingBg,
                )
            }

            // Reading content
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        vm.setViewport(
                            size.width - (2 * padXPx).toInt(),
                            size.height - (2 * padYPx).toInt(),
                            densities.density
                        )
                    },
            ) {
                when {
                    state.loadError -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Text(
                                    state.loadErrorMessage.ifBlank { "加载失败" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                if (state.loadErrorMessage.contains("编码")) {
                                    Text(
                                        "请返回后在设置中切换编码方式",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    state.totalPages == 0 -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("该书为空")
                        }
                    }

                    else -> {
                        ReaderPagerHost(
                            vm = vm,
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Bottom bar slot
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .navigationBarsPadding()
            ) {
                ReaderTabBar(
                    onChapter = { activeSheet = SheetKind.CHAPTERS },
                    onTts = {
                        if (activeSheet == SheetKind.TTS) {
                            activeSheet = null
                        } else {
                            activeSheet = SheetKind.TTS
                        }
                    },
                    onSearch = { activeSheet = SheetKind.SEARCH },
                    onSettings = { activeSheet = SheetKind.SETTINGS },
                    modifier = Modifier.fillMaxSize(),
                    textColor = readingText,
                    bgColor = readingBg,
                )
            }
        }

        if (state.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }

    activeSheet?.let { sheet ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { activeSheet = null }, sheetState = sheetState) {
            when (sheet) {
                SheetKind.CHAPTERS -> ChapterSheet(
                    currentChapter = state.currentChapter,
                    chapters = state.chapters,
                    onJump = { chapter -> vm.jumpChapter(chapter); activeSheet = null },
                )

                SheetKind.TTS -> TtsSheet(
                    playing = state.ttsPlaying,
                    available = state.ttsAvailable,
                    chapterTitle = state.ttsChapterTitle,
                    progressText = state.ttsProgressText,
                    speed = state.ttsSpeed,
                    fileName = state.fileName,
                    onTogglePlay = {
                        vm.ttsToggle()
                    },
                    onPrevPage = vm::ttsPrevPage,
                    onNextPage = vm::ttsNextPage,
                    onPrevChapter = vm::ttsPrevChapter,
                    onNextChapter = vm::ttsNextChapter,
                    onSpeed = vm::ttsSpeed,
                )

                SheetKind.SEARCH -> SearchSheet(
                    state = state,
                    onSearch = vm::search,
                    onJump = { hit -> vm.jumpToHit(hit, state.searchKeyword); activeSheet = null },
                )

                SheetKind.SETTINGS -> SettingsSheet(
                    state = state,
                    onFont = vm::setFont,
                    onLineSpacing = vm::setLineSpacing,
                    onTextColor = vm::setTextColor,
                    onBgColor = vm::setBgColor,
                    onRegex = vm::setRegex,
                    onRegexCustom = vm::setRegexCustom,
                    onEncode = vm::setEncode,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReaderTopBar(
    fileName: String,
    progress: String,
    onBack: () -> Unit,
    textColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(bgColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = textColor,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = textColor,
            )
            if (progress.isNotEmpty()) {
                Text(
                    progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ReaderTabBar(
    onChapter: () -> Unit,
    onTts: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    textColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(bgColor)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderTab(onChapter, "章节", drawableIcon = R.drawable.ic_chapters, iconColor = textColor)
        ReaderTab(onTts, "朗读", drawableIcon = R.drawable.ic_speaker, iconColor = textColor)
        ReaderTab(onSearch, "搜索", drawableIcon = R.drawable.ic_search, iconColor = textColor)
        ReaderTab(onSettings, "设置", drawableIcon = R.drawable.ic_settings, iconColor = textColor)
    }
}

@Composable
private fun ReaderTab(
    onClick: () -> Unit,
    label: String,
    drawableIcon: Int? = null,
    iconColor: Color,
) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            if (drawableIcon != null) {
                Icon(
                    painterResource(drawableIcon),
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPagerHost(
    vm: ReaderViewModel,
    state: ReaderUiState,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = vm.currentGlobal(),
        pageCount = { state.totalPages },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { global -> vm.onPageChanged(global) }
    }

    LaunchedEffect(state.scrollEpoch) {
        val target = state.pendingScroll
        if (target != null) {
            if (target in 0 until pagerState.pageCount) {
                pagerState.scrollToPage(target)
            }
            vm.consumeScroll()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        key = { it },
    ) { page ->
        val text = vm.pageTextAt(page)
        val kw = state.highlightKeyword
        val dark = isSystemInDarkTheme()
        val highlighted = if (kw.isNotBlank()) highlightKeyword(text, kw, dark) else AnnotatedString(text)
        val sentenceHighlighted = if (state.ttsHighlightIndex >= 0) {
            highlightSentence(highlighted, state.ttsHighlightIndex, state.ttsHighlightRanges, dark)
        } else highlighted
        Text(
            text = sentenceHighlighted,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = state.fontSp.sp,
                lineHeight = (state.fontSp * state.lineSpacing).sp,
                color = Color(state.textColor.toInt()),
            ),
        )
    }
}

/** 章节目录每页章节数：当前章节前后 20 章 + 当前章 = 41 */
private const val CHAPTER_PAGE_SIZE = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    currentChapter: Int,
    chapters: List<String>,
    onJump: (Int) -> Unit,
) {
    // 章节目录搜索关键词（局部 UI 状态）
    var keyword by remember { mutableStateOf("") }
    val dark = isSystemInDarkTheme()

    // 窗口分页：不一次性渲染全书章节，每页 CHAPTER_PAGE_SIZE 条
    val totalChapterPages = ((chapters.size - 1).coerceAtLeast(0) / CHAPTER_PAGE_SIZE) + 1
    // 初始页定位到包含当前章节的窗口（面板每次打开重新组合，自然重置）
    var page by remember { mutableStateOf(currentChapter / CHAPTER_PAGE_SIZE) }
    val listState = rememberLazyListState()
    // 打开面板或翻页后，自动滚动到当前章节所在位置
    LaunchedEffect(page) {
        if (keyword.isBlank() && currentChapter in chapters.indices) {
            val pageStart = page * CHAPTER_PAGE_SIZE
            if (currentChapter >= pageStart && currentChapter < pageStart + CHAPTER_PAGE_SIZE) {
                listState.scrollToItem(currentChapter - pageStart)
            }
        }
    }

    // 跳转到指定章（输入框，1-based）
    var jumpInput by remember { mutableStateOf("") }
    val jumpTarget = jumpInput.trim().toIntOrNull()
    val jumpValid = jumpTarget != null && jumpTarget in 1..chapters.size

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索章节") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = { keyword = "" }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "清除")
                    }
                }
            },
            singleLine = true,
        )

        // 保留原始章节索引以便过滤后仍能正确跳转
        val indexed = chapters.mapIndexed { index, title -> index to title }
        val searching = keyword.isNotBlank()
        val filtered = if (!searching) indexed
        else indexed.filter { it.second.contains(keyword.trim(), ignoreCase = true) }

        // 搜索时跨全书过滤；未搜索时按窗口分页渲染，仅加载当前页 CHAPTER_PAGE_SIZE 条
        val pageStart = page * CHAPTER_PAGE_SIZE
        val windowItems = if (searching) filtered
        else filtered.drop(pageStart).take(CHAPTER_PAGE_SIZE)

        if (filtered.isEmpty()) {
            Text(
                "无匹配章节",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.height(360.dp)) {
                itemsIndexed(windowItems, key = { _, item -> item.first }) { _, (index, title) ->
                    val display = title.ifBlank { "第${index + 1}章" }
                    val isCurrent = index == currentChapter
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onJump(index) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = if (!searching) AnnotatedString(display)
                            else highlightKeyword(display, keyword, dark),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            // 分页切换栏（搜索时隐藏，跨全书显示结果）
            if (!searching) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { page = (page - 1).coerceAtLeast(0) },
                        enabled = page > 0,
                    ) { Text("上一页") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第 ${page + 1}/$totalChapterPages 页",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val end = minOf(pageStart + CHAPTER_PAGE_SIZE, chapters.size)
                        Text(
                            "${pageStart + 1}–$end 章 / 共 ${chapters.size} 章",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { page = (page + 1).coerceAtMost(totalChapterPages - 1) },
                        enabled = page < totalChapterPages - 1,
                    ) { Text("下一页") }
                }
            }
        }
    }
}

@Composable
private fun TtsSheet(
    playing: Boolean,
    available: Boolean,
    chapterTitle: String,
    progressText: String,
    speed: Float,
    fileName: String,
    onTogglePlay: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Text("${fileName}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${if (chapterTitle.isBlank()) "尚未播放" else chapterTitle} (${progressText})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                onClick = onPrevChapter,
                icon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_left), null) },
                label = "上一章"
            )
            ControlButton(
                onClick = onPrevPage,
                icon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_left), null) },
                label = "上一页"
            )
            Box(
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    Icon(
                        painterResource(R.drawable.ic_pause),
                        contentDescription = "暂停",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_play),
                        contentDescription = "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            ControlButton(
                onClick = onNextPage,
                icon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), null) },
                label = "下一页"
            )
            ControlButton(
                onClick = onNextChapter,
                icon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), null) },
                label = "下一章"
            )
        }

        Text(
            "播放速度",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "1.0x",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = speed,
                onValueChange = onSpeed,
                valueRange = 1f..3f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                steps = 1
            )
            Text(
                "3.0x",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "当前语速 ${String.format(java.util.Locale.CHINA, "%.2f", speed)}x",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchSheet(
    state: ReaderUiState,
    onSearch: (String) -> Unit,
    onJump: (BookSearcher.Hit) -> Unit,
) {
    Text(
        "搜索内容",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    OutlinedTextField(
        value = state.searchKeyword,
        onValueChange = onSearch,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        placeholder = { Text("输入关键词搜索全书") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        singleLine = true,
    )
    if (state.searching) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp), contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    } else if (state.searchResults.isEmpty() && state.searchKeyword.isNotBlank()) {
        Text("没有找到相关内容", modifier = Modifier.padding(horizontal = 16.dp))
    } else {
        LazyColumn(Modifier.height(300.dp)) {
            itemsIndexed(state.searchResults) { _, hit ->
                val kw = state.searchKeyword
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onJump(hit) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            highlightKeyword(hit.snippet, kw, isSystemInDarkTheme()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "第${hit.chapter + 1}章 · 第${hit.pageInChapter + 1}页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun highlightKeyword(snippet: String, keyword: String, dark: Boolean): AnnotatedString {
    val kw = keyword.trim()
    if (kw.isEmpty()) return AnnotatedString(snippet)
    // 暗色下用半透明黄底，保证其上的浅色文字仍可读
    val highlightBg = if (dark) Color(0x80FFE082) else Color(0xFFFFE082)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val idx = snippet.indexOf(kw, from, ignoreCase = true)
            if (idx < 0) {
                append(snippet.substring(from))
                break
            }
            append(snippet.substring(from, idx))
            withStyle(SpanStyle(background = highlightBg, fontWeight = FontWeight.Bold)) {
                append(snippet.substring(idx, idx + kw.length))
            }
            from = idx + kw.length
        }
    }
}

private fun highlightSentence(
    text: AnnotatedString,
    index: Int,
    ranges: List<IntRange>,
    dark: Boolean,
): AnnotatedString {
    if (index < 0 || ranges.size <= index) return text
    val range = ranges[index]
    if (range.start < 0 || range.endInclusive >= text.length) return text
    // 暗色下提高蓝底不透明度，保证黑底上可见
    val sentenceBg = if (dark) Color(0x6664B4F0) else Color(0x4464B4F0)
    return buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(background = sentenceBg)) {
            addStyle(SpanStyle(background = sentenceBg), range.start, range.endInclusive)
        }
    }
}

private val TEXT_COLORS = listOf(
    0xFF000000 to "黑",
    0xFF333333 to "深灰",
    0xFF1A237E to "深蓝",
    0xFF4E342E to "棕",
    0xFFFFFFFF to "白",
    0xFFCCCCCC to "灰",
)
private val BG_COLORS = listOf(
    0xFFFFF9F0 to "米白",
    0xFFFFFFFF to "白",
    0xFFF5F5DC to "米黄",
    0xFFECEFF1 to "浅灰",
    0xFFE0F2F1 to "浅青",
    0xFF000000 to "夜黑",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: ReaderUiState,
    onFont: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onTextColor: (Long) -> Unit,
    onBgColor: (Long) -> Unit,
    onRegex: (Int) -> Unit,
    onRegexCustom: (String) -> Unit,
    onEncode: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)

        Text(
            "字体大小",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                state.fontSp,
                onFont,
                valueRange = 12f..32f,
                steps = 1,
                modifier = Modifier.weight(1f)
            )
            Text("${state.fontSp.toInt()}px", style = MaterialTheme.typography.bodySmall)
        }

        Text("行高", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                state.lineSpacing,
                onLineSpacing,
                valueRange = 1f..3f, steps = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${String.format(java.util.Locale.CHINA, "%.1f", state.lineSpacing)}x",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            "字体颜色",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TEXT_COLORS.forEach { (argb, name) ->
                ColorChip(argb, name, state.textColor == argb) { onTextColor(argb) }
            }
        }

        Text(
            "背景颜色",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BG_COLORS.forEach { (argb, name) ->
                ColorChip(argb, name, state.bgColor == argb) { onBgColor(argb) }
            }
        }

        var regexExpanded by remember { mutableStateOf(false) }
        var encodeExpanded by remember { mutableStateOf(false) }
        var customRegex by remember { mutableStateOf(state.regexStr) }

        Text(
            "章节匹配规则",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 20.dp)
        )
        ExposedDropdownMenuBox(
            expanded = regexExpanded,
            onExpandedChange = { regexExpanded = it }) {
            OutlinedTextField(
                value = RegexPresets.list.getOrNull(state.regexType)?.name ?: "自定义",
                onValueChange = {},
                readOnly = true,
                label = { Text("正则规则") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regexExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = regexExpanded,
                onDismissRequest = { regexExpanded = false }) {
                RegexPresets.list.forEachIndexed { index, preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = { onRegex(index); regexExpanded = false },
                    )
                }
            }
        }

        if (state.regexType == RegexPresets.CUSTOM_INDEX) {
            OutlinedTextField(
                value = customRegex,
                onValueChange = { customRegex = it },
                label = { Text("自定义正则表达式") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Button(
                onClick = { onRegexCustom(customRegex) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { Text("应用") }
        }

        Text(
            "编码方式",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        ExposedDropdownMenuBox(
            expanded = encodeExpanded,
            onExpandedChange = { encodeExpanded = it }) {
            OutlinedTextField(
                value = state.encodeStr,
                onValueChange = {},
                readOnly = true,
                label = { Text("编码") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encodeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = encodeExpanded,
                onDismissRequest = { encodeExpanded = false }) {
                listOf("自动", "GBK", "UTF-8", "UTF-16").forEach { encoding ->
                    DropdownMenuItem(
                        text = { Text(encoding) },
                        onClick = { onEncode(encoding); encodeExpanded = false },
                    )
                }
            }
        }
        Text(
            "切换编码后会自动重新解析章节",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ColorChip(argb: Long, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(Color(argb.toInt()), CircleShape)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape
                ),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
