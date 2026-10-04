package org.lia.accessibility.voice.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveSpeechGateTest {
    @Test
    fun startsAfterStableVoiceAndEndsAfterSilence() {
        val gate = AdaptiveSpeechGate(
            minimumRms = 0.01f,
            startFrames = 2,
            endFrames = 3,
            initialNoiseFloor = 0.002f
        )

        assertEquals(SpeechGateEvent.QUIET, gate.accept(0.003f))
        assertEquals(SpeechGateEvent.QUIET, gate.accept(0.03f))
        assertEquals(SpeechGateEvent.STARTED, gate.accept(0.03f))
        assertEquals(SpeechGateEvent.ACTIVE, gate.accept(0.025f))
        assertEquals(SpeechGateEvent.ACTIVE, gate.accept(0.001f))
        assertEquals(SpeechGateEvent.ACTIVE, gate.accept(0.001f))
        assertEquals(SpeechGateEvent.ENDED, gate.accept(0.001f))
    }
}
