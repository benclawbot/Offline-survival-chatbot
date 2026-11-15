package com.localchat.data.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure vector helpers for semantic retrieval: L2 normalization, dot/cosine, and the
 * FloatArray ↔ ByteArray (BLOB) serialization used to persist chunk vectors in Room.
 * No Android, no allocation surprises — fully JVM-harness-testable.
 */
object VectorMath {

    /** Return a unit-length copy of [v]; a zero vector is returned unchanged (copy). */
    fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    /** Dot product over the shared prefix length. For L2-normalized inputs this is cosine. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    /** Little-endian float32 serialization for a Room BLOB column. */
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    /** Inverse of [toBytes]. A byte length not divisible by 4 truncates the trailing partial float. */
    fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }
}
