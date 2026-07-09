package com.babycry.analyzer.ml

import com.babycry.analyzer.model.CryReason
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.MappedByteBuffer

/**
 * The trained reason classifier: a 1024-d YAMNet embedding in, a probability per class
 * out. The [labels] list gives the class order (from labels.txt) so output index i maps
 * to `labels[i]`.
 */
class ReasonClassifier(
    model: MappedByteBuffer,
    val labels: List<CryReason>,
) : Closeable {

    private val interpreter = Interpreter(model, Interpreter.Options())

    /** @return probabilities aligned with [labels] (already softmaxed by the model). */
    fun classify(embedding: FloatArray): FloatArray {
        val input = arrayOf(embedding)                 // shape [1, 1024]
        val output = Array(1) { FloatArray(labels.size) } // shape [1, numClasses]
        interpreter.run(input, output)
        return output[0]
    }

    override fun close() = interpreter.close()
}
