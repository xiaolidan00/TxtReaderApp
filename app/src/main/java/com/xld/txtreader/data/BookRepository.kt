package com.xld.txtreader.data

import android.content.Context
import com.xld.txtreader.core.BookContent
import com.xld.txtreader.core.ChapterParser
import com.xld.txtreader.core.EncodingReader
import com.xld.txtreader.core.RegexPresets
import com.xld.txtreader.data.db.AppDatabase
import com.xld.txtreader.data.db.BookDao
import com.xld.txtreader.data.db.BookGroup
import com.xld.txtreader.data.db.BookGroupDao
import com.xld.txtreader.data.db.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(private val db: AppDatabase, private val context: Context) {

    private val dao: BookDao = db.bookDao()
    private val groupDao: BookGroupDao = db.bookGroupDao()

    val books: Flow<List<BookRecord>> = dao.observeAll()

    val groups: Flow<List<BookGroup>> = groupDao.observeAll()

    /** 未分类分组 ID（虚拟分组，不入库） */
    val ungroupedId: Int get() = UNCLASSIFIED_ID

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
        val affected = dao.groupIdsOfPaths(paths)
        dao.deleteByPaths(paths)
        refreshGroups(affected)
    }

    suspend fun deleteFilesAndRecords(paths: List<String>) {
        if (paths.isEmpty()) return
        val affected = dao.groupIdsOfPaths(paths)
        for (p in paths) {
            runCatching { File(p).delete() }
        }
        dao.deleteByPaths(paths)
        refreshGroups(affected)
    }

    // ---------------------------------------------------------------- 分组

    /** 新增分组；名称重复时返回失败 */
    suspend fun addGroup(name: String): Result<Long> = runCatching {
        if (groupDao.getByName(name) != null) error("分组已存在")
        groupDao.insert(BookGroup(groupName = name, fileNum = 0, updateTime = System.currentTimeMillis()))
    }

    /** 重命名分组：只改 book_groups.groupName，books.groupId 指向自增 ID，无需级联 */
    suspend fun renameGroup(id: Int, name: String): Result<Unit> = runCatching {
        val existing = groupDao.getByName(name)
        if (existing != null && existing.id != id) error("分组已存在")
        groupDao.rename(id, name, System.currentTimeMillis())
    }

    /** 删除分组：组内书本自动归入未分类（groupId = 0） */
    suspend fun deleteGroup(id: Int) {
        val paths = pathsInGroup(id)
        if (paths.isNotEmpty()) {
            dao.moveToGroupBatch(paths, UNCLASSIFIED_ID)
        }
        groupDao.delete(id)
    }

    /** 移动书本到分组（含批量），并刷新源分组与目标分组统计 */
    suspend fun moveBooksToGroup(paths: List<String>, groupId: Int) {
        if (paths.isEmpty()) return
        val sourceIds = dao.groupIdsOfPaths(paths)
        dao.moveToGroupBatch(paths, groupId)
        refreshGroups(sourceIds + groupId)
    }

    /** 单本移动到分组 */
    suspend fun moveBookToGroup(path: String, groupId: Int) = moveBooksToGroup(listOf(path), groupId)

    suspend fun pathsInGroup(groupId: Int): List<String> = dao.pathsInGroup(groupId)

    /** 回写分组的 fileNum 与 updateTime（跳过未分类虚拟分组 0） */
    private suspend fun refreshGroups(ids: Collection<Int>) {
        val time = System.currentTimeMillis()
        ids.filter { it != UNCLASSIFIED_ID }.distinct().forEach { groupDao.refreshGroup(it, time) }
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
            val chapters = ChapterParser.parse(text, preset.value, file.name)
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

    companion object {
        /** 未分类虚拟分组 ID（不写入 book_groups 表） */
        const val UNCLASSIFIED_ID = 0
    }
}
