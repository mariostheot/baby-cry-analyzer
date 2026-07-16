package com.babycry.analyzer.ml

import android.content.Context
import com.babycry.analyzer.model.CryReason
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Resolves and memory-maps the TFLite models and labels.
 *
 * Lookup order for every file:
 *   1. `<externalFilesDir>/models/<name>` - lets you drop a freshly trained model onto
 *      the phone without rebuilding the app.
 *   2. `assets/<name>` - the model bundled at build time.
 *   3. (labels only) the canonical [CryReason] order as a last resort.
 */
object ModelStore {

    const val YAMNET = "yamnet.tflite"
    const val REASON = "cry_reason.tflite"
    const val REASON_TRAINABLE = "cry_reason_trainable.tflite"
    const val LABELS = "labels.txt"
    const val METADATA = "metadata.json"

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun externalFile(context: Context, name: String): File =
        File(modelsDir(context), name)

    fun exists(context: Context, name: String): Boolean {
        if (externalFile(context, name).exists()) return true
        return runCatching { context.assets.open(name).close() }.isSuccess
    }

    /** Memory-map a model from the external dir or assets, or null if not present. */
    fun mappedModel(context: Context, name: String): MappedByteBuffer? {
        val ext = externalFile(context, name)
        if (ext.exists() && ext.length() > 0) {
            FileInputStream(ext).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, ext.length())
            }
        }
        return runCatching {
            context.assets.openFd(name).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                }
            }
        }.getOrNull()
    }

    /** Class order matching the model output layer. */
    fun labels(context: Context): List<CryReason> {
        val raw: List<String>? = readLabelLines(context)
        val mapped = raw
            ?.mapNotNull { CryReason.fromNameOrNull(it) }
            ?.takeIf { it.isNotEmpty() }
        return mapped ?: CryReason.canonicalOrder
    }

    /** Training metadata follows the same external-file-over-asset policy as the models. */
    fun metadata(context: Context): ModelMetadata = ModelMetadata.parse(readText(context, METADATA))

    private fun readText(context: Context, name: String): String? {
        val ext = externalFile(context, name)
        if (ext.exists()) return runCatching { ext.readText() }.getOrNull()
        return runCatching {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun readLabelLines(context: Context): List<String>? {
        val ext = externalFile(context, LABELS)
        if (ext.exists()) {
            return runCatching { ext.readLines() }.getOrNull()
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
        }
        return runCatching {
            context.assets.open(LABELS).bufferedReader().use { it.readLines() }
        }.getOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() }
    }
}
