package com.babycry.analyzer.data

import android.content.Context
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.ml.CryAnalyzer
import com.babycry.analyzer.model.CryReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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
        )
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder(
            "timestamp,cryDetected,predicted,confirmed,confidence,engine,gateScore\n"
        )
        for (e in cryDao.allEvents()) {
            val pred = e.predictedIndex.takeIf { it in labels.indices }?.let { labels[it].name } ?: ""
            val conf = e.confirmedIndex?.takeIf { it in labels.indices }?.let { labels[it].name } ?: ""
            sb.append(e.timestamp).append(',')
                .append(e.cryDetected).append(',')
                .append(pred).append(',')
                .append(conf).append(',')
                .append(e.confidence).append(',')
                .append(e.engine).append(',')
                .append(e.gateScore).append('\n')
        }
        sb.toString()
    }

    private suspend fun contextMultipliers(): FloatArray {
        val hours = hoursSinceLastFeed()
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        return com.babycry.analyzer.context.ContextPrior.multipliers(hours, hour)
    }

    companion object {
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
