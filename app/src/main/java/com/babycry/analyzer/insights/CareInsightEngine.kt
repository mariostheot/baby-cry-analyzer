package com.babycry.analyzer.insights

import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.ui.i18n.AppLang
import java.util.TimeZone

/**
 * Pure, deterministic care-pattern engine. No Room, no Compose, no network.
 * All wording is observational and evidence-gated.
 */
object CareInsightEngine {

    private val HOME_PRIORITY = listOf(
        CareInsightCategory.CRY_CLUSTERING,
        CareInsightCategory.TIRED_CRY_WINDOW,
        CareInsightCategory.FEED_GAP,
        CareInsightCategory.WAKE_WINDOW_COOCCURRENCE,
        CareInsightCategory.FEED_COUNT_SHIFT,
        CareInsightCategory.DIAPER_CADENCE,
        CareInsightCategory.SLEEP_TREND,
    )

    fun compute(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): CareInsightSummary {
        val allTimestamps = buildList {
            addAll(input.feeds.map { it.startedAt })
            addAll(input.diapers.map { it.timestamp })
            addAll(input.sleeps.map { it.startedAt })
            addAll(input.cries.filter { it.cryDetected }.map { it.timestamp })
        }
        val trackedDays = CareInsightTime.distinctTrackedDays(allTimestamps, zone)
        val disclaimer = CareInsightCopy.disclaimer(lang)

        if (trackedDays < CareInsightThresholds.MIN_TRACKED_DAYS) {
            return CareInsightSummary(
                trackedDays = trackedDays,
                insights = emptyList(),
                primaryInsight = null,
                insufficientDataMessage = CareInsightCopy.insufficientData(
                    lang,
                    trackedDays,
                    CareInsightThresholds.MIN_TRACKED_DAYS,
                ),
                disclaimer = disclaimer,
            )
        }

        val insights = buildList {
            detectCryClustering(input, lang, nowMs, zone)?.let(::add)
            detectTiredCryWindow(input, lang, nowMs, zone)?.let(::add)
            detectFeedGap(input, lang, nowMs, zone)?.let(::add)
            detectFeedCountShift(input, lang, nowMs, zone)?.let(::add)
            detectDiaperCadence(input, lang, nowMs, zone)?.let(::add)
            detectSleepTrend(input, lang, nowMs, zone)?.let(::add)
            detectWakeWindowCooccurrence(input, lang, nowMs, zone)?.let(::add)
        }.sortedWith(compareByDescending<CareInsight> { it.confidence.ordinal }.thenByDescending { it.evidenceCount })

        val primary = insights
            .filter { it.confidence == CareInsightConfidence.HIGH }
            .sortedBy { HOME_PRIORITY.indexOf(it.category).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .firstOrNull()

        return CareInsightSummary(
            trackedDays = trackedDays,
            insights = insights,
            primaryInsight = primary,
            // Enough dates were tracked to evaluate the rules. No result here means that no
            // observation cleared its evidence gate, not that the user's data is incomplete.
            insufficientDataMessage = null,
            disclaimer = disclaimer,
        )
    }

    // ---- Rule 1: neutral cry clustering --------------------------------------

    private fun detectCryClustering(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val cries = input.cries.filter { it.cryDetected }
        if (cries.size < CareInsightThresholds.MIN_DETECTED_CRIES) return null

        val recentTs = CareInsightTime.eventsInDayRange(
            cries.map { it.timestamp },
            nowMs,
            daysBackStart = 0,
            daysBackEnd = CareInsightThresholds.RECENT_DAYS,
            zone = zone,
        )
        if (recentTs.size < CareInsightThresholds.MIN_RECENT_CRIES) return null
        if (CareInsightTime.isDominatedBySingleDay(recentTs, zone = zone)) return null

        val previousTs = CareInsightTime.eventsInDayRange(
            cries.map { it.timestamp },
            nowMs,
            daysBackStart = CareInsightThresholds.RECENT_DAYS,
            daysBackEnd = CareInsightThresholds.COMPARISON_DAYS,
            zone = zone,
        )

        val recentByBucket = IntArray(8)
        val previousByBucket = IntArray(8)
        for (ts in recentTs) {
            val bucket = CareInsightTime.hourBucket(CareInsightTime.hourOfDay(ts, zone))
            recentByBucket[bucket]++
        }
        for (ts in previousTs) {
            val bucket = CareInsightTime.hourBucket(CareInsightTime.hourOfDay(ts, zone))
            previousByBucket[bucket]++
        }

        val peak = recentByBucket.indices.maxByOrNull { recentByBucket[it] } ?: return null
        val peakCount = recentByBucket[peak]
        val prevCount = previousByBucket[peak]
        if (peakCount < CareInsightThresholds.MIN_PEAK_WINDOW_CRIES || peakCount <= prevCount) return null

        val window = CareInsightTime.formatHourWindow(peak)
        val confidence = when {
            peakCount >= CareInsightThresholds.HIGH_PEAK_WINDOW_CRIES &&
                recentTs.size >= 5 &&
                CareInsightTime.distinctTrackedDays(recentTs, zone) >= CareInsightThresholds.MIN_TRACKED_DAYS_HIGH ->
                CareInsightConfidence.HIGH
            peakCount >= CareInsightThresholds.HIGH_PEAK_WINDOW_CRIES ->
                CareInsightConfidence.MEDIUM
            else -> CareInsightConfidence.MEDIUM
        }

        return CareInsight(
            id = "cry_cluster_$peak",
            category = CareInsightCategory.CRY_CLUSTERING,
            evidenceCount = peakCount,
            confidence = confidence,
            homeSummary = CareInsightCopy.cryClusteringHome(window, peakCount, lang),
            statsDetail = CareInsightCopy.cryClusteringDetail(window, recentTs.size, prevCount, lang),
            sampleContext = CareInsightCopy.cryClusteringContext(window, peakCount, lang),
        )
    }

    // ---- Rule 2: confirmed tired-cry windows --------------------------------

    private fun detectTiredCryWindow(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val tired = input.cries.filter { it.confirmedReason == CryReason.TIRED }
        if (tired.size < CareInsightThresholds.MIN_CONFIRMED_TIRED) return null
        if (CareInsightTime.distinctTrackedDays(tired.map { it.timestamp }, zone) <
            CareInsightThresholds.MIN_TRACKED_DAYS
        ) {
            return null
        }

        val recent = tired.filter {
            it.timestamp >= nowMs - CareInsightThresholds.COMPARISON_DAYS * 86_400_000L
        }
        if (recent.isEmpty()) return null
        if (CareInsightTime.isDominatedBySingleDay(recent.map { it.timestamp }, zone = zone)) return null

        val byBucket = IntArray(8)
        for (c in recent) {
            val bucket = CareInsightTime.hourBucket(CareInsightTime.hourOfDay(c.timestamp, zone))
            byBucket[bucket]++
        }
        val peak = byBucket.indices.maxByOrNull { byBucket[it] } ?: return null
        val peakCount = byBucket[peak]
        if (peakCount < CareInsightThresholds.MIN_TIRED_IN_PEAK) return null

        val window = CareInsightTime.formatHourWindow(peak)
        val confidence = if (peakCount >= CareInsightThresholds.HIGH_PEAK_WINDOW_CRIES &&
            tired.size >= CareInsightThresholds.MIN_CONFIRMED_TIRED + 1
        ) {
            CareInsightConfidence.HIGH
        } else {
            CareInsightConfidence.MEDIUM
        }

        return CareInsight(
            id = "tired_window_$peak",
            category = CareInsightCategory.TIRED_CRY_WINDOW,
            evidenceCount = peakCount,
            confidence = confidence,
            homeSummary = CareInsightCopy.tiredCryHome(window, peakCount, lang),
            statsDetail = CareInsightCopy.tiredCryDetail(window, tired.size, lang),
            sampleContext = CareInsightCopy.tiredCryContext(window, peakCount, lang),
        )
    }

    // ---- Rule 3: typical feed gaps -------------------------------------------

    private fun detectFeedGap(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val feeds = input.feeds.sortedBy { it.endedAt() }
        if (feeds.size < CareInsightThresholds.MIN_COMPLETED_FEEDS) return null
        if (CareInsightTime.distinctTrackedDays(feeds.map { it.startedAt }, zone) <
            CareInsightThresholds.MIN_TRACKED_DAYS
        ) {
            return null
        }

        val gaps = feeds.zipWithNext { a, b -> b.startedAt - a.endedAt() }
            .filter { it > 0L }
        if (gaps.size < 3) return null

        val medianGap = CareInsightTime.medianMs(gaps) ?: return null
        val english = lang == AppLang.EN
        val range = CareInsightTime.formatGapRange(medianGap, english)

        val recentCutoff = nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L
        val recentGaps = feeds.filter { it.endedAt() >= recentCutoff }
            .zipWithNext { a, b -> b.startedAt - a.endedAt() }
            .filter { it > 0L }
        val olderGaps = feeds.filter { it.endedAt() < recentCutoff }
            .zipWithNext { a, b -> b.startedAt - a.endedAt() }
            .filter { it > 0L }
        val shifted = recentGaps.size >= 2 && olderGaps.size >= 2 &&
            run {
                val recentMed = CareInsightTime.medianMs(recentGaps) ?: return@run false
                val olderMed = CareInsightTime.medianMs(olderGaps) ?: return@run false
                val delta = kotlin.math.abs(recentMed - olderMed).toFloat() / olderMed.coerceAtLeast(1L)
                delta >= CareInsightThresholds.FEED_GAP_SHIFT_RATIO
            }

        val confidence = if (gaps.size >= 6 && !CareInsightTime.isDominatedBySingleDay(
                feeds.map { it.startedAt },
                zone = zone,
            )
        ) {
            CareInsightConfidence.HIGH
        } else {
            CareInsightConfidence.MEDIUM
        }

        return CareInsight(
            id = "feed_gap",
            category = CareInsightCategory.FEED_GAP,
            evidenceCount = feeds.size,
            confidence = confidence,
            homeSummary = CareInsightCopy.feedGapHome(range, lang),
            statsDetail = CareInsightCopy.feedGapDetail(range, feeds.size, shifted, lang),
            sampleContext = CareInsightCopy.feedGapContext(range, feeds.size, lang),
        )
    }

    // ---- Rule 4: feed count shift --------------------------------------------

    private fun detectFeedCountShift(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val feeds = input.feeds
        val recent = CareInsightTime.eventsInDayRange(
            feeds.map { it.startedAt },
            nowMs,
            0,
            CareInsightThresholds.RECENT_DAYS,
            zone,
        )
        val previous = CareInsightTime.eventsInDayRange(
            feeds.map { it.startedAt },
            nowMs,
            CareInsightThresholds.RECENT_DAYS,
            CareInsightThresholds.COMPARISON_DAYS,
            zone,
        )
        if (recent.size < CareInsightThresholds.MIN_FEEDS_PER_PERIOD ||
            previous.size < CareInsightThresholds.MIN_FEEDS_PER_PERIOD
        ) {
            return null
        }

        val recentAvg = recent.size.toFloat() / CareInsightThresholds.RECENT_DAYS
        val previousAvg = previous.size.toFloat() / CareInsightThresholds.RECENT_DAYS
        val delta = kotlin.math.abs(recentAvg - previousAvg) / previousAvg.coerceAtLeast(0.1f)
        if (delta < CareInsightThresholds.FEED_COUNT_SHIFT_RATIO) return null
        if (CareInsightTime.isDominatedBySingleDay(recent, zone = zone)) return null

        val english = lang == AppLang.EN
        val recentTxt = CareInsightTime.formatPerDay(recentAvg, english)
        val previousTxt = CareInsightTime.formatPerDay(previousAvg, english)

        return CareInsight(
            id = "feed_count_shift",
            category = CareInsightCategory.FEED_COUNT_SHIFT,
            evidenceCount = recent.size + previous.size,
            confidence = if (delta >= 0.45f) CareInsightConfidence.HIGH else CareInsightConfidence.MEDIUM,
            homeSummary = CareInsightCopy.feedCountShiftHome(recentTxt, previousTxt, lang),
            statsDetail = CareInsightCopy.feedCountShiftDetail(recentTxt, previousTxt, lang),
            sampleContext = CareInsightCopy.feedCountContext(recentTxt, previousTxt, lang),
        )
    }

    // ---- Rule 5: diaper cadence ----------------------------------------------

    private fun detectDiaperCadence(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val diapers = input.diapers
        val recentStool = diapers.filter {
            it.hasStool && it.timestamp >= nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L
        }
        val previousStool = diapers.filter {
            it.hasStool &&
                it.timestamp in (nowMs - CareInsightThresholds.COMPARISON_DAYS * 86_400_000L) until
                (nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L)
        }
        val recentWet = diapers.filter {
            it.isWet && it.timestamp >= nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L
        }
        val previousWet = diapers.filter {
            it.isWet &&
                it.timestamp in (nowMs - CareInsightThresholds.COMPARISON_DAYS * 86_400_000L) until
                (nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L)
        }

        data class Candidate(val metric: String, val recent: Float, val previous: Float, val count: Int)

        val english = lang == AppLang.EN
        val candidates = buildList {
            if (recentStool.size >= CareInsightThresholds.MIN_DIAPERS_PER_PERIOD / 2 &&
                previousStool.size >= CareInsightThresholds.MIN_DIAPERS_PER_PERIOD / 2
            ) {
                add(
                    Candidate(
                        CareInsightCopy.metricStool(lang),
                        recentStool.size.toFloat() / CareInsightThresholds.RECENT_DAYS,
                        previousStool.size.toFloat() / CareInsightThresholds.RECENT_DAYS,
                        recentStool.size + previousStool.size,
                    ),
                )
            }
            if (recentWet.size >= CareInsightThresholds.MIN_DIAPERS_PER_PERIOD &&
                previousWet.size >= CareInsightThresholds.MIN_DIAPERS_PER_PERIOD
            ) {
                add(
                    Candidate(
                        CareInsightCopy.metricWet(lang),
                        recentWet.size.toFloat() / CareInsightThresholds.RECENT_DAYS,
                        previousWet.size.toFloat() / CareInsightThresholds.RECENT_DAYS,
                        recentWet.size + previousWet.size,
                    ),
                )
            }
        }

        val best = candidates
            .map { c ->
                val delta = kotlin.math.abs(c.recent - c.previous) / c.previous.coerceAtLeast(0.1f)
                c to delta
            }
            .filter { (_, delta) -> delta >= CareInsightThresholds.DIAPER_CADENCE_SHIFT_RATIO }
            .maxByOrNull { (_, delta) -> delta }
            ?.first ?: return null

        val recentTxt = CareInsightTime.formatPerDay(best.recent, english)
        val previousTxt = CareInsightTime.formatPerDay(best.previous, english)

        return CareInsight(
            id = "diaper_cadence",
            category = CareInsightCategory.DIAPER_CADENCE,
            evidenceCount = best.count,
            confidence = CareInsightConfidence.MEDIUM,
            homeSummary = CareInsightCopy.diaperCadenceHome(best.metric, recentTxt, previousTxt, lang),
            statsDetail = CareInsightCopy.diaperCadenceDetail(best.metric, recentTxt, previousTxt, lang),
            sampleContext = CareInsightCopy.diaperCadenceContext(best.metric, recentTxt, previousTxt, lang),
        )
    }

    // ---- Rule 6: sleep trends ------------------------------------------------

    private fun detectSleepTrend(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val sleeps = input.sleeps
        val recent = sleeps.filter { it.endedAt() >= nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L }
        val previous = sleeps.filter {
            it.endedAt() in (nowMs - CareInsightThresholds.COMPARISON_DAYS * 86_400_000L) until
                (nowMs - CareInsightThresholds.RECENT_DAYS * 86_400_000L)
        }
        if (recent.size < CareInsightThresholds.MIN_SLEEPS_PER_PERIOD ||
            previous.size < CareInsightThresholds.MIN_SLEEPS_PER_PERIOD
        ) {
            return null
        }

        val english = lang == AppLang.EN
        val recentCount = recent.size.toFloat() / CareInsightThresholds.RECENT_DAYS
        val previousCount = previous.size.toFloat() / CareInsightThresholds.RECENT_DAYS
        val countDelta = kotlin.math.abs(recentCount - previousCount) / previousCount.coerceAtLeast(0.1f)

        val recentDur = CareInsightTime.medianMs(recent.map { it.durationMs }) ?: return null
        val previousDur = CareInsightTime.medianMs(previous.map { it.durationMs }) ?: return null
        val durDelta = kotlin.math.abs(recentDur - previousDur).toFloat() / previousDur.coerceAtLeast(1L)

        val countShift = countDelta >= CareInsightThresholds.SLEEP_COUNT_SHIFT_RATIO
        val durShift = durDelta >= CareInsightThresholds.SLEEP_DURATION_SHIFT_RATIO
        if (!countShift && !durShift) return null

        val aspect = when {
            countShift && durShift -> CareInsightCopy.aspectSleepCount(lang)
            countShift -> CareInsightCopy.aspectSleepCount(lang)
            else -> CareInsightCopy.aspectSleepDuration(lang)
        }
        val recentTxt = if (aspect == CareInsightCopy.aspectSleepDuration(lang)) {
            CareInsightTime.formatDurationShort(recentDur, english)
        } else {
            CareInsightTime.formatPerDay(recentCount, english)
        }
        val previousTxt = if (aspect == CareInsightCopy.aspectSleepDuration(lang)) {
            CareInsightTime.formatDurationShort(previousDur, english)
        } else {
            CareInsightTime.formatPerDay(previousCount, english)
        }

        return CareInsight(
            id = "sleep_trend",
            category = CareInsightCategory.SLEEP_TREND,
            evidenceCount = recent.size + previous.size,
            confidence = if (countShift && durShift) CareInsightConfidence.HIGH else CareInsightConfidence.MEDIUM,
            homeSummary = CareInsightCopy.sleepTrendHome(aspect, recentTxt, previousTxt, lang),
            statsDetail = CareInsightCopy.sleepTrendDetail(aspect, recentTxt, previousTxt, sleeps.size, lang),
            sampleContext = CareInsightCopy.sleepTrendContext(aspect, recentTxt, previousTxt, lang),
        )
    }

    // ---- Rule 7: wake-window / cry co-occurrence (non-causal) ----------------

    private fun detectWakeWindowCooccurrence(
        input: CareInsightInput,
        lang: AppLang,
        nowMs: Long,
        zone: TimeZone,
    ): CareInsight? {
        val sleeps = input.sleeps.filter {
            it.endedAt() >= nowMs - CareInsightThresholds.COMPARISON_DAYS * 86_400_000L
        }
        if (sleeps.size < CareInsightThresholds.MIN_SLEEPS_PER_PERIOD) return null

        val cries = input.cries.filter { it.cryDetected }.map { it.timestamp }
        if (cries.isEmpty()) return null

        var pairs = 0
        for (sleep in sleeps) {
            val wakeAt = sleep.endedAt()
            val hasCry = cries.any { ts ->
                ts in wakeAt..(wakeAt + CareInsightThresholds.WAKE_WINDOW_MAX_MS)
            }
            if (hasCry) pairs++
        }
        val coverage = pairs.toFloat() / sleeps.size
        if (pairs < CareInsightThresholds.MIN_WAKE_CRY_PAIRS ||
            coverage < CareInsightThresholds.WAKE_CRY_COVERAGE_RATIO
        ) {
            return null
        }

        val windowMinutes = (
            (CareInsightThresholds.WAKE_WINDOW_MIN_MS + CareInsightThresholds.WAKE_WINDOW_MAX_MS) / 2 / 60_000L
            ).toInt()
        val coveragePct = (coverage * 100).toInt()

        return CareInsight(
            id = "wake_window",
            category = CareInsightCategory.WAKE_WINDOW_COOCCURRENCE,
            evidenceCount = pairs,
            confidence = if (coverage >= 0.55f && pairs >= 4) CareInsightConfidence.HIGH else CareInsightConfidence.MEDIUM,
            homeSummary = CareInsightCopy.wakeWindowHome(windowMinutes, coveragePct, lang),
            statsDetail = CareInsightCopy.wakeWindowDetail(pairs, sleeps.size, windowMinutes, lang),
            sampleContext = CareInsightCopy.wakeWindowContext(pairs, sleeps.size, lang),
        )
    }
}
