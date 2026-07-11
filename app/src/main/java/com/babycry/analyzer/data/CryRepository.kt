package com.babycry.analyzer.data

import android.content.Context
import android.util.Base64
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.ml.CryAnalyzer
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.model.DiaperType
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Aggregated accuracy metrics computed from the parent's confirmations. */
data class StatsSummary(
    val labels: List<CryReason>,
    val confirmedCount: Int,
    val accuracy: Float?,               // null when no confirmations yet
    val perClassRecall: List<Float?>,   // aligned with [labels]
    val confusion: Array<IntArray>,     // confusion[true][pred]
    val feedbackCount: Int,
    val hasModel: Boolean,
    val tier2Available: Boolean,
    val tier2Ready: Boolean,
    val predictedDistribution: IntArray, // how often each reason was predicted (all events)
    val totalCries: Int,                 // events where a cry was detected
)

/**
 * Single source of truth for the UI: runs analysis, records history, applies feedback,
 * logs feedings and computes stats. All heavy work is dispatched off the main thread.
 */
class CryRepository private constructor(
    context: Context,
    private val analyzer: CryAnalyzer,
) {
    private val db = AppDatabase.get(context)
    private val cryDao = db.cryEventDao()
    private val feedbackDao = db.feedbackDao()
    private val feedingDao = db.feedingDao()
    private val diaperDao = db.diaperDao()
    private val tummyDao = db.tummyDao()
    private val profilePrefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val clipStore = ClipStore(context)

    init {
        currentAppLang = getLanguage()
    }

    val labels: List<CryReason> get() = analyzer.labels
    val hasModel: Boolean get() = analyzer.hasModel
    val tier2Available: Boolean get() = analyzer.personalization.tier2Available

    fun recentEvents(): Flow<List<CryEvent>> = cryDao.recent()
    fun feedbackCount(): Flow<Int> = feedbackDao.count()
    fun recentFeedings(): Flow<List<FeedingEvent>> = feedingDao.recent()
    fun recentDiapers(): Flow<List<DiaperEvent>> = diaperDao.recent()
    fun recentTummy(): Flow<List<TummyTimeEvent>> = tummyDao.recent()

    fun isPersonalizationEnabled(): Boolean = profilePrefs.getBoolean(PERSONALIZATION_ON, true)

    fun setPersonalizationEnabled(enabled: Boolean) {
        profilePrefs.edit().putBoolean(PERSONALIZATION_ON, enabled).apply()
    }

    /** Load stored feedback into the personalization engine (call once at startup). */
    suspend fun refreshPersonalization() = withContext(Dispatchers.Default) {
        rebuildPersonalization()
    }

    /**
     * Rebuild from the *current* feedback table only. This deliberately resets Tier 2 first, so
     * deleted/restored/relabelled examples cannot leave old fine-tuned weights behind.
     */
    private suspend fun rebuildPersonalization() = withContext(Dispatchers.Default) {
        val all = feedbackDao.all()
        analyzer.personalization.reset()
        analyzer.personalization.updatePrototypes(all)
        analyzer.personalization.maybeTrain(all)
    }

    suspend fun analyze(
        waveform: FloatArray,
        personalizationEnabled: Boolean,
        contextEnabled: Boolean,
    ): CryAnalysis = withContext(Dispatchers.Default) {
        val multipliers = if (contextEnabled) contextMultipliers() else null
        analyzer.analyze(
            waveform = waveform,
            personalizationEnabled = personalizationEnabled,
            useTier2 = true,
            contextMultipliers = multipliers,
        )
    }

    suspend fun saveEvent(analysis: CryAnalysis, waveform: FloatArray? = null): Long =
        withContext(Dispatchers.IO) {
            val r = analysis.result
            val predicted = r.topReason?.let { labels.indexOf(it) } ?: -1
            val id = cryDao.insert(
                CryEvent(
                    timestamp = System.currentTimeMillis(),
                    cryDetected = r.cryDetected,
                    predictedIndex = predicted,
                    confidence = r.confidence,
                    engine = r.engine.name,
                    gateScore = analysis.gateScore,
                )
            )
            // Only real cries are worth keeping/asking about. Confirmed cries are always kept
            // on-device (the recording + embedding) so the parent can replay them and build a
            // personal dataset - there is no user toggle for this.
            if (r.cryDetected) {
                if (waveform != null) {
                    clipStore.saveClip(id, waveform, analysis.embedding)
                }
                setPending(id)
            }
            id
        }

    /**
     * Record a confirmation/correction and update personalization. Used by the immediate
     * "I already know" flow (passes the in-memory [embedding]).
     */
    suspend fun confirm(
        eventId: Long,
        correctReason: CryReason,
        embedding: FloatArray?,
    ) = setReason(eventId, correctReason, embedding)

    /**
     * Sets/updates the real reason for a past cry - from the delayed prompt, the notification,
     * or a later correction in History. When we don't have the embedding in memory (e.g. after
     * the app restarted) we recover it from the stored clip so personalization still learns.
     */
    suspend fun setReason(
        eventId: Long,
        correctReason: CryReason,
        embedding: FloatArray? = null,
    ) = withContext(Dispatchers.Default) {
        val event = cryDao.byId(eventId) ?: return@withContext
        val idx = labels.indexOf(correctReason).coerceAtLeast(0)
        cryDao.update(event.copy(confirmedIndex = idx))

        // Always drop any earlier learning example from this same cry first, so correcting the
        // reason days later never leaves a stale/contradictory label behind - even if the
        // recording is no longer stored and we can't re-add a corrected one.
        feedbackDao.deleteByEvent(eventId)

        val emb = embedding ?: clipStore.readEmbedding(eventId)
        if (emb != null) {
            feedbackDao.insert(
                FeedbackExample(
                    timestamp = System.currentTimeMillis(),
                    labelIndex = idx,
                    embedding = emb,
                    sourceEventId = eventId,
                )
            )
        }
        // Rebuild the personalization from the current (clean) set - covers both the added
        // corrected example and the case where we only removed a stale one.
        rebuildPersonalization()
        if (pendingId() == eventId) clearPending()
    }

    // ---- Delayed confirmation ("why did the baby cry?") ----------------------

    /** The most recent detected cry still awaiting the parent's confirmation, or null. */
    suspend fun pendingConfirmation(): CryEvent? = withContext(Dispatchers.IO) {
        val id = pendingId().takeIf { it > 0L } ?: return@withContext null
        val event = cryDao.byId(id)
        if (event == null || !event.cryDetected || event.confirmedIndex != null) {
            clearPending()
            null
        } else {
            event
        }
    }

    fun dismissPending() = clearPending()

    private fun pendingId(): Long = profilePrefs.getLong(PENDING_ID, 0L)

    private fun setPending(eventId: Long) {
        profilePrefs.edit()
            .putLong(PENDING_ID, eventId)
            .putLong(PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearPending() {
        profilePrefs.edit().remove(PENDING_ID).remove(PENDING_AT).apply()
    }

    suspend fun logFeeding(note: String? = null) = withContext(Dispatchers.IO) {
        feedingDao.insert(FeedingEvent(timestamp = System.currentTimeMillis(), note = note))
    }

    suspend fun logDiaper(type: DiaperType) = withContext(Dispatchers.IO) {
        diaperDao.insert(DiaperEvent(timestamp = System.currentTimeMillis(), type = type.name))
    }

    suspend fun logTummy() = withContext(Dispatchers.IO) {
        tummyDao.insert(TummyTimeEvent(timestamp = System.currentTimeMillis()))
    }

    // ---- Tummy time: age-based daily goal + reminder --------------------------

    /** Recommended tummy-time sessions per day for the active baby's age. */
    fun tummyDailyGoal(): Int =
        com.babycry.analyzer.model.TummyTime.dailyGoal(getProfile().ageDays())

    /** How many tummy-time sessions have been logged since the start of today. */
    suspend fun tummyDoneToday(): Int = withContext(Dispatchers.IO) {
        tummyDao.countSince(startOfToday())
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun isTummyReminderEnabled(): Boolean = profilePrefs.getBoolean(TUMMY_REMINDER_ON, true)

    fun setTummyReminderEnabled(enabled: Boolean) {
        profilePrefs.edit().putBoolean(TUMMY_REMINDER_ON, enabled).apply()
    }

    /** Morning tummy-time reminder hour (0..23). Defaults to 11:00. */
    fun tummyReminderHourAm(): Int = profilePrefs.getInt(TUMMY_REMINDER_HOUR_AM, 11).coerceIn(0, 23)

    fun setTummyReminderHourAm(hour: Int) {
        profilePrefs.edit().putInt(TUMMY_REMINDER_HOUR_AM, hour.coerceIn(0, 23)).apply()
    }

    /** Afternoon tummy-time reminder hour (0..23). Defaults to 18:00. */
    fun tummyReminderHourPm(): Int = profilePrefs.getInt(TUMMY_REMINDER_HOUR_PM, 18).coerceIn(0, 23)

    fun setTummyReminderHourPm(hour: Int) {
        profilePrefs.edit().putInt(TUMMY_REMINDER_HOUR_PM, hour.coerceIn(0, 23)).apply()
    }

    suspend fun hoursSinceLastFeed(): Float? = withContext(Dispatchers.IO) {
        feedingDao.last()?.let {
            (System.currentTimeMillis() - it.timestamp) / 3_600_000f
        }
    }

    suspend fun lastFeedTimestamp(): Long? = withContext(Dispatchers.IO) { feedingDao.last()?.timestamp }

    /**
     * When to fire the "feeding time is near" heads-up, as (delayFromNowMs, lastFeedTimestamp),
     * or null if we can't/shouldn't (no feed logged yet, or the lead time already passed). Uses
     * the age-appropriate feeding interval and warns [FeedReminder.LEAD_MINUTES] before it.
     */
    suspend fun feedReminderPlan(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val last = feedingDao.last()?.timestamp ?: return@withContext null
        val profile = getProfile()
        val intervalHours = com.babycry.analyzer.context.ContextPrior
            .expectedFeedIntervalHours(profile.ageMonths(), profile.ageDays())
        val remindAt = last + (intervalHours * 3_600_000L).toLong() -
            com.babycry.analyzer.notify.FeedReminder.LEAD_MINUTES * 60_000L
        val delay = remindAt - System.currentTimeMillis()
        if (delay <= 0L) null else delay to last
    }

    suspend fun resetPersonalization() = withContext(Dispatchers.Default) {
        feedbackDao.clear()
        analyzer.personalization.reset()
        analyzer.personalization.updatePrototypes(emptyList())
    }

    suspend fun stats(): StatsSummary = withContext(Dispatchers.Default) {
        val n = labels.size
        val confusion = Array(n) { IntArray(n) }
        val confirmed = cryDao.confirmedEvents()
        var correct = 0
        val perClassTotal = IntArray(n)
        val perClassCorrect = IntArray(n)
        for (e in confirmed) {
            val t = e.confirmedIndex ?: continue
            val p = e.predictedIndex
            if (t !in 0 until n || p !in 0 until n) continue
            confusion[t][p]++
            perClassTotal[t]++
            if (t == p) {
                correct++
                perClassCorrect[t]++
            }
        }
        val total = confirmed.count {
            (it.confirmedIndex ?: -1) in 0 until n && it.predictedIndex in 0 until n
        }

        val distribution = IntArray(n)
        var cries = 0
        for (e in cryDao.allEvents()) {
            if (!e.cryDetected) continue
            cries++
            // Prefer the parent's correction when present; else the model's prediction.
            val idx = e.confirmedIndex?.takeIf { it in 0 until n } ?: e.predictedIndex
            if (idx in 0 until n) distribution[idx]++
        }

        StatsSummary(
            labels = labels,
            confirmedCount = total,
            accuracy = if (total > 0) correct.toFloat() / total else null,
            perClassRecall = (0 until n).map {
                if (perClassTotal[it] > 0) perClassCorrect[it].toFloat() / perClassTotal[it] else null
            },
            confusion = confusion,
            feedbackCount = feedbackDao.countNow(),
            hasModel = hasModel,
            tier2Available = tier2Available,
            tier2Ready = analyzer.personalization.tier2Ready,
            predictedDistribution = distribution,
            totalCries = cries,
        )
    }

    // ---- Baby profile --------------------------------------------------------

    /** All babies on this device (empty before onboarding). */
    fun getProfiles(): List<BabyProfile> {
        profilePrefs.getString(PROFILES, null)?.let { json ->
            return runCatching { parseProfiles(json) }.getOrDefault(emptyList())
        }
        // One-time migration from the old single-profile keys.
        val oldName = profilePrefs.getString(PROFILE_NAME, null)
        val oldBirth = if (profilePrefs.contains(PROFILE_BIRTH))
            profilePrefs.getLong(PROFILE_BIRTH, 0L).takeIf { it > 0L } else null
        if (!oldName.isNullOrBlank() || oldBirth != null) {
            val p = BabyProfile(name = oldName ?: "", birthMillis = oldBirth, id = newProfileId())
            persistProfiles(listOf(p), p.id)
            return listOf(p)
        }
        return emptyList()
    }

    /** The currently selected baby (or an empty profile if none yet). */
    fun getProfile(): BabyProfile {
        val list = getProfiles()
        if (list.isEmpty()) return BabyProfile()
        val activeId = profilePrefs.getString(ACTIVE_PROFILE, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    suspend fun addProfile(
        name: String,
        birthMillis: Long?,
        colicConfirmed: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val p = BabyProfile(
            name = name.trim(),
            birthMillis = birthMillis,
            id = newProfileId(),
            colicConfirmed = colicConfirmed,
        )
        list.add(p)
        persistProfiles(list, p.id)
        p.id
    }

    /** Edits the currently active baby (creating one if there isn't any yet). */
    suspend fun updateActiveProfile(name: String, birthMillis: Long?) = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val activeId = getProfile().id
        val idx = list.indexOfFirst { activeId.isNotBlank() && it.id == activeId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.trim(), birthMillis = birthMillis)
            persistProfiles(list, list[idx].id)
        } else {
            val p = BabyProfile(name = name.trim(), birthMillis = birthMillis, id = newProfileId())
            list.add(p)
            persistProfiles(list, p.id)
        }
    }

    /** Backwards-compatible alias used by existing callers: edits the active baby. */
    suspend fun setProfile(profile: BabyProfile) = updateActiveProfile(profile.name, profile.birthMillis)

    /** Flags/unflags pediatrician-confirmed colic for the active baby (drives the context prior). */
    suspend fun setColicConfirmed(enabled: Boolean) = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val activeId = getProfile().id
        val idx = list.indexOfFirst { activeId.isNotBlank() && it.id == activeId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(colicConfirmed = enabled)
            persistProfiles(list, list[idx].id)
        }
    }

    suspend fun setActiveProfile(id: String) = withContext(Dispatchers.IO) {
        profilePrefs.edit().putString(ACTIVE_PROFILE, id).apply()
    }

    suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) {
        val remaining = getProfiles().filterNot { it.id == id }
        val activeWas = getProfile().id
        val newActive = if (activeWas == id) remaining.firstOrNull()?.id else activeWas
        persistProfiles(remaining, newActive)
    }

    private fun newProfileId(): String = java.util.UUID.randomUUID().toString()

    private fun parseProfiles(json: String): List<BabyProfile> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BabyProfile(
                name = o.optString("name", ""),
                birthMillis = if (o.has("birthMillis")) o.optLong("birthMillis") else null,
                id = o.optString("id", "").ifBlank { newProfileId() },
                colicConfirmed = o.optBoolean("colicConfirmed", false),
            )
        }
    }

    private fun persistProfiles(list: List<BabyProfile>, activeId: String?) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.birthMillis?.let { put("birthMillis", it) }
                put("colicConfirmed", p.colicConfirmed)
            })
        }
        profilePrefs.edit().apply {
            putString(PROFILES, arr.toString())
            if (activeId != null) putString(ACTIVE_PROFILE, activeId) else remove(ACTIVE_PROFILE)
        }.apply()
    }

    /** Whether the one-time welcome/profile screen has already been shown. */
    fun isOnboardingComplete(): Boolean = profilePrefs.getBoolean(ONBOARDING_DONE, false)

    fun setOnboardingComplete() {
        profilePrefs.edit().putBoolean(ONBOARDING_DONE, true).apply()
    }

    fun getLanguage(): AppLang =
        if (profilePrefs.getString(APP_LANG, "el") == "en") AppLang.EN else AppLang.EL

    fun setLanguage(lang: AppLang) {
        profilePrefs.edit().putString(APP_LANG, lang.code).apply()
        currentAppLang = lang
    }

    /** Clears the cry history and feeding log (what the Stats screen counts). Keeps what the
     *  model learned from you (feedback examples / personalization). Also drops the saved
     *  recordings, since they're tied to the deleted events. */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        cryDao.clear()
        feedingDao.clear()
        diaperDao.clear()
        tummyDao.clear()
        clipStore.clearAll()
        clearPending()
    }

    /** Deletes a single cry event from the history (and its saved recording). */
    suspend fun deleteEvent(id: Long) = withContext(Dispatchers.IO) {
        cryDao.deleteById(id)
        feedbackDao.deleteByEvent(id)
        clipStore.deleteClip(id)
        rebuildPersonalization()
        if (pendingId() == id) clearPending()
    }

    // ---- Personal dataset (exportable) ---------------------------------------

    /** (#clips, totalBytes) currently stored on device. */
    suspend fun datasetInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        clipStore.count() to clipStore.totalBytes()
    }

    /** Zips every confirmed clip + a labels.csv into [out]. Returns how many clips were written. */
    suspend fun writeDatasetZip(out: java.io.OutputStream): Int = withContext(Dispatchers.IO) {
        clipStore.writeDatasetZip(out, cryDao.allEvents(), labels)
    }

    /** Confirmed cries that still have a saved recording - the parent's playable library. */
    suspend fun libraryEvents(): List<CryEvent> = withContext(Dispatchers.IO) {
        cryDao.confirmedEvents().filter { clipStore.hasClip(it.id) }
    }

    /** Reads a saved recording back to a waveform for replay from the library. */
    suspend fun readClipSamples(eventId: Long): FloatArray? = withContext(Dispatchers.IO) {
        clipStore.readClipSamples(eventId)
    }

    // ---- Human-friendly report (HTML) ----------------------------------------

    /**
     * A styled, self-contained HTML report anyone can open in a browser (and print to PDF).
     * Far friendlier than raw CSV: a summary, the reason breakdown, and a readable table.
     */
    suspend fun exportReportHtml(): String = withContext(Dispatchers.IO) {
        val lang = getLanguage()
        val df = SimpleDateFormat(
            "EEE dd/MM/yyyy HH:mm",
            if (lang == AppLang.EN) Locale.ENGLISH else Locale("el"),
        )
        val events = cryDao.allEvents()
        val feedings = feedingDao.allList()
        val diapers = diaperDao.allList()
        val tummy = tummyDao.allList()
        val stats = stats()
        val profile = getProfile()
        val cries = events.filter { it.cryDetected }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val sb = StringBuilder()
        val htmlLang = if (lang == AppLang.EN) "en" else "el"
        sb.append("<!DOCTYPE html><html lang=\"$htmlLang\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>${trS("Αναφορά")} — ${trS("Γιατί Κλαίει;")}</title><style>")
        sb.append(
            """
            body{font-family:-apple-system,Roboto,Segoe UI,sans-serif;margin:0;background:#f5f4f8;color:#1c1b1f}
            .wrap{max-width:760px;margin:0 auto;padding:24px}
            h1{font-size:22px;margin:0 0 4px}
            .sub{color:#6b6b74;margin:0 0 20px;font-size:13px}
            .cards{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:20px}
            .card{background:#fff;border-radius:16px;padding:16px 18px;box-shadow:0 1px 3px rgba(0,0,0,.08);flex:1;min-width:140px}
            .card .n{font-size:26px;font-weight:700;color:#6750A4}
            .card .l{font-size:12px;color:#6b6b74}
            h2{font-size:16px;margin:24px 0 10px}
            .bar{background:#eceaf3;border-radius:8px;overflow:hidden;height:22px;margin:4px 0}
            .bar>span{display:block;height:100%;background:#6750A4}
            .row{display:flex;justify-content:space-between;font-size:14px;margin-top:10px}
            table{width:100%;border-collapse:collapse;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08)}
            th,td{padding:10px 12px;text-align:left;font-size:13px;border-bottom:1px solid #eee}
            th{background:#6750A4;color:#fff;font-weight:600}
            .ok{color:#3a8a4f}.corr{color:#b5651d}
            .heat{display:flex;gap:2px;margin:6px 0}
            .heat .c{flex:1;height:34px;border-radius:3px}
            .axis{display:flex;justify-content:space-between;font-size:10px;color:#9a9aa2;margin-bottom:4px}
            .days{display:flex;align-items:flex-end;gap:4px;height:96px;margin-top:6px}
            .days .col{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end}
            .days .b{width:100%;background:#6750A4;border-radius:5px}
            .days .lbl{font-size:9px;color:#9a9aa2;margin-top:3px}
            .days .cnt{font-size:9px;color:#6b6b74}
            .note{font-size:12px;color:#6b6b74;margin:2px 0 10px}
            .foot{color:#9a9aa2;font-size:11px;margin-top:24px;text-align:center}
            """.trimIndent()
        )
        sb.append("</style></head><body><div class=\"wrap\">")

        val title = if (profile.hasName) "${trS("Αναφορά")} — ${esc(profile.name)}"
        else "${trS("Αναφορά")} — ${trS("Γιατί Κλαίει;")}"
        sb.append("<h1>$title</h1>")
        sb.append("<p class=\"sub\">${trS("Δημιουργήθηκε")} ${esc(df.format(Date()))}</p>")

        // Summary cards
        sb.append("<div class=\"cards\">")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.totalCries}</div><div class=\"l\">${trS("Κλάματα")}</div></div>")
        val accTxt = stats.accuracy?.let { "${Math.round(it * 100)}%" } ?: "—"
        sb.append("<div class=\"card\"><div class=\"n\">$accTxt</div><div class=\"l\">${trS("Ακρίβεια (feedback)")}</div></div>")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.confirmedCount}</div><div class=\"l\">${trS("Επιβεβαιώσεις")}</div></div>")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.feedbackCount}</div><div class=\"l\">${trS("Δείγματα εκμάθησης")}</div></div>")
        sb.append("</div>")

        // ---- Long-term overview: the picture that only emerges after days/weeks of use. ----
        if (cries.isNotEmpty()) {
            val cal = java.util.Calendar.getInstance()
            val perHour = IntArray(24)
            val perDow = IntArray(7)
            for (e in cries) {
                cal.timeInMillis = e.timestamp
                perHour[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
                perDow[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]++
            }
            val maxHour = (perHour.maxOrNull() ?: 0).coerceAtLeast(1)
            val peakHour = perHour.indexOf(perHour.maxOrNull() ?: 0)
            val busiestDow = perDow.indexOf(perDow.maxOrNull() ?: 0)
            val firstTs = cries.minOf { it.timestamp }
            val daysTracked = (((System.currentTimeMillis() - firstTs) / 86_400_000L) + 1).toInt().coerceAtLeast(1)
            val avgPerDay = cries.size.toFloat() / daysTracked
            val dowNames = java.text.DateFormatSymbols(
                if (lang == AppLang.EN) Locale.ENGLISH else Locale("el"),
            ).weekdays
            val avgFeedGapMs = if (feedings.size >= 2) {
                var sum = 0L // feedings come DESC by timestamp
                for (i in 0 until feedings.size - 1) sum += (feedings[i].timestamp - feedings[i + 1].timestamp)
                sum / (feedings.size - 1)
            } else null

            sb.append("<h2>${trS("Συνολική εικόνα")}</h2>")
            sb.append("<div class=\"row\"><span>${trS("Ημέρες καταγραφής")}</span><span>$daysTracked</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Μέσος όρος κλαμάτων/ημέρα")}</span><span>${"%.1f".format(avgPerDay)}</span></div>")
            val peakTxt = "%02d:00–%02d:00".format(peakHour, (peakHour + 1) % 24)
            sb.append("<div class=\"row\"><span>${trS("Ώρα αιχμής")}</span><span>$peakTxt</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Πιο δύσκολη ημέρα")}</span><span>${esc(dowNames.getOrElse(busiestDow + 1) { "" })}</span></div>")
            if (avgFeedGapMs != null) {
                val hours = avgFeedGapMs / 3_600_000L
                val minutes = (avgFeedGapMs % 3_600_000L) / 60_000L
                val fg = when (lang) {
                    AppLang.EN -> "${hours}h ${minutes}m"
                    AppLang.EL -> "${hours}ω ${minutes}λ"
                }
                sb.append("<div class=\"row\"><span>${trS("Μέσο διάστημα ταϊσμάτων")}</span><span>$fg</span></div>")
            }

            // Hour-of-day heatmap
            sb.append("<h2>${trS("Κλάμα ανά ώρα της ημέρας")}</h2>")
            sb.append("<p class=\"note\">${trS("Πιο σκούρο = περισσότερα κλάματα εκείνη την ώρα.")}</p>")
            sb.append("<div class=\"heat\">")
            for (h in 0 until 24) {
                val a = if (perHour[h] == 0) 0.06 else 0.25 + 0.75 * (perHour[h].toDouble() / maxHour)
                sb.append("<div class=\"c\" style=\"background:rgba(103,80,164,${"%.2f".format(a)})\"></div>")
            }
            sb.append("</div>")
            sb.append("<div class=\"axis\"><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div>")

            // Cries per day (last 14) - shows the trend over the past two weeks.
            val dayMs = 86_400_000L
            cal.timeInMillis = System.currentTimeMillis()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            val dayFmt = SimpleDateFormat("d/M", if (lang == AppLang.EN) Locale.ENGLISH else Locale("el"))
            val perDay = (13 downTo 0).map { back ->
                val start = todayStart - back * dayMs
                dayFmt.format(Date(start)) to cries.count { it.timestamp in start until (start + dayMs) }
            }
            val maxDay = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            sb.append("<h2>${trS("Κλάματα ανά ημέρα (τελευταίες 14)")}</h2>")
            sb.append("<div class=\"days\">")
            for ((label, count) in perDay) {
                val hpx = (6 + 74.0 * count / maxDay).toInt()
                sb.append("<div class=\"col\"><div class=\"cnt\">$count</div><div class=\"b\" style=\"height:${hpx}px\"></div><div class=\"lbl\">${esc(label)}</div></div>")
            }
            sb.append("</div>")
        }

        // ---- Diapers: counts, poop frequency and the two-week trend. ----
        if (diapers.isNotEmpty()) {
            val dayMs = 86_400_000L
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = System.currentTimeMillis()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            val firstTs = diapers.minOf { it.timestamp }
            val daysTracked = (((System.currentTimeMillis() - firstTs) / dayMs) + 1).toInt().coerceAtLeast(1)
            val poops = diapers.count { DiaperType.fromNameOrNull(it.type)?.hasStool == true }
            val avgPerDay = diapers.size.toFloat() / daysTracked
            val poopsPerDay = poops.toFloat() / daysTracked

            sb.append("<h2>${trS("Πάνες")}</h2>")
            sb.append("<div class=\"row\"><span>${trS("Σύνολο αλλαγών")}</span><span>${diapers.size}</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Μέσος όρος αλλαγών/ημέρα")}</span><span>${"%.1f".format(avgPerDay)}</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Κακά (σύνολο)")}</span><span>$poops</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Κακά ανά ημέρα (μ.ο.)")}</span><span>${"%.1f".format(poopsPerDay)}</span></div>")

            val dayFmt = SimpleDateFormat("d/M", if (lang == AppLang.EN) Locale.ENGLISH else Locale("el"))
            val perDay = (13 downTo 0).map { back ->
                val start = todayStart - back * dayMs
                dayFmt.format(Date(start)) to diapers.count { it.timestamp in start until (start + dayMs) }
            }
            val maxDay = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            sb.append("<h2>${trS("Πάνες ανά ημέρα (τελευταίες 14)")}</h2>")
            sb.append("<div class=\"days\">")
            for ((label, count) in perDay) {
                val hpx = (6 + 74.0 * count / maxDay).toInt()
                sb.append("<div class=\"col\"><div class=\"cnt\">$count</div><div class=\"b\" style=\"height:${hpx}px\"></div><div class=\"lbl\">${esc(label)}</div></div>")
            }
            sb.append("</div>")
        }

        // ---- Tummy time: totals, today vs age-goal and the two-week trend. ----
        if (tummy.isNotEmpty()) {
            val dayMs = 86_400_000L
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = System.currentTimeMillis()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            val firstTs = tummy.minOf { it.timestamp }
            val daysTracked = (((System.currentTimeMillis() - firstTs) / dayMs) + 1).toInt().coerceAtLeast(1)
            val avgPerDay = tummy.size.toFloat() / daysTracked
            val doneToday = tummy.count { it.timestamp >= todayStart }
            val goal = tummyDailyGoal()

            sb.append("<h2>${trS("Tummy Time")}</h2>")
            sb.append("<div class=\"row\"><span>${trS("Σύνολο συνεδριών")}</span><span>${tummy.size}</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Μέσος όρος/ημέρα")}</span><span>${"%.1f".format(avgPerDay)}</span></div>")
            sb.append("<div class=\"row\"><span>${trS("Σήμερα")}</span><span>$doneToday / $goal</span></div>")

            val dayFmt = SimpleDateFormat("d/M", if (lang == AppLang.EN) Locale.ENGLISH else Locale("el"))
            val perDay = (13 downTo 0).map { back ->
                val start = todayStart - back * dayMs
                dayFmt.format(Date(start)) to tummy.count { it.timestamp in start until (start + dayMs) }
            }
            val maxDay = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            sb.append("<h2>${trS("Tummy time ανά ημέρα (τελευταίες 14)")}</h2>")
            sb.append("<div class=\"days\">")
            for ((label, count) in perDay) {
                val hpx = (6 + 74.0 * count / maxDay).toInt()
                sb.append("<div class=\"col\"><div class=\"cnt\">$count</div><div class=\"b\" style=\"height:${hpx}px\"></div><div class=\"lbl\">${esc(label)}</div></div>")
            }
            sb.append("</div>")
        }

        // Reason breakdown
        val distTotal = stats.predictedDistribution.sum().coerceAtLeast(1)
        sb.append("<h2>${trS("Κατανομή αιτιών")}</h2>")
        stats.labels.forEachIndexed { i, reason ->
            val c = stats.predictedDistribution.getOrElse(i) { 0 }
            val pct = 100 * c / distTotal
            sb.append("<div class=\"row\"><span>${esc(reason.emoji + " " + trS(reason.displayName))}</span><span>$c ($pct%)</span></div>")
            sb.append("<div class=\"bar\"><span style=\"width:$pct%\"></span></div>")
        }

        // Table of recent events
        sb.append("<h2>${trS("Καταγραφές")} (${cries.size})</h2>")
        sb.append("<table><tr><th>${trS("Ώρα")}</th><th>${trS("Αιτία")}</th><th>${trS("Βεβαιότητα")}</th><th>${trS("Ανατροφοδότηση")}</th></tr>")
        for (e in cries.take(300)) {
            val pred = e.predictedIndex.takeIf { it in labels.indices }?.let { labels[it] }
            val predTxt = pred?.let { esc(it.emoji + " " + trS(it.displayName)) } ?: "—"
            val confTxt = "${Math.round(e.confidence * 100)}%"
            val fb = when {
                e.confirmedIndex == null -> "—"
                e.confirmedIndex == e.predictedIndex -> "<span class=\"ok\">${trS("✓ σωστό")}</span>"
                else -> {
                    val corr = e.confirmedIndex!!.takeIf { it in labels.indices }?.let { trS(labels[it].displayName) } ?: ""
                    "<span class=\"corr\">✎ ${esc(corr)}</span>"
                }
            }
            sb.append("<tr><td>${esc(df.format(Date(e.timestamp)))}</td><td>$predTxt</td><td>$confTxt</td><td>$fb</td></tr>")
        }
        sb.append("</table>")

        sb.append("<p class=\"foot\">${trS("«Γιατί Κλαίει;» — Δημιουργήθηκε από τον Μάριο Θεοτή. Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή.")}</p>")
        sb.append("</div></body></html>")
        sb.toString()
    }

    // ---- Backup / restore (JSON, all on-device data) -------------------------

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val profile = getProfile()
        root.put("profile", JSONObject().apply {
            put("name", profile.name)
            profile.birthMillis?.let { put("birthMillis", it) }
            put("colicConfirmed", profile.colicConfirmed)
        })
        // All babies (multi-profile). "profile" above stays for backwards compatibility.
        val profilesArr = JSONArray()
        for (p in getProfiles()) {
            profilesArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.birthMillis?.let { put("birthMillis", it) }
                put("colicConfirmed", p.colicConfirmed)
            })
        }
        root.put("profiles", profilesArr)
        root.put("activeProfile", profile.id)

        val eventsArr = JSONArray()
        for (e in cryDao.allEvents()) {
            eventsArr.put(JSONObject().apply {
                put("id", e.id)
                put("timestamp", e.timestamp)
                put("cryDetected", e.cryDetected)
                put("predictedIndex", e.predictedIndex)
                if (e.confirmedIndex != null) put("confirmedIndex", e.confirmedIndex)
                put("confidence", e.confidence.toDouble())
                put("engine", e.engine)
                put("gateScore", e.gateScore.toDouble())
            })
        }
        root.put("events", eventsArr)

        val clipsArr = JSONArray()
        for (e in cryDao.allEvents()) {
            val wavBytes = clipStore.readClipBytes(e.id)
            val embeddingBytes = clipStore.readEmbeddingBytes(e.id)
            if (wavBytes != null || embeddingBytes != null) {
                clipsArr.put(JSONObject().apply {
                    put("eventId", e.id)
                    wavBytes?.let { put("wav", Base64.encodeToString(it, Base64.NO_WRAP)) }
                    embeddingBytes?.let { put("embedding", Base64.encodeToString(it, Base64.NO_WRAP)) }
                })
            }
        }
        root.put("clips", clipsArr)

        val fbArr = JSONArray()
        for (f in feedbackDao.all()) {
            fbArr.put(JSONObject().apply {
                put("timestamp", f.timestamp)
                put("labelIndex", f.labelIndex)
                put("embedding", encodeFloats(f.embedding))
                put("sourceEventId", f.sourceEventId)
            })
        }
        root.put("feedback", fbArr)

        val feedArr = JSONArray()
        for (fe in feedingDao.allList()) {
            feedArr.put(JSONObject().apply {
                put("timestamp", fe.timestamp)
                fe.note?.let { put("note", it) }
            })
        }
        root.put("feedings", feedArr)

        val diaperArr = JSONArray()
        for (de in diaperDao.allList()) {
            diaperArr.put(JSONObject().apply {
                put("timestamp", de.timestamp)
                put("type", de.type)
            })
        }
        root.put("diapers", diaperArr)

        val tummyArr = JSONArray()
        for (te in tummyDao.allList()) {
            tummyArr.put(JSONObject().apply {
                put("timestamp", te.timestamp)
            })
        }
        root.put("tummy", tummyArr)

        root.toString()
    }

    /** Replaces all local data with the backup's contents, then rebuilds personalization. */
    suspend fun importBackupJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)

        val profilesArr = root.optJSONArray("profiles")
        if (profilesArr != null && profilesArr.length() > 0) {
            val list = (0 until profilesArr.length()).map { i ->
                val o = profilesArr.getJSONObject(i)
                BabyProfile(
                    name = o.optString("name", ""),
                    birthMillis = if (o.has("birthMillis")) o.optLong("birthMillis") else null,
                    id = o.optString("id", "").ifBlank { newProfileId() },
                    colicConfirmed = o.optBoolean("colicConfirmed", false),
                )
            }
            val active = root.optString("activeProfile", "")
            persistProfiles(list, if (list.any { it.id == active }) active else list.first().id)
        } else {
            root.optJSONObject("profile")?.let { p ->
                val one = BabyProfile(
                    name = p.optString("name", ""),
                    birthMillis = if (p.has("birthMillis")) p.optLong("birthMillis") else null,
                    id = newProfileId(),
                    colicConfirmed = p.optBoolean("colicConfirmed", false),
                )
                persistProfiles(listOf(one), one.id)
            }
        }

        cryDao.clear()
        feedbackDao.clear()
        feedingDao.clear()
        diaperDao.clear()
        tummyDao.clear()
        // Old clips are keyed by the previous event ids, which no longer match.
        clipStore.clearAll()
        clearPending()

        var restored = 0
        val eventIdMap = HashMap<Long, Long>()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val newId = cryDao.insert(
                    CryEvent(
                        timestamp = o.getLong("timestamp"),
                        cryDetected = o.optBoolean("cryDetected", true),
                        predictedIndex = o.optInt("predictedIndex", -1),
                        confirmedIndex = if (o.has("confirmedIndex")) o.getInt("confirmedIndex") else null,
                        confidence = o.optDouble("confidence", 0.0).toFloat(),
                        engine = o.optString("engine", "MODEL"),
                        gateScore = o.optDouble("gateScore", 0.0).toFloat(),
                    )
                )
                val oldId = o.optLong("id", 0L)
                if (oldId > 0L) eventIdMap[oldId] = newId
                restored++
            }
        }
        root.optJSONArray("clips")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val oldEventId = o.optLong("eventId", 0L)
                val newEventId = eventIdMap[oldEventId]
                if (newEventId != null) {
                    val wavBytes = o.optString("wav", "")
                        .takeIf { it.isNotBlank() }
                        ?.let { Base64.decode(it, Base64.NO_WRAP) }
                    val embeddingBytes = o.optString("embedding", "")
                        .takeIf { it.isNotBlank() }
                        ?.let { Base64.decode(it, Base64.NO_WRAP) }
                    clipStore.restoreClipBytes(newEventId, wavBytes, embeddingBytes)
                }
            }
        }
        root.optJSONArray("feedback")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val oldSourceEventId = o.optLong("sourceEventId", 0L)
                val restoredSourceEventId = eventIdMap[oldSourceEventId] ?: 0L
                feedbackDao.insert(
                    FeedbackExample(
                        timestamp = o.getLong("timestamp"),
                        labelIndex = o.getInt("labelIndex"),
                        embedding = decodeFloats(o.getString("embedding")),
                        sourceEventId = restoredSourceEventId,
                    )
                )
            }
        }
        root.optJSONArray("feedings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                feedingDao.insert(
                    FeedingEvent(
                        timestamp = o.getLong("timestamp"),
                        note = if (o.has("note")) o.getString("note") else null,
                    )
                )
            }
        }
        root.optJSONArray("diapers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                diaperDao.insert(
                    DiaperEvent(
                        timestamp = o.getLong("timestamp"),
                        type = o.optString("type", DiaperType.WET.name),
                    )
                )
            }
        }
        root.optJSONArray("tummy")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tummyDao.insert(TummyTimeEvent(timestamp = o.getLong("timestamp")))
            }
        }

        rebuildPersonalization()
        restored
    }

    private fun encodeFloats(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun decodeFloats(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    private suspend fun contextMultipliers(): FloatArray {
        val hours = hoursSinceLastFeed()
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        val profile = getProfile()
        return com.babycry.analyzer.context.ContextPrior.multipliers(
            hoursSinceFeed = hours,
            hourOfDay = hour,
            ageMonths = profile.ageMonths(),
            ageDays = profile.ageDays(),
            colicConfirmed = profile.colicConfirmed,
        )
    }

    companion object {
        private const val PROFILE_NAME = "baby_name"
        private const val PROFILE_BIRTH = "baby_birth_millis"
        private const val PROFILES = "profiles_json"
        private const val ACTIVE_PROFILE = "active_profile_id"
        private const val ONBOARDING_DONE = "onboarding_done"
        private const val PERSONALIZATION_ON = "personalization_on"
        private const val PENDING_ID = "pending_confirm_id"
        private const val PENDING_AT = "pending_confirm_at"
        private const val APP_LANG = "app_lang"
        private const val TUMMY_REMINDER_ON = "tummy_reminder_on"
        private const val TUMMY_REMINDER_HOUR_AM = "tummy_reminder_hour_am"
        private const val TUMMY_REMINDER_HOUR_PM = "tummy_reminder_hour_pm"

        @Volatile
        private var instance: CryRepository? = null

        fun get(context: Context): CryRepository =
            instance ?: synchronized(this) {
                instance ?: CryRepository(
                    context.applicationContext,
                    CryAnalyzer(context.applicationContext),
                ).also { instance = it }
            }
    }
}
