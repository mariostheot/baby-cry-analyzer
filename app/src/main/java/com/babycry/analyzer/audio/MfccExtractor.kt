package com.babycry.analyzer.audio

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Extracts a fixed-length MFCC feature vector from a mono audio signal.
 *
 * The exact same math is implemented in `ml-training/features.py`. If you change any
 * constant here you MUST change it there as well and retrain the model, otherwise the
 * on-device features will not match what the model was trained on.
 *
 * Pipeline (per frame): Hann window -> FFT -> power spectrum -> mel filterbank (HTK) ->
 * natural-log -> orthonormal DCT-II -> first [N_MFCC] coefficients. The per-utterance
 * feature vector is the mean followed by the (population) standard deviation of every
 * coefficient across all frames, giving a vector of length [FEATURE_DIM].
 */
class MfccExtractor {

    private val window: DoubleArray = DoubleArray(N_FFT) { n ->
        // Periodic Hann window (matches numpy 0.5 - 0.5*cos(2*pi*n/N)).
        0.5 - 0.5 * cos(2.0 * Math.PI * n / N_FFT)
    }

    private val melFilters: Array<DoubleArray> = buildMelFilterbank()

    private val dctBasis: Array<DoubleArray> = buildDctBasis()

    /** @return a [FEATURE_DIM]-length feature vector (mean then std of each MFCC). */
    fun extract(samples: FloatArray): FloatArray {
        val signal: DoubleArray = if (samples.size >= N_FFT) {
            DoubleArray(samples.size) { samples[it].toDouble() }
        } else {
            DoubleArray(N_FFT) { if (it < samples.size) samples[it].toDouble() else 0.0 }
        }

        val numFrames = 1 + (signal.size - N_FFT) / HOP
        val bins = N_FFT / 2 + 1

        // Running sums to compute mean and std in a single pass.
        val sum = DoubleArray(N_MFCC)
        val sumSq = DoubleArray(N_MFCC)

        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(bins)
        val logMel = DoubleArray(N_MELS)

        for (f in 0 until numFrames) {
            val offset = f * HOP
            for (i in 0 until N_FFT) {
                re[i] = signal[offset + i] * window[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)

            for (k in 0 until bins) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }

            for (m in 0 until N_MELS) {
                val filter = melFilters[m]
                var energy = 0.0
                for (k in 0 until bins) {
                    energy += filter[k] * power[k]
                }
                logMel[m] = ln(if (energy < LOG_FLOOR) LOG_FLOOR else energy)
            }

            for (c in 0 until N_MFCC) {
                val basis = dctBasis[c]
                var acc = 0.0
                for (m in 0 until N_MELS) {
                    acc += basis[m] * logMel[m]
                }
                sum[c] += acc
                sumSq[c] += acc * acc
            }
        }

        val features = FloatArray(FEATURE_DIM)
        val n = numFrames.toDouble()
        for (c in 0 until N_MFCC) {
            val mean = sum[c] / n
            val variance = (sumSq[c] / n) - (mean * mean)
            features[c] = mean.toFloat()
            features[N_MFCC + c] = sqrt(if (variance < 0.0) 0.0 else variance).toFloat()
        }
        return features
    }

    private fun buildMelFilterbank(): Array<DoubleArray> {
        val bins = N_FFT / 2 + 1
        val melMin = hzToMel(FMIN)
        val melMax = hzToMel(FMAX)

        // nMels + 2 equally spaced points on the mel axis.
        val melPoints = DoubleArray(N_MELS + 2) { melMin + (melMax - melMin) * it / (N_MELS + 1) }
        val hzPoints = DoubleArray(N_MELS + 2) { melToHz(melPoints[it]) }

        // Frequency of each FFT bin.
        val binHz = DoubleArray(bins) { it * SAMPLE_RATE.toDouble() / N_FFT }

        return Array(N_MELS) { m ->
            val left = hzPoints[m]
            val center = hzPoints[m + 1]
            val right = hzPoints[m + 2]
            DoubleArray(bins) { k ->
                val hz = binHz[k]
                when {
                    hz in left..center && center > left -> (hz - left) / (center - left)
                    hz in center..right && right > center -> (right - hz) / (right - center)
                    else -> 0.0
                }
            }
        }
    }

    private fun buildDctBasis(): Array<DoubleArray> {
        val scale0 = sqrt(1.0 / N_MELS)
        val scaleN = sqrt(2.0 / N_MELS)
        return Array(N_MFCC) { c ->
            val scale = if (c == 0) scale0 else scaleN
            DoubleArray(N_MELS) { m ->
                scale * cos(Math.PI * c * (2 * m + 1) / (2.0 * N_MELS))
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 1024
        const val HOP = 512
        const val N_MELS = 40
        const val N_MFCC = 13
        const val FMIN = 0.0
        const val FMAX = 8000.0
        const val FEATURE_DIM = 2 * N_MFCC

        private const val LOG_FLOOR = 1e-10
    }
}
