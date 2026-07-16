package com.babycry.analyzer.insights

import java.util.Calendar
import java.util.TimeZone

internal object CareInsightTime {
    private const val DAY_MS = 86_400_000L

    fun startOfDay(ts: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun dayKey(ts: Long, zone: TimeZone = TimeZone.getDefault()): Long = startOfDay(ts, zone)

    fun hourOfDay(ts: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    fun hourBucket(hour: Int, bucketSize: Int = CareInsightThresholds.HOUR_BUCKET_SIZE): Int =
        (hour / bucketSize).coerceIn(0, (24 / bucketSize) - 1)

    fun formatHourWindow(bucket: Int, bucketSize: Int = CareInsightThresholds.HOUR_BUCKET_SIZE): String {
        val from = bucket * bucketSize
        val to = (from + bucketSize).coerceAtMost(24)
        return "%02d:00–%02d:00".format(from, to)
    }

    fun distinctTrackedDays(timestamps: List<Long>, zone: TimeZone = TimeZone.getDefault()): Int =
        timestamps.map { dayKey(it, zone) }.toSet().size

    fun eventsInDayRange(
        timestamps: List<Long>,
        nowMs: Long,
        daysBackStart: Int,
        daysBackEnd: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Long> {
        val today = startOfDay(nowMs, zone)
        // Include today in the most recent range: 0..3 means today plus the
        // two preceding calendar days. Consecutive ranges therefore do not overlap.
        val rangeStart = today - (daysBackEnd - 1).coerceAtLeast(0) * DAY_MS
        val rangeEnd = today - daysBackStart * DAY_MS + DAY_MS
        return timestamps.filter { it in rangeStart until rangeEnd }
    }

    fun countByDay(timestamps: List<Long>, zone: TimeZone = TimeZone.getDefault()): Map<Long, Int> =
        timestamps.groupingBy { dayKey(it, zone) }.eachCount()

    fun isDominatedBySingleDay(
        timestamps: List<Long>,
        ratio: Float = CareInsightThresholds.SINGLE_DAY_DOMINANCE_RATIO,
        zone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        if (timestamps.isEmpty()) return false
        val byDay = countByDay(timestamps, zone)
        val max = byDay.values.maxOrNull() ?: 0
        return max.toFloat() / timestamps.size > ratio
    }

    fun medianMs(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    fun formatDurationShort(ms: Long, english: Boolean): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 ->
                if (english) "${hours}h ${minutes}m" else "${hours}ω ${minutes}λ"
            hours > 0 ->
                if (english) "${hours}h" else "${hours}ω"
            else ->
                if (english) "${minutes}m" else "${minutes}λ"
        }
    }

    fun formatGapRange(medianMs: Long, english: Boolean): String {
        val low = (medianMs * 0.85).toLong().coerceAtLeast(15 * 60_000L)
        val high = (medianMs * 1.15).toLong()
        return "${formatDurationShort(low, english)}–${formatDurationShort(high, english)}"
    }

    fun formatPerDay(value: Float, english: Boolean): String =
        if (english) "%.1f/day".format(value) else "%.1f/ημέρα".format(value)
}
