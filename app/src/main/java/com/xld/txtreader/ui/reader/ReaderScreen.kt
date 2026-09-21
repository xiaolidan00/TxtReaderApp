package com.xld.txtreader.ui.reader

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.xld.txtreader.R
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

    var activeSheet by remember { mutableStateOf<SheetKind?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val densities = LocalDensity.current
    val padXPx = with(densities) { 20.dp.toPx() }
    val padYPx = with(densities) { 12.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(state.bgColor.toInt())),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Top bar slot
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                ReaderTopBar(
                    fileName = state.fileName,
                    progress = if (state.totalChapter > 0) "第${state.currentChapter + 1}/${state.totalChapter}章" else "",
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Reading content
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        vm.setViewport(size.width - (2 * padXPx).toInt(), size.height - (2 * padYPx).toInt(), densities.density)
                    },
            ) {
                when {
                    state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.loadError -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
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
            Box(Modifier.fillMaxWidth().height(56.dp)) {
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
                )
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
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
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
                    onJump = { chapter, page -> vm.jumpTo(chapter, page, state.searchKeyword); activeSheet = null },
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (progress.isNotEmpty()) {
                Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderTab(onChapter, "章节", drawableIcon = R.drawable.ic_chapters)
        ReaderTab(onTts, "朗读", drawableIcon = R.drawable.ic_speaker)
        ReaderTab(onSearch, "搜索", vectorIcon = Icons.Default.Search)
        ReaderTab(onSettings, "设置", vectorIcon = Icons.Default.Settings)
    }
}

@Composable
private fun ReaderTab(
    onClick: () -> Unit,
    label: String,
    vectorIcon: ImageVector? = null,
    drawableIcon: Int? = null,
) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            if (vectorIcon != null) {
                Icon(vectorIcon, contentDescription = label, modifier = Modifier.size(24.dp))
            } else if (drawableIcon != null) {
                Icon(painterResource(drawableIcon), contentDescription = label, modifier = Modifier.size(24.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
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
        val displayText = if (kw.isNotBlank()) highlightKeyword(text, kw) else AnnotatedString(text)
        Text(
            text = displayText,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = state.fontSp.sp,
                lineHeight = (state.fontSp * state.lineSpacing).sp,
                color = Color(state.textColor.toInt()),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    currentChapter: Int,
    chapters: List<String>,
    onJump: (Int) -> Unit,
) {
    Text("章节目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    LazyColumn(Modifier.height(360.dp)) {
        itemsIndexed(chapters) { index, title ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onJump(index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    title.ifBlank { "第${index + 1}章" },
                    color = if (index == currentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (index == currentChapter) FontWeight.Bold else FontWeight.Normal,
                )
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text("语音朗读", style = MaterialTheme.typography.titleMedium)
        Text(
            "${fileName.take(20)} · ${if (chapterTitle.isBlank()) "尚未播放" else chapterTitle} (${progressText})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(onClick = onPrevChapter, icon = { Icon(Icons.Default.KeyboardArrowLeft, null) }, label = "上一章")
            ControlButton(onClick = onPrevPage, icon = { Icon(Icons.Default.KeyboardArrowLeft, null) }, label = "上一页")
            Box(
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = "暂停", tint = Color.White, modifier = Modifier.size(32.dp))
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            ControlButton(onClick = onNextPage, icon = { Icon(Icons.Default.KeyboardArrowRight, null) }, label = "下一页")
            ControlButton(onClick = onNextChapter, icon = { Icon(Icons.Default.KeyboardArrowRight, null) }, label = "下一章")
        }

        Text("播放速度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0.5x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = speed,
                onValueChange = onSpeed,
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("2.0x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("当前语速 ${String.format(java.util.Locale.CHINA, "%.2f", speed)}x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
) {
    Column(
        Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchSheet(
    state: ReaderUiState,
    onSearch: (String) -> Unit,
    onJump: (Int, Int) -> Unit,
) {
    Text("搜索内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    OutlinedTextField(
        value = state.searchKeyword,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        placeholder = { Text("输入关键词搜索全书") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
    )
    if (state.searching) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
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
                        .clickable { onJump(hit.chapter, hit.pageInChapter) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            highlightKeyword(hit.snippet, kw),
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

private fun highlightKeyword(snippet: String, keyword: String): AnnotatedString {
    val kw = keyword.trim()
    if (kw.isEmpty()) return AnnotatedString(snippet)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val idx = snippet.indexOf(kw, from, ignoreCase = true)
            if (idx < 0) {
                append(snippet.substring(from))
                break
            }
            append(snippet.substring(from, idx))
            withStyle(SpanStyle(background = Color(0xFFFFE082), fontWeight = FontWeight.Bold)) {
                append(snippet.substring(idx, idx + kw.length))
            }
            from = idx + kw.length
        }
    }
}

private val TEXT_COLORS = listOf(
    0xFF000000 to "黑",
    0xFF333333 to "深灰",
    0xFF1A237E to "深蓝",
    0xFF4E342E to "棕",
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

        Text("字体大小", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(state.fontSp, onFont, valueRange = 12f..32f, modifier = Modifier.weight(1f))
            Text("${state.fontSp.toInt()}px", style = MaterialTheme.typography.bodySmall)
        }

        Text("行高", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(state.lineSpacing, onLineSpacing, valueRange = 1f..3f, modifier = Modifier.weight(1f))
            Text("${String.format(java.util.Locale.CHINA, "%.1f", state.lineSpacing)}x", style = MaterialTheme.typography.bodySmall)
        }

        Text("字体颜色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TEXT_COLORS.forEach { (argb, name) ->
                ColorChip(argb, name, state.textColor == argb) { onTextColor(argb) }
            }
        }

        Text("背景颜色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BG_COLORS.forEach { (argb, name) ->
                ColorChip(argb, name, state.bgColor == argb) { onBgColor(argb) }
            }
        }

        var regexExpanded by remember { mutableStateOf(false) }
        var encodeExpanded by remember { mutableStateOf(false) }
        var customRegex by remember { mutableStateOf(state.regexStr) }

        Text("章节匹配规则", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 20.dp))
        ExposedDropdownMenuBox(expanded = regexExpanded, onExpandedChange = { regexExpanded = it }) {
            OutlinedTextField(
                value = RegexPresets.list.getOrNull(state.regexType)?.name ?: "自定义",
                onValueChange = {},
                readOnly = true,
                label = { Text("正则规则") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regexExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = regexExpanded, onDismissRequest = { regexExpanded = false }) {
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
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { onRegexCustom(customRegex) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("应用") }
        }

        Text("编码方式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        ExposedDropdownMenuBox(expanded = encodeExpanded, onExpandedChange = { encodeExpanded = it }) {
            OutlinedTextField(
                value = state.encodeStr,
                onValueChange = {},
                readOnly = true,
                label = { Text("编码") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encodeExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = encodeExpanded, onDismissRequest = { encodeExpanded = false }) {
                listOf("UTF-8", "GBK").forEach { enc ->
                    DropdownMenuItem(
                        text = { Text(enc) },
                        onClick = { onEncode(enc); encodeExpanded = false },
                    )
                }
            }
        }
        Text("切换编码后会自动重新解析章节", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}