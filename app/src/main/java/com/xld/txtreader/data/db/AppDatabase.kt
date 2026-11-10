package com.xld.txtreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookRecord::class, BookGroup::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookGroupDao(): BookGroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 1 → 2：新增 book_groups 表，books 表增加 groupId 列（默认 0 = 未分类）。
         * 手写 DDL 必须与 Room 生成结构一致（NOT NULL、索引名 index_book_groups_groupName）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupName` TEXT NOT NULL, " +
                        "`fileNum` INTEGER NOT NULL, " +
                        "`updateTime` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_groups_groupName` " +
                        "ON `book_groups` (`groupName`)"
                )
                db.execSQL("ALTER TABLE `books` ADD COLUMN `groupId` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "txtreader.db",
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
