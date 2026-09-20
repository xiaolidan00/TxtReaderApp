package com.xld.txtreader.ui.booklist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.txtreader.R
import com.xld.txtreader.data.db.BookRecord
import com.xld.txtreader.formatSize
import com.xld.txtreader.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(onOpenBook: (String) -> Unit) {
    val factory = remember {
        viewModelFactory {
            initializer { BookListViewModel(ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY.let { this[it]!! }) }
        }
    }
    val vm: BookListViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()

    var sortMenu by remember { mutableStateOf(false) }
    var confirmDeletePath by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf<Boolean?>(null) }
    var detailBook by remember { mutableStateOf<BookRecord?>(null) }

    val inSelectionMode = state.selectedPaths.isNotEmpty()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("已选 ${state.selectedPaths.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Default.Check, contentDescription = "全选")
                        }
                        IconButton(onClick = { confirmBatchDelete = false }) {
                            Icon(painterResource(R.drawable.ic_delete_record), contentDescription = "删除记录")
                        }
                        IconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete_file), contentDescription = "删除文件")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("书架") },
                    actions = {
                        IconButton(onClick = { filePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }) {
                            Icon(Icons.Default.Add, contentDescription = "添加txt")
                        }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(painterResource(R.drawable.ic_sort), contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            BookListViewModel.SortKey.entries.forEach { key ->
                                listOf(true to "升序", false to "降序").forEach { (asc, label) ->
                                    val selected = state.sortKey == key && state.ascending == asc
                                    DropdownMenuItem(
                                        text = { Text("${key.label} · $label") },
                                        trailingIcon = {
                                            if (selected) Icon(Icons.Default.Check, contentDescription = null)
                                            else null
                                        },
                                        onClick = { vm.setSort(key, asc); sortMenu = false },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }) {
                Icon(Icons.Default.Add, contentDescription = "添加txt")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::setKeyword,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索书名") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { vm.setKeyword("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
            )

            if (state.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.keyword.isBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("书架空空如也")
                            Text(
                                "点击 + 添加 txt 文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        Text("没有匹配的书籍")
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.books, key = { it.filePath }) { book ->
                        val selected = book.filePath in state.selectedPaths
                        BookCard(
                            book = book,
                            selected = selected,
                            inSelectionMode = inSelectionMode,
                            onClick = {
                                if (inSelectionMode) vm.toggleSelect(book.filePath)
                                else onOpenBook(book.filePath)
                            },
                            onLongClick = {
                                if (!inSelectionMode) vm.toggleSelect(book.filePath)
                            },
                            onDetail = { detailBook = book },
                            onDeleteRecord = { confirmDeletePath = book.filePath to false },
                            onDeleteFile = { confirmDeletePath = book.filePath to true },
                        )
                    }
                }
            }
        }
    }

    detailBook?.let { book ->
        BookDetailDialog(book = book, onDismiss = { detailBook = null })
    }

    confirmDeletePath?.let { (path, deleteFile) ->
        val fileName = state.books.find { it.filePath == path }?.fileName ?: ""
        AlertDialog(
            onDismissRequest = { confirmDeletePath = null },
            title = { Text(if (deleteFile) "删除文件？" else "删除阅读记录？") },
            text = {
                Text(
                    if (deleteFile) {
                        "将删除 $fileName 及其阅读记录，不可恢复。确定继续吗？"
                    } else {
                        "将从书架移除 $fileName，仅删除阅读记录，不影响原 txt 文件。确定继续吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePath = null
                    if (deleteFile) vm.deleteFile(path) else vm.deleteRecord(path)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePath = null }) { Text("取消") }
            },
        )
    }

    confirmBatchDelete?.let { deleteFile ->
        val count = state.selectedPaths.size
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = null },
            title = { Text(if (deleteFile) "批量删除文件？" else "批量删除记录？") },
            text = {
                Text(
                    if (deleteFile) {
                        "将删除 $count 个文件及其阅读记录，不可恢复。确定继续吗？"
                    } else {
                        "将从书架移除 $count 条记录，仅删除阅读记录，不影响原 txt 文件。确定继续吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatchDelete = null
                    if (deleteFile) vm.deleteSelectedFiles() else vm.deleteSelectedRecords()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookRecord,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDetail: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (inSelectionMode) 4.dp else 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inSelectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                Column {
                    Text(
                        book.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "第${book.currentChapter + 1}/${book.totalChapter}章 · ${formatSize(book.fileSize)} · ${formatTime(book.updateTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!inSelectionMode) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("查看详情") },
                            onClick = { menuExpanded = false; onDetail() },
                        )
                        HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("删除记录") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete_record), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDeleteRecord() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除文件") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete_file), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDeleteFile() },
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetailDialog(book: BookRecord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.fileName) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                DetailRow("文件路径", book.filePath)
                DetailRow("文件大小", formatSize(book.fileSize))
                DetailRow("总章数", "${book.totalChapter} 章")
                DetailRow("当前进度", "第${book.currentChapter + 1}/${book.totalChapter}章 · 第${book.pageIndex + 1}页")
                DetailRow("字符数", "${book.textNum} 字")
                DetailRow("编码格式", book.encodeStr)
                DetailRow("章节规则", book.regexStr)
                DetailRow("最后阅读", formatTime(book.updateTime))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
