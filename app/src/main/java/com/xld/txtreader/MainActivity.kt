package com.xld.txtreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xld.txtreader.ui.booklist.BookListScreen
import com.xld.txtreader.ui.reader.ReaderScreen
import com.xld.txtreader.ui.theme.TxtReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val pendingBookPath = MutableStateFlow<Int>(0)

    private var openedByViewIntent = false

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果无需额外处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIntent(intent)
        openedByViewIntent = intent?.action == Intent.ACTION_VIEW
        enableEdgeToEdge()
        setContent {
            TxtReaderTheme {
                MainNav()
            }
        }
        handleOpenIntent(intent)
        requestStoragePermissionIfNeeded()
        requestBluetoothPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedByViewIntent = intent?.action == Intent.ACTION_VIEW
        handleOpenIntent(intent)
    }

    private fun requestStoragePermissionIfNeeded() {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        val permission = Manifest.permission.BLUETOOTH_CONNECT
        if ((application as TxtReaderApplication).settings.bluetoothPermissionRequested) return
        (application as TxtReaderApplication).settings.bluetoothPermissionRequested = true
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(permission)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val repo = (applicationContext as TxtReaderApplication).repository

        when (data.scheme) {
            "file" -> {
                val path = data.path ?: return
                val file = File(path)
                if (!file.exists()) return
                val absolutePath = file.absolutePath
                lifecycleScope.launch {
                    val charset = runCatching { com.xld.txtreader.core.EncodingReader.detectCharset(file) }.getOrDefault("UTF-8")
                    val text = com.xld.txtreader.core.EncodingReader.readText(file, charset)
                    val preset = com.xld.txtreader.core.RegexPresets.default()
                    val chapters = com.xld.txtreader.core.ChapterParser.parse(text, preset.value)
                    val content = com.xld.txtreader.core.BookContent(
                        filePath = absolutePath,
                        fileName = file.name,
                        text = text,
                        chapters = chapters,
                        encodeStr = charset,
                        regexStr = preset.value,
                        regexType = com.xld.txtreader.core.RegexPresets.list.indexOf(preset),
                        fileSize = file.length(),
                    )
                    repo.addBook(content)
                    OpenBookStore.open(content.filePath)
                    pendingBookPath.value = pendingBookPath.value + 1
                }
            }
            "content" -> lifecycleScope.launch {
                val realPath = resolveRealPathFromContent(data)
                val file = realPath?.let { File(it) }
                val name = file?.name
                    ?: runCatching {
                        contentResolver.query(data, null, null, null, null)?.use { c ->
                            c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME).let { c.getString(it) }
                        }
                    }.getOrNull()
                    ?: data.lastPathSegment?.substringAfterLast('/')
                    ?: "unknown.txt"
                val bytes = if (file != null && file.exists()) {
                    runCatching { file.readBytes() }.getOrNull()
                } else {
                    contentResolver.openInputStream(data)?.use { it.readBytes() }
                } ?: return@launch
                val charset = com.xld.txtreader.core.EncodingReader.detectCharset(bytes)
                val text = com.xld.txtreader.core.EncodingReader.decode(bytes, charset)
                val preset = com.xld.txtreader.core.RegexPresets.default()
                val chapters = com.xld.txtreader.core.ChapterParser.parse(text, preset.value)
                val content = com.xld.txtreader.core.BookContent(
                    filePath = realPath ?: data.toString(),
                    fileName = name,
                    text = text,
                    chapters = chapters,
                    encodeStr = charset,
                    regexStr = preset.value,
                    regexType = com.xld.txtreader.core.RegexPresets.list.indexOf(preset),
                    fileSize = bytes.size.toLong(),
                )
                repo.addBook(content)
                OpenBookStore.open(content.filePath)
                pendingBookPath.value = pendingBookPath.value + 1
            }
        }
    }

    private fun resolveRealPathFromContent(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val displayName = c.getString(idx) ?: return null
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val candidate = File(downloads, displayName)
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val dataIdx = c.getColumnIndex("_data")
                if (dataIdx >= 0) {
                    val dataPath = c.getString(dataIdx)
                    if (dataPath != null) {
                        val f = File(dataPath)
                        if (f.exists()) return f.absolutePath
                    }
                }
            }
        }
        return null
    }

    @Composable
    private fun MainNav() {
        val navController = rememberNavController()

        val startDestination = remember {
            if (openedByViewIntent) "readerContent" else "booklist"
        }

        val trigger by pendingBookPath.collectAsStateWithLifecycle()
        LaunchedEffect(trigger) {
            navController.navigate("readerContent") {
                launchSingleTop = true
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
            }
        }

        NavHost(navController = navController, startDestination = startDestination) {
            composable("booklist") {
                BookListScreen(
                    onOpenBook = { path ->
                        OpenBookStore.open(path)
                        navController.navigate("readerContent") { launchSingleTop = true }
                    },
                )
            }
            composable("readerContent") {
                ReaderScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
