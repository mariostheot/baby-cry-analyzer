package com.babycry.analyzer.model

import java.util.Calendar

/**
 * Optional details about the baby. Stored locally (SharedPreferences), never uploaded.
 * [birthMillis] drives age-aware context priors and lets the UI greet the baby by name.
 */
data class BabyProfile(
    val name: String = "",
    val birthMillis: Long? = null,
) {
    val hasName: Boolean get() = name.isNotBlank()

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
}
