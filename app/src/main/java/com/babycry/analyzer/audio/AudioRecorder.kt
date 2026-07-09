package com.babycry.analyzer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM from the microphone into a normalized float buffer.
 *
 * Recording runs on [Dispatchers.IO]. Call [record] to start; it returns the captured
 * samples once [stop] is called or [maxDurationMs] elapses. The [onLevel] callback is
 * invoked (on the IO thread) with a rough 0f..1f loudness value for a live meter.
 */
class AudioRecorder(private val sampleRate: Int = MfccExtractor.SAMPLE_RATE) {

    private val stopRequested = AtomicBoolean(false)

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Signals an in-progress [record] call to finish and return what it captured. */
    fun stop() {
        stopRequested.set(true)
    }

    @SuppressLint("MissingPermission") // The caller is responsible for the RECORD_AUDIO grant.
    suspend fun record(
        maxDurationMs: Int,
        onLevel: (Float) -> Unit = {},
    ): FloatArray = withContext(Dispatchers.IO) {
        stopRequested.set(false)

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("Το μικρόφωνο δεν υποστηρίζει τη ζητούμενη μορφή ήχου.")
        }
        // Give the recorder ~1s of internal buffer to avoid overruns.
        val bufferBytes = maxOf(minBufferBytes, sampleRate * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Αποτυχία αρχικοποίησης του μικροφώνου.")
            }

            val chunk = ShortArray(2048)
            val chunks = ArrayList<ShortArray>()
            var totalSamples = 0

            isRecording = true
            recorder.startRecording()
            val startedAt = SystemClock.elapsedRealtime()

            while (true) {
                coroutineContext.ensureActive()
                if (stopRequested.get()) break
                if (SystemClock.elapsedRealtime() - startedAt >= maxDurationMs) break

                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    chunks.add(chunk.copyOf(read))
                    totalSamples += read
                    onLevel(rmsLevel(chunk, read))
                } else if (read < 0) {
                    throw IllegalStateException("Σφάλμα ανάγνωσης από το μικρόφωνο (κωδικός $read).")
                }
            }

            recorder.stop()

            val out = FloatArray(totalSamples)
            var idx = 0
            for (c in chunks) {
                for (s in c) {
                    out[idx++] = s / 32768f
                }
            }
            out
        } finally {
            isRecording = false
            recorder.release()
        }
    }

    private fun rmsLevel(buffer: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sumSq = 0.0
        for (i in 0 until length) {
            val v = buffer[i] / 32768.0
            sumSq += v * v
        }
        return min(1.0, sqrt(sumSq / length)).toFloat()
    }
}
