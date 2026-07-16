package com.babycry.analyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babycry.analyzer.ml.ModelStore
import com.babycry.analyzer.ml.YamnetEmbedder
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Golden parity test: the on-device YAMNet must produce (almost) the same embedding as
 * the Python training pipeline for the SAME clip. This catches feature mismatches that
 * would silently ruin accuracy.
 *
 * Training exports `parity_sample.wav` and `parity_expected.json` with the model bundle.
 * CI copies them into test assets and runs this on an emulator when they are present.
 */
@RunWith(AndroidJUnit4::class)
class YamnetParityTest {

    @Test
    fun onDeviceEmbeddingMatchesPython() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val test = InstrumentationRegistry.getInstrumentation().context

        val modelBuf = ModelStore.mappedModel(target, ModelStore.YAMNET)
        assumeTrue("yamnet.tflite not bundled", modelBuf != null)
        val samplePresent = runCatching { test.assets.open("parity_sample.wav").close() }.isSuccess
        assumeTrue("parity assets were not included in the model bundle", samplePresent)

        val wave = readWavPcm16(test.assets.open("parity_sample.wav").readBytesCompat())
        val expected = JSONObject(
            test.assets.open("parity_expected.json").bufferedReader().use { it.readText() }
        )
        val expectedEmbedding = expected.getJSONArray("embedding").let { arr ->
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        }

        val embedder = YamnetEmbedder(modelBuf!!)
        val actual = embedder.embed(wave).embedding
        embedder.close()

        val cos = cosine(expectedEmbedding, actual)
        assertTrue("Embedding cosine too low: $cos", cos > 0.99f)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-9f)
    }

    /** Minimal 16-bit PCM WAV reader (mono) -> normalized float samples. */
    private fun readWavPcm16(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var dataOffset = 44
        // Find the "data" chunk defensively (header size can vary).
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataOffset = i + 8
                break
            }
            i += 8 + size
        }
        val sampleCount = (bytes.size - dataOffset) / 2
        buffer.position(dataOffset)
        return FloatArray(sampleCount) { buffer.short / 32768f }
    }

    private fun java.io.InputStream.readBytesCompat(): ByteArray = use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val r = input.read(chunk)
            if (r < 0) break
            out.write(chunk, 0, r)
        }
        out.toByteArray()
    }
}
