package com.babycry.analyzer.insights

import com.babycry.analyzer.ui.i18n.AppLang

/**
 * Localized observational copy for care insights. Greek strings are canonical; English
 * mirrors [com.babycry.analyzer.ui.i18n.I18n] style without pulling in Compose.
 */
internal object CareInsightCopy {
    fun disclaimer(lang: AppLang): String = t(
        lang,
        "Ενημερωτικές παρατηρήσεις από τις καταγραφές σου — όχι ιατρική συμβουλή.",
        "Observations from your logs — not medical advice.",
    )

    fun insufficientData(lang: AppLang, trackedDays: Int, minDays: Int): String = t(
        lang,
        "Δεν υπάρχουν ακόμη αρκετές καταγραφές ($trackedDays/${minDays} ημέρες). " +
            "Με λίγες ακόμη μέρες χρήσης θα εμφανίζονται παρατηρήσεις.",
        "Not enough logs yet ($trackedDays/$minDays days). " +
            "A few more days of tracking will unlock observations.",
    )

    fun cryClusteringHome(window: String, count: Int, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, τα κλάματα φαίνεται να συγκεντρώνονται περίπου $window ($count φορές).",
        "In your logs, cries seem to cluster around $window ($count times).",
    )

    fun cryClusteringDetail(window: String, recent: Int, previous: Int, lang: AppLang): String = t(
        lang,
        "Τις τελευταίες ${CareInsightThresholds.RECENT_DAYS} μέρες υπάρχουν $recent καταγεγραμμένα κλάματα " +
            "και περισσότερα περίπου $window από τις προηγούμενες ${CareInsightThresholds.RECENT_DAYS} " +
            "(πριν: $previous εκεί).",
        "Over the last ${CareInsightThresholds.RECENT_DAYS} days you logged $recent cries, " +
            "with more around $window than the prior ${CareInsightThresholds.RECENT_DAYS} " +
            "(before: $previous there).",
    )

    fun cryClusteringContext(window: String, count: Int, lang: AppLang): String = t(
        lang,
        "Παράθυρο $window · $count κλάματα",
        "Window $window · $count cries",
    )

    fun tiredCryHome(window: String, count: Int, lang: AppLang): String = t(
        lang,
        "Στις επιβεβαιωμένες καταγραφές σου, η κούραση/υπνηλία εμφανίζεται συχνότερα περίπου $window ($count φορές).",
        "Among your confirmed logs, tired/sleepy cries appear more often around $window ($count times).",
    )

    fun tiredCryDetail(window: String, total: Int, lang: AppLang): String = t(
        lang,
        "Με βάση μόνο επιβεβαιωμένες αιτίες, $total κλάματα κούρασης/υπνηλίας φαίνεται να " +
            "συγκεντρώνονται περίπου $window.",
        "Using confirmed reasons only, $total tired/sleepy cries seem to cluster around $window.",
    )

    fun tiredCryContext(window: String, count: Int, lang: AppLang): String = t(
        lang,
        "Επιβεβαιωμένη κούραση · $window · $count",
        "Confirmed tired · $window · $count",
    )

    fun feedGapHome(range: String, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, τα διαστήματα μεταξύ ταϊσμάτων είναι συνήθως περίπου $range.",
        "In your logs, gaps between feeds are usually around $range.",
    )

    fun feedGapDetail(range: String, feeds: Int, shifted: Boolean, lang: AppLang): String =
        if (shifted) {
            t(
                lang,
                "Από $feeds ολοκληρωμένα ταΐσματα, το τυπικό διάστημα είναι περίπου $range. " +
                    "Πρόσφατα φαίνεται μικρή μετατόπιση στο ρυθμό σε σχέση με τις προηγούμενες μέρες.",
                "From $feeds completed feeds, the typical gap is about $range. " +
                    "Recently there seems to be a slight shift compared with earlier days.",
            )
        } else {
            t(
                lang,
                "Από $feeds ολοκληρωμένα ταΐσματα, το τυπικό διάστημα είναι περίπου $range.",
                "From $feeds completed feeds, the typical gap is about $range.",
            )
        }

    fun feedGapContext(range: String, feeds: Int, lang: AppLang): String = t(
        lang,
        "$feeds ταΐσματα · διάστημα $range",
        "$feeds feeds · gap $range",
    )

    fun feedCountShiftHome(recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, τα ταΐσματα/ημέρα φαίνεται να άλλαξαν από $previous σε $recent.",
        "In your logs, feeds per day seem to have shifted from $previous to $recent.",
    )

    fun feedCountShiftDetail(recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "Μέσος όρος ταϊσμάτων/ημέρα: πρόσφατα $recent, πριν $previous (ολοκληρωμένα ταΐσματα).",
        "Average feeds per day: recently $recent, before $previous (completed feeds).",
    )

    fun feedCountContext(recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "$previous → $recent /ημέρα",
        "$previous → $recent per day",
    )

    fun diaperCadenceHome(metric: String, recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, $metric φαίνεται να άλλαξε από $previous σε $recent ανά ημέρα.",
        "In your logs, $metric seems to have shifted from $previous to $recent per day.",
    )

    fun diaperCadenceDetail(metric: String, recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "Σύγκριση πάνων: $metric ήταν $previous/ημέρα και πρόσφατα $recent/ημέρα.",
        "Diaper comparison: $metric was $previous/day and recently $recent/day.",
    )

    fun diaperCadenceContext(metric: String, recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "$metric · $previous → $recent",
        "$metric · $previous → $recent",
    )

    fun metricStool(lang: AppLang): String = t(lang, "τα κακά", "stool changes")
    fun metricWet(lang: AppLang): String = t(lang, "οι βρεγμένες αλλαγές", "wet changes")

    fun sleepTrendHome(aspect: String, recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, $aspect φαίνεται να άλλαξε από $previous σε $recent.",
        "In your logs, $aspect seems to have shifted from $previous to $recent.",
    )

    fun sleepTrendDetail(aspect: String, recent: String, previous: String, sleeps: Int, lang: AppLang): String = t(
        lang,
        "Από $sleeps ολοκληρωμένους ύπνους/ύπνιακους: $aspect ήταν $previous και πρόσφατα $recent.",
        "From $sleeps completed sleeps/naps: $aspect was $previous and recently $recent.",
    )

    fun sleepTrendContext(aspect: String, recent: String, previous: String, lang: AppLang): String = t(
        lang,
        "$aspect · $previous → $recent",
        "$aspect · $previous → $recent",
    )

    fun aspectSleepCount(lang: AppLang): String = t(lang, "οι ύπνοι/ύπνιακοι ανά ημέρα", "sleeps/naps per day")
    fun aspectSleepDuration(lang: AppLang): String = t(lang, "η διάρκεια ύπνου", "sleep duration")

    fun wakeWindowHome(windowMinutes: Int, coveragePct: Int, lang: AppLang): String = t(
        lang,
        "Στις καταγραφές σου, μετά από ύπνο εμφανίζονται συχνά κλάματα μέσα σε περίπου $windowMinutes λεπτά " +
            "($coveragePct% των ξυπνημάτων).",
        "In your logs, cries often appear within about $windowMinutes minutes after sleep " +
            "($coveragePct% of wake-ups).",
    )

    fun wakeWindowDetail(pairs: Int, wakes: Int, windowMinutes: Int, lang: AppLang): String = t(
        lang,
        "Παρατήρηση συχνότητας, όχι αιτίας: σε $pairs από $wakes ξυπνήματα υπήρχε καταγεγραμμένο κλάμα " +
            "μέσα σε ~$windowMinutes λεπτά.",
        "A frequency observation, not a cause: in $pairs of $wakes wake-ups a cry was logged " +
            "within ~$windowMinutes minutes.",
    )

    fun wakeWindowContext(pairs: Int, wakes: Int, lang: AppLang): String = t(
        lang,
        "$pairs/$wakes ξυπνήματα",
        "$pairs/$wakes wake-ups",
    )

    fun categoryTitle(category: CareInsightCategory, lang: AppLang): String = when (category) {
        CareInsightCategory.CRY_CLUSTERING -> t(lang, "Κλάματα ανά ώρα", "Cries by time")
        CareInsightCategory.TIRED_CRY_WINDOW -> t(lang, "Κούραση / ύπνος", "Tired / sleepy")
        CareInsightCategory.FEED_GAP -> t(lang, "Διαστήματα ταϊσμάτων", "Feed gaps")
        CareInsightCategory.FEED_COUNT_SHIFT -> t(lang, "Συχνότητα ταϊσμάτων", "Feed frequency")
        CareInsightCategory.DIAPER_CADENCE -> t(lang, "Ρυθμός πάνων", "Diaper cadence")
        CareInsightCategory.SLEEP_TREND -> t(lang, "Ύπνος / ύπνιακοι", "Sleep / naps")
        CareInsightCategory.WAKE_WINDOW_COOCCURRENCE -> t(lang, "Μετά τον ύπνο", "After sleep")
    }

    private fun t(lang: AppLang, el: String, en: String): String =
        if (lang == AppLang.EN) en else el
}
