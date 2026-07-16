package com.babycry.analyzer.ml

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow

/** Training-time inference policy bundled with the model. Missing/bad fields preserve v1 defaults. */
data class ModelMetadata(
    val modelVersion: String = "unknown",
    val trainedAt: String? = null,
    val temperature: Float = 1f,
    val gateThreshold: Float = 0.30f,
    val confidenceThreshold: Float = 0.50f,
    val marginThreshold: Float = 0.15f,
) {
    fun calibrate(probabilities: FloatArray): FloatArray {
        if (probabilities.isEmpty() || temperature <= 0f || temperature == 1f) return probabilities
        // softmax(log(p) / T), equivalent to temperature scaling when a head exports softmax.
        val powers = FloatArray(probabilities.size) {
            max(probabilities[it], 1e-8f).toDouble().pow(1.0 / temperature).toFloat()
        }
        val sum = powers.sum().coerceAtLeast(1e-8f)
        return FloatArray(powers.size) { powers[it] / sum }
    }

    companion object {
        fun parse(raw: String?): ModelMetadata {
            if (raw.isNullOrBlank()) return ModelMetadata()
            return runCatching {
                val root = JSONObject(raw)
                val metrics = root.optJSONObject("metrics")
                ModelMetadata(
                    modelVersion = root.optString("model_version", "unknown"),
                    trainedAt = root.optString("trained_at", "").ifBlank { null },
                    temperature = metrics?.optDouble("temperature", 1.0)?.toFloat()?.takeIf { it > 0f } ?: 1f,
                    gateThreshold = root.optDouble("gate_threshold", 0.30).toFloat().coerceIn(0f, 1f),
                    confidenceThreshold = root.optDouble("confidence_threshold", 0.50).toFloat().coerceIn(0f, 1f),
                    marginThreshold = root.optDouble("margin_threshold", 0.15).toFloat().coerceIn(0f, 1f),
                )
            }.getOrElse { ModelMetadata() }
        }
    }
}
