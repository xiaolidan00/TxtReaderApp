package com.xld.txtreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookRecord(
    @PrimaryKey val filePath: String,
    val currentChapter: Int,
    val pageIndex: Int,
    val totalChapter: Int,
    val textNum: Long,
    val updateTime: Long,
    val fileName: String,
    val fileSize: Long,
    val regexType: Int,
    val regexStr: String,
    val encodeStr: String,
)