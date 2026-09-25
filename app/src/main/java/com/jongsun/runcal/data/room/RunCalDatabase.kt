package com.jongsun.runcal.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS event_color_styles (" +
                "sourceKey TEXT NOT NULL PRIMARY KEY, paletteKey TEXT, bold INTEGER NOT NULL DEFAULT 0)",
        )
    }
}

@Database(
    entities = [NotionDatabaseEntity::class, NotionEventEntity::class, EventColorStyleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RunCalDatabase : RoomDatabase() {
    abstract fun notionDatabaseDao(): NotionDatabaseDao
    abstract fun notionEventDao(): NotionEventDao
    abstract fun eventColorStyleDao(): EventColorStyleDao

    companion object {
        @Volatile private var instance: RunCalDatabase? = null

        fun getInstance(context: Context): RunCalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunCalDatabase::class.java,
                    "runcal_database",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
