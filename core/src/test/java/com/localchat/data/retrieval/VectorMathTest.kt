package com.localchat.data.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Harness for the pure vector helpers used by semantic retrieval + Room persistence. */
class VectorMathTest {

    @Test
    fun `blob round-trips a float vector exactly`() {
        val v = floatArrayOf(0f, 1.5f, -2.25f, 3.1415927f, 1e-7f, -1e7f)
        val back = VectorMath.fromBytes(VectorMath.toBytes(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 0f) // exact: float32 in, float32 out
    }

    @Test
    fun `l2normalize yields unit length`() {
        val n = VectorMath.l2normalize(floatArrayOf(3f, 4f)) // |(3,4)| = 5
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
        val len = sqrt((n[0] * n[0] + n[1] * n[1]).toDouble())
        assertEquals(1.0, len, 1e-6)
    }

    @Test
    fun `zero vector normalizes without NaN`() {
        val n = VectorMath.l2normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(n.all { it == 0f })
    }

    @Test
    fun `cosine of identical normalized vectors is 1, orthogonal is 0`() {
        val a = VectorMath.l2normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, VectorMath.dot(a, a), 1e-6f)
        val x = VectorMath.l2normalize(floatArrayOf(1f, 0f))
        val y = VectorMath.l2normalize(floatArrayOf(0f, 1f))
        assertEquals(0f, VectorMath.dot(x, y), 1e-6f)
    }
}
