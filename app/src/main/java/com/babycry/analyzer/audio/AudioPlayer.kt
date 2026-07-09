package com.babycry.analyzer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays back a normalized -1f..1f mono waveform (the clip we just analyzed) so the parent
 * can hear exactly what the app heard. Uses a one-shot [AudioTrack] on the IO dispatcher.
 */
class AudioPlayer(private val sampleRate: Int = MfccExtractor.SAMPLE_RATE) {

    @Volatile
    private var track: AudioTrack? = null

    suspend fun play(waveform: FloatArray) = withContext(Dispatchers.IO) {
        stop()
        if (waveform.isEmpty()) return@withContext

        val pcm = ShortArray(waveform.size) { i ->
            (waveform[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBytes, pcm.size * 2)

        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        try {
            t.play()
            var offset = 0
            while (offset < pcm.size) {
                val current = track ?: break // stopped
                val written = current.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
        } finally {
            stop()
        }
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
