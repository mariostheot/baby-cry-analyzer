package com.babycry.analyzer.ml

import com.babycry.analyzer.audio.Fft
import com.babycry.analyzer.audio.MfccExtractor
import com.babycry.analyzer.model.CryReason
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/** Loudness gate + a rough class tilt used only when no trained model is present. */
data class HeuristicResult(
    val gateScore: Float,
    val probabilities: FloatArray,
)

/**
 * A deliberately simple, low-confidence fallback for when the TFLite models are missing.
 *
 * It cannot really tell WHY a baby cries - it only detects that a loud sound is present
 * and nudges the distribution using the spectral centroid (sharper/higher cries lean
 * towards pain/discomfort). The UI always labels these results as rough guesses.
 */
class HeuristicClassifier {

    fun analyze(waveform: FloatArray): HeuristicResult {
        val rms = rms(waveform)
        // Map RMS loudness to a 0..1 gate (0.02 ~ quiet room, 0.15+ ~ crying).
        val gate = clamp01((rms - 0.02f) / 0.13f)

        val centroid = spectralCentroidHz(waveform)     // ~ perceived pitch/brightness
        val probs = tiltFromCentroid(centroid)
        return HeuristicResult(gate, probs)
    }

    private fun tiltFromCentroid(centroidHz: Float): FloatArray {
        // Center the tilt around ~1200 Hz; higher -> pain/discomfort, lower -> hunger/tired.
        val t = clamp(-1f, 1f, (centroidHz - 1200f) / 1200f)
        val logits = FloatArray(CryReason.canonicalOrder.size)
        for ((i, reason) in CryReason.canonicalOrder.withIndex()) {
            logits[i] = when (reason) {
                CryReason.BELLY_PAIN -> 0.6f * t
                CryReason.DISCOMFORT -> 0.4f * t
                CryReason.HUNGRY -> -0.3f * t
                CryReason.TIRED -> -0.5f * t
                CryReason.BURPING -> 0f
            }
        }
        return softmax(logits, temperature = 2.0f) // high temperature => low confidence
    }

    private fun spectralCentroidHz(waveform: FloatArray): Float {
        val n = MfccExtractor.N_FFT
        if (waveform.size < n) return 0f
        val start = (waveform.size - n) / 2
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * Math.PI * i / n) // Hann
            re[i] = waveform[start + i] * w
            im[i] = 0.0
        }
        Fft.transform(re, im)
        val bins = n / 2 + 1
        var num = 0.0
        var den = 0.0
        for (k in 0 until bins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val hz = k * MfccExtractor.SAMPLE_RATE.toDouble() / n
            num += hz * mag
            den += mag
        }
        return if (den <= 0.0) 0f else (num / den).toFloat()
    }

    private fun rms(waveform: FloatArray): Float {
        if (waveform.isEmpty()) return 0f
        var sum = 0.0
        for (v in waveform) sum += v.toDouble() * v
        return sqrt(sum / waveform.size).toFloat()
    }

    private fun softmax(logits: FloatArray, temperature: Float): FloatArray {
        val scaled = FloatArray(logits.size) { logits[it] / temperature }
        val max = scaled.maxOrNull() ?: 0f
        var sum = 0f
        val out = FloatArray(scaled.size)
        for (i in scaled.indices) {
            out[i] = exp(scaled[i] - max)
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    private fun clamp01(v: Float): Float = clamp(0f, 1f, v)

    private fun clamp(lo: Float, hi: Float, v: Float): Float = maxOf(lo, minOf(hi, v))
}
