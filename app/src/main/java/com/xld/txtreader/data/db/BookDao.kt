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
}