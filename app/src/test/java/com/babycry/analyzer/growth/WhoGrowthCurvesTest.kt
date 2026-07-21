package com.babycry.analyzer.growth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class WhoGrowthCurvesTest {

    @Test
    fun ageMonthsForPlot_beforeBirth_returnsNull() {
        val birth = 1_000_000L
        assertNull(WhoGrowthMath.ageMonthsForPlot(birth, birth - 1))
    }

    @Test
    fun ageMonthsForPlot_after60Months_returnsNull() {
        val birth = 0L
        val ts = birth + (61.0 * WhoGrowthMath.MS_PER_MONTH).toLong()
        assertNull(WhoGrowthMath.ageMonthsForPlot(birth, ts))
    }

    @Test
    fun ageMonthsCoerced_clampsToRange() {
        val birth = 100_000L
        assertEquals(0.0, WhoGrowthMath.ageMonthsCoerced(birth, birth - 50_000), 0.001)
        val at60 = birth + (60.0 * WhoGrowthMath.MS_PER_MONTH).toLong()
        assertEquals(60.0, WhoGrowthMath.ageMonthsCoerced(birth, at60 + 1_000_000), 0.01)
    }

    @Test
    fun parseRecordsJson_validatesStructure() {
        val json = JSONObject(
            """
            {"records":[
              {"month":0,"sd2neg":2500,"sd0":3300,"sd2":4400},
              {"month":1,"sd2neg":3400,"sd0":4500,"sd2":5800}
            ]}
            """.trimIndent(),
        )
        try {
            WhoGrowthDataValidation.parseRecordsJson(json)
            error("Expected validation failure for short series")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("61") == true)
        }
    }

    @Test
    fun bundledWfaBoy_has61MonthsAndMonotonicMedian() {
        val path = assetPath("who_cgs2006/wfa_boy.json")
        val text = Files.readString(path)
        val records = WhoGrowthDataValidation.parseRecordsJson(JSONObject(text))
        assertEquals(0, records.first().month)
        assertEquals(60, records.last().month)
        assertEquals(2500, records[0].lower)
        assertEquals(3300, records[0].median)
        assertEquals(4400, records[0].upper)
        for (i in 1 until records.size) {
            assertTrue(records[i].median >= records[i - 1].median)
        }
    }

    @Test
    fun bundledLhfaGirl_matchesExpectedMonth24FromMerge() {
        val path = assetPath("who_cgs2006/lhfa_girl.json")
        val records = WhoGrowthDataValidation.parseRecordsJson(JSONObject(Files.readString(path)))
        val m24 = records[24]
        assertEquals(857, m24.median)
    }

    private fun assetPath(relative: String): java.nio.file.Path {
        val root = Paths.get("").toAbsolutePath()
        val candidates = listOf(
            root.resolve("src/main/assets/$relative"),
            root.resolve("app/src/main/assets/$relative"),
        )
        return candidates.firstOrNull(Files::exists)
            ?: error("WHO asset is missing: $relative")
    }
}
