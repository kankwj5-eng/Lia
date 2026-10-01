package org.lia.accessibility.voice

import kotlin.math.sqrt

object VoiceAudioPreprocessor {
    private const val FRAME_MS = 20
    private const val RMS_FLOOR = 0.012f

    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val frameSize = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val rms = mutableListOf<Float>()
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + frameSize, samples.size)
            var energy = 0.0
            for (i in start until end) {
                val s = samples[i].toDouble()
                energy += s * s
            }
            rms += sqrt(energy / (end - start)).toFloat()
            start = end
        }
        val peak = rms.maxOrNull() ?: 0f
        val threshold = maxOf(RMS_FLOOR, peak * 0.15f)
        val first = rms.indexOfFirst { it >= threshold }
        val last = rms.indexOfLast { it >= threshold }
        if (first < 0 || last < 0) return FloatArray(0)
        val padding = sampleRate / 10
        val from = (first * frameSize - padding).coerceAtLeast(0)
        val to = ((last + 1) * frameSize + padding).coerceAtMost(samples.size)
        return samples.copyOfRange(from, to)
    }
}
