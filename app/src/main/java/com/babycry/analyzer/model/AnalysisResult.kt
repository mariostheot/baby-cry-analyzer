package com.babycry.analyzer.model

/** Which engine produced a prediction. */
enum class AnalysisEngine {
    /** A trained TensorFlow Lite model bundled in assets. */
    MODEL,

    /** The built-in signal-processing heuristic (used when no model is present). */
    HEURISTIC,
}

/** A single category together with its predicted probability (0f..1f). */
data class ReasonScore(
    val reason: CryReason,
    val probability: Float,
)

/**
 * The outcome of analyzing one audio recording.
 *
 * @param cryDetected whether the recording actually contained a loud-enough cry.
 *   When false, [scores]/[topReason] are not meaningful.
 * @param scores every category with its probability, sorted descending.
 * @param engine which engine produced the result.
 */
data class AnalysisResult(
    val cryDetected: Boolean,
    val topReason: CryReason?,
    val confidence: Float,
    val scores: List<ReasonScore>,
    val engine: AnalysisEngine,
) {
    /**
     * Whether the result should be treated as trustworthy enough to surface strongly.
     * The heuristic engine is always low-confidence by design.
     */
    val isConfident: Boolean
        get() = cryDetected && engine == AnalysisEngine.MODEL && confidence >= 0.5f

    companion object {
        fun noCry(engine: AnalysisEngine): AnalysisResult =
            AnalysisResult(
                cryDetected = false,
                topReason = null,
                confidence = 0f,
                scores = emptyList(),
                engine = engine,
            )
    }
}
