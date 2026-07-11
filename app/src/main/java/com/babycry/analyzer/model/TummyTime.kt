package com.babycry.analyzer.model

/**
 * Age-appropriate tummy-time daily goal, expressed as a number of short sessions per day.
 *
 * Authoritative sources (AAP / HealthyChildren, Pathways.org, NHS, WHO) give a *minutes-per-day*
 * target that ramps from a few minutes for a newborn up to ~60'/day by 3-6 months, rather than a
 * fixed number of repetitions. We turn that into a friendly session count (total ÷ typical
 * session length) so the parent can just tap "I did it" and see "X/Y today". The numbers mirror
 * the in-app Tummy Time guide.
 */
object TummyTime {

    /** Recommended number of tummy-time sessions per day for a baby [ageDays] old. */
    fun dailyGoal(ageDays: Int?): Int = when {
        ageDays == null -> 4        // unknown age: a sensible middle-of-the-road target
        ageDays < 30 -> 3           // 0-1 month: 2-3 very short sessions, building up
        ageDays < 60 -> 4           // 1-2 months
        ageDays < 90 -> 5           // 2-3 months
        ageDays < 120 -> 6          // 3-4 months: peak, building toward ~60'/day
        ageDays < 180 -> 5          // 4-6 months: longer sessions, fewer needed
        else -> 4                   // 6+ months: long play/rolling sessions
    }
}
