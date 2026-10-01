package org.lia.accessibility.voice

import kotlin.math.sqrt

object VoiceMath {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "Embeddings incompatibles" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }

    fun pairwiseSimilarities(items: List<FloatArray>): List<Float> {
        val out = mutableListOf<Float>()
        for (i in 0 until items.lastIndex) {
            for (j in i + 1 until items.size) out += cosineSimilarity(items[i], items[j])
        }
        return out
    }
}
