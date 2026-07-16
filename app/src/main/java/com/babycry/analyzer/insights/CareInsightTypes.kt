package com.babycry.analyzer.insights

/** Non-medical care observation category. */
enum class CareInsightCategory {
    CRY_CLUSTERING,
    TIRED_CRY_WINDOW,
    FEED_GAP,
    FEED_COUNT_SHIFT,
    DIAPER_CADENCE,
    SLEEP_TREND,
    WAKE_WINDOW_COOCCURRENCE,
}

enum class CareInsightConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * One evidence-gated, observational insight. Copy is already localized for the requested
 * [com.babycry.analyzer.ui.i18n.AppLang].
 */
data class CareInsight(
    val id: String,
    val category: CareInsightCategory,
    val evidenceCount: Int,
    val confidence: CareInsightConfidence,
    /** Short copy suitable for a Home card. */
    val homeSummary: String,
    /** Fuller non-medical detail for Stats. */
    val statsDetail: String,
    /** Supporting sample context (counts, windows, ranges). */
    val sampleContext: String,
)

/**
 * Full engine output for one baby profile. [primaryInsight] is the best high-confidence
 * candidate for a future Home card; null when nothing meets the bar.
 */
data class CareInsightSummary(
    val trackedDays: Int,
    val insights: List<CareInsight>,
    val primaryInsight: CareInsight?,
    val insufficientDataMessage: String?,
    val disclaimer: String,
)
