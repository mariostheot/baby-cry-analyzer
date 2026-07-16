package com.babycry.analyzer.data

import android.content.Context
import android.util.Base64
import com.babycry.analyzer.insights.CareCryRecord
import com.babycry.analyzer.insights.CareDiaperRecord
import com.babycry.analyzer.insights.CareFeedRecord
import com.babycry.analyzer.insights.CareInsightEngine
import com.babycry.analyzer.insights.CareInsightInput
import com.babycry.analyzer.insights.CareInsightSummary
import com.babycry.analyzer.insights.CareSleepRecord
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.ml.CryAnalyzer
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.BabyGender
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
    val validationCount: Int,
    val validationBaseAccuracy: Float?,
    val validationPersonalizedAccuracy: Float?,
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
    private val sleepDao = db.sleepDao()
    private val profilePrefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val clipStore = ClipStore(context)

    init {
        currentAppLang = getLanguage()
    }

    val labels: List<CryReason> get() = analyzer.labels
    val hasModel: Boolean get() = analyzer.hasModel
    val tier2Available: Boolean get() = analyzer.personalization.tier2Available

    fun recentEvents(profileId: String = activeProfileId()): Flow<List<CryEvent>> = cryDao.recent(profileId)
    fun feedbackCount(profileId: String = activeProfileId()): Flow<Int> = feedbackDao.count(profileId)
    fun recentFeedings(profileId: String = activeProfileId()): Flow<List<FeedingEvent>> = feedingDao.recent(profileId)
    fun recentDiapers(profileId: String = activeProfileId()): Flow<List<DiaperEvent>> = diaperDao.recent(profileId)
    fun recentTummy(profileId: String = activeProfileId()): Flow<List<TummyTimeEvent>> = tummyDao.recent(profileId)
    fun recentSleep(profileId: String = activeProfileId()): Flow<List<SleepEvent>> = sleepDao.recent(profileId)

    suspend fun assignLegacyDataToActiveProfile() = withContext(Dispatchers.IO) {
        val profileId = activeProfileId()
        if (profileId.isBlank()) return@withContext
        cryDao.assignLegacy(profileId)
        feedbackDao.assignLegacy(profileId)
        feedingDao.assignLegacy(profileId)
        diaperDao.assignLegacy(profileId)
        tummyDao.assignLegacy(profileId)
        sleepDao.assignLegacy(profileId)
    }

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
        val all = feedbackDao.all(activeProfileId()).filterNot { it.isValidationHoldout }
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
            val profileId = activeProfileId()
            val r = analysis.result
            val predicted = r.topReason?.let { labels.indexOf(it) } ?: -1
            val id = cryDao.insert(
                CryEvent(
                    profileId = profileId,
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
        val wasValidationHoldout = feedbackDao.validationForEvent(eventId) ?: false
        feedbackDao.deleteByEvent(eventId)

        val emb = embedding ?: clipStore.readEmbedding(eventId)
        if (emb != null) {
            feedbackDao.insert(
                FeedbackExample(
                    profileId = event.profileId.ifBlank { activeProfileId() },
                    timestamp = System.currentTimeMillis(),
                    labelIndex = idx,
                    embedding = emb,
                    sourceEventId = eventId,
                    // The first few confirmed examples in every class form a fixed test set.
                    // Keep this assignment when the parent later corrects the same recording.
                    isValidationHoldout = wasValidationHoldout ||
                        feedbackDao.validationCount(
                            event.profileId.ifBlank { activeProfileId() },
                            idx,
                        ) < PERSONAL_VALIDATION_PER_CLASS,
                )
            )
        }
        // Rebuild the personalization from the current (clean) set - covers both the added
        // corrected example and the case where we only removed a stale one.
        rebuildPersonalization()
        removePending(event.profileId.ifBlank { activeProfileId() }, eventId)
    }

    // ---- Delayed confirmation ("why did the baby cry?") ----------------------

    /** The oldest detected cry still awaiting the parent's confirmation, or null. */
    suspend fun pendingConfirmation(profileId: String = activeProfileId()): CryEvent? = withContext(Dispatchers.IO) {
        migrateLegacyPendingIfNeeded()
        val validIds = mutableListOf<Long>()
        var first: CryEvent? = null
        for (id in pendingIds(profileId)) {
            val event = cryDao.byId(id)
            if (event != null && event.profileId == profileId && event.cryDetected && event.confirmedIndex == null) {
                validIds += id
                if (first == null) first = event
            }
        }
        writePendingIds(profileId, validIds)
        first
    }

    /** True only while the given event is still queued for delayed confirmation. */
    suspend fun pendingConfirmationForEvent(profileId: String, eventId: Long): CryEvent? =
        withContext(Dispatchers.IO) {
            migrateLegacyPendingIfNeeded()
            if (eventId !in pendingIds(profileId)) return@withContext null
            val event = cryDao.byId(eventId)
            if (event == null || event.profileId != profileId || !event.cryDetected || event.confirmedIndex != null) {
                removePending(profileId, eventId)
                null
            } else {
                event
            }
        }

    fun dismissPending(profileId: String = activeProfileId()) {
        pendingIds(profileId).firstOrNull()?.let { removePending(profileId, it) }
    }

    fun pendingEventIds(profileId: String): List<Long> = pendingIds(profileId)

    fun focusPendingEvent(profileId: String, eventId: Long) {
        val ids = pendingIds(profileId)
        if (eventId in ids) writePendingIds(profileId, listOf(eventId) + ids.filterNot { it == eventId })
    }

    suspend fun profileIdForEvent(eventId: Long): String? =
        withContext(Dispatchers.IO) { cryDao.byId(eventId)?.profileId }

    private fun setPending(eventId: Long, profileId: String = activeProfileId()) {
        val ids = pendingIds(profileId).toMutableList()
        if (eventId !in ids) ids += eventId
        writePendingIds(profileId, ids)
    }

    private fun removePending(profileId: String, eventId: Long) {
        writePendingIds(profileId, pendingIds(profileId).filterNot { it == eventId })
    }

    private fun clearPending(profileId: String) {
        profilePrefs.edit().remove(pendingIdsKey(profileId)).apply()
    }

    private fun pendingIds(profileId: String): List<Long> =
        runCatching {
            val arr = JSONArray(profilePrefs.getString(pendingIdsKey(profileId), "[]"))
            (0 until arr.length()).mapNotNull { i -> arr.optLong(i, 0L).takeIf { it > 0L } }
        }.getOrDefault(emptyList())

    private fun writePendingIds(profileId: String, ids: List<Long>) {
        val unique = ids.distinct()
        val encoded = JSONArray().apply { unique.forEach(::put) }.toString()
        profilePrefs.edit().apply {
            if (unique.isEmpty()) remove(pendingIdsKey(profileId))
            else putString(pendingIdsKey(profileId), encoded)
        }.apply()
    }

    private suspend fun migrateLegacyPendingIfNeeded() {
        val legacyId = profilePrefs.getLong(PENDING_ID, 0L)
        if (legacyId <= 0L) return
        val legacyEvent = cryDao.byId(legacyId)
        if (legacyEvent != null && legacyEvent.cryDetected && legacyEvent.confirmedIndex == null) {
            val profileId = legacyEvent.profileId.ifBlank { activeProfileId() }
            setPending(legacyId, profileId)
            clearLegacyPending()
        } else {
            clearLegacyPending()
        }
    }

    private fun clearLegacyPending() {
        profilePrefs.edit().remove(PENDING_ID).remove(PENDING_AT).apply()
    }

    private fun clearAllPending() {
        val editor = profilePrefs.edit()
        for (key in profilePrefs.all.keys) {
            if (key == PENDING_ID || key == PENDING_AT ||
                key.startsWith("$PENDING_ID:") || key.startsWith("$PENDING_AT:") ||
                key.startsWith("$PENDING_IDS:")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /** Starts one timed feeding session, or returns the one already running for this baby. */
    suspend fun startFeeding(profileId: String = activeProfileId()): FeedingEvent = withContext(Dispatchers.IO) {
        feedingDao.startIfNone(
            FeedingEvent(
                profileId = profileId,
                timestamp = System.currentTimeMillis(),
                durationMs = -1,
            ),
        )
    }

    /** Stops the currently running session and returns it with its measured duration. */
    suspend fun stopFeeding(profileId: String = activeProfileId()): FeedingEvent? = withContext(Dispatchers.IO) {
        val active = feedingDao.inProgress(profileId) ?: return@withContext null
        val durationMs = (System.currentTimeMillis() - active.timestamp).coerceAtLeast(0L)
        if (feedingDao.complete(active.id, profileId, durationMs) == 0) return@withContext null
        active.copy(durationMs = durationMs)
    }

    /** Allows a parent to correct a completed session's start time and measured duration. */
    suspend fun updateFeeding(
        eventId: Long,
        startedAt: Long,
        durationMs: Long,
        profileId: String = activeProfileId(),
    ): Boolean = withContext(Dispatchers.IO) {
        feedingDao.updateCompleted(
            id = eventId,
            profileId = profileId,
            timestamp = startedAt,
            durationMs = durationMs.coerceAtLeast(0L),
        ) > 0
    }

    suspend fun activeFeeding(profileId: String = activeProfileId()): FeedingEvent? =
        withContext(Dispatchers.IO) { feedingDao.inProgress(profileId) }

    /** Kept for callers/imports that record a feed without a measured duration. */
    suspend fun logFeeding(note: String? = null) = withContext(Dispatchers.IO) {
        feedingDao.insert(FeedingEvent(profileId = activeProfileId(), timestamp = System.currentTimeMillis(), note = note))
    }

    suspend fun logDiaper(type: DiaperType) = withContext(Dispatchers.IO) {
        diaperDao.insert(DiaperEvent(profileId = activeProfileId(), timestamp = System.currentTimeMillis(), type = type.name))
    }

    suspend fun logTummy() = withContext(Dispatchers.IO) {
        tummyDao.insert(TummyTimeEvent(profileId = activeProfileId(), timestamp = System.currentTimeMillis()))
    }

    /** Starts one timed sleep session, or returns the one already running for this baby. */
    suspend fun startSleep(profileId: String = activeProfileId()): SleepEvent = withContext(Dispatchers.IO) {
        sleepDao.startIfNone(
            SleepEvent(
                profileId = profileId,
                timestamp = System.currentTimeMillis(),
                durationMs = -1,
            ),
        )
    }

    /** Stops the currently running sleep session and returns it with its measured duration. */
    suspend fun stopSleep(profileId: String = activeProfileId()): SleepEvent? = withContext(Dispatchers.IO) {
        val active = sleepDao.inProgress(profileId) ?: return@withContext null
        val durationMs = (System.currentTimeMillis() - active.timestamp).coerceAtLeast(0L)
        if (sleepDao.complete(active.id, profileId, durationMs) == 0) return@withContext null
        active.copy(durationMs = durationMs)
    }

    /** Allows a parent to correct a completed sleep session's start time and measured duration. */
    suspend fun updateSleep(
        eventId: Long,
        startedAt: Long,
        durationMs: Long,
        profileId: String = activeProfileId(),
    ): Boolean = withContext(Dispatchers.IO) {
        sleepDao.updateCompleted(
            id = eventId,
            profileId = profileId,
            timestamp = startedAt,
            durationMs = durationMs.coerceAtLeast(0L),
        ) > 0
    }

    suspend fun activeSleep(profileId: String = activeProfileId()): SleepEvent? =
        withContext(Dispatchers.IO) { sleepDao.inProgress(profileId) }

    // ---- Tummy time: age-based daily goal + reminder --------------------------

    /** Recommended tummy-time sessions per day for the active baby's age. */
    fun tummyDailyGoal(profileId: String = activeProfileId()): Int =
        com.babycry.analyzer.model.TummyTime.dailyGoal(
            (profileById(profileId) ?: getProfile()).ageDays(),
        )

    /** How many tummy-time sessions have been logged since the start of today. */
    suspend fun tummyDoneToday(profileId: String = activeProfileId()): Int = withContext(Dispatchers.IO) {
        tummyDao.countSince(profileId, startOfToday())
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
        // While a feed is underway the baby is, for the model's purpose, being fed "now".
        // This keeps the hunger prior from rising based on the previous completed session.
        if (feedingDao.inProgress(activeProfileId()) != null) return@withContext 0f
        feedingDao.lastCompleted(activeProfileId())?.let {
            (System.currentTimeMillis() - it.completedAt()) / 3_600_000f
        }
    }

    suspend fun lastFeedTimestamp(profileId: String = activeProfileId()): Long? = withContext(Dispatchers.IO) {
        // Also makes an alarm already being delivered harmless if the parent started feeding
        // just as it fired.
        if (feedingDao.inProgress(profileId) != null) null
        else feedingDao.lastCompleted(profileId)?.completedAt()
    }

    /**
     * When to fire the "feeding time is near" heads-up, as (delayFromNowMs, lastFeedTimestamp),
     * or null if we can't/shouldn't (no feed logged yet, or the lead time already passed). Uses
     * the age-appropriate feeding interval and warns [FeedReminder.LEAD_MINUTES] before it.
     */
    suspend fun feedReminderPlan(profileId: String = activeProfileId()): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        // Never notify while a feeding timer is already running; stopping it will schedule the
        // next reminder from the actual end of that session.
        if (feedingDao.inProgress(profileId) != null) return@withContext null
        val last = feedingDao.lastCompleted(profileId)?.completedAt() ?: return@withContext null
        val profile = profileById(profileId) ?: return@withContext null
        val intervalHours = com.babycry.analyzer.context.ContextPrior
            .expectedFeedIntervalHours(profile.ageMonths(), profile.ageDays())
        val remindAt = last + (intervalHours * 3_600_000L).toLong() -
            com.babycry.analyzer.notify.FeedReminder.LEAD_MINUTES * 60_000L
        val delay = remindAt - System.currentTimeMillis()
        if (delay <= 0L) null else delay to last
    }

    private fun FeedingEvent.completedAt(): Long = timestamp + durationMs.coerceAtLeast(0L)

    suspend fun resetPersonalization() = withContext(Dispatchers.Default) {
        // Keep the independent personal holdout so the stats screen can still compare a reset
        // model with a personalized one. Full history/data reset still removes everything.
        feedbackDao.clearTraining(activeProfileId())
        analyzer.personalization.reset()
        analyzer.personalization.updatePrototypes(emptyList())
    }

    /**
     * Local, evidence-gated care-pattern observations for the active baby. Loads the full
     * profile-scoped histories (completed feeds/diapers/sleeps and detected cries) and runs
     * the pure [CareInsightEngine] — no derived records are stored.
     */
    suspend fun careInsights(profileId: String = activeProfileId()): CareInsightSummary =
        withContext(Dispatchers.Default) {
            val lang = getLanguage()
            // A zero duration is an unknown/legacy marker, not a completed timed session.
            val feedings = feedingDao.allList(profileId).filter { it.durationMs > 0L }
            val diapers = diaperDao.allList(profileId)
            val sleeps = sleepDao.allList(profileId).filter { it.durationMs > 0L }
            val cries = cryDao.allEvents(profileId)

            CareInsightEngine.compute(
                input = CareInsightInput(
                    feeds = feedings.map { CareFeedRecord(it.timestamp, it.durationMs) },
                    diapers = diapers.map { event ->
                        val type = DiaperType.fromNameOrNull(event.type)
                        CareDiaperRecord(
                            timestamp = event.timestamp,
                            hasStool = type?.hasStool == true,
                            isWet = type == DiaperType.WET || type == DiaperType.MIXED,
                        )
                    },
                    sleeps = sleeps.map { CareSleepRecord(it.timestamp, it.durationMs) },
                    cries = cries.map { event ->
                        CareCryRecord(
                            timestamp = event.timestamp,
                            cryDetected = event.cryDetected,
                            confirmedReason = event.confirmedIndex
                                ?.takeIf { it in labels.indices }
                                ?.let { labels[it] },
                            predictedReason = event.predictedIndex
                                .takeIf { it in labels.indices }
                                ?.let { labels[it] },
                        )
                    },
                ),
                lang = lang,
            )
        }

    suspend fun stats(): StatsSummary = withContext(Dispatchers.Default) {
        val profileId = activeProfileId()
        val n = labels.size
        val confusion = Array(n) { IntArray(n) }
        val confirmed = cryDao.confirmedEvents(profileId)
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
        for (e in cryDao.allEvents(profileId)) {
            if (!e.cryDetected) continue
            cries++
            // Prefer the parent's correction when present; else the model's prediction.
            val idx = e.confirmedIndex?.takeIf { it in 0 until n } ?: e.predictedIndex
            if (idx in 0 until n) distribution[idx]++
        }
        val validation = feedbackDao.all(profileId).filter { it.isValidationHoldout }
        var validationBaseCorrect = 0
        var validationPersonalizedCorrect = 0
        var validationScored = 0
        for (example in validation) {
            val (base, personalized) = analyzer.comparePersonalization(example.embedding) ?: continue
            validationScored++
            if (base == example.labelIndex) validationBaseCorrect++
            if (personalized == example.labelIndex) validationPersonalizedCorrect++
        }

        StatsSummary(
            labels = labels,
            confirmedCount = total,
            accuracy = if (total > 0) correct.toFloat() / total else null,
            perClassRecall = (0 until n).map {
                if (perClassTotal[it] > 0) perClassCorrect[it].toFloat() / perClassTotal[it] else null
            },
            confusion = confusion,
            feedbackCount = feedbackDao.countNow(profileId),
            hasModel = hasModel,
            tier2Available = tier2Available,
            tier2Ready = analyzer.personalization.tier2Ready,
            predictedDistribution = distribution,
            totalCries = cries,
            validationCount = validation.size,
            validationBaseAccuracy = validationScored.takeIf { it > 0 }
                ?.let { validationBaseCorrect.toFloat() / it },
            validationPersonalizedAccuracy = validationScored.takeIf { it > 0 }
                ?.let { validationPersonalizedCorrect.toFloat() / it },
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

    fun currentProfileId(): String = activeProfileId()

    fun profileById(profileId: String): BabyProfile? =
        getProfiles().firstOrNull { it.id == profileId }

    fun hasProfile(profileId: String): Boolean =
        profileById(profileId) != null

    private fun activeProfileId(): String = getProfile().id

    suspend fun addProfile(
        name: String,
        birthMillis: Long?,
        colicConfirmed: Boolean = false,
        gender: BabyGender = BabyGender.UNKNOWN,
    ): String = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val p = BabyProfile(
            name = name.trim(),
            birthMillis = birthMillis,
            gender = gender,
            id = newProfileId(),
            colicConfirmed = colicConfirmed,
        )
        list.add(p)
        persistProfiles(list, p.id)
        p.id
    }

    /** Edits the currently active baby (creating one if there isn't any yet). */
    suspend fun updateActiveProfile(
        name: String,
        birthMillis: Long?,
        gender: BabyGender = BabyGender.UNKNOWN,
    ) = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val activeId = getProfile().id
        val idx = list.indexOfFirst { activeId.isNotBlank() && it.id == activeId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.trim(), birthMillis = birthMillis, gender = gender)
            persistProfiles(list, list[idx].id)
        } else {
            val p = BabyProfile(name = name.trim(), birthMillis = birthMillis, gender = gender, id = newProfileId())
            list.add(p)
            persistProfiles(list, p.id)
        }
    }

    /** Backwards-compatible alias used by existing callers: edits the active baby. */
    suspend fun setProfile(profile: BabyProfile) =
        updateActiveProfile(profile.name, profile.birthMillis, profile.gender)

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
        clearProfileData(id)
        clearPending(id)
        val remaining = getProfiles().filterNot { it.id == id }
        val activeWas = getProfile().id
        val newActive = if (activeWas == id) remaining.firstOrNull()?.id else activeWas
        persistProfiles(remaining, newActive)
    }

    private suspend fun clearProfileData(profileId: String) {
        clearProfileHistory(profileId)
        feedbackDao.clear(profileId)
    }

    private suspend fun clearProfileHistory(profileId: String) {
        cryDao.allEvents(profileId).forEach { clipStore.deleteClip(it.id) }
        cryDao.clear(profileId)
        feedingDao.clear(profileId)
        diaperDao.clear(profileId)
        tummyDao.clear(profileId)
        sleepDao.clear(profileId)
    }

    private fun newProfileId(): String = java.util.UUID.randomUUID().toString()

    private fun parseProfiles(json: String): List<BabyProfile> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BabyProfile(
                name = o.optString("name", ""),
                birthMillis = if (o.has("birthMillis")) o.optLong("birthMillis") else null,
                gender = BabyGender.fromNameOrNull(o.optString("gender", null)),
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
                put("gender", p.gender.name)
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

    fun lastBackupAt(): Long = profilePrefs.getLong(LAST_BACKUP_AT, 0L)

    fun markBackupCreated() {
        profilePrefs.edit().putLong(LAST_BACKUP_AT, System.currentTimeMillis()).apply()
    }

    /** Clears the cry history and feeding log (what the Stats screen counts). Keeps what the
     *  model learned from you (feedback examples / personalization). Also drops the saved
     *  recordings, since they're tied to the deleted events. */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val profileId = activeProfileId()
        clearProfileHistory(profileId)
        clearPending(profileId)
    }

    /** Deletes a single cry event from the history (and its saved recording). */
    suspend fun deleteEvent(id: Long) = withContext(Dispatchers.IO) {
        val event = cryDao.byId(id)
        cryDao.deleteById(id)
        feedbackDao.deleteByEvent(id)
        clipStore.deleteClip(id)
        rebuildPersonalization()
        event?.profileId?.let { removePending(it, id) }
    }

    /**
     * Discards a fresh prediction when the parent immediately retries the same cry. Confirmed
     * records are deliberately protected: only an unconfirmed event can be removed this way.
     * Returns the owning profile so its scheduled confirmation alarm can be cancelled.
     */
    suspend fun discardUnconfirmedEvent(id: Long): String? = withContext(Dispatchers.IO) {
        val event = cryDao.byId(id) ?: return@withContext null
        if (event.confirmedIndex != null) return@withContext null
        cryDao.deleteById(id)
        feedbackDao.deleteByEvent(id)
        clipStore.deleteClip(id)
        val profileId = event.profileId.ifBlank { activeProfileId() }
        removePending(profileId, id)
        profileId
    }

    // ---- Personal dataset (exportable) ---------------------------------------

    /** (#confirmed clips, totalBytes) for the active baby's exportable dataset. */
    suspend fun datasetInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val events = cryDao.confirmedEvents(activeProfileId()).filter { clipStore.hasClip(it.id) }
        events.size to events.sumOf { clipStore.bytesFor(it.id) }
    }

    /** How many actual WAV recordings the full backup will include across all babies. */
    suspend fun backupRecordingCount(): Int = withContext(Dispatchers.IO) {
        cryDao.allEventsAllProfiles().count { clipStore.readClipBytes(it.id) != null }
    }

    /** Zips every confirmed clip + a labels.csv into [out]. Returns how many clips were written. */
    suspend fun writeDatasetZip(out: java.io.OutputStream): Int = withContext(Dispatchers.IO) {
        clipStore.writeDatasetZip(out, cryDao.allEvents(activeProfileId()), labels)
    }

    /** Confirmed cries that still have a saved recording - the parent's playable library. */
    suspend fun libraryEvents(): List<CryEvent> = withContext(Dispatchers.IO) {
        cryDao.confirmedEvents(activeProfileId()).filter { clipStore.hasClip(it.id) }
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
        val profileId = activeProfileId()
        val events = cryDao.allEvents(profileId)
        val feedings = feedingDao.allList(profileId)
        val diapers = diaperDao.allList(profileId)
        val tummy = tummyDao.allList(profileId)
        val stats = stats()
        val profile = getProfile()
        val cries = events.filter { it.cryDetected }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val sb = StringBuilder()
        val htmlLang = if (lang == AppLang.EN) "en" else "el"
        sb.append("<!DOCTYPE html><html lang=\"$htmlLang\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>${trS("Αναφορά")} — ${trS("NiniSense")}</title><style>")
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

        val profileNameForTitle = profile.displayNameNominative(lang == AppLang.EN)
        val title = if (profile.hasName) "${trS("Αναφορά")} — ${esc(profileNameForTitle)}"
        else "${trS("Αναφορά")} — ${trS("NiniSense")}"
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

        // ---- Compact doctor-visit summary: quick numbers a pediatrician may ask for. ----
        val nowMs = System.currentTimeMillis()
        val last24h = nowMs - 86_400_000L
        val last7d = nowMs - 7L * 86_400_000L
        val cries24 = cries.count { it.timestamp >= last24h }
        val cries7 = cries.count { it.timestamp >= last7d }
        val feeds24 = feedings.count { it.timestamp >= last24h && it.durationMs >= 0L }
        val feedingDuration24 = feedings
            .filter { it.timestamp >= last24h && it.durationMs > 0L }
            .sumOf { it.durationMs }
        val diapers24 = diapers.count { it.timestamp >= last24h }
        val poops24 = diapers.count {
            it.timestamp >= last24h && DiaperType.fromNameOrNull(it.type)?.hasStool == true
        }
        val tummy24 = tummy.count { it.timestamp >= last24h }
        sb.append("<h2>${trS("Σύνοψη για παιδίατρο")}</h2>")
        sb.append("<p class=\"note\">${trS("Σύντομη εικόνα για επίσκεψη: κλάματα, ταΐσματα, πάνες και tummy time από τα πρόσφατα δεδομένα.")}</p>")
        sb.append("<div class=\"row\"><span>${trS("Κλάματα τελευταίου 24ώρου")}</span><span>$cries24</span></div>")
        sb.append("<div class=\"row\"><span>${trS("Κλάματα τελευταίων 7 ημερών")}</span><span>$cries7</span></div>")
        sb.append("<div class=\"row\"><span>${trS("Ταΐσματα τελευταίου 24ώρου")}</span><span>$feeds24</span></div>")
        if (feedingDuration24 > 0L) {
            val feedHours = feedingDuration24 / 3_600_000L
            val feedMinutes = (feedingDuration24 % 3_600_000L) / 60_000L
            val feedDuration = when (lang) {
                AppLang.EN -> "${feedHours}h ${feedMinutes}m"
                AppLang.EL -> "${feedHours}ω ${feedMinutes}λ"
            }
            sb.append("<div class=\"row\"><span>${trS("Χρόνος ταΐσματος τελευταίου 24ώρου")}</span><span>$feedDuration</span></div>")
        }
        sb.append("<div class=\"row\"><span>${trS("Πάνες τελευταίου 24ώρου")}</span><span>$diapers24 (${trS("κακά")}: $poops24)</span></div>")
        sb.append("<div class=\"row\"><span>${trS("Tummy time τελευταίου 24ώρου")}</span><span>$tummy24</span></div>")
        sb.append("<p class=\"note\">${trS("Αν υπάρχουν κόκκινες σημαίες (πυρετός, δυσκολία στην αναπνοή, αφυδάτωση, ασυνήθιστο/επίμονο κλάμα), επικοινώνησε άμεσα με γιατρό.")}</p>")

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
            // Sort by end time (start + duration) so edited/backfilled sessions don't produce
            // negative gaps; the average gap is the mean distance between consecutive feed ends.
            val completedEnds = feedings.filter { it.durationMs >= 0L }
                .map { it.completedAt() }
                .sortedDescending()
            val avgFeedGapMs = if (completedEnds.size >= 2) {
                var sum = 0L
                for (i in 0 until completedEnds.size - 1) {
                    sum += completedEnds[i] - completedEnds[i + 1]
                }
                sum / (completedEnds.size - 1)
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

        sb.append("<p class=\"foot\">${trS("«NiniSense» — Δημιουργήθηκε από τον Μάριο Θεοτή. Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή.")}</p>")
        sb.append("</div></body></html>")
        sb.toString()
    }

    // ---- Backup / restore (JSON, all on-device data) -------------------------

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 6)
        root.put("exportedAt", System.currentTimeMillis())

        val profile = getProfile()
        root.put("profile", JSONObject().apply {
            put("name", profile.name)
            profile.birthMillis?.let { put("birthMillis", it) }
            put("gender", profile.gender.name)
            put("colicConfirmed", profile.colicConfirmed)
        })
        // All babies (multi-profile). "profile" above stays for backwards compatibility.
        val profilesArr = JSONArray()
        for (p in getProfiles()) {
            profilesArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.birthMillis?.let { put("birthMillis", it) }
                put("gender", p.gender.name)
                put("colicConfirmed", p.colicConfirmed)
            })
        }
        root.put("profiles", profilesArr)
        root.put("activeProfile", profile.id)
        root.put("settings", JSONObject().apply {
            put("language", getLanguage().code)
            put("personalizationEnabled", isPersonalizationEnabled())
            put("tummyReminderEnabled", isTummyReminderEnabled())
            put("tummyReminderHourAm", tummyReminderHourAm())
            put("tummyReminderHourPm", tummyReminderHourPm())
        })

        val eventsArr = JSONArray()
        for (e in cryDao.allEventsAllProfiles()) {
            eventsArr.put(JSONObject().apply {
                put("id", e.id)
                put("profileId", e.profileId)
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
        for (e in cryDao.allEventsAllProfiles()) {
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
        for (f in feedbackDao.allAllProfiles()) {
            fbArr.put(JSONObject().apply {
                put("profileId", f.profileId)
                put("timestamp", f.timestamp)
                put("labelIndex", f.labelIndex)
                put("embedding", encodeFloats(f.embedding))
                put("sourceEventId", f.sourceEventId)
                put("isValidationHoldout", f.isValidationHoldout)
            })
        }
        root.put("feedback", fbArr)

        val feedArr = JSONArray()
        for (fe in feedingDao.allListAllProfiles()) {
            feedArr.put(JSONObject().apply {
                put("profileId", fe.profileId)
                put("timestamp", fe.timestamp)
                put("durationMs", fe.durationMs)
                fe.note?.let { put("note", it) }
            })
        }
        root.put("feedings", feedArr)

        val diaperArr = JSONArray()
        for (de in diaperDao.allListAllProfiles()) {
            diaperArr.put(JSONObject().apply {
                put("profileId", de.profileId)
                put("timestamp", de.timestamp)
                put("type", de.type)
            })
        }
        root.put("diapers", diaperArr)

        val tummyArr = JSONArray()
        for (te in tummyDao.allListAllProfiles()) {
            tummyArr.put(JSONObject().apply {
                put("profileId", te.profileId)
                put("timestamp", te.timestamp)
            })
        }
        root.put("tummy", tummyArr)

        val sleepArr = JSONArray()
        for (se in sleepDao.allListAllProfiles()) {
            sleepArr.put(JSONObject().apply {
                put("profileId", se.profileId)
                put("timestamp", se.timestamp)
                put("durationMs", se.durationMs)
                se.note?.let { put("note", it) }
            })
        }
        root.put("sleeps", sleepArr)

        val pendingArr = JSONArray()
        for (p in getProfiles()) {
            for (eventId in pendingEventIds(p.id)) {
                pendingArr.put(JSONObject().apply {
                    put("profileId", p.id)
                    put("eventId", eventId)
                })
            }
        }
        root.put("pending", pendingArr)

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
                    gender = BabyGender.fromNameOrNull(o.optString("gender", null)),
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
                    gender = BabyGender.fromNameOrNull(p.optString("gender", null)),
                    id = newProfileId(),
                    colicConfirmed = p.optBoolean("colicConfirmed", false),
                )
                persistProfiles(listOf(one), one.id)
            }
        }

        cryDao.clearAllProfiles()
        feedbackDao.clear()
        feedingDao.clearAllProfiles()
        diaperDao.clearAllProfiles()
        tummyDao.clearAllProfiles()
        sleepDao.clearAllProfiles()
        // Old clips are keyed by the previous event ids, which no longer match.
        clipStore.clearAll()
        clearAllPending()

        root.optJSONObject("settings")?.let { settings ->
            val lang = if (settings.optString("language", "el") == "en") AppLang.EN else AppLang.EL
            setLanguage(lang)
            setPersonalizationEnabled(settings.optBoolean("personalizationEnabled", true))
            setTummyReminderEnabled(settings.optBoolean("tummyReminderEnabled", true))
            setTummyReminderHourAm(settings.optInt("tummyReminderHourAm", 11))
            setTummyReminderHourPm(settings.optInt("tummyReminderHourPm", 18))
        }

        var restored = 0
        val eventIdMap = HashMap<Long, Long>()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val newId = cryDao.insert(
                    CryEvent(
                        profileId = o.optString("profileId", activeProfileId()),
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
                        profileId = o.optString("profileId", activeProfileId()),
                        timestamp = o.getLong("timestamp"),
                        labelIndex = o.getInt("labelIndex"),
                        embedding = decodeFloats(o.getString("embedding")),
                        sourceEventId = restoredSourceEventId,
                        isValidationHoldout = o.optBoolean("isValidationHoldout", false),
                    )
                )
            }
        }
        root.optJSONArray("feedings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                feedingDao.insert(
                    FeedingEvent(
                        profileId = o.optString("profileId", activeProfileId()),
                        timestamp = o.getLong("timestamp"),
                        durationMs = o.optLong("durationMs", 0L),
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
                        profileId = o.optString("profileId", activeProfileId()),
                        timestamp = o.getLong("timestamp"),
                        type = o.optString("type", DiaperType.WET.name),
                    )
                )
            }
        }
        root.optJSONArray("tummy")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tummyDao.insert(
                    TummyTimeEvent(
                        profileId = o.optString("profileId", activeProfileId()),
                        timestamp = o.getLong("timestamp"),
                    )
                )
            }
        }
        root.optJSONArray("sleeps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sleepDao.insert(
                    SleepEvent(
                        profileId = o.optString("profileId", activeProfileId()),
                        timestamp = o.getLong("timestamp"),
                        durationMs = o.optLong("durationMs", 0L),
                        note = if (o.has("note")) o.getString("note") else null,
                    )
                )
            }
        }
        root.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val profileId = o.optString("profileId", "")
                val oldEventId = o.optLong("eventId", 0L)
                val newEventId = eventIdMap[oldEventId]
                if (profileId.isNotBlank() && newEventId != null) {
                    val event = cryDao.byId(newEventId)
                    if (event != null && event.profileId == profileId &&
                        event.cryDetected && event.confirmedIndex == null
                    ) {
                        setPending(newEventId, profileId)
                    }
                }
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

    private fun pendingIdsKey(profileId: String): String = "$PENDING_IDS:$profileId"

    companion object {
        private const val PROFILE_NAME = "baby_name"
        private const val PROFILE_BIRTH = "baby_birth_millis"
        private const val PROFILES = "profiles_json"
        private const val ACTIVE_PROFILE = "active_profile_id"
        private const val ONBOARDING_DONE = "onboarding_done"
        private const val PERSONALIZATION_ON = "personalization_on"
        private const val PENDING_ID = "pending_confirm_id"
        private const val PENDING_AT = "pending_confirm_at"
        private const val PENDING_IDS = "pending_confirm_ids"
        private const val APP_LANG = "app_lang"
        private const val LAST_BACKUP_AT = "last_backup_at"
        private const val TUMMY_REMINDER_ON = "tummy_reminder_on"
        private const val TUMMY_REMINDER_HOUR_AM = "tummy_reminder_hour_am"
        private const val TUMMY_REMINDER_HOUR_PM = "tummy_reminder_hour_pm"
        private const val PERSONAL_VALIDATION_PER_CLASS = 3

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
