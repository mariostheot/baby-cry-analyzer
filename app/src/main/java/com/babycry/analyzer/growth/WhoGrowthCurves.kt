package com.babycry.analyzer.growth

import android.content.Context
import com.babycry.analyzer.model.BabyGender
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class WhoGrowthPoint(
    val month: Int,
    val lower: Int,
    val median: Int,
    val upper: Int,
)

data class WhoGrowthCurves(
    val weight: List<WhoGrowthPoint>,
    val height: List<WhoGrowthPoint>,
)

object WhoGrowthMath {
    const val MS_PER_MONTH: Double = 30.4375 * 86_400_000.0

    /** Fractional age in months at measurement time, for plotting (null if before birth or after 60 months). */
    fun ageMonthsForPlot(birthMillis: Long, measurementMillis: Long): Double? {
        if (measurementMillis < birthMillis) return null
        val age = (measurementMillis - birthMillis) / MS_PER_MONTH
        if (age > 60.0) return null
        return age
    }

    /** Age in months with the same formula used for chart X positioning (coerced 0..60). */
    fun ageMonthsCoerced(birthMillis: Long, measurementMillis: Long): Double =
        ((measurementMillis - birthMillis) / MS_PER_MONTH).coerceIn(0.0, 60.0)
}

object WhoGrowthDataValidation {
    fun validateRecords(records: List<WhoGrowthPoint>) {
        require(records.size == 61) { "Expected 61 records, got ${records.size}" }
        records.forEachIndexed { index, point ->
            require(point.month == index) { "Expected month $index, got ${point.month}" }
            require(point.lower < point.median && point.median < point.upper) {
                "Month $index: expected lower < median < upper"
            }
            if (index > 0) {
                val prev = records[index - 1].median
                require(point.median >= prev) {
                    "Month $index: median ${point.median} < previous $prev"
                }
            }
        }
    }

    fun parseRecordsJson(json: JSONObject): List<WhoGrowthPoint> {
        val arr = json.getJSONArray("records")
        val out = ArrayList<WhoGrowthPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += WhoGrowthPoint(
                month = o.getInt("month"),
                lower = o.getInt("sd2neg"),
                median = o.getInt("sd0"),
                upper = o.getInt("sd2"),
            )
        }
        validateRecords(out)
        return out
    }
}

object WhoGrowthCurvesLoader {
    private val cache = java.util.Collections.synchronizedMap(mutableMapOf<BabyGender, WhoGrowthCurves>())

    fun load(context: Context, gender: BabyGender): WhoGrowthCurves? {
        if (gender == BabyGender.UNKNOWN) return null
        cache[gender]?.let { return it }
        return synchronized(this) {
            cache[gender] ?: run {
                val loaded = loadUncached(context.applicationContext, gender)
                if (loaded != null) cache[gender] = loaded
                loaded
            }
        }
    }

    internal fun loadUncached(context: Context, gender: BabyGender): WhoGrowthCurves? {
        if (gender == BabyGender.UNKNOWN) return null
        val suffix = when (gender) {
            BabyGender.BOY -> "boy"
            BabyGender.GIRL -> "girl"
            BabyGender.UNKNOWN -> return null
        }
        return try {
            val weightJson = readAssetJson(context, "who_cgs2006/wfa_$suffix.json")
            val heightJson = readAssetJson(context, "who_cgs2006/lhfa_$suffix.json")
            WhoGrowthCurves(
                weight = WhoGrowthDataValidation.parseRecordsJson(weightJson),
                height = WhoGrowthDataValidation.parseRecordsJson(heightJson),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readAssetJson(context: Context, assetPath: String): JSONObject {
        context.assets.open(assetPath).use { stream ->
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            return JSONObject(text)
        }
    }
}
