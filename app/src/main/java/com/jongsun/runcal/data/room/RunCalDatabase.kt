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

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS local_event_provenance (" +
                "calendarEventId INTEGER NOT NULL PRIMARY KEY, calendarId INTEGER NOT NULL, " +
                "createdAtMillis INTEGER NOT NULL)",
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS scheduled_reminders (" +
                "`key` TEXT NOT NULL PRIMARY KEY, eventId INTEGER NOT NULL, " +
                "occurrenceBeginMillis INTEGER NOT NULL, reminderMinutes INTEGER NOT NULL, " +
                "triggerAtMillis INTEGER NOT NULL)",
        )
    }
}

@Database(
    entities = [
        NotionDatabaseEntity::class,
        NotionEventEntity::class,
        EventColorStyleEntity::class,
        LocalEventProvenanceEntity::class,
        ScheduledReminderEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class RunCalDatabase : RoomDatabase() {
    abstract fun notionDatabaseDao(): NotionDatabaseDao
    abstract fun notionEventDao(): NotionEventDao
    abstract fun eventColorStyleDao(): EventColorStyleDao
    abstract fun localEventProvenanceDao(): LocalEventProvenanceDao
    abstract fun scheduledReminderDao(): ScheduledReminderDao

    companion object {
        @Volatile private var instance: RunCalDatabase? = null

        fun getInstance(context: Context): RunCalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunCalDatabase::class.java,
                    "runcal_database",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
