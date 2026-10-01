package org.lia.accessibility.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMathTest {
    @Test
    fun identicalVectorsMatch() {
        val vector = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VoiceMath.cosineSimilarity(vector, vector), 0.0001f)
    }

    @Test
    fun orthogonalVectorsDoNotMatch() {
        assertEquals(
            0f,
            VoiceMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            0.0001f
        )
    }

    @Test
    fun pairwiseGeneratesEveryPair() {
        val values = listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(0.9f, 0.1f),
            floatArrayOf(0.8f, 0.2f)
        )
        val result = VoiceMath.pairwiseSimilarities(values)
        assertEquals(3, result.size)
        assertTrue(result.all { it in -1f..1f })
    }
}
