package com.xld.txtreader.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xld.txtreader.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 升级弹窗状态 */
sealed interface UpdateUiState {
    /** 无新版、未检查完成或用户已忽略该版本 */
    data object None : UpdateUiState

    /** 发现新版本，等待用户确认 */
    data class Available(val update: AppUpdate) : UpdateUiState

    /**
     * 下载中。[dialogVisible] 为 false 表示用户点了「后台下载」，
     * 弹窗收起但 DownloadManager 继续下载，完成后仍会自动拉起安装。
     */
    data class Downloading(
        val update: AppUpdate,
        val progress: Int,
        val dialogVisible: Boolean
    ) : UpdateUiState
}

/** 需要 UI 执行的一次性动作 */
sealed interface UpdateEvent {
    /** 启动系统安装页 */
    data class Install(val apkFile: File) : UpdateEvent

    /** 先跳转「安装未知应用」授权页，结果通过 [UpdateViewModel.onInstallPermissionResult] 回调 */
    data object RequestInstallPermission : UpdateEvent

    /** 提示消息（字符串资源 id） */
    data class Message(@param:StringRes val resId: Int) : UpdateEvent
}

/**
 * 应用内升级：启动检查 → 确认弹窗 → DownloadManager 后台下载 → 自动安装。
 *
 * 输出：[state] 驱动弹窗；[events] 承载安装/授权/提示等一次性动作。
 */
class UpdateViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    /** 当前安装版本号，弹窗展示用 */
    val currentVersionName: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.None)
    val state: StateFlow<UpdateUiState> = _state

    private val _events = MutableSharedFlow<UpdateEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UpdateEvent> = _events

    private var downloadId = -1L
    private var checked = false
    private var pendingInstallFile: File? = null

    /** 打开 App 时检查一次新版本（每次会话仅检查一次，失败静默） */
    fun checkOnStart() {
        if (checked) return
        checked = true
        viewModelScope.launch {
            val update = UpdateChecker.check(currentVersionName) ?: return@launch
            // 用户已忽略该版本，不再提示
            if (update.versionName == prefs.getString(KEY_IGNORED_VERSION, null)) return@launch
            _state.value = UpdateUiState.Available(update)
        }
    }

    /** 弹窗「立即升级」：本地已有该版本完整 APK 则直接安装，否则开始后台下载 */
    fun onUpgradeClicked() {
        val available = _state.value as? UpdateUiState.Available ?: return
        beginUpgrade(available.update)
    }

    /** 弹窗「稍后」/ 下载弹窗「取消下载」：忽略该版本，本次不再提示 */
    fun onCancelClicked() {
        when (val current = _state.value) {
            is UpdateUiState.Available -> {
                ignoreVersion(current.update.versionName)
                _state.value = UpdateUiState.None
            }

            is UpdateUiState.Downloading -> {
                cancelDownload()
                ignoreVersion(current.update.versionName)
                _state.value = UpdateUiState.None
            }

            else -> Unit
        }
    }

    /** 下载弹窗「后台下载」：收起弹窗，下载继续，完成后自动安装 */
    fun onBackgroundClicked() {
        _state.update { current ->
            if (current is UpdateUiState.Downloading) current.copy(dialogVisible = false) else current
        }
    }

    /** 从「安装未知应用」设置页返回后重试安装 */
    fun onInstallPermissionResult() {
        val file = pendingInstallFile ?: return
        if (ApkInstaller.hasInstallPermission(appContext)) {
            _events.tryEmit(UpdateEvent.Install(file))
        } else {
            pendingInstallFile = null
            _events.tryEmit(UpdateEvent.Message(R.string.update_install_permission_denied))
        }
    }

    // ---- 下载/安装 ----

    private fun beginUpgrade(update: AppUpdate) {
        // 立即切到下载中：防止确认弹窗重复点击重复入队/重复安装
        _state.value = UpdateUiState.Downloading(update, 0, dialogVisible = true)
        viewModelScope.launch {
            // 上次已下载完成的 APK 直接安装，不重复下载
            val existing = withContext(Dispatchers.IO) { findValidApk(update) }
            if (existing != null) {
                _state.value = UpdateUiState.None
                pendingInstallFile = existing
                launchInstall()
                return@launch
            }
            val apkFile = withContext(Dispatchers.IO) { prepareApkFile(update.versionName) }
            val id = apkFile?.let {
                withContext(Dispatchers.IO) { enqueueDownload(update, it) }
            }
            if (apkFile == null || id == null) {
                _state.value = UpdateUiState.None
                _events.emit(UpdateEvent.Message(R.string.update_download_failed))
                return@launch
            }
            // 入队期间用户点了「取消下载」：撤掉刚入队的任务，不再轮询
            if (_state.value !is UpdateUiState.Downloading) {
                withContext(Dispatchers.IO) { downloadManager.remove(id) }
                return@launch
            }
            downloadId = id
            prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
            observeDownload(apkFile)
        }
    }

    /**
     * 查找下载目录中该版本已下载完成的 APK。
     *
     * 按 Release asset 的 size 校验完整性，排除上次下载中断留下的残缺文件
     * （不匹配则删除，走重新下载）；size 未知（<=0）时按存在即有效处理。
     */
    private fun findValidApk(update: AppUpdate): File? {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return null
        val apkFile = File(dir, "TXT-Reader-v${update.versionName}.apk")
        if (!apkFile.isFile) return null
        if (update.apkSize > 0 && apkFile.length() != update.apkSize) {
            apkFile.delete()
            return null
        }
        return apkFile
    }

    /** 清理上一次残留的下载任务与文件，返回目标 APK 文件 */
    private fun prepareApkFile(versionName: String): File? {
        val oldId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (oldId != -1L) {
            downloadManager.remove(oldId)
            prefs.edit().putLong(KEY_DOWNLOAD_ID, -1L).apply()
        }
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        if (!dir.exists() && !dir.mkdirs()) return null
        val apkFile = File(dir, "TXT-Reader-v$versionName.apk")
        if (apkFile.exists()) apkFile.delete()
        return apkFile
    }

    private fun enqueueDownload(update: AppUpdate, apkFile: File): Long? = runCatching {
        val request = DownloadManager.Request(Uri.parse(update.apkUrl)).apply {
            setTitle(appContext.getString(R.string.update_download_title, update.versionName))
            setMimeType("application/vnd.android.package-archive")
            setDestinationUri(Uri.fromFile(apkFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        downloadManager.enqueue(request)
    }.getOrNull()

    /** 轮询 DownloadManager 进度，完成/失败后收尾（用户已取消则静默退出） */
    private fun observeDownload(apkFile: File) {
        viewModelScope.launch {
            while (isActive) {
                val (status, progress) = withContext(Dispatchers.IO) { queryProgress(downloadId) }
                // 轮询期间用户取消会把状态置回 None，此处不再上报结果
                if (_state.value !is UpdateUiState.Downloading) break
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.value = UpdateUiState.None
                        pendingInstallFile = apkFile
                        launchInstall()
                        break
                    }

                    DownloadManager.STATUS_FAILED -> {
                        _state.value = UpdateUiState.None
                        _events.emit(UpdateEvent.Message(R.string.update_download_failed))
                        break
                    }

                    else -> {
                        _state.update { current ->
                            if (current is UpdateUiState.Downloading) {
                                current.copy(progress = progress)
                            } else {
                                current
                            }
                        }
                        delay(PROGRESS_POLL_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun queryProgress(id: Long): Pair<Int, Int> {
        if (id == -1L) return DownloadManager.STATUS_FAILED to 0
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
            ?: return DownloadManager.STATUS_FAILED to 0
        cursor.use {
            if (!it.moveToFirst()) return DownloadManager.STATUS_FAILED to 0
            val status = it.getInt(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            )
            val total = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            val done = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val percent = if (total > 0) (done * 100 / total).toInt() else 0
            return status to percent.coerceIn(0, 100)
        }
    }

    private fun cancelDownload() {
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
            downloadId = -1L
            prefs.edit().putLong(KEY_DOWNLOAD_ID, -1L).apply()
        }
    }

    // ---- 安装 ----

    private fun launchInstall() {
        val file = pendingInstallFile ?: return
        if (!file.exists()) {
            pendingInstallFile = null
            _events.tryEmit(UpdateEvent.Message(R.string.update_download_failed))
            return
        }
        if (ApkInstaller.hasInstallPermission(appContext)) {
            _events.tryEmit(UpdateEvent.Install(file))
        } else {
            _events.tryEmit(UpdateEvent.RequestInstallPermission)
        }
    }

    private fun ignoreVersion(versionName: String) {
        prefs.edit().putString(KEY_IGNORED_VERSION, versionName).apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update"
        const val KEY_IGNORED_VERSION = "ignored_version"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val PROGRESS_POLL_INTERVAL_MS = 500L
    }
}
