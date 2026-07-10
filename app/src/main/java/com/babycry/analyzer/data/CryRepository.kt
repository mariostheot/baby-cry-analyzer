package com.babycry.analyzer.data

import android.content.Context
import android.util.Base64
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.ml.CryAnalyzer
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
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

    /** Load stored feedback into the personalization engine (call once at startup). */
    suspend fun refreshPersonalization() = withContext(Dispatchers.Default) {
        val all = feedbackDao.all()
        analyzer.personalization.updatePrototypes(all)
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
            // Only real cries are worth keeping/asking about.
            if (r.cryDetected) {
                if (isSaveClipsEnabled() && waveform != null) {
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

        val emb = embedding ?: clipStore.readEmbedding(eventId)
        if (emb != null) {
            feedbackDao.insert(
                FeedbackExample(
                    timestamp = System.currentTimeMillis(),
                    labelIndex = idx,
                    embedding = emb,
                )
            )
            val all = feedbackDao.all()
            analyzer.personalization.updatePrototypes(all)
            analyzer.personalization.maybeTrain(all)
        }
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

    suspend fun hoursSinceLastFeed(): Float? = withContext(Dispatchers.IO) {
        feedingDao.last()?.let {
            (System.currentTimeMillis() - it.timestamp) / 3_600_000f
        }
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

    suspend fun addProfile(name: String, birthMillis: Long?): String = withContext(Dispatchers.IO) {
        val list = getProfiles().toMutableList()
        val p = BabyProfile(name = name.trim(), birthMillis = birthMillis, id = newProfileId())
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
        clipStore.clearAll()
        clearPending()
    }

    /** Deletes a single cry event from the history (and its saved recording). */
    suspend fun deleteEvent(id: Long) = withContext(Dispatchers.IO) {
        cryDao.deleteById(id)
        clipStore.deleteClip(id)
        if (pendingId() == id) clearPending()
    }

    // ---- Personal dataset (exportable) ---------------------------------------

    fun isSaveClipsEnabled(): Boolean = profilePrefs.getBoolean(SAVE_CLIPS, true)

    fun setSaveClips(enabled: Boolean) {
        profilePrefs.edit().putBoolean(SAVE_CLIPS, enabled).apply()
    }

    /** (#clips, totalBytes) currently stored on device. */
    suspend fun datasetInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        clipStore.count() to clipStore.totalBytes()
    }

    /** Zips every confirmed clip + a labels.csv into [out]. Returns how many clips were written. */
    suspend fun writeDatasetZip(out: java.io.OutputStream): Int = withContext(Dispatchers.IO) {
        clipStore.writeDatasetZip(out, cryDao.allEvents(), labels)
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
        sb.append("<table><tr><th>${trS("Ώρα")}</th><th>${trS("Αιτία")}</th><th>${trS("Βεβαιότητα")}</th><th>${trS("Feedback")}</th></tr>")
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
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val profile = getProfile()
        root.put("profile", JSONObject().apply {
            put("name", profile.name)
            profile.birthMillis?.let { put("birthMillis", it) }
        })
        // All babies (multi-profile). "profile" above stays for backwards compatibility.
        val profilesArr = JSONArray()
        for (p in getProfiles()) {
            profilesArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.birthMillis?.let { put("birthMillis", it) }
            })
        }
        root.put("profiles", profilesArr)
        root.put("activeProfile", profile.id)

        val eventsArr = JSONArray()
        for (e in cryDao.allEvents()) {
            eventsArr.put(JSONObject().apply {
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

        val fbArr = JSONArray()
        for (f in feedbackDao.all()) {
            fbArr.put(JSONObject().apply {
                put("timestamp", f.timestamp)
                put("labelIndex", f.labelIndex)
                put("embedding", encodeFloats(f.embedding))
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
                )
                persistProfiles(listOf(one), one.id)
            }
        }

        cryDao.clear()
        feedbackDao.clear()
        feedingDao.clear()
        // Old clips are keyed by the previous event ids, which no longer match.
        clipStore.clearAll()
        clearPending()

        var restored = 0
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cryDao.insert(
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
                restored++
            }
        }
        root.optJSONArray("feedback")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                feedbackDao.insert(
                    FeedbackExample(
                        timestamp = o.getLong("timestamp"),
                        labelIndex = o.getInt("labelIndex"),
                        embedding = decodeFloats(o.getString("embedding")),
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

        val all = feedbackDao.all()
        analyzer.personalization.updatePrototypes(all)
        analyzer.personalization.maybeTrain(all)
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
        )
    }

    companion object {
        private const val PROFILE_NAME = "baby_name"
        private const val PROFILE_BIRTH = "baby_birth_millis"
        private const val PROFILES = "profiles_json"
        private const val ACTIVE_PROFILE = "active_profile_id"
        private const val ONBOARDING_DONE = "onboarding_done"
        private const val PENDING_ID = "pending_confirm_id"
        private const val PENDING_AT = "pending_confirm_at"
        private const val SAVE_CLIPS = "save_clips"
        private const val APP_LANG = "app_lang"

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
