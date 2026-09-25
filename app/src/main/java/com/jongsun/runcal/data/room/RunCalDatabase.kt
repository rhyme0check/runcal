package com.jongsun.runcal.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotionDatabaseEntity::class, NotionEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RunCalDatabase : RoomDatabase() {
    abstract fun notionDatabaseDao(): NotionDatabaseDao
    abstract fun notionEventDao(): NotionEventDao

    companion object {
        @Volatile private var instance: RunCalDatabase? = null

        fun getInstance(context: Context): RunCalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunCalDatabase::class.java,
                    "runcal_database",
                ).build().also { instance = it }
            }
    }
}
