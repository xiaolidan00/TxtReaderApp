package com.xld.txtreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书架分组表。
 * - [id] 自增主键，即 books.groupId 引用的分组 ID（0 为虚拟的"未分类"，不入库）
 * - [groupName] 分组名称，唯一索引保证不重复
 * - [fileNum] 组内书本数量（冗余字段，增删/移动书本后回写）
 * - [updateTime] 最近一次分组变更时间（同上）
 */
@Entity(tableName = "book_groups", indices = [Index(value = ["groupName"], unique = true)])
data class BookGroup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val groupName: String,
    val fileNum: Int = 0,
    val updateTime: Long = 0L,
)
