package com.babycry.analyzer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CryEvent::class, FeedbackExample::class, FeedingEvent::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cryEventDao(): CryEventDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun feedingDao(): FeedingDao

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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "babycry.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
