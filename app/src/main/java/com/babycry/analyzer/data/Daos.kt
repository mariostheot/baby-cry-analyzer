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

    @Query("SELECT * FROM cry_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :limit")
    fun recent(profileId: String, limit: Int = 200): Flow<List<CryEvent>>

    @Query("SELECT * FROM cry_events WHERE profileId = :profileId AND confirmedIndex IS NOT NULL ORDER BY timestamp DESC")
    suspend fun confirmedEvents(profileId: String): List<CryEvent>

    @Query("SELECT * FROM cry_events WHERE profileId = :profileId ORDER BY timestamp DESC")
    suspend fun allEvents(profileId: String): List<CryEvent>

    @Query("SELECT * FROM cry_events ORDER BY timestamp DESC")
    suspend fun allEventsAllProfiles(): List<CryEvent>

    @Query("SELECT * FROM cry_events WHERE id = :id")
    suspend fun byId(id: Long): CryEvent?

    @Query("DELETE FROM cry_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE cry_events SET profileId = :profileId WHERE profileId = ''")
    suspend fun assignLegacy(profileId: String)

    @Query("DELETE FROM cry_events WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    @Query("DELETE FROM cry_events")
    suspend fun clearAllProfiles()
}

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(example: FeedbackExample): Long

    @Query("SELECT * FROM feedback_examples WHERE profileId = :profileId ORDER BY timestamp ASC")
    suspend fun all(profileId: String): List<FeedbackExample>

    @Query("SELECT * FROM feedback_examples ORDER BY timestamp ASC")
    suspend fun allAllProfiles(): List<FeedbackExample>

    @Query("SELECT COUNT(*) FROM feedback_examples WHERE profileId = :profileId")
    fun count(profileId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM feedback_examples WHERE profileId = :profileId")
    suspend fun countNow(profileId: String): Int

    /** Remove any example(s) that came from a given cry, so a correction can replace them. */
    @Query("DELETE FROM feedback_examples WHERE sourceEventId = :eventId")
    suspend fun deleteByEvent(eventId: Long)

    @Query("UPDATE feedback_examples SET profileId = :profileId WHERE profileId = ''")
    suspend fun assignLegacy(profileId: String)

    @Query("DELETE FROM feedback_examples")
    suspend fun clear()

    @Query("DELETE FROM feedback_examples WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

@Dao
interface FeedingDao {
    @Insert
    suspend fun insert(event: FeedingEvent): Long

    @Query("SELECT * FROM feeding_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT 1")
    suspend fun last(profileId: String): FeedingEvent?

    @Query("SELECT * FROM feeding_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :limit")
    fun recent(profileId: String, limit: Int = 200): Flow<List<FeedingEvent>>

    @Query("SELECT * FROM feeding_events WHERE profileId = :profileId ORDER BY timestamp DESC")
    suspend fun allList(profileId: String): List<FeedingEvent>

    @Query("SELECT * FROM feeding_events ORDER BY timestamp DESC")
    suspend fun allListAllProfiles(): List<FeedingEvent>

    @Query("UPDATE feeding_events SET profileId = :profileId WHERE profileId = ''")
    suspend fun assignLegacy(profileId: String)

    @Query("DELETE FROM feeding_events WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    @Query("DELETE FROM feeding_events")
    suspend fun clearAllProfiles()
}

@Dao
interface DiaperDao {
    @Insert
    suspend fun insert(event: DiaperEvent): Long

    @Query("SELECT * FROM diaper_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT 1")
    suspend fun last(profileId: String): DiaperEvent?

    @Query("SELECT * FROM diaper_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :limit")
    fun recent(profileId: String, limit: Int = 200): Flow<List<DiaperEvent>>

    @Query("SELECT * FROM diaper_events WHERE profileId = :profileId ORDER BY timestamp DESC")
    suspend fun allList(profileId: String): List<DiaperEvent>

    @Query("SELECT * FROM diaper_events ORDER BY timestamp DESC")
    suspend fun allListAllProfiles(): List<DiaperEvent>

    @Query("UPDATE diaper_events SET profileId = :profileId WHERE profileId = ''")
    suspend fun assignLegacy(profileId: String)

    @Query("DELETE FROM diaper_events WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    @Query("DELETE FROM diaper_events")
    suspend fun clearAllProfiles()
}

@Dao
interface TummyDao {
    @Insert
    suspend fun insert(event: TummyTimeEvent): Long

    @Query("SELECT * FROM tummy_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT 1")
    suspend fun last(profileId: String): TummyTimeEvent?

    @Query("SELECT * FROM tummy_events WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :limit")
    fun recent(profileId: String, limit: Int = 200): Flow<List<TummyTimeEvent>>

    @Query("SELECT * FROM tummy_events WHERE profileId = :profileId ORDER BY timestamp DESC")
    suspend fun allList(profileId: String): List<TummyTimeEvent>

    @Query("SELECT * FROM tummy_events ORDER BY timestamp DESC")
    suspend fun allListAllProfiles(): List<TummyTimeEvent>

    /** How many sessions were logged since [since] (used for "done today"). */
    @Query("SELECT COUNT(*) FROM tummy_events WHERE profileId = :profileId AND timestamp >= :since")
    suspend fun countSince(profileId: String, since: Long): Int

    @Query("UPDATE tummy_events SET profileId = :profileId WHERE profileId = ''")
    suspend fun assignLegacy(profileId: String)

    @Query("DELETE FROM tummy_events WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    @Query("DELETE FROM tummy_events")
    suspend fun clearAllProfiles()
}
