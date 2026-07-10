package com.babycry.analyzer.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV (PCM 16-bit mono) writer, used to persist the exact clip we analyzed so the
 * parent can later build a small labelled dataset of their own recordings.
 */
object WavIo {

    fun writeWav(out: OutputStream, samples: FloatArray, sampleRate: Int = MfccExtractor.SAMPLE_RATE) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // PCM header size
        header.putShort(1)                // PCM format
        header.putShort(1)                // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)     // byte rate (sampleRate * channels * bytesPerSample)
        header.putShort(2)                // block align
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            pcm.putShort(v)
        }
        out.write(pcm.array())
    }
}
