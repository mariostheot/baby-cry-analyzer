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
    version = 7,
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

        // v4 -> v5: make all parent-entered records belong to a specific baby profile.
        // Existing rows start as legacy ("") and the repository assigns them to the active baby
        // on first launch after upgrade, because the active profile lives in SharedPreferences.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cry_events ADD COLUMN profileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE feedback_examples ADD COLUMN profileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE feeding_events ADD COLUMN profileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE diaper_events ADD COLUMN profileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tummy_events ADD COLUMN profileId TEXT NOT NULL DEFAULT ''")
            }
        }

        // v5 -> v6: retain the actual duration of a feeding session. Existing "fed now" markers
        // get 0 (unknown duration); -1 is reserved for a timer that is currently running.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeding_events ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v6 -> v7: reserve a small, permanent per-class holdout set. These confirmed clips
        // stay out of personalization training, making before/after metrics honest.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE feedback_examples ADD COLUMN isValidationHoldout INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "babycry.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                ).build()
                    .also { instance = it }
            }
    }
}
