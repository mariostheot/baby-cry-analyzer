package com.babycry.analyzer.ml

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/** YAMNet output: a pooled 1024-d embedding plus the infant-cry gate score (0f..1f). */
data class EmbeddingResult(
    val embedding: FloatArray,
    val gateScore: Float,
)

/**
 * Runs the frozen YAMNet feature extractor on device.
 *
 * The exported `yamnet.tflite` (see ml-training `convert_yamnet`) takes a variable-length
 * 16 kHz mono waveform and **pools frames internally**, returning STATIC outputs: a 1024-d
 * embedding and a 1-element cry gate. Pooling inside the model matters: the raw YAMNet has a
 * dynamic per-frame output dimension that does not propagate through the Flex ops on Android
 * (reading it fails with "cannot fill a Java array ... Tensor of 0 bytes"). Static outputs
 * sidestep that entirely, and the math matches the Python training path exactly.
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

        // Outputs are static: a 1024-element embedding and a 1-element gate. Identify each by
        // its element count and read via direct ByteBuffers (rank-agnostic, and immune to the
        // dynamic-shape propagation issue that broke the per-frame model).
        val outputs = HashMap<Int, Any>()
        var embIdx = -1
        var gateIdx = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val count = interpreter.getOutputTensor(i).shape().fold(1) { acc, d -> acc * d }
            outputs[i] = ByteBuffer.allocateDirect(maxOf(count, 1) * 4)
                .order(ByteOrder.nativeOrder())
            if (count == EMBED_DIM) embIdx = i else gateIdx = i
        }

        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(wave), outputs)

        val embedding = FloatArray(EMBED_DIM)
        if (embIdx >= 0) {
            val buf = (outputs[embIdx] as ByteBuffer).also { it.rewind() }
            var j = 0
            while (j < EMBED_DIM && buf.remaining() >= 4) {
                embedding[j] = buf.float
                j++
            }
        }
        val gate = if (gateIdx >= 0) {
            val buf = (outputs[gateIdx] as ByteBuffer).also { it.rewind() }
            if (buf.remaining() >= 4) buf.float else 0f
        } else 0f

        return EmbeddingResult(embedding, gate)
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

        private const val SAMPLE_RATE = 16000
        private const val MIN_SAMPLES = SAMPLE_RATE          // pad up to 1s
        private const val MAX_SAMPLES = SAMPLE_RATE * 10     // cap at 10s
    }
}
