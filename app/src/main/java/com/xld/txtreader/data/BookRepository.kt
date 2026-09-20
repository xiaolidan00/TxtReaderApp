package com.xld.txtreader.data

import android.content.Context
import com.xld.txtreader.core.BookContent
import com.xld.txtreader.core.ChapterParser
import com.xld.txtreader.core.EncodingReader
import com.xld.txtreader.core.RegexPresets
import com.xld.txtreader.data.db.AppDatabase
import com.xld.txtreader.data.db.BookDao
import com.xld.txtreader.data.db.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(private val db: AppDatabase, private val context: Context) {

    private val dao: BookDao = db.bookDao()

    val books: Flow<List<BookRecord>> = dao.observeAll()

    suspend fun get(path: String): BookRecord? = dao.getByPath(path)

    suspend fun addBook(content: BookContent) {
        dao.upsert(
            BookRecord(
                filePath = content.filePath,
                currentChapter = 0,
                pageIndex = 0,
                totalChapter = content.totalChapter,
                textNum = content.text.length.toLong(),
                updateTime = System.currentTimeMillis(),
                fileName = content.fileName,
                fileSize = content.fileSize,
                regexType = content.regexType,
                regexStr = content.regexStr,
                encodeStr = content.encodeStr,
            )
        )
    }

    suspend fun updateProgress(path: String, chapter: Int, page: Int, total: Int, textNum: Long) {
        dao.updateProgress(path, chapter, page, total, textNum, System.currentTimeMillis())
    }

    suspend fun updateRegex(path: String, type: Int, regex: String) = dao.updateRegex(path, type, regex)

    suspend fun updateEncode(path: String, encode: String) = dao.updateEncode(path, encode)

    suspend fun deleteRecords(paths: List<String>) {
        if (paths.isEmpty()) return
        dao.deleteByPaths(paths)
    }

    suspend fun deleteFilesAndRecords(paths: List<String>) {
        if (paths.isEmpty()) return
        for (p in paths) {
            runCatching { File(p).delete() }
        }
        dao.deleteByPaths(paths)
    }

    suspend fun scanFolder(dir: File): Int = withContext(Dispatchers.IO) {
        if (!dir.isDirectory) return@withContext 0
        val txtFiles = dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".txt", ignoreCase = true)
        } ?: return@withContext 0
        var added = 0
        for (file in txtFiles) {
            val path = file.absolutePath
            val existing = dao.getByPath(path)
            if (existing != null) continue
            val charset = runCatching { EncodingReader.detectCharset(file) }.getOrDefault("UTF-8")
            val text = EncodingReader.readText(file, charset)
            val preset = RegexPresets.default()
            val chapters = ChapterParser.parse(text, preset.value)
            dao.upsert(
                BookRecord(
                    filePath = path,
                    currentChapter = 0,
                    pageIndex = 0,
                    totalChapter = chapters.size,
                    textNum = text.length.toLong(),
                    updateTime = file.lastModified(),
                    fileName = file.name,
                    fileSize = file.length(),
                    regexType = RegexPresets.list.indexOf(preset),
                    regexStr = preset.value,
                    encodeStr = charset,
                )
            )
            added++
        }
        added
    }
}
