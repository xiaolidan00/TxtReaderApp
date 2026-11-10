package com.xld.txtreader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<BookRecord>>

    @Query("SELECT * FROM books WHERE filePath = :path")
    suspend fun getByPath(path: String): BookRecord?

    @Upsert
    suspend fun upsert(book: BookRecord)

    @Query(
        "UPDATE books SET currentChapter = :chapter, pageIndex = :page, " +
            "totalChapter = :total, textNum = :textNum, updateTime = :time WHERE filePath = :path"
    )
    suspend fun updateProgress(
        path: String,
        chapter: Int,
        page: Int,
        total: Int,
        textNum: Long,
        time: Long,
    )

    @Query("UPDATE books SET regexType = :type, regexStr = :regex WHERE filePath = :path")
    suspend fun updateRegex(path: String, type: Int, regex: String)

    @Query("UPDATE books SET encodeStr = :encode WHERE filePath = :path")
    suspend fun updateEncode(path: String, encode: String)

    @Query("DELETE FROM books WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /** 移动单本书到指定分组（0 = 未分类） */
    @Query("UPDATE books SET groupId = :groupId WHERE filePath = :path")
    suspend fun moveToGroup(path: String, groupId: Int)

    /** 批量移动到指定分组 */
    @Query("UPDATE books SET groupId = :groupId WHERE filePath IN (:paths)")
    suspend fun moveToGroupBatch(paths: List<String>, groupId: Int)

    /** 取这些书当前所在的分组 ID（去重），用于移动/删除后刷新统计 */
    @Query("SELECT DISTINCT groupId FROM books WHERE filePath IN (:paths)")
    suspend fun groupIdsOfPaths(paths: List<String>): List<Int>

    /** 指定分组下的书本数量 */
    @Query("SELECT COUNT(*) FROM books WHERE groupId = :groupId")
    suspend fun countByGroup(groupId: Int): Int

    /** 指定分组下的文件路径列表 */
    @Query("SELECT filePath FROM books WHERE groupId = :groupId")
    suspend fun pathsInGroup(groupId: Int): List<String>
}