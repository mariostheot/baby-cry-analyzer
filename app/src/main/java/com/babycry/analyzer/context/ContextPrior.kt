package com.babycry.analyzer.context

import com.babycry.analyzer.model.CryReason

/**
 * A gentle re-weighting of the model output using real-world context the audio cannot
 * know: how long since the last feed, and the time of day. It only nudges probabilities
 * (bounded multipliers), never overrides the acoustic evidence. Fully optional (toggle).
 */
object ContextPrior {

    /**
     * Typical hours between feeds by age, from AAP (HealthyChildren.org), NHS and Johns
     * Hopkins guidance. Younger babies feed far more often, so "hunger" should ramp up much
     * faster for a newborn than for a 6-month-old. Used to scale the hunger prior below.
     *
     * - 0-4 weeks: ~2-3 h (8-12 feeds/24h; evening cluster-feeding can be far more frequent)
     * - 1-2 months: ~3-4 h (6-8/day)
     * - 2-4 months: ~3-4 h (5-6/day)
     * - 4-6 months: ~4 h
     * - 6+ months:  ~4-5 h (plus solids)
     */
    fun expectedFeedIntervalHours(ageMonths: Int?): Float = when {
        ageMonths == null -> 3f
        ageMonths < 1 -> 2.5f
        ageMonths < 2 -> 3f
        ageMonths < 4 -> 3.5f
        ageMonths < 6 -> 4f
        else -> 4.5f
    }

    /**
     * @param hoursSinceFeed hours since the last logged feeding, or null if unknown.
     * @param hourOfDay 0..23 local hour.
     * @param ageMonths baby's age in whole months, or null if unknown.
     * @return one multiplier per class, in [CryReason.canonicalOrder].
     */
    fun multipliers(hoursSinceFeed: Float?, hourOfDay: Int, ageMonths: Int? = null): FloatArray {
        val m = FloatArray(CryReason.canonicalOrder.size) { 1f }
        val idxHungry = CryReason.HUNGRY.ordinal
        val idxTired = CryReason.TIRED.ordinal
        val idxBelly = CryReason.BELLY_PAIN.ordinal
        val idxBurp = CryReason.BURPING.ordinal
        val idxDiscomfort = CryReason.DISCOMFORT.ordinal

        // Hunger scales with how far we are into the age-appropriate feeding interval, rather
        // than fixed hour thresholds. ratio = 1.0 means "about the usual time for a feed".
        if (hoursSinceFeed != null) {
            val expected = expectedFeedIntervalHours(ageMonths)
            val ratio = hoursSinceFeed / expected
            m[idxHungry] = when {
                ratio < 0.4f -> 0.5f                                   // just fed
                ratio < 0.8f -> 0.9f
                ratio < 1.1f -> 1.4f                                   // around due time
                else -> (1.4f + (ratio - 1.1f) * 1.6f).coerceAtMost(2.6f) // overdue
            }
        }

        // Common nap windows (afternoon) and night -> tiredness slightly more likely.
        val sleepy = hourOfDay in 12..15 || hourOfDay in 19..23 || hourOfDay in 0..6
        if (sleepy) m[idxTired] = 1.3f

        // Age-aware nudges (gentle). Newborns: colic/gas & burping peak in the first months;
        // older babies: teething discomfort becomes more common and burping issues fade.
        if (ageMonths != null) {
            when {
                ageMonths < 4 -> {
                    m[idxBelly] *= 1.25f
                    m[idxBurp] *= 1.2f
                }
                ageMonths < 7 -> {
                    m[idxBelly] *= 1.1f
                }
                else -> {
                    m[idxDiscomfort] *= 1.2f
                    m[idxBurp] *= 0.85f
                }
            }
        }

        return m
    }

    /** Apply multipliers to probabilities and renormalize. */
    fun apply(probs: FloatArray, multipliers: FloatArray): FloatArray {
        val out = FloatArray(probs.size) { probs[it] * multipliers.getOrElse(it) { 1f } }
        val sum = out.sum().coerceAtLeast(1e-8f)
        for (i in out.indices) out[i] /= sum
        return out
    }
}
