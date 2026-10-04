package org.lia.accessibility.voice.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseMatcherTest {
    @Test
    fun recognizesWakePhraseAndInlineCommand() {
        val result = WakePhraseMatcher.match("Lía, abre WhatsApp")
        assertTrue(result is WakePhraseMatch.Command)
        assertEquals("abre WhatsApp", (result as WakePhraseMatch.Command).command)
    }

    @Test
    fun supportsNaturalLeadIns() {
        assertEquals(
            WakePhraseMatch.Command("sube el volumen"),
            WakePhraseMatcher.match("Oye Lía, sube el volumen")
        )
        assertEquals(
            WakePhraseMatch.WakeOnly,
            WakePhraseMatcher.match("Hola Lía")
        )
    }

    @Test
    fun ignoresOrdinaryConversationWithoutWakePhrase() {
        assertNull(WakePhraseMatcher.match("mañana hablamos con Lía"))
        assertNull(WakePhraseMatcher.match("abre WhatsApp"))
    }
}
