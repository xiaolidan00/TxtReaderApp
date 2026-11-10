package com.xld.txtreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Query("SELECT * FROM book_groups")
    fun observeAll(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups WHERE id = :id")
    suspend fun getById(id: Int): BookGroup?

    @Query("SELECT * FROM book_groups WHERE groupName = :name")
    suspend fun getByName(name: String): BookGroup?

    /** 新增分组，groupName 冲突时抛异常（由上层转换为"分组已存在"提示） */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: BookGroup): Long

    @Query("UPDATE book_groups SET groupName = :name, updateTime = :time WHERE id = :id")
    suspend fun rename(id: Int, name: String, time: Long)

    @Query("DELETE FROM book_groups WHERE id = :id")
    suspend fun delete(id: Int)

    /** 回写指定分组的书本数量与更新时间 */
    @Query(
        "UPDATE book_groups SET fileNum = (SELECT COUNT(*) FROM books WHERE groupId = :id), " +
            "updateTime = :time WHERE id = :id"
    )
    suspend fun refreshGroup(id: Int, time: Long)
}
