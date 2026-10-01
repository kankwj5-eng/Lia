package org.lia.accessibility.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedVoiceCommandProcessorTest {
    @Test
    fun rejectedVoiceNeverReachesSpeechToText() {
        var sttCalls = 0

        val processor = VerifiedVoiceCommandProcessor(
            verifier = VoiceIdentityVerifier {
                VoiceVerification(
                    matched = false,
                    score = 0.20f,
                    threshold = 0.62f,
                    message = "rechazada"
                )
            },
            speechToText = object : LocalSpeechToTextAdapter {
                override fun transcribe(pcm16KhzMono: FloatArray): CommandTranscript {
                    sttCalls++
                    return CommandTranscript("esto no debe ejecutarse", "es")
                }
            }
        )

        val result = processor.process(FloatArray(16_000) { 0.1f })

        assertTrue(result is VerifiedVoiceCommandResult.VoiceRejected)
        assertEquals(0, sttCalls)
    }

    @Test
    fun acceptedVoiceTranscribesSameAudioObject() {
        val audio = FloatArray(16_000) { 0.1f }
        var verifiedAudio: FloatArray? = null
        var transcribedAudio: FloatArray? = null

        val processor = VerifiedVoiceCommandProcessor(
            verifier = VoiceIdentityVerifier {
                verifiedAudio = it
                VoiceVerification(
                    matched = true,
                    score = 0.80f,
                    threshold = 0.62f,
                    message = "ok"
                )
            },
            speechToText = object : LocalSpeechToTextAdapter {
                override fun transcribe(pcm16KhzMono: FloatArray): CommandTranscript {
                    transcribedAudio = pcm16KhzMono
                    return CommandTranscript("abre WhatsApp", "es")
                }
            }
        )

        val result = processor.process(audio)

        assertTrue(result is VerifiedVoiceCommandResult.Accepted)
        assertTrue(verifiedAudio === audio)
        assertTrue(transcribedAudio === audio)
        assertEquals(
            "abre WhatsApp",
            (result as VerifiedVoiceCommandResult.Accepted).transcript.text
        )
    }

    @Test
    fun emptyTranscriptDoesNotBecomeCommand() {
        val processor = VerifiedVoiceCommandProcessor(
            verifier = VoiceIdentityVerifier {
                VoiceVerification(true, 0.8f, 0.62f, "ok")
            },
            speechToText = object : LocalSpeechToTextAdapter {
                override fun transcribe(pcm16KhzMono: FloatArray) =
                    CommandTranscript("   ", "es")
            }
        )

        val result = processor.process(FloatArray(16_000) { 0.1f })
        assertTrue(result is VerifiedVoiceCommandResult.EmptyTranscript)
    }
}
