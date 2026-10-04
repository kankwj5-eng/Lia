package org.lia.accessibility.voice.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeConversationStateTest {
    @Test
    fun inlineCommandDispatchesImmediately() {
        val state = WakeConversationState()
        assertEquals(
            WakeConversationDecision.Command("abre WhatsApp"),
            state.accept("Lía, abre WhatsApp", 1_000L)
        )
    }

    @Test
    fun wakeOnlyArmsNextUtterance() {
        val state = WakeConversationState(followUpWindowMs = 8_000L)

        assertEquals(
            WakeConversationDecision.AwaitCommand,
            state.accept("Lía", 1_000L)
        )
        assertEquals(
            WakeConversationDecision.Command("abre WhatsApp"),
            state.accept("abre WhatsApp", 4_000L)
        )
    }

    @Test
    fun expiredFollowUpDoesNotExecuteOrdinarySpeech() {
        val state = WakeConversationState(followUpWindowMs = 2_000L)
        state.accept("Lía", 1_000L)

        assertEquals(
            WakeConversationDecision.Ignore,
            state.accept("abre WhatsApp", 4_000L)
        )
    }
}
