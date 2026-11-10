package com.xld.txtreader.ui.booklist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xld.txtreader.R
import com.xld.txtreader.data.db.BookRecord
import com.xld.txtreader.formatSize
import com.xld.txtreader.formatTime

/**
 * 书架文件列表项（列表视图与分组详情页共用）。
 *
 * @param groupName 所属分组名（未分类显示"未分类"）
 * @param onMoveGroup 点击"修改分组"菜单项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    book: BookRecord,
    groupName: String,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDetail: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteFile: () -> Unit,
    onMoveGroup: () -> Unit,
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
            Modifier
                .fillMaxWidth()
                .padding(start = if (inSelectionMode) 4.dp else 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
                        "$groupName · 第${book.currentChapter + 1}/${book.totalChapter}章 · ${formatSize(book.fileSize)} · ${formatTime(book.updateTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )                     
                }
            }
            if (!inSelectionMode) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("查看详情") },
                            onClick = { menuExpanded = false; onDetail() },
                        )
                        DropdownMenuItem(
                            text = { Text("修改分组") },
                            onClick = { menuExpanded = false; onMoveGroup() },
                        )                         
                        DropdownMenuItem(
                            text = { Text("删除记录") },                           
                            onClick = { menuExpanded = false; onDeleteRecord() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除文件") },                             
                            onClick = { menuExpanded = false; onDeleteFile() },
                        )
                    }
                }
            }
        }
    }
}
