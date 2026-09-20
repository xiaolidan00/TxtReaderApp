package com.xld.txtreader

import android.app.Application
import com.xld.txtreader.data.BookRepository
import com.xld.txtreader.data.SettingsStore
import com.xld.txtreader.data.db.AppDatabase

class TxtReaderApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: BookRepository by lazy { BookRepository(database, this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}

val android.app.Application.appRepositories: BookRepository
    get() = (this as TxtReaderApplication).repository

val android.app.Application.appSettings: SettingsStore
    get() = (this as TxtReaderApplication).settings