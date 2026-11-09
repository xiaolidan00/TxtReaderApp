package com.xld.txtreader.ui.update

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.txtreader.R
import com.xld.txtreader.update.ApkInstaller
import com.xld.txtreader.update.AppUpdate
import com.xld.txtreader.update.UpdateEvent
import com.xld.txtreader.update.UpdateUiState
import com.xld.txtreader.update.UpdateViewModel

/**
 * 应用内升级宿主：启动时检查新版本并全局叠加升级弹窗。
 * 应放在 NavHost 之后组合，弹窗为独立窗口，会覆盖在任意页面之上。
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val viewModel: UpdateViewModel = viewModel(
        factory = viewModelFactory {
            initializer { UpdateViewModel(context.applicationContext) }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 「安装未知应用」授权页返回后继续安装
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onInstallPermissionResult()
    }

    LaunchedEffect(Unit) {
        viewModel.checkOnStart()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UpdateEvent.Install -> runCatching {
                    context.startActivity(ApkInstaller.buildInstallIntent(context, event.apkFile))
                }.onFailure {
                    Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT)
                        .show()
                }

                is UpdateEvent.RequestInstallPermission -> runCatching {
                    permissionLauncher.launch(ApkInstaller.buildManageSourcesIntent(context))
                }.onFailure {
                    Toast.makeText(
                        context, R.string.update_install_permission_denied, Toast.LENGTH_SHORT
                    ).show()
                }

                is UpdateEvent.Message ->
                    Toast.makeText(context, event.resId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    when (val current = state) {
        is UpdateUiState.Available -> UpdatePromptDialog(
            update = current.update,
            currentVersion = viewModel.currentVersionName,
            onUpgrade = viewModel::onUpgradeClicked,
            onDismiss = viewModel::onCancelClicked
        )

        is UpdateUiState.Downloading -> if (current.dialogVisible) {
            UpdateProgressDialog(
                progress = current.progress,
                onBackground = viewModel::onBackgroundClicked,
                onCancel = viewModel::onCancelClicked
            )
        }

        UpdateUiState.None -> Unit
    }
}

/** 新版本确认弹窗：版本号 + 新版本描述 */
@Composable
private fun UpdatePromptDialog(
    update: AppUpdate,
    currentVersion: String,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_prompt_title, update.versionName))
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(
                        R.string.update_prompt_versions,
                        currentVersion,
                        update.versionName
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (update.description.isBlank()) {
                        stringResource(R.string.update_prompt_no_notes)
                    } else {
                        update.description
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUpgrade) {
                Text(stringResource(R.string.update_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}

/** 下载进度弹窗：下载期间不可点外部关闭，可后台下载或取消 */
@Composable
private fun UpdateProgressDialog(
    progress: Int,
    onBackground: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.update_downloading_title))
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.update_downloading_percent, progress))
            }
        },
        confirmButton = {
            TextButton(onClick = onBackground) {
                Text(stringResource(R.string.update_background))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel_download))
            }
        }
    )
}
