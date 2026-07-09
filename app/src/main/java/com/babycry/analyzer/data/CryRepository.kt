package com.babycry.analyzer.data

import android.content.Context
import android.util.Base64
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.ml.CryAnalyzer
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
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

    suspend fun saveEvent(analysis: CryAnalysis): Long = withContext(Dispatchers.IO) {
        val r = analysis.result
        val predicted = r.topReason?.let { labels.indexOf(it) } ?: -1
        cryDao.insert(
            CryEvent(
                timestamp = System.currentTimeMillis(),
                cryDetected = r.cryDetected,
                predictedIndex = predicted,
                confidence = r.confidence,
                engine = r.engine.name,
                gateScore = analysis.gateScore,
            )
        )
    }

    /** Record a confirmation/correction and update personalization. */
    suspend fun confirm(
        eventId: Long,
        correctReason: CryReason,
        embedding: FloatArray?,
    ) = withContext(Dispatchers.Default) {
        val event = cryDao.byId(eventId) ?: return@withContext
        val idx = labels.indexOf(correctReason).coerceAtLeast(0)
        cryDao.update(event.copy(confirmedIndex = idx))
        if (embedding != null) {
            feedbackDao.insert(
                FeedbackExample(
                    timestamp = System.currentTimeMillis(),
                    labelIndex = idx,
                    embedding = embedding,
                )
            )
            val all = feedbackDao.all()
            analyzer.personalization.updatePrototypes(all)
            analyzer.personalization.maybeTrain(all)
        }
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

    fun getProfile(): BabyProfile {
        val name = profilePrefs.getString(PROFILE_NAME, "") ?: ""
        val birth = if (profilePrefs.contains(PROFILE_BIRTH))
            profilePrefs.getLong(PROFILE_BIRTH, 0L).takeIf { it > 0L } else null
        return BabyProfile(name = name, birthMillis = birth)
    }

    suspend fun setProfile(profile: BabyProfile) = withContext(Dispatchers.IO) {
        profilePrefs.edit().apply {
            putString(PROFILE_NAME, profile.name.trim())
            if (profile.birthMillis != null) putLong(PROFILE_BIRTH, profile.birthMillis)
            else remove(PROFILE_BIRTH)
        }.apply()
    }

    // ---- Human-friendly report (HTML) ----------------------------------------

    /**
     * A styled, self-contained HTML report anyone can open in a browser (and print to PDF).
     * Far friendlier than raw CSV: a summary, the reason breakdown, and a readable table.
     */
    suspend fun exportReportHtml(): String = withContext(Dispatchers.IO) {
        val df = SimpleDateFormat("EEE dd/MM/yyyy HH:mm", Locale("el"))
        val events = cryDao.allEvents()
        val stats = stats()
        val profile = getProfile()
        val cries = events.filter { it.cryDetected }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"el\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Αναφορά — Γιατί Κλαίει;</title><style>")
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

        val title = if (profile.hasName) "Αναφορά — ${esc(profile.name)}" else "Αναφορά — Γιατί Κλαίει;"
        sb.append("<h1>$title</h1>")
        sb.append("<p class=\"sub\">Δημιουργήθηκε ${esc(df.format(Date()))}</p>")

        // Summary cards
        sb.append("<div class=\"cards\">")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.totalCries}</div><div class=\"l\">Κλάματα</div></div>")
        val accTxt = stats.accuracy?.let { "${Math.round(it * 100)}%" } ?: "—"
        sb.append("<div class=\"card\"><div class=\"n\">$accTxt</div><div class=\"l\">Ακρίβεια (feedback)</div></div>")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.confirmedCount}</div><div class=\"l\">Επιβεβαιώσεις</div></div>")
        sb.append("<div class=\"card\"><div class=\"n\">${stats.feedbackCount}</div><div class=\"l\">Δείγματα εκμάθησης</div></div>")
        sb.append("</div>")

        // Reason breakdown
        val distTotal = stats.predictedDistribution.sum().coerceAtLeast(1)
        sb.append("<h2>Κατανομή αιτιών</h2>")
        stats.labels.forEachIndexed { i, reason ->
            val c = stats.predictedDistribution.getOrElse(i) { 0 }
            val pct = 100 * c / distTotal
            sb.append("<div class=\"row\"><span>${esc(reason.emoji + " " + reason.displayName)}</span><span>$c ($pct%)</span></div>")
            sb.append("<div class=\"bar\"><span style=\"width:$pct%\"></span></div>")
        }

        // Table of recent events
        sb.append("<h2>Καταγραφές (${cries.size})</h2>")
        sb.append("<table><tr><th>Ώρα</th><th>Αιτία</th><th>Βεβαιότητα</th><th>Feedback</th></tr>")
        for (e in cries.take(300)) {
            val pred = e.predictedIndex.takeIf { it in labels.indices }?.let { labels[it] }
            val predTxt = pred?.let { esc(it.emoji + " " + it.displayName) } ?: "—"
            val confTxt = "${Math.round(e.confidence * 100)}%"
            val fb = when {
                e.confirmedIndex == null -> "—"
                e.confirmedIndex == e.predictedIndex -> "<span class=\"ok\">✓ σωστό</span>"
                else -> {
                    val corr = e.confirmedIndex!!.takeIf { it in labels.indices }?.let { labels[it].displayName } ?: ""
                    "<span class=\"corr\">✎ ${esc(corr)}</span>"
                }
            }
            sb.append("<tr><td>${esc(df.format(Date(e.timestamp)))}</td><td>$predTxt</td><td>$confTxt</td><td>$fb</td></tr>")
        }
        sb.append("</table>")

        sb.append("<p class=\"foot\">«Γιατί Κλαίει;» — Δημιουργήθηκε από τον Μάριο Θεοτή. ")
        sb.append("Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή.</p>")
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

        root.optJSONObject("profile")?.let { p ->
            setProfile(
                BabyProfile(
                    name = p.optString("name", ""),
                    birthMillis = if (p.has("birthMillis")) p.optLong("birthMillis") else null,
                )
            )
        }

        cryDao.clear()
        feedbackDao.clear()
        feedingDao.clear()

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
        val ageMonths = getProfile().ageMonths()
        return com.babycry.analyzer.context.ContextPrior.multipliers(hours, hour, ageMonths)
    }

    companion object {
        private const val PROFILE_NAME = "baby_name"
        private const val PROFILE_BIRTH = "baby_birth_millis"

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
