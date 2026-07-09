package com.babycry.analyzer.data

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stores FloatArray embeddings as a compact little-endian BLOB. */
class Converters {

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in value) buffer.putFloat(v)
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
