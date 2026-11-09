package com.xld.txtreader.ui.booklist

import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.txtreader.R
import com.xld.txtreader.formatSize
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 导入 txt 文件界面：扫描常用文件夹，只列出未添加到书架的 txt 文件，
 * 底部提供勾选全部 / 取消勾选全部 / 确认操作，导入中可随时停止。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTxtScreen(onBack: () -> Unit) {
    val factory = remember {
        viewModelFactory {
            initializer {
                ImportTxtViewModel(
                    ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY.let { this[it]!! },
                )
            }
        }
    }
    val vm: ImportTxtViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 进入页面/从系统授权页返回时检查权限并扫描（ViewModel 内部保证只扫一次）
    LifecycleResumeEffect(Unit) {
        vm.startScan()
        onPauseOrDispose { }
    }

    // 滚动到底部前 5 项时追加 100 条；key 含已展示条数，追加后重新评估
    LaunchedEffect(listState, state.visibleFileCount, state.files.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 5
        }.distinctUntilChanged().collect { nearBottom ->
            if (nearBottom) vm.loadMoreFiles()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val p = state.phase) {
                            is ImportTxtViewModel.ScanPhase.Scanning -> "扫描中… 已找到 ${p.found} 个"
                            ImportTxtViewModel.ScanPhase.Done -> "导入 txt 文件（${state.files.size}）"
                            ImportTxtViewModel.ScanPhase.NeedPermission -> "需要存储权限"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            ImportBottomBar(
                state = state,
                onCheckAll = vm::checkAll,
                onUncheckAll = vm::uncheckAll,
                onConfirm = vm::confirmImport,
                onStop = vm::stopImport,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = state.phase) {
                ImportTxtViewModel.ScanPhase.NeedPermission -> {
                    PermissionGuide(
                        onOpenSettings = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            ).apply { data = android.net.Uri.parse("package:${context.packageName}") }
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }

                is ImportTxtViewModel.ScanPhase.Scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("正在扫描 ${phase.folderLabel}… 已发现 ${phase.found} 个 txt 文件")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "仅扫描大于 1KB 的 txt 文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                ImportTxtViewModel.ScanPhase.Done -> {
                    if (state.files.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("没有可导入的 txt 文件")
                                Text(
                                    "仅列出大于 1KB 且尚未添加的 txt（扫描：Download / Documents / DCIM / Pictures 等）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    } else {
                        val visible = state.visibleFiles
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.filePath }) { item ->
                                ImportFileRow(
                                    item = item,
                                    checked = item.filePath in state.checked,
                                    enabled = state.importProgress == null,
                                    onToggle = { vm.toggleCheck(item.filePath) },
                                )
                            }
                            if (visible.size < state.files.size) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "下拉加载更多（${visible.size}/${state.files.size}）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 导入完成后返回书架（书架列表由 Room Flow 自动刷新）
    LaunchedEffect(state.imported) {
        if (state.imported) onBack()
    }
}

@Composable
private fun PermissionGuide(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("需要「所有文件访问」权限才能扫描设备中的 txt 文件", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("去授权") }
        }
    }
}

@Composable
private fun ImportFileRow(
    item: TxtFileItem,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.parentLabel} · ${formatSize(item.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImportBottomBar(
    state: ImportTxtViewModel.UiState,
    onCheckAll: () -> Unit,
    onUncheckAll: () -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
) {
    // edge-to-edge 下底部栏需避让系统导航栏，否则按钮被遮盖
    Column(Modifier.navigationBarsPadding()) {
        state.importProgress?.let { (done, total) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "正在导入 $done/$total…",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            val total = state.files.size
            val selected = state.checked.size
            if (state.importProgress != null) {
                // 导入中：只提供停止按钮，其余操作禁用
                Button(onClick = onStop, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                )) {
                    Text("停止导入")
                }
            } else {
                TextButton(onClick = onCheckAll, enabled = total > 0) {
                    Text("全选")
                }
                if (selected > 0) {
                    TextButton(onClick = onUncheckAll) {
                        Text("取消全选")
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = selected > 0) {
                    Text("确认（$selected/$total）")
                }
            }
        }
    }
}
