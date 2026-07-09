package com.babycry.analyzer.ml

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.MappedByteBuffer

/** YAMNet output: a pooled 1024-d embedding plus the infant-cry gate score (0f..1f). */
data class EmbeddingResult(
    val embedding: FloatArray,
    val gateScore: Float,
)

/**
 * Runs the frozen YAMNet feature extractor on device.
 *
 * The model (converted from TF-Hub) takes a variable-length 16 kHz mono waveform and
 * returns per-frame scores (521 AudioSet classes) and per-frame embeddings (1024). We
 * mean-pool the embeddings over frames and read the peak "Baby cry, infant cry" score as
 * the gate. Output tensors are located by shape so we do not depend on their order.
 *
 * Requires the Flex runtime (`tensorflow-lite-select-tf-ops`) because YAMNet's STFT/mel
 * frontend uses a few ops that are not TFLite builtins.
 */
class YamnetEmbedder(model: MappedByteBuffer) : Closeable {

    private val interpreter = Interpreter(model, Interpreter.Options())

    fun embed(waveform: FloatArray): EmbeddingResult {
        val wave = fitLength(waveform)
        interpreter.resizeInput(0, intArrayOf(wave.size))
        interpreter.allocateTensors()

        val outputs = HashMap<Int, Any>()
        val shapes = ArrayList<IntArray>()
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            shapes.add(shape)
            outputs[i] = allocate(shape)
        }

        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(wave), outputs)

        var embedding: FloatArray? = null
        var gate = 0f
        for (i in shapes.indices) {
            val shape = shapes[i]
            if (shape.size != 2) continue
            @Suppress("UNCHECKED_CAST")
            val frames = outputs[i] as Array<FloatArray>
            when (shape[1]) {
                EMBED_DIM -> embedding = meanPool(frames)
                SCORE_DIM -> gate = peakGate(frames)
            }
        }
        val emb = embedding ?: FloatArray(EMBED_DIM)
        return EmbeddingResult(emb, gate)
    }

    private fun allocate(shape: IntArray): Any {
        val frames = if (shape[0] > 0) shape[0] else 1
        val dim = shape[1]
        return Array(frames) { FloatArray(dim) }
    }

    private fun meanPool(frames: Array<FloatArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(EMBED_DIM)
        val out = FloatArray(frames[0].size)
        for (frame in frames) {
            for (j in frame.indices) out[j] += frame[j]
        }
        val n = frames.size.toFloat()
        for (j in out.indices) out[j] /= n
        return out
    }

    private fun peakGate(frames: Array<FloatArray>): Float {
        var peak = 0f
        for (frame in frames) {
            if (INFANT_CRY_INDEX < frame.size) {
                peak = maxOf(peak, frame[INFANT_CRY_INDEX])
            }
        }
        return peak
    }

    private fun fitLength(waveform: FloatArray): FloatArray {
        if (waveform.size < MIN_SAMPLES) return waveform.copyOf(MIN_SAMPLES)
        if (waveform.size > MAX_SAMPLES) {
            val start = (waveform.size - MAX_SAMPLES) / 2
            return waveform.copyOfRange(start, start + MAX_SAMPLES)
        }
        return waveform
    }

    override fun close() = interpreter.close()

    companion object {
        const val EMBED_DIM = 1024
        const val SCORE_DIM = 521

        /** AudioSet index of "Baby cry, infant cry". */
        const val INFANT_CRY_INDEX = 20

        private const val SAMPLE_RATE = 16000
        private const val MIN_SAMPLES = SAMPLE_RATE          // pad up to 1s
        private const val MAX_SAMPLES = SAMPLE_RATE * 10     // cap at 10s
    }
}
