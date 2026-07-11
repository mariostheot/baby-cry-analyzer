package com.babycry.analyzer.model

import java.util.Calendar

enum class BabyGender {
    UNKNOWN,
    BOY,
    GIRL;

    companion object {
        fun fromNameOrNull(name: String?): BabyGender =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * Optional details about the baby. Stored locally (SharedPreferences), never uploaded.
 * [birthMillis] drives age-aware context priors and lets the UI greet the baby by name.
 */
data class BabyProfile(
    val name: String = "",
    val birthMillis: Long? = null,
    val gender: BabyGender = BabyGender.UNKNOWN,
    /** Stable local id, so we can support more than one baby (e.g. siblings/twins). */
    val id: String = "",
    /**
     * Set when a pediatrician has confirmed colic/gas for this baby. When true, the context
     * prior gives extra weight to belly-pain / burping (especially between feeds), because
     * for a diagnosed baby those causes really are more likely. Per-baby, never uploaded.
     */
    val colicConfirmed: Boolean = false,
) {
    val hasName: Boolean get() = name.isNotBlank()

    fun displayNameNominative(langIsEnglish: Boolean): String = when {
        !hasName -> if (langIsEnglish) "baby" else "το μωρό"
        langIsEnglish -> name
        gender == BabyGender.BOY -> "ο $name"
        gender == BabyGender.GIRL -> "η $name"
        else -> name
    }

    fun displayNameAccusative(langIsEnglish: Boolean): String = when {
        !hasName -> if (langIsEnglish) "baby" else "το μωρό"
        langIsEnglish -> name
        gender == BabyGender.BOY -> "τον $name"
        gender == BabyGender.GIRL -> "την $name"
        else -> name
    }

    /** Whole days since birth, or null if no birth date is set. Needed for the very early
     *  weeks, where feeding frequency changes week-to-week (months is too coarse). */
    fun ageDays(now: Long = System.currentTimeMillis()): Int? {
        val birth = birthMillis ?: return null
        if (birth <= 0L || birth > now) return null
        return ((now - birth) / 86_400_000L).toInt()
    }

    /** Whole months since birth, or null if no birth date is set. */
    fun ageMonths(now: Long = System.currentTimeMillis()): Int? {
        val birth = birthMillis ?: return null
        if (birth <= 0L || birth > now) return null
        val bc = Calendar.getInstance().apply { timeInMillis = birth }
        val nc = Calendar.getInstance().apply { timeInMillis = now }
        var months = (nc.get(Calendar.YEAR) - bc.get(Calendar.YEAR)) * 12 +
            (nc.get(Calendar.MONTH) - bc.get(Calendar.MONTH))
        if (nc.get(Calendar.DAY_OF_MONTH) < bc.get(Calendar.DAY_OF_MONTH)) months -= 1
        return months.coerceAtLeast(0)
    }

    /**
     * Age split into whole months + leftover whole weeks (e.g. 1 month & 2 weeks), or null if
     * there's no birth date. Lets the UI show a friendly "1 μήνας και 2 εβδομάδες" instead of
     * a bare "1 μηνών".
     */
    fun ageMonthsWeeks(now: Long = System.currentTimeMillis()): Pair<Int, Int>? {
        val birth = birthMillis ?: return null
        val months = ageMonths(now) ?: return null
        // Land on the most recent "month-iversary", then count the leftover days as weeks.
        val anchor = Calendar.getInstance().apply {
            timeInMillis = birth
            add(Calendar.MONTH, months)
        }
        val leftoverDays = ((now - anchor.timeInMillis) / 86_400_000L).toInt().coerceAtLeast(0)
        return months to (leftoverDays / 7)
    }
}
