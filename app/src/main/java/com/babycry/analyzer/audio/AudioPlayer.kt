package com.babycry.analyzer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays back a normalized -1f..1f mono waveform (the clip we just analyzed) so the parent
 * can hear exactly what the app heard.
 *
 * Uses a one-shot [AudioTrack] in **static** mode: we load the whole clip, start playback, and
 * then wait for the play head to reach the end before releasing. (An earlier streaming version
 * released the track immediately after `write()` returned - which happens as soon as the data
 * is buffered, not when it has finished playing - so nothing was audible.)
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
        val bytes = pcm.size * 2
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        val bufferBytes = maxOf(minBytes, bytes)

        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferBytes,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        try {
            // Static mode: load the entire clip BEFORE calling play().
            var written = 0
            while (written < pcm.size) {
                val n = t.write(pcm, written, pcm.size - written)
                if (n <= 0) break
                written += n
            }
            t.setVolume(1f)
            t.play()

            // Wait until the whole clip has been rendered (or we've been stopped/replaced).
            while (track === t) {
                val head = try {
                    t.playbackHeadPosition
                } catch (e: IllegalStateException) {
                    break
                }
                if (head >= written) break
                Thread.sleep(20)
            }
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            if (track === t) track = null
        }
    }

    fun stop() {
        track?.let {
            track = null
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    fun pause() {
        track?.let { runCatching { it.pause() } }
    }

    fun resume() {
        track?.let { runCatching { it.play() } }
    }
}
