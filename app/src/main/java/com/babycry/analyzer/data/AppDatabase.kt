package com.babycry.analyzer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [CryEvent::class, FeedbackExample::class, FeedingEvent::class],
    version = 1,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "babycry.db",
                ).build().also { instance = it }
            }
    }
}
