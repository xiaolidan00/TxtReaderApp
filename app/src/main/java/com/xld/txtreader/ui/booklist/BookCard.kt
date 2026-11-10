package com.xld.txtreader.ui.booklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xld.txtreader.data.db.BookRecord
import com.xld.txtreader.formatSize
import com.xld.txtreader.formatTime

/** 文件详情弹窗 */
@Composable
fun BookDetailDialog(book: BookRecord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.fileName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {                
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
