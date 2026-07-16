package com.babycry.analyzer.insights

import com.babycry.analyzer.model.CryReason

/** Completed feeding session (duration known, not in progress). */
data class CareFeedRecord(
    val startedAt: Long,
    val durationMs: Long,
) {
    fun endedAt(): Long = startedAt + durationMs.coerceAtLeast(0L)
}

data class CareDiaperRecord(
    val timestamp: Long,
    val hasStool: Boolean,
    val isWet: Boolean,
)

/** Completed sleep/nap session. */
data class CareSleepRecord(
    val startedAt: Long,
    val durationMs: Long,
) {
    fun endedAt(): Long = startedAt + durationMs.coerceAtLeast(0L)
}

/**
 * One recorded cry. Reason-specific rules must use [confirmedReason] only; neutral volume
 * patterns may use any detected cry regardless of confirmation.
 */
data class CareCryRecord(
    val timestamp: Long,
    val cryDetected: Boolean,
    val confirmedReason: CryReason?,
    val predictedReason: CryReason?,
)

/** All dated care logs for one profile, supplied to the pure engine. */
data class CareInsightInput(
    val feeds: List<CareFeedRecord>,
    val diapers: List<CareDiaperRecord>,
    val sleeps: List<CareSleepRecord>,
    val cries: List<CareCryRecord>,
)
