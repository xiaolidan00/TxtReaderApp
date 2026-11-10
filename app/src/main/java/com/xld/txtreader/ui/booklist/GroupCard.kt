package com.xld.txtreader.ui.booklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 「新增分组」选项的哨兵 ID（真实分组 ID 从 1 自增，不会为负） */
const val NEW_GROUP_ID = -1

/**
 * 修改分组弹窗：单选列表选择目标分组（首项为"未分类"），末项为"新增分组"。
 * 选中"新增分组"时展开输入框，确认后由 [onCreateGroup] 创建分组，
 * 创建成功后把文件移入新分组；失败时通过 [error] 内联提示（弹窗保持打开）。
 * 单个文件移动与批量移动共用。
 *
 * @param options 分组 ID 到名称的候选列表，首项应为未分类
 * @param pickedId 当前选中的分组 ID，[NEW_GROUP_ID] 表示新增分组
 * @param subtitle 批量移动时的说明文本，单个移动传 null
 * @param onCreateGroup 点击"创建并移动"，参数为输入的新分组名
 */
@Composable
fun GroupPickerDialog(
    title: String,
    subtitle: String?,
    options: List<Pair<Int, String>>,
    pickedId: Int,
    onPick: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCreateGroup: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    var newName by remember { mutableStateOf("") }
    val creating = pickedId == NEW_GROUP_ID

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                options.forEach { (id, name) ->
                    OptionRow(
                        name = name,
                        selected = pickedId == id && !creating,
                        onClick = { onPick(id) },
                    )
                }
                // 新增分组选项
                OptionRow(
                    name = "新增分组",
                    selected = creating,
                    onClick = { onPick(NEW_GROUP_ID) },
                )
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        placeholder = { Text("输入新分组名称") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (creating) onCreateGroup(newName)
                    else onConfirm()
                },
                enabled = !creating || newName.isNotBlank(),
            ) {
                Text(if (creating) "创建并移动" else "移动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun OptionRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Text(name, modifier = Modifier.padding(start = 4.dp))
    }
}
