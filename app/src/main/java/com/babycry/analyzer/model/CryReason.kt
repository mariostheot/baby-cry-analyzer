package com.babycry.analyzer.model

/**
 * The set of cry categories the app can report.
 *
 * IMPORTANT: the order of these entries is the canonical label order. The Python
 * training pipeline (`ml-training/`) must emit its output classes in exactly this
 * order (see `labels.txt`), otherwise on-device predictions will be mislabeled.
 *
 * The five categories mirror the public "donateacry-corpus" dataset.
 */
enum class CryReason(
    val displayName: String,
    val emoji: String,
    val advice: String,
) {
    HUNGRY(
        displayName = "Πείνα",
        emoji = "\uD83C\uDF7C", // baby bottle
        advice = "Δοκίμασε τάισμα. Ψάξε για σημάδια πείνας: πιπίλισμα, στρέψιμο του κεφαλιού, γλείψιμο χειλιών.",
    ),
    TIRED(
        displayName = "Κούραση / Υπνηλία",
        emoji = "\uD83D\uDE34", // sleepy
        advice = "Μπορεί να θέλει ύπνο. Χαμήλωσε τα ερεθίσματα, νανούρισμα, ήρεμο περιβάλλον.",
    ),
    DISCOMFORT(
        displayName = "Δυσφορία",
        emoji = "\uD83D\uDE23", // uncomfortable
        advice = "Έλεγξε πάνα, ρούχα, θερμοκρασία ή στάση. Κάτι μπορεί να το ενοχλεί.",
    ),
    BELLY_PAIN(
        displayName = "Κοιλόπονος / Αέρια",
        emoji = "\uD83D\uDE29", // distressed
        advice = "Πιθανά αέρια ή κολικός. Δοκίμασε απαλό μασάζ στην κοιλιά ή ποδηλατάκι με τα πόδια.",
    ),
    BURPING(
        displayName = "Ρέψιμο (θέλει να βγάλει αέρα)",
        emoji = "\uD83E\uDD30", // needs care
        advice = "Κράτησέ το όρθιο στον ώμο σου και χτύπα απαλά την πλάτη για ρέψιμο.",
    );

    companion object {
        /** Canonical order used by the model output layer and `labels.txt`. */
        val canonicalOrder: List<CryReason> = entries.toList()

        fun fromNameOrNull(name: String): CryReason? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
