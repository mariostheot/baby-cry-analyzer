package com.babycry.analyzer.model

/**
 * What was in the diaper at a change. Tracking wet vs dirty matters medically: poop
 * frequency is a real signal for constipation/tummy trouble (which ties into the
 * belly-pain / colic reasons), so parents can spot trends the same way they do for cries.
 */
enum class DiaperType(val displayName: String, val emoji: String) {
    WET("Βρεγμένη (πιπί)", "\uD83D\uDCA7"),          // droplet
    DIRTY("Κακά", "\uD83D\uDCA9"),                    // poop
    MIXED("Βρεγμένη + κακά", "\uD83D\uDCA7\uD83D\uDCA9");

    /** True when the change involved stool (used for constipation/poop-frequency trends). */
    val hasStool: Boolean get() = this == DIRTY || this == MIXED

    companion object {
        fun fromNameOrNull(name: String?): DiaperType? =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
