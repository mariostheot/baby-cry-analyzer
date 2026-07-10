package com.babycry.analyzer.context

import com.babycry.analyzer.model.CryReason

/**
 * A gentle re-weighting of the model output using real-world context the audio cannot
 * know: how long since the last feed, and the time of day. It only nudges probabilities
 * (bounded multipliers), never overrides the acoustic evidence. Fully optional (toggle).
 */
object ContextPrior {

    /**
     * Typical hours between feeds by age. Based on AAP (HealthyChildren.org), NHS and Johns
     * Hopkins guidance, but tuned for the newborn weeks from real-world experience: in the
     * first ~3 weeks a baby often wants to feed roughly every hour (much more frequently than
     * the ~2-3h the guidelines quote, especially with cluster feeding). Younger babies feed
     * far more often, so "hunger" ramps up much faster for a newborn than for a 6-month-old.
     *
     * - 0-3 weeks:  ~1 h   (very frequent / cluster feeding - from experience)
     * - 3-6 weeks:  ~2 h
     * - 6-8 weeks:  ~2.5 h
     * - 2-4 months: ~3 h
     * - 4-6 months: ~3.5 h
     * - 6+ months:  ~4 h   (plus solids)
     *
     * [ageDays] takes precedence for the earliest weeks; [ageMonths] covers the rest.
     */
    fun expectedFeedIntervalHours(ageMonths: Int?, ageDays: Int? = null): Float = when {
        ageDays != null && ageDays < 21 -> 1f    // first 3 weeks: ~hourly
        ageDays != null && ageDays < 42 -> 2f    // weeks 3-6
        ageMonths == null -> 3f
        ageMonths < 2 -> 2.5f
        ageMonths < 4 -> 3f
        ageMonths < 6 -> 3.5f
        else -> 4f
    }

    /**
     * @param hoursSinceFeed hours since the last logged feeding, or null if unknown.
     * @param hourOfDay 0..23 local hour.
     * @param ageMonths baby's age in whole months, or null if unknown.
     * @param ageDays baby's age in whole days, or null if unknown (drives the newborn weeks).
     * @return one multiplier per class, in [CryReason.canonicalOrder].
     */
    fun multipliers(
        hoursSinceFeed: Float?,
        hourOfDay: Int,
        ageMonths: Int? = null,
        ageDays: Int? = null,
    ): FloatArray {
        val m = FloatArray(CryReason.canonicalOrder.size) { 1f }
        val idxHungry = CryReason.HUNGRY.ordinal
        val idxTired = CryReason.TIRED.ordinal
        val idxBelly = CryReason.BELLY_PAIN.ordinal
        val idxBurp = CryReason.BURPING.ordinal
        val idxDiscomfort = CryReason.DISCOMFORT.ordinal

        // Hunger scales with how far we are into the age-appropriate feeding interval, rather
        // than fixed hour thresholds. ratio = 1.0 means "about the usual time for a feed".
        if (hoursSinceFeed != null) {
            val expected = expectedFeedIntervalHours(ageMonths, ageDays)
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
