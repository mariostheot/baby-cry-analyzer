package com.babycry.analyzer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CryEvent::class,
        FeedbackExample::class,
        FeedingEvent::class,
        DiaperEvent::class,
        TummyTimeEvent::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cryEventDao(): CryEventDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun feedingDao(): FeedingDao
    abstract fun diaperDao(): DiaperDao
    abstract fun tummyDao(): TummyDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // v1 -> v2: link each learning example to the cry it came from, so a corrected reason
        // replaces the old one. Additive column -> data is preserved (no destructive migration).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE feedback_examples ADD COLUMN sourceEventId INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        // v2 -> v3: add diaper-change logging. New table only -> existing data is untouched.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diaper_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL)",
                )
            }
        }

        // v3 -> v4: add tummy-time logging. New table only -> existing data is untouched.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tummy_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "babycry.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                    .also { instance = it }
            }
    }
}
