package com.babycry.analyzer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The soothing sounds we can synthesize on the fly (no audio files bundled). */
enum class SoundType(val displayName: String, val emoji: String, val description: String) {
    WHITE_NOISE("Λευκός θόρυβος", "🌫️", "Σταθερός «σσσ» - καλύπτει ήχους του σπιτιού"),
    SHUSH("Ροζ θόρυβος", "🤫", "Πιο απαλό «σσσ», σαν καταρράκτης"),
    VACUUM("Ηλεκτρική σκούπα", "🔊", "Χαμηλό βουητό που ηρεμεί τα νεογέννητα"),
    OCEAN("Κύματα", "🌊", "Αργά κύματα που πάνε κι έρχονται"),
    HEARTBEAT("Καρδιακός παλμός", "❤️", "Σαν μέσα στην κοιλιά της μαμάς"),
    LULLABY("Νανούρισμα", "🎵", "Απαλή μελωδία"),
}

/**
 * Plays a continuous, looping soothing sound generated in real time. Everything is synthesized
 * mathematically (noise + simple oscillators), so we don't ship any audio assets and can loop
 * forever without gaps. Runs on its own daemon thread; call [stop] to end.
 */
class SoothingPlayer(private val sampleRate: Int = 22_050) {

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    val isPlaying: Boolean get() = running

    fun start(type: SoundType, volume: Float = 0.7f) {
        stop()
        running = true
        val t = Thread { render(type, volume) }.apply { isDaemon = true }
        worker = t
        t.start()
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(400) } }
        worker = null
    }

    private fun render(type: SoundType, volume: Float) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBuf * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )

        val block = 1024
        val out = ShortArray(block)
        val rnd = Random(System.nanoTime())
        var n = 0L
        var lp = 0f          // one-pole low-pass state (for coloured noise)
        var gain = 0f        // fade-in to avoid a click at the start

        track.play()
        try {
            while (running) {
                for (i in 0 until block) {
                    val time = n.toDouble() / sampleRate
                    var s = when (type) {
                        SoundType.WHITE_NOISE -> rnd.nextFloat() * 2f - 1f
                        SoundType.SHUSH -> {
                            val w = rnd.nextFloat() * 2f - 1f
                            lp += 0.05f * (w - lp)
                            lp * 3.2f
                        }
                        SoundType.VACUUM -> {
                            val w = rnd.nextFloat() * 2f - 1f
                            lp += 0.25f * (w - lp)
                            lp * 1.8f + 0.25f * sin(2.0 * PI * 120.0 * time).toFloat()
                        }
                        SoundType.OCEAN -> {
                            val w = rnd.nextFloat() * 2f - 1f
                            lp += 0.02f * (w - lp)
                            val swell = 0.5f + 0.5f * sin(2.0 * PI * 0.09 * time).toFloat()
                            lp * 4.5f * swell
                        }
                        SoundType.HEARTBEAT -> heartbeat(time)
                        SoundType.LULLABY -> lullaby(time)
                    }
                    if (gain < 1f) gain = (gain + 1f / (sampleRate * 0.4f)).coerceAtMost(1f)
                    s *= volume * gain
                    out[i] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    n++
                }
                if (track.write(out, 0, block) < 0) break
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** Two soft thumps ("lub-dub") ~75 bpm with an exponential decay envelope. */
    private fun heartbeat(time: Double): Float {
        val period = 0.8
        val tt = time % period
        fun thump(center: Double, freq: Double): Float {
            val x = tt - center
            if (x < 0.0 || x > 0.16) return 0f
            val env = exp(-x / 0.05).toFloat()
            return sin(2.0 * PI * freq * x).toFloat() * env
        }
        return (thump(0.0, 55.0) + 0.7f * thump(0.18, 48.0)) * 1.3f
    }

    /** A gentle "Twinkle Twinkle" melody, one note every 0.5 s, with a soft per-note bell envelope. */
    private fun lullaby(time: Double): Float {
        val notes = intArrayOf(0, 0, 7, 7, 9, 9, 7, 5, 5, 4, 4, 2, 2, 0)
        val noteDur = 0.5
        val idx = (time / noteDur).toInt() % notes.size
        val freq = 261.63 * 2.0.pow(notes[idx] / 12.0)
        val tt = time % noteDur
        val env = sin(PI * (tt / noteDur)).toFloat()
        val tone = (sin(2.0 * PI * freq * time) + 0.3 * sin(2.0 * PI * 2.0 * freq * time)).toFloat()
        return tone * env * 0.4f
    }
}
