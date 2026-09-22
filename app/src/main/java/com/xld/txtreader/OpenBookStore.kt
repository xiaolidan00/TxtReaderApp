package com.xld.txtreader

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OpenBookStore {
    @Volatile
    var filePath: String? = null
        private set

    private val _fileChangeTrigger = MutableStateFlow(0)
    val fileChangeTrigger: StateFlow<Int> = _fileChangeTrigger.asStateFlow()

    fun open(path: String) {
        filePath = path
        _fileChangeTrigger.value++
    }

    fun openFile(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        filePath = file.absolutePath
        _fileChangeTrigger.value++
        return true
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))