package com.babycry.analyzer.personalization

import android.content.Context
import android.util.Base64
import com.babycry.analyzer.data.FeedbackExample
import com.babycry.analyzer.ml.HeadWeights
import com.babycry.analyzer.ml.OnDeviceTrainer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns the parent's corrections into better predictions - entirely on device.
 *
 * Tier 1 (always on): builds a normalized "prototype" (mean embedding) per confirmed
 * class and blends a similarity distribution into the base model output. Works from the
 * very first correction and cannot catastrophically forget.
 *
 * Tier 2 (optional): once enough examples exist and `cry_reason_trainable.tflite` is
 * present, fine-tunes only the final linear layer and persists the new weights. Falls
 * back to Tier 1 automatically if anything is unavailable.
 */
class PersonalizationEngine(
    context: Context,
    private val numClasses: Int,
    private val trainer: OnDeviceTrainer?,
) {
    private val prefs = context.getSharedPreferences("personalization", Context.MODE_PRIVATE)

    private val prototypes = HashMap<Int, FloatArray>() // classIndex -> normalized mean
    private var totalExamples = 0

    private var baseWeights: HeadWeights? = null
    var tier2Ready: Boolean = false
        private set

    init {
        if (trainer?.available == true) {
            baseWeights = trainer.getWeights()
            restorePersistedWeights()
        }
    }

    val tier2Available: Boolean get() = trainer?.available == true

    // ---- Tier 1: prototypes ---------------------------------------------------

    fun updatePrototypes(examples: List<FeedbackExample>) {
        prototypes.clear()
        totalExamples = examples.size
        val byClass = examples.groupBy { it.labelIndex }
        for ((cls, items) in byClass) {
            if (cls !in 0 until numClasses || items.isEmpty()) continue
            val dim = items.first().embedding.size
            val mean = FloatArray(dim)
            for (ex in items) {
                val e = ex.embedding
                for (j in 0 until minOf(dim, e.size)) mean[j] += e[j]
            }
            for (j in mean.indices) mean[j] /= items.size
            prototypes[cls] = l2normalize(mean)
        }
    }

    /** Blend the base probabilities with the prototype-similarity distribution. */
    fun tier1Blend(base: FloatArray, embedding: FloatArray): FloatArray {
        if (prototypes.size < 2) return base
        val query = l2normalize(embedding.copyOf())

        val present = prototypes.keys.sorted()
        val sims = FloatArray(present.size) { cosine(query, prototypes[present[it]]!!) }
        val protoPresent = softmax(sims, temperature = PROTO_TEMPERATURE)

        val baseMassPresent = present.sumOf { base[it].toDouble() }.toFloat()
        val proto = base.copyOf() // classes without a prototype keep their base mass
        for (i in present.indices) {
            proto[present[i]] = protoPresent[i] * baseMassPresent
        }

        val alpha = alphaFor(totalExamples)
        val out = FloatArray(numClasses) { (1f - alpha) * base[it] + alpha * proto[it] }
        return normalize(out)
    }

    /** Combined personalized inference: Tier 2 head (if ready) then Tier 1 blend. */
    fun personalizedProbs(
        base: FloatArray,
        embedding: FloatArray,
        useTier2: Boolean,
    ): FloatArray {
        val stage = if (useTier2 && tier2Ready) {
            trainer?.infer(embedding) ?: base
        } else {
            base
        }
        return tier1Blend(stage, embedding)
    }

    // ---- Tier 2: on-device fine-tuning ---------------------------------------

    /**
     * Retrain the linear head when there are enough examples. Cheap enough to run after a
     * correction. Returns true if a training pass ran.
     */
    fun maybeTrain(examples: List<FeedbackExample>): Boolean {
        val t = trainer ?: return false
        if (!t.available) return false
        if (examples.size < MIN_TIER2_EXAMPLES) return false
        if (distinctClasses(examples) < 2) return false

        val xs = examples.map { it.embedding }.toTypedArray()
        val ys = IntArray(examples.size) { examples[it].labelIndex }

        val order = xs.indices.toMutableList()
        repeat(TIER2_EPOCHS) {
            order.shuffle()
            var i = 0
            while (i < order.size) {
                val end = minOf(i + TIER2_BATCH, order.size)
                val idx = order.subList(i, end)
                val bx = Array(idx.size) { xs[idx[it]] }
                val by = IntArray(idx.size) { ys[idx[it]] }
                if (t.trainStep(bx, by) == null) return false // signature failed -> bail
                i = end
            }
        }
        t.getWeights()?.let { persistWeights(it) }
        tier2Ready = true
        return true
    }

    fun reset() {
        prototypes.clear()
        totalExamples = 0
        tier2Ready = false
        prefs.edit().clear().apply()
        baseWeights?.let { trainer?.setWeights(it) }
    }

    // ---- Weight persistence ---------------------------------------------------

    private fun persistWeights(w: HeadWeights) {
        prefs.edit()
            .putString(KEY_KERNEL, encode(flatten(w.kernel)))
            .putString(KEY_BIAS, encode(w.bias))
            .putInt(KEY_ROWS, w.kernel.size)
            .putInt(KEY_COLS, numClasses)
            .apply()
    }

    private fun restorePersistedWeights() {
        val t = trainer ?: return
        val kStr = prefs.getString(KEY_KERNEL, null) ?: return
        val bStr = prefs.getString(KEY_BIAS, null) ?: return
        val rows = prefs.getInt(KEY_ROWS, 0)
        val cols = prefs.getInt(KEY_COLS, 0)
        if (rows <= 0 || cols <= 0) return
        val kernel = unflatten(decode(kStr), rows, cols)
        val bias = decode(bStr)
        if (t.setWeights(HeadWeights(kernel, bias))) tier2Ready = true
    }

    // ---- helpers --------------------------------------------------------------

    private fun distinctClasses(examples: List<FeedbackExample>): Int =
        examples.map { it.labelIndex }.toSet().size

    private fun alphaFor(n: Int): Float =
        minOf(MAX_ALPHA, n.toFloat() / (n + ALPHA_SMOOTHING))

    private fun l2normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val d = sqrt(norm).toFloat().coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= d
        return v
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot // both inputs are already L2-normalized
    }

    private fun softmax(v: FloatArray, temperature: Float): FloatArray {
        val scaled = FloatArray(v.size) { v[it] / temperature }
        val max = scaled.maxOrNull() ?: 0f
        var sum = 0f
        val out = FloatArray(v.size)
        for (i in v.indices) {
            out[i] = exp(scaled[i] - max)
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        val sum = v.sum().coerceAtLeast(1e-8f)
        return FloatArray(v.size) { v[it] / sum }
    }

    private fun flatten(m: Array<FloatArray>): FloatArray {
        if (m.isEmpty()) return FloatArray(0)
        val cols = m[0].size
        val out = FloatArray(m.size * cols)
        for (i in m.indices) System.arraycopy(m[i], 0, out, i * cols, cols)
        return out
    }

    private fun unflatten(flat: FloatArray, rows: Int, cols: Int): Array<FloatArray> =
        Array(rows) { r -> FloatArray(cols) { c -> flat[r * cols + c] } }

    private fun encode(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun decode(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    private companion object {
        const val MAX_ALPHA = 0.5f
        const val ALPHA_SMOOTHING = 10f
        const val PROTO_TEMPERATURE = 0.15f
        const val MIN_TIER2_EXAMPLES = 15
        const val TIER2_EPOCHS = 8
        const val TIER2_BATCH = 8

        const val KEY_KERNEL = "head_kernel"
        const val KEY_BIAS = "head_bias"
        const val KEY_ROWS = "head_rows"
        const val KEY_COLS = "head_cols"
    }
}
