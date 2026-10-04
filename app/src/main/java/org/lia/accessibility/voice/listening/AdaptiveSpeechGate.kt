package org.lia.accessibility.voice.listening

enum class SpeechGateEvent {
    QUIET,
    STARTED,
    ACTIVE,
    ENDED
}

class AdaptiveSpeechGate(
    private val minimumRms: Float = 0.012f,
    private val startFrames: Int = 3,
    private val endFrames: Int = 28,
    initialNoiseFloor: Float = 0.004f
) {
    private var noiseFloor = initialNoiseFloor.coerceAtLeast(0.0001f)
    private var voicedFrames = 0
    private var silentFrames = 0
    private var active = false

    fun accept(rms: Float): SpeechGateEvent {
        val level = rms.coerceAtLeast(0f)

        if (!active) {
            noiseFloor = (noiseFloor * 0.985f) + (level.coerceAtMost(0.05f) * 0.015f)
            val threshold = maxOf(minimumRms, noiseFloor * 2.8f)

            voicedFrames = if (level >= threshold) voicedFrames + 1 else 0
            if (voicedFrames >= startFrames) {
                active = true
                voicedFrames = 0
                silentFrames = 0
                return SpeechGateEvent.STARTED
            }

            return SpeechGateEvent.QUIET
        }

        val activeThreshold = maxOf(minimumRms * 0.85f, noiseFloor * 2.0f)
        silentFrames = if (level < activeThreshold) silentFrames + 1 else 0

        if (silentFrames >= endFrames) {
            active = false
            silentFrames = 0
            return SpeechGateEvent.ENDED
        }

        return SpeechGateEvent.ACTIVE
    }

    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        active = false
    }
}
