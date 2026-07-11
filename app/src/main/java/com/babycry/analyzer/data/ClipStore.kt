package com.babycry.analyzer.data

import android.content.Context
import com.babycry.analyzer.audio.WavIo
import com.babycry.analyzer.model.CryReason
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device store of the actual recordings the parent analyzed, keyed by cry-event id.
 *
 * For each detected cry we keep two small files under the app's private storage:
 *  - `<id>.wav` : the exact 16 kHz mono clip we heard.
 *  - `<id>.emb` : the YAMNet embedding (float32), so a *delayed* confirmation can still teach
 *                 the personalization engine even after the app was closed and reopened.
 *
 * Once a cry is confirmed/corrected, the clip + label become a labelled example - the seed of
 * a personal dataset the parent can export (zip) and later use to retrain the model. Nothing
 * leaves the device unless the parent explicitly exports it.
 */
class ClipStore(context: Context) {

    private val dir = File(context.filesDir, "clips").apply { mkdirs() }

    private fun wav(id: Long) = File(dir, "$id.wav")
    private fun emb(id: Long) = File(dir, "$id.emb")

    fun saveClip(eventId: Long, samples: FloatArray, embedding: FloatArray?) {
        runCatching {
            wav(eventId).outputStream().use { WavIo.writeWav(it, samples) }
            if (embedding != null) {
                val buf = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (x in embedding) buf.putFloat(x)
                emb(eventId).writeBytes(buf.array())
            }
        }
    }

    fun readEmbedding(eventId: Long): FloatArray? {
        val f = emb(eventId)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buf.float }
        }.getOrNull()
    }

    fun readClipBytes(eventId: Long): ByteArray? =
        wav(eventId).takeIf { it.exists() }?.readBytes()

    fun readEmbeddingBytes(eventId: Long): ByteArray? =
        emb(eventId).takeIf { it.exists() }?.readBytes()

    fun restoreClipBytes(eventId: Long, wavBytes: ByteArray?, embeddingBytes: ByteArray?) {
        runCatching {
            if (wavBytes != null) wav(eventId).writeBytes(wavBytes)
            if (embeddingBytes != null) emb(eventId).writeBytes(embeddingBytes)
        }
    }

    /** Whether we still have the audio clip for this event. */
    fun hasClip(eventId: Long): Boolean = wav(eventId).exists()

    /**
     * Reads a stored clip back into a normalized -1f..1f waveform so it can be replayed later
     * (e.g. from the saved-recordings library). Our WAV writer uses a fixed 44-byte PCM
     * 16-bit mono header, so we skip that and read the samples.
     */
    fun readClipSamples(eventId: Long): FloatArray? {
        val f = wav(eventId)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            if (bytes.size <= 44) return null
            val count = (bytes.size - 44) / 2
            val buf = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(count) { buf.short / 32768f }
        }.getOrNull()
    }

    fun deleteClip(eventId: Long) {
        runCatching { wav(eventId).delete() }
        runCatching { emb(eventId).delete() }
    }

    fun clearAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Number of stored audio clips. */
    fun count(): Int = dir.listFiles { f -> f.extension == "wav" }?.size ?: 0

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Writes a zip of every **confirmed** clip, grouped in per-reason folders, plus a
     * `labels.csv`. This is the exportable, labelled dataset.
     */
    fun writeDatasetZip(out: OutputStream, events: List<CryEvent>, labels: List<CryReason>): Int {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        var written = 0
        ZipOutputStream(out).use { zip ->
            val csv = StringBuilder("id,timestamp,label,predicted,confidence,engine,file\n")
            for (e in events) {
                if (!e.cryDetected) continue
                val labelIdx = e.confirmedIndex ?: continue
                val label = labels.getOrNull(labelIdx)?.name ?: continue
                val wavFile = wav(e.id)
                if (!wavFile.exists()) continue

                val entryName = "audio/$label/${e.id}.wav"
                zip.putNextEntry(ZipEntry(entryName))
                wavFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                val predicted = labels.getOrNull(e.predictedIndex)?.name ?: ""
                csv.append(e.id).append(',')
                    .append(df.format(Date(e.timestamp))).append(',')
                    .append(label).append(',')
                    .append(predicted).append(',')
                    .append(e.confidence).append(',')
                    .append(e.engine).append(',')
                    .append(entryName).append('\n')
                written++
            }
            zip.putNextEntry(ZipEntry("labels.csv"))
            zip.write(csv.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return written
    }
}
