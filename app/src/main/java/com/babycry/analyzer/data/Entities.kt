package com.babycry.analyzer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One analyzed recording. [confirmedIndex] is filled in when the parent confirms or
 * corrects the prediction; the pair (predictedIndex, confirmedIndex) drives the in-app
 * accuracy stats and the personal confusion matrix.
 */
@Entity(tableName = "cry_events")
data class CryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val cryDetected: Boolean,
    val predictedIndex: Int,          // canonical class index, or -1 if no cry
    val confirmedIndex: Int? = null,  // null until the parent gives feedback
    val confidence: Float,
    val engine: String,               // AnalysisEngine name
    val gateScore: Float,
)

/**
 * A user-confirmed example (YAMNet embedding + true label) kept on device to personalize
 * predictions. Never leaves the phone.
 *
 * [sourceEventId] links the example back to the cry it came from. That way, if the parent
 * later corrects the reason of that cry, we can REPLACE this example instead of leaving a
 * contradictory duplicate (same embedding, two different labels) behind. 0 = legacy/unknown.
 */
@Entity(tableName = "feedback_examples")
data class FeedbackExample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val labelIndex: Int,
    val embedding: FloatArray,
    // Default declared here to exactly match the v1->v2 migration's `DEFAULT 0`, so Room's
    // schema check passes on upgrade regardless of Room version.
    @ColumnInfo(defaultValue = "0") val sourceEventId: Long = 0,
) {
    // data class with an array field: override equality on identity to keep Room/data happy.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

/** A "fed now" marker used by the context prior (hours-since-feed -> hunger likelihood). */
@Entity(tableName = "feeding_events")
data class FeedingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val note: String? = null,
)

/**
 * A logged diaper change. [type] is a [com.babycry.analyzer.model.DiaperType] name
 * (WET/DIRTY/MIXED) so we can chart poop frequency separately from wet changes.
 */
@Entity(tableName = "diaper_events")
data class DiaperEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
)

/**
 * One logged tummy-time session (count-based: each tap = one session the parent did with the
 * baby). Drives the daily "X/Y sessions" goal, history and the age-based reminder.
 */
@Entity(tableName = "tummy_events")
data class TummyTimeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
)
