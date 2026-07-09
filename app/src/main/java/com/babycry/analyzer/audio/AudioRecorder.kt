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
        minDurationMs: Int = 0,
        evalIntervalMs: Int = 700,
        onLevel: (Float) -> Unit = {},
        shouldFinish: suspend (FloatArray) -> Boolean = { false },
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
        // ~2s of internal buffer: the streaming `shouldFinish` analysis briefly pauses reads,
        // and this headroom prevents mic overruns during that pause.
        val bufferBytes = maxOf(minBufferBytes, sampleRate * 4)

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

            var lastEvalAt = 0L
            while (true) {
                coroutineContext.ensureActive()
                if (stopRequested.get()) break
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= maxDurationMs) break

                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    chunks.add(chunk.copyOf(read))
                    totalSamples += read
                    onLevel(rmsLevel(chunk, read))
                } else if (read < 0) {
                    throw IllegalStateException("Σφάλμα ανάγνωσης από το μικρόφωνο (κωδικός $read).")
                }

                // Shazam-style auto-finish: once we have enough audio, periodically let the
                // caller inspect what we've captured so far and stop early if it's confident.
                if (elapsed >= minDurationMs &&
                    SystemClock.elapsedRealtime() - lastEvalAt >= evalIntervalMs
                ) {
                    lastEvalAt = SystemClock.elapsedRealtime()
                    if (shouldFinish(toFloat(chunks, totalSamples))) break
                }
            }

            recorder.stop()
            toFloat(chunks, totalSamples)
        } finally {
            isRecording = false
            recorder.release()
        }
    }

    /** Flattens captured 16-bit chunks into a normalized -1f..1f mono buffer. */
    private fun toFloat(chunks: List<ShortArray>, total: Int): FloatArray {
        val out = FloatArray(total)
        var idx = 0
        for (c in chunks) {
            for (s in c) {
                if (idx >= total) break
                out[idx++] = s / 32768f
            }
        }
        return out
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
