package com.babycry.analyzer.insights

/** Deterministic, documented gates for every insight rule. */
object CareInsightThresholds {
    const val MIN_TRACKED_DAYS = 3
    const val MIN_TRACKED_DAYS_HIGH = 5

    /** Recent vs previous comparison window (calendar days). */
    const val RECENT_DAYS = 3
    const val COMPARISON_DAYS = 6

    /** If one day holds more than this share of events, suppress the insight. */
    const val SINGLE_DAY_DOMINANCE_RATIO = 0.55f

    // ---- Cries (neutral clustering) ----
    const val MIN_DETECTED_CRIES = 6
    const val MIN_RECENT_CRIES = 3
    const val MIN_PEAK_WINDOW_CRIES = 2
    const val HIGH_PEAK_WINDOW_CRIES = 3

    // ---- Confirmed tired cries ----
    const val MIN_CONFIRMED_TIRED = 3
    const val MIN_TIRED_IN_PEAK = 2

    // ---- Feeds ----
    const val MIN_COMPLETED_FEEDS = 4
    const val MIN_FEEDS_PER_PERIOD = 3
    const val FEED_GAP_SHIFT_RATIO = 0.25f
    const val FEED_COUNT_SHIFT_RATIO = 0.30f

    // ---- Diapers ----
    const val MIN_DIAPERS_PER_PERIOD = 4
    const val DIAPER_CADENCE_SHIFT_RATIO = 0.30f

    // ---- Sleep ----
    const val MIN_SLEEPS_PER_PERIOD = 3
    const val SLEEP_COUNT_SHIFT_RATIO = 0.30f
    const val SLEEP_DURATION_SHIFT_RATIO = 0.25f

    // ---- Wake window co-occurrence (observational, not causal) ----
    const val WAKE_WINDOW_MIN_MS = 45 * 60_000L
    const val WAKE_WINDOW_MAX_MS = 90 * 60_000L
    const val MIN_WAKE_CRY_PAIRS = 3
    const val WAKE_CRY_COVERAGE_RATIO = 0.40f

    /** Three-hour buckets for time-of-day clustering (0..7). */
    const val HOUR_BUCKET_SIZE = 3
}
