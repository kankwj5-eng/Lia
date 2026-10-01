package org.lia.accessibility.voice

sealed interface VerifiedVoiceCommandResult {
    data class Accepted(
        val transcript: CommandTranscript,
        val verification: VoiceVerification
    ) : VerifiedVoiceCommandResult

    data class VoiceRejected(
        val verification: VoiceVerification
    ) : VerifiedVoiceCommandResult

    data class EmptyTranscript(
        val verification: VoiceVerification
    ) : VerifiedVoiceCommandResult

    data class Failed(
        val reason: String
    ) : VerifiedVoiceCommandResult
}

fun interface VoiceIdentityVerifier {
    fun verify(
        audio: FloatArray
    ): VoiceVerification
}

class VerifiedVoiceCommandProcessor(
    private val verifier: VoiceIdentityVerifier,
    private val speechToText: LocalSpeechToTextAdapter
) {
    fun process(
        audio: FloatArray
    ): VerifiedVoiceCommandResult {
        if (audio.isEmpty()) {
            return VerifiedVoiceCommandResult.Failed(
                "No hay audio para procesar."
            )
        }

        val verification = runCatching {
            verifier.verify(audio)
        }.getOrElse { error ->
            return VerifiedVoiceCommandResult.Failed(
                error.message ?: "No pude verificar la identidad de voz."
            )
        }

        if (!verification.matched) {
            return VerifiedVoiceCommandResult.VoiceRejected(
                verification = verification
            )
        }

        val transcript = runCatching {
            speechToText.transcribe(audio)
        }.getOrElse { error ->
            return VerifiedVoiceCommandResult.Failed(
                error.message ?: "No pude transcribir la orden."
            )
        }

        if (transcript.text.isBlank()) {
            return VerifiedVoiceCommandResult.EmptyTranscript(
                verification = verification
            )
        }

        return VerifiedVoiceCommandResult.Accepted(
            transcript = transcript,
            verification = verification
        )
    }
}

fun OwnerVoiceAuthenticator.asVoiceIdentityVerifier(): VoiceIdentityVerifier =
    VoiceIdentityVerifier { audio ->
        verifyAudio(audio)
    }
