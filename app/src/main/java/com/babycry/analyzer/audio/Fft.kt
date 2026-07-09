package com.babycry.analyzer.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal in-place radix-2 Cooley-Tukey FFT operating on double-precision buffers.
 *
 * Only power-of-two lengths are supported (the app always uses [MfccExtractor.N_FFT]).
 * Double precision is used deliberately so that results match NumPy's float64 pipeline
 * in `ml-training/features.py`.
 */
internal object Fft {

    /**
     * In-place complex FFT. [re] and [im] must have the same, power-of-two length.
     * On return they hold the transformed real/imaginary parts.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re and im must have equal length" }
        if (n == 0) return
        require(n and (n - 1) == 0) { "FFT length must be a power of two, was $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wReal = cos(ang)
            val wImag = sin(ang)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val iTop = i + k
                    val iBot = i + k + half
                    val tReal = curReal * re[iBot] - curImag * im[iBot]
                    val tImag = curReal * im[iBot] + curImag * re[iBot]
                    re[iBot] = re[iTop] - tReal
                    im[iBot] = im[iTop] - tImag
                    re[iTop] += tReal
                    im[iTop] += tImag
                    val newReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newReal
                }
                i += len
            }
            len = len shl 1
        }
    }
}
