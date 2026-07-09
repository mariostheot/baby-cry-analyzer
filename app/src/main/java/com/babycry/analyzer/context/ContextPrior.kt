package com.babycry.analyzer.context

import com.babycry.analyzer.model.CryReason

/**
 * A gentle re-weighting of the model output using real-world context the audio cannot
 * know: how long since the last feed, and the time of day. It only nudges probabilities
 * (bounded multipliers), never overrides the acoustic evidence. Fully optional (toggle).
 */
object ContextPrior {

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

        if (hoursSinceFeed != null) {
            m[idxHungry] = when {
                hoursSinceFeed < 1f -> 0.5f                       // just fed -> less likely
                hoursSinceFeed < 2f -> 1.0f
                else -> (1f + (hoursSinceFeed - 2f) / 2f).coerceAtMost(2.5f)
            }
        }

        // Common nap windows (afternoon) and night -> tiredness slightly more likely.
        val sleepy = hourOfDay in 12..15 || hourOfDay in 19..23 || hourOfDay in 0..6
        if (sleepy) m[idxTired] = 1.3f

        // Age-aware nudges (gentle). Newborns: colic/gas & burping peak in the first months
        // and feedings are frequent; older babies: teething discomfort becomes more common.
        if (ageMonths != null) {
            when {
                ageMonths < 4 -> {
                    m[idxBelly] *= 1.25f
                    m[idxBurp] *= 1.2f
                    m[idxHungry] *= 1.1f
                }
                ageMonths < 7 -> {
                    m[idxBelly] *= 1.1f
                }
                else -> {
                    m[idxDiscomfort] *= 1.2f   // teething / environment
                    m[idxBurp] *= 0.85f        // burping issues fade
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
