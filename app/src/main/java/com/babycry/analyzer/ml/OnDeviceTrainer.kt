package com.babycry.analyzer.ml

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.MappedByteBuffer

/** The trainable linear head's weights (kernel [inputDim][numClasses], bias [numClasses]). */
data class HeadWeights(
    val kernel: Array<FloatArray>,
    val bias: FloatArray,
)

/**
 * Tier 2 personalization: fine-tunes ONLY the final linear layer on device, using the
 * `cry_reason_trainable.tflite` model's `train` / `infer` / `get_weights` / `set_weights`
 * signatures (exported by `ml-training/src/export/to_tflite.py`).
 *
 * Everything here is best-effort: if the trainable model is absent or the signatures are
 * unavailable on this device, [available] is false and the caller stays on Tier 1.
 */
class OnDeviceTrainer(
    model: MappedByteBuffer,
    private val numClasses: Int,
    private val inputDim: Int = YamnetEmbedder.EMBED_DIM,
) : Closeable {

    private val interpreter = Interpreter(model, Interpreter.Options())

    val available: Boolean =
        runCatching {
            val keys = interpreter.signatureKeys.toSet()
            listOf("train", "infer", "get_weights", "set_weights").all { it in keys }
        }.getOrDefault(false)

    fun infer(embedding: FloatArray): FloatArray? = runCatching {
        val inputs = mapOf<String, Any>("x" to arrayOf(embedding))
        val probs = Array(1) { FloatArray(numClasses) }
        val outputs = mutableMapOf<String, Any>("probs" to probs)
        interpreter.runSignature(inputs, outputs, "infer")
        probs[0]
    }.getOrNull()

    /** Run one gradient step on a mini-batch; returns the loss or null on failure. */
    fun trainStep(batchX: Array<FloatArray>, batchY: IntArray): Float? = runCatching {
        val inputs = mapOf<String, Any>("x" to batchX, "y" to batchY)
        val loss = FloatArray(1)
        val outputs = mutableMapOf<String, Any>("loss" to loss)
        interpreter.runSignature(inputs, outputs, "train")
        loss[0]
    }.onFailure { Log.w(TAG, "trainStep failed", it) }.getOrNull()

    fun getWeights(): HeadWeights? = runCatching {
        val kernel = Array(inputDim) { FloatArray(numClasses) }
        val bias = FloatArray(numClasses)
        val outputs = mutableMapOf<String, Any>("kernel" to kernel, "bias" to bias)
        interpreter.runSignature(emptyMap<String, Any>(), outputs, "get_weights")
        HeadWeights(kernel, bias)
    }.getOrNull()

    fun setWeights(weights: HeadWeights): Boolean = runCatching {
        val inputs = mapOf<String, Any>("kernel" to weights.kernel, "bias" to weights.bias)
        val ok = FloatArray(1)
        val outputs = mutableMapOf<String, Any>("ok" to ok)
        interpreter.runSignature(inputs, outputs, "set_weights")
        true
    }.getOrDefault(false)

    override fun close() = interpreter.close()

    private companion object {
        const val TAG = "OnDeviceTrainer"
    }
}
