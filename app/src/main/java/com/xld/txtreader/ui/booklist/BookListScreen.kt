package com.xld.txtreader.ui.booklist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.txtreader.R
import com.xld.txtreader.data.db.BookGroup
import com.xld.txtreader.data.db.BookRecord
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 修改分组弹窗的目标：[paths] 为待移动的文件，[isBatch] 为是否批量操作 */
private data class MoveTarget(val paths: List<String>, val isBatch: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(onOpenBook: (String) -> Unit, onImportAll: () -> Unit) {
    val factory = remember {
        viewModelFactory {
            initializer { BookListViewModel(ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY.let { this[it]!! }) }
        }
    }
    val vm: BookListViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var showAddGroup by remember { mutableStateOf(false) }
    var addGroupName by remember { mutableStateOf("") }
    var renamingGroup by remember { mutableStateOf<BookListViewModel.GroupCard?>(null) }
    var renameName by remember { mutableStateOf("") }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var movePickedId by remember { mutableStateOf(BookListViewModel.UNCLASSIFIED_ID) }
    var confirmDeletePath by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf<Boolean?>(null) }
    var confirmDeleteGroup by remember { mutableStateOf<BookListViewModel.GroupCard?>(null) }
    var detailBook by remember { mutableStateOf<BookRecord?>(null) }

    val inSelectionMode = state.selectedPaths.isNotEmpty()
    val inGroupDetail = state.currentGroupId != null
    val viewMode = state.viewMode

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
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = "取消选择"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = "全选")
                        }
                        // 批量修改分组
                        IconButton(onClick = {
                            vm.clearGroupError()
                            moveTarget = MoveTarget(state.selectedPaths.toList(), isBatch = true)
                            movePickedId = BookListViewModel.UNCLASSIFIED_ID
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_group),
                                contentDescription = "批量修改分组"
                            )
                        }
                        IconButton(onClick = { confirmBatchDelete = false }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_record),
                                contentDescription = "删除记录"
                            )
                        }
                        IconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_file),
                                contentDescription = "删除文件"
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (inGroupDetail) state.currentGroupName else "书架") },
                    navigationIcon = {
                        if (inGroupDetail) {
                            IconButton(onClick = { vm.exitGroup() }) {
                                Icon(
                                    painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = "返回"
                                )
                            }
                        }
                    },
                    actions = {
                        // 列表视图 / 分组视图切换（分组详情页内不显示）
                        if (!inGroupDetail) {
                            IconButton(onClick = {
                                vm.setViewMode(
                                    if (viewMode == BookListViewModel.ViewMode.LIST) BookListViewModel.ViewMode.GROUP
                                    else BookListViewModel.ViewMode.LIST
                                )
                            }) {
                                Icon(
                                    painterResource(
                                        if (viewMode == BookListViewModel.ViewMode.LIST) R.drawable.ic_view_group
                                        else R.drawable.ic_view_list
                                    ),
                                    contentDescription = "切换视图",
                                )
                            }
                            // 分组视图下显示添加分组按钮
                            if (viewMode == BookListViewModel.ViewMode.GROUP) {
                                IconButton(onClick = {
                                    vm.clearGroupError()
                                    addGroupName = ""
                                    showAddGroup = true
                                }) {
                                    Icon(
                                        painterResource(R.drawable.ic_group),
                                        contentDescription = "添加分组"
                                    )
                                }
                            }
                        }                        
                        IconButton(onClick = { addMenu = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = "添加")
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("导入txt文件") },
                                onClick = { onImportAll(); addMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("添加多个txt") },
                                onClick = {
                                    filePicker.launch(
                                        arrayOf(
                                            "text/plain",
                                            "text/*",
                                            "application/octet-stream"
                                        )
                                    ); addMenu = false
                                },
                            )
                        }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(painterResource(R.drawable.ic_sort), contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            if (viewMode == BookListViewModel.ViewMode.GROUP && !inGroupDetail) {
                                // 分组视图：按 文件数 / 时间 / 名称 排序分组卡片
                                BookListViewModel.GroupSortKey.entries.forEach { key ->
                                    listOf(
                                        true to "升序",
                                        false to "降序"
                                    ).forEach { (asc, label) ->
                                        val selected =
                                            state.groupSortKey == key && state.groupAscending == asc
                                        DropdownMenuItem(
                                            text = { Text("${key.label} · $label") },
                                            trailingIcon = {
                                                if (selected) Icon(
                                                    painterResource(R.drawable.ic_check),
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                vm.setGroupSort(key, asc); sortMenu = false
                                            },
                                        )
                                    }
                                }
                            } else {
                                // 列表视图 / 分组详情页：按 时间 / 名称 / 大小 排序文件
                                BookListViewModel.SortKey.entries.forEach { key ->
                                    listOf(
                                        true to "升序",
                                        false to "降序"
                                    ).forEach { (asc, label) ->
                                        val selected =
                                            state.sortKey == key && state.ascending == asc
                                        DropdownMenuItem(
                                            text = { Text("${key.label} · $label") },
                                            trailingIcon = {
                                                if (selected) Icon(
                                                    painterResource(R.drawable.ic_check),
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = { vm.setSort(key, asc); sortMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier
            .fillMaxSize()
            .padding(padding)) {
            // 搜索框：列表视图书名、分组视图组名、分组详情页书名
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::setKeyword,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(if (viewMode == BookListViewModel.ViewMode.GROUP && !inGroupDetail) "搜索分组名" else "搜索书名") },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { vm.setKeyword("") }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
            )

            when {
                // 分组详情页
                inGroupDetail -> {
                    Text(
                        "${state.currentGroupName} · ${state.currentGroupTotal} 个文件",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.groupBooks.isEmpty()) {
                        EmptyTip(if (state.keyword.isBlank()) "该分组下暂无书籍" else "没有匹配的书籍")
                    } else {
                        val listState = rememberLazyListState()
                        ScrollToTopEffect(
                            listState,
                            state.keyword,
                            state.sortKey,
                            state.ascending,
                            state.groupBooks.size
                        )
                        LoadMoreEffect(
                            listState,
                            state.groupBooks.size,
                            state.groupBookVisibleCount,
                            vm::loadMoreGroupBooks
                        )
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.visibleGroupBooks, key = { it.filePath }) { book ->
                                FileRow(
                                    book = book,
                                    groupName = groupNameOf(book, state.allGroups),
                                    selected = book.filePath in state.selectedPaths,
                                    inSelectionMode = inSelectionMode,
                                    onClick = {
                                        if (inSelectionMode) vm.toggleSelect(book.filePath)
                                        else onOpenBook(book.filePath)
                                    },
                                    onLongClick = { if (!inSelectionMode) vm.toggleSelect(book.filePath) },
                                    onDetail = { detailBook = book },
                                    onDeleteRecord = { confirmDeletePath = book.filePath to false },
                                    onDeleteFile = { confirmDeletePath = book.filePath to true },
                                    onMoveGroup = {
                                        vm.clearGroupError()
                                        moveTarget =
                                            MoveTarget(listOf(book.filePath), isBatch = false)
                                        movePickedId = book.groupId
                                    },
                                )
                            }
                        }
                    }
                }

                // 分组视图根页面
                viewMode == BookListViewModel.ViewMode.GROUP -> {
                    if (state.groupCards.isEmpty()) {
                        EmptyTip(if (state.keyword.isBlank()) "暂无分组" else "没有匹配的分组")
                    } else {
                        val listState = rememberLazyListState()
                        ScrollToTopEffect(
                            listState,
                            state.keyword,
                            state.groupSortKey,
                            state.groupAscending,
                            state.groupCards.size
                        )
                        LoadMoreEffect(
                            listState,
                            state.groupCards.size,
                            state.groupVisibleCount,
                            vm::loadMoreGroups
                        )
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.visibleGroupCards, key = { it.id }) { card ->
                                GroupRow(
                                    card = card,
                                    onClick = { vm.enterGroup(card.id) },
                                    onRename = {
                                        vm.clearGroupError()
                                        renamingGroup = card
                                        renameName = card.groupName
                                    },
                                    onDelete = { confirmDeleteGroup = card },
                                )
                            }
                        }
                    }
                }

                // 列表视图
                else -> {
                    if (state.books.isEmpty()) {
                        if (state.keyword.isBlank()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("书架空空如也")
                                    Text(
                                        "点击 + 手动添加多个 txt 文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    TextButton(
                                        onClick = onImportAll,
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Text("导入 txt 文件")
                                    }
                                }
                            }
                        } else {
                            EmptyTip("没有匹配的书籍")
                        }
                    } else {
                        val listState = rememberLazyListState()
                        ScrollToTopEffect(
                            listState,
                            state.keyword,
                            state.sortKey,
                            state.ascending,
                            state.books.size
                        )
                        LoadMoreEffect(
                            listState,
                            state.books.size,
                            state.visibleCount,
                            vm::loadMore
                        )
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.visibleBooks, key = { it.filePath }) { book ->
                                FileRow(
                                    book = book,
                                    groupName = groupNameOf(book, state.allGroups),
                                    selected = book.filePath in state.selectedPaths,
                                    inSelectionMode = inSelectionMode,
                                    onClick = {
                                        if (inSelectionMode) vm.toggleSelect(book.filePath)
                                        else onOpenBook(book.filePath)
                                    },
                                    onLongClick = { if (!inSelectionMode) vm.toggleSelect(book.filePath) },
                                    onDetail = { detailBook = book },
                                    onDeleteRecord = { confirmDeletePath = book.filePath to false },
                                    onDeleteFile = { confirmDeletePath = book.filePath to true },
                                    onMoveGroup = {
                                        vm.clearGroupError()
                                        moveTarget =
                                            MoveTarget(listOf(book.filePath), isBatch = false)
                                        movePickedId = book.groupId
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- 弹窗

    // 新增分组
    if (showAddGroup) {
        AlertDialog(
            onDismissRequest = { showAddGroup = false },
            title = { Text("添加分组") },
            text = {
                OutlinedTextField(
                    value = addGroupName,
                    onValueChange = { addGroupName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入分组名称") },
                    singleLine = true,
                    isError = state.groupError != null,
                    supportingText = state.groupError?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.addGroup(addGroupName).onSuccess {
                            showAddGroup = false
                            addGroupName = ""
                        }.onFailure { e -> vm.setGroupError(e.message ?: "创建失败") }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddGroup = false; vm.clearGroupError()
                }) { Text("取消") }
            },
        )
    }

    // 重命名分组
    renamingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入新名称") },
                    singleLine = true,
                    isError = state.groupError != null,
                    supportingText = state.groupError?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.renameGroup(group.id, renameName).onSuccess {
                            renamingGroup = null
                        }.onFailure { e -> vm.setGroupError(e.message ?: "重命名失败") }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    renamingGroup = null; vm.clearGroupError()
                }) { Text("取消") }
            },
        )
    }

    // 修改分组（单个 / 批量共用）
    moveTarget?.let { target ->
        val options = listOf(
            BookListViewModel.UNCLASSIFIED_ID to BookListViewModel.UNCLASSIFIED_NAME,
        ) + state.allGroups.map { it.id to it.groupName }
        GroupPickerDialog(
            title = if (target.isBatch) "批量修改分组" else "修改分组",
            subtitle = if (target.isBatch) "将 ${target.paths.size} 个文件移动到：" else null,
            options = options,
            pickedId = movePickedId,
            onPick = { movePickedId = it },
            onConfirm = {
                val picked = movePickedId
                moveTarget = null
                if (target.isBatch) vm.moveSelectedToGroup(picked)
                else vm.moveBookToGroup(target.paths.first(), picked)
            },
            onCreateGroup = { name ->
                scope.launch {
                    vm.addGroup(name).onSuccess { newId ->
                        moveTarget = null
                        if (target.isBatch) vm.moveSelectedToGroup(newId)
                        else vm.moveBookToGroup(target.paths.first(), newId)
                    }.onFailure { e -> vm.setGroupError(e.message ?: "创建失败") }
                }
            },
            onDismiss = { moveTarget = null; vm.clearGroupError() },
            error = state.groupError,
        )
    }

    // 删除确认
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

    // 删除分组确认
    confirmDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { Text("删除分组？") },
            text = {
                Text(
                    "将删除分组「${group.groupName}」，组内 ${group.fileNum} 个文件会自动归入未分类，" +
                            "书籍文件本身不受影响。确定继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteGroup = null
                    vm.deleteGroup(group.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteGroup = null }) { Text("取消") }
            },
        )
    }

    // 批量删除确认
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

    // 文件详情
    detailBook?.let { book ->
        BookDetailDialog(book = book, onDismiss = { detailBook = null })
    }
}

/** 文件所属分组名：groupId=0 为未分类 */
private fun groupNameOf(book: BookRecord, groups: List<BookGroup>): String =
    if (book.groupId == BookListViewModel.UNCLASSIFIED_ID) BookListViewModel.UNCLASSIFIED_NAME
    else groups.find { it.id == book.groupId }?.groupName ?: BookListViewModel.UNCLASSIFIED_NAME

/** 通用空态提示 */
@Composable
private fun EmptyTip(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

/** 搜索/排序变化时回到列表顶部 */
@Composable
private fun ScrollToTopEffect(
    listState: LazyListState,
    keyword: String,
    sortKey: Any,
    ascending: Boolean,
    dataSize: Int,
) {
    LaunchedEffect(keyword, sortKey, ascending, dataSize) {
        listState.scrollToItem(0)
    }
}

/** 滚动到倒数第 5 项时追加一页 */
@Composable
private fun LoadMoreEffect(
    listState: LazyListState,
    total: Int,
    visible: Int,
    loadMore: () -> Unit,
) {
    LaunchedEffect(listState, total, visible) {
        snapshotFlow {
            val info = listState.layoutInfo
            val count = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            count > 0 && last >= count - 5
        }.distinctUntilChanged().collect { nearBottom ->
            if (nearBottom && visible < total) loadMore()
        }
    }
}
