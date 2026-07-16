package com.babycry.analyzer.insights

import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.ui.i18n.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CareInsightEngineTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val baseNow = 1_700_000_000_000L // fixed anchor for deterministic windows

    @Test
    fun insufficientData_whenFewerThanMinTrackedDays() {
        val now = baseNow
        val input = CareInsightInput(
            feeds = listOf(CareFeedRecord(now - day, 600_000L)),
            diapers = emptyList(),
            sleeps = emptyList(),
            cries = listOf(
                cry(now - day, confirmed = CryReason.HUNGRY),
                cry(now - day + 3_600_000L, confirmed = CryReason.HUNGRY),
            ),
        )

        val summary = CareInsightEngine.compute(input, AppLang.EL, now, zone)

        assertEquals(1, summary.trackedDays)
        assertTrue(summary.insights.isEmpty())
        assertNull(summary.primaryInsight)
        assertNotNull(summary.insufficientDataMessage)
    }

    @Test
    fun cryClustering_detected_whenPeakWindowIncreases() {
        val now = baseNow
        val cries = buildList {
            // Spread across 4 days; cluster around 18:00 UTC in recent 3 days.
            for (d in 0..3) {
                val dayStart = now - d * day
                add(cry(dayStart + 18 * 3_600_000L))
                add(cry(dayStart + 19 * 3_600_000L))
            }
            // Older days: different hours (lower peak at 18:00).
            for (d in 4..5) {
                val dayStart = now - d * day
                add(cry(dayStart + 9 * 3_600_000L))
            }
        }
        val feeds = (0..5).map { d ->
            CareFeedRecord(now - d * day - 8 * 3_600_000L, 900_000L)
        }

        val summary = CareInsightEngine.compute(
            CareInsightInput(feeds, emptyList(), emptyList(), cries),
            AppLang.EN,
            now,
            zone,
        )

        val clustering = summary.insights.firstOrNull { it.category == CareInsightCategory.CRY_CLUSTERING }
        assertNotNull(clustering)
        assertTrue(clustering!!.statsDetail.contains("18:00"))
        assertFalse(containsCausalLanguage(clustering.homeSummary))
        assertFalse(containsCausalLanguage(clustering.statsDetail))
    }

    @Test
    fun cryClustering_suppressed_whenSingleDayDominates() {
        val now = baseNow
        val cries = (0 until 8).map { i ->
            cry(now - 3_600_000L * i) // all same calendar day
        }
        val feeds = (0..3).map { d -> CareFeedRecord(now - d * day, 600_000L) }

        val summary = CareInsightEngine.compute(
            CareInsightInput(feeds, emptyList(), emptyList(), cries),
            AppLang.EL,
            now,
            zone,
        )

        assertNull(summary.insights.firstOrNull { it.category == CareInsightCategory.CRY_CLUSTERING })
    }

    @Test
    fun tiredCryWindow_usesConfirmedOnly() {
        val now = baseNow
        val cries = buildList {
            for (d in 0..3) {
                val dayStart = now - d * day
                add(cry(dayStart + 20 * 3_600_000L, confirmed = CryReason.TIRED))
            }
            // Unconfirmed tired predictions must not count toward tired window.
            add(cry(now - day, confirmed = null, predicted = CryReason.TIRED))
            add(cry(now - 2 * day, confirmed = null, predicted = CryReason.TIRED))
        }
        val feeds = (0..3).map { d -> CareFeedRecord(now - d * day, 600_000L) }

        val summary = CareInsightEngine.compute(
            CareInsightInput(feeds, emptyList(), emptyList(), cries),
            AppLang.EN,
            now,
            zone,
        )

        val tired = summary.insights.firstOrNull { it.category == CareInsightCategory.TIRED_CRY_WINDOW }
        assertNotNull(tired)
        assertTrue(tired!!.statsDetail.contains("confirmed", ignoreCase = true))
    }

    @Test
    fun feedGap_reportsMedianRange() {
        val now = baseNow
        val twoHours = 2 * 3_600_000L
        val feeds = buildList {
            for (d in 0..5) {
                val dayStart = now - d * day
                add(CareFeedRecord(dayStart - 10 * 3_600_000L, 600_000L))
                add(CareFeedRecord(dayStart - 8 * 3_600_000L, 600_000L))
            }
        }

        val summary = CareInsightEngine.compute(
            CareInsightInput(feeds, emptyList(), emptyList(), emptyList()),
            AppLang.EN,
            now,
            zone,
        )

        val gap = summary.insights.firstOrNull { it.category == CareInsightCategory.FEED_GAP }
        assertNotNull(gap)
        assertTrue(gap!!.homeSummary.contains("gap", ignoreCase = true))
    }

    @Test
    fun wakeWindow_observationalNotCausal() {
        val now = baseNow
        val sleeps = (0..4).map { d ->
            CareSleepRecord(now - d * day - 4 * 3_600_000L, 3_600_000L)
        }
        val cries = sleeps.map { sleep ->
            cry(sleep.endedAt() + 30 * 60_000L)
        }

        val summary = CareInsightEngine.compute(
            CareInsightInput(emptyList(), emptyList(), sleeps, cries),
            AppLang.EL,
            now,
            zone,
        )

        val wake = summary.insights.firstOrNull { it.category == CareInsightCategory.WAKE_WINDOW_COOCCURRENCE }
        assertNotNull(wake)
        assertTrue(wake!!.statsDetail.contains("όχι αιτίας"))
        assertFalse(containsCausalLanguage(wake.homeSummary))
    }

    @Test
    fun primaryInsight_onlyHighConfidence() {
        val now = baseNow
        val cries = buildList {
            for (d in 0..4) {
                val dayStart = now - d * day
                add(cry(dayStart + 18 * 3_600_000L))
                add(cry(dayStart + 18 * 3_600_000L + 600_000L))
                add(cry(dayStart + 19 * 3_600_000L))
            }
        }
        val feeds = (0..5).map { d -> CareFeedRecord(now - d * day, 900_000L) }

        val summary = CareInsightEngine.compute(
            CareInsightInput(feeds, emptyList(), emptyList(), cries),
            AppLang.EN,
            now,
            zone,
        )

        summary.primaryInsight?.let { primary ->
            assertEquals(CareInsightConfidence.HIGH, primary.confidence)
        }
    }

    @Test
    fun localizedCopy_greekAndEnglish() {
        val now = baseNow
        val feeds = (0..5).map { d -> CareFeedRecord(now - d * day, 900_000L) }
        val input = CareInsightInput(feeds, emptyList(), emptyList(), emptyList())

        val el = CareInsightEngine.compute(input, AppLang.EL, now, zone)
        val en = CareInsightEngine.compute(input, AppLang.EN, now, zone)

        assertTrue(el.disclaimer.contains("όχι ιατρική"))
        assertTrue(en.disclaimer.contains("not medical advice"))
    }

    private fun cry(
        timestamp: Long,
        confirmed: CryReason? = null,
        predicted: CryReason? = CryReason.HUNGRY,
    ) = CareCryRecord(
        timestamp = timestamp,
        cryDetected = true,
        confirmedReason = confirmed,
        predictedReason = predicted,
    )

    private fun containsCausalLanguage(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("επειδή", "because", "caused by", "means that", "due to").any { lower.contains(it) }
    }
}
