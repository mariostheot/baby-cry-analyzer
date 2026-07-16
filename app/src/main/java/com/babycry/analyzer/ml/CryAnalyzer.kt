package com.babycry.analyzer.ml

import android.content.Context
import android.util.Log
import com.babycry.analyzer.context.ContextPrior
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.model.AnalysisResult
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.model.ReasonScore
import com.babycry.analyzer.personalization.PersonalizationEngine

/**
 * The full on-device inference result plus the extras the app needs to log feedback.
 *
 * @param embedding the YAMNet embedding of the clip (null in heuristic mode) - stored
 *   when the parent gives feedback so the model can personalize.
 * @param uncertain true when the model is not confident enough (OOD / "not sure").
 */
data class CryAnalysis(
    val result: AnalysisResult,
    val embedding: FloatArray?,
    val gateScore: Float,
    val uncertain: Boolean,
)

/**
 * Orchestrates the pipeline: YAMNet gate -> embedding -> trained head -> personalization
 * -> optional context prior -> confidence/OOD check. Degrades gracefully:
 *   trained models present -> MODEL engine; otherwise -> HEURISTIC engine.
 */
class CryAnalyzer(context: Context) {

    private val appContext = context.applicationContext

    private var yamnet: YamnetEmbedder? = null
    private var classifier: ReasonClassifier? = null
    private var trainer: OnDeviceTrainer? = null
    private val heuristic = HeuristicClassifier()
    private val metadata = ModelStore.metadata(appContext)

    /** Class order for the active engine (model labels, or canonical for heuristic). */
    val labels: List<CryReason>
    val personalization: PersonalizationEngine
    val hasModel: Boolean

    init {
        val loadedLabels = ModelStore.labels(appContext)
        var ok = false
        try {
            val yamnetBuf = ModelStore.mappedModel(appContext, ModelStore.YAMNET)
            val reasonBuf = ModelStore.mappedModel(appContext, ModelStore.REASON)
            if (yamnetBuf != null && reasonBuf != null) {
                yamnet = YamnetEmbedder(yamnetBuf)
                classifier = ReasonClassifier(reasonBuf, loadedLabels)
                ok = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Model load failed; falling back to heuristic", t)
            yamnet = null
            classifier = null
            ok = false
        }
        hasModel = ok
        labels = if (ok) loadedLabels else CryReason.canonicalOrder

        trainer = try {
            ModelStore.mappedModel(appContext, ModelStore.REASON_TRAINABLE)
                ?.let { OnDeviceTrainer(it, numClasses = labels.size) }
        } catch (t: Throwable) {
            Log.w(TAG, "Trainable model load failed", t)
            null
        }
        personalization = PersonalizationEngine(appContext, labels.size, trainer)
    }

    fun analyze(
        waveform: FloatArray,
        personalizationEnabled: Boolean,
        useTier2: Boolean,
        contextMultipliers: FloatArray? = null,
    ): CryAnalysis {
        return if (hasModel) analyzeWithModel(
            waveform, personalizationEnabled, useTier2, contextMultipliers,
        ) else analyzeWithHeuristic(waveform, contextMultipliers)
    }

    private fun analyzeWithModel(
        waveform: FloatArray,
        personalizationEnabled: Boolean,
        useTier2: Boolean,
        contextMultipliers: FloatArray?,
    ): CryAnalysis {
        val embedder = yamnet!!
        val head = classifier!!
        val (embedding, gate) = embedder.embed(waveform)

        if (gate < metadata.gateThreshold) {
            return CryAnalysis(AnalysisResult.noCry(AnalysisEngine.MODEL), embedding, gate, false)
        }

        var probs = metadata.calibrate(head.classify(embedding))
        if (personalizationEnabled) {
            probs = personalization.personalizedProbs(probs, embedding, useTier2)
        }
        contextMultipliers?.let { probs = ContextPrior.apply(probs, it) }

        val scores = toSortedScores(probs, head.labels)
        val confidence = scores.firstOrNull()?.probability ?: 0f
        val margin = confidence - (scores.getOrNull(1)?.probability ?: 0f)
        val uncertain = confidence < metadata.confidenceThreshold || margin < metadata.marginThreshold

        val result = AnalysisResult(
            cryDetected = true,
            topReason = scores.first().reason,
            confidence = confidence,
            scores = scores,
            engine = AnalysisEngine.MODEL,
        )
        return CryAnalysis(result, embedding, gate, uncertain)
    }

    private fun analyzeWithHeuristic(
        waveform: FloatArray,
        contextMultipliers: FloatArray?,
    ): CryAnalysis {
        val h = heuristic.analyze(waveform)
        if (h.gateScore < HEURISTIC_GATE) {
            return CryAnalysis(AnalysisResult.noCry(AnalysisEngine.HEURISTIC), null, h.gateScore, false)
        }
        var probs = h.probabilities
        contextMultipliers?.let { probs = ContextPrior.apply(probs, it) }
        val scores = toSortedScores(probs, CryReason.canonicalOrder)
        val result = AnalysisResult(
            cryDetected = true,
            topReason = scores.first().reason,
            confidence = scores.first().probability,
            scores = scores,
            engine = AnalysisEngine.HEURISTIC,
        )
        // The heuristic is always "uncertain" - it cannot really know the reason.
        return CryAnalysis(result, null, h.gateScore, uncertain = true)
    }

    private fun toSortedScores(probs: FloatArray, order: List<CryReason>): List<ReasonScore> =
        order.indices
            .map { ReasonScore(order[it], probs.getOrElse(it) { 0f }) }
            .sortedByDescending { it.probability }

    fun close() {
        yamnet?.close()
        classifier?.close()
        trainer?.close()
    }

    /**
     * Scores a fixed confirmed embedding without context. It is used only for the personal
     * holdout report; the holdouts themselves are excluded from personalization training.
     */
    fun comparePersonalization(embedding: FloatArray): Pair<Int, Int>? {
        val head = classifier ?: return null
        val base = metadata.calibrate(head.classify(embedding))
        val personalized = personalization.personalizedProbs(base, embedding, useTier2 = true)
        return base.indices.maxByOrNull { base[it] }!! to
            personalized.indices.maxByOrNull { personalized[it] }!!
    }

    private companion object {
        const val TAG = "CryAnalyzer"
        const val HEURISTIC_GATE = 0.30f
    }
}
