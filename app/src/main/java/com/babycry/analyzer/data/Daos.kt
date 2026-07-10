package com.babycry.analyzer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CryEventDao {
    @Insert
    suspend fun insert(event: CryEvent): Long

    @Update
    suspend fun update(event: CryEvent)

    @Query("SELECT * FROM cry_events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<CryEvent>>

    @Query("SELECT * FROM cry_events WHERE confirmedIndex IS NOT NULL ORDER BY timestamp DESC")
    suspend fun confirmedEvents(): List<CryEvent>

    @Query("SELECT * FROM cry_events ORDER BY timestamp DESC")
    suspend fun allEvents(): List<CryEvent>

    @Query("SELECT * FROM cry_events WHERE id = :id")
    suspend fun byId(id: Long): CryEvent?

    @Query("DELETE FROM cry_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cry_events")
    suspend fun clear()
}

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(example: FeedbackExample): Long

    @Query("SELECT * FROM feedback_examples ORDER BY timestamp ASC")
    suspend fun all(): List<FeedbackExample>

    @Query("SELECT COUNT(*) FROM feedback_examples")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM feedback_examples")
    suspend fun countNow(): Int

    @Query("DELETE FROM feedback_examples")
    suspend fun clear()
}

@Dao
interface FeedingDao {
    @Insert
    suspend fun insert(event: FeedingEvent): Long

    @Query("SELECT * FROM feeding_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun last(): FeedingEvent?

    @Query("SELECT * FROM feeding_events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<FeedingEvent>>

    @Query("SELECT * FROM feeding_events ORDER BY timestamp DESC")
    suspend fun allList(): List<FeedingEvent>

    @Query("DELETE FROM feeding_events")
    suspend fun clear()
}
