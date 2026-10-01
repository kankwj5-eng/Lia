package org.lia.accessibility.voice

data class EnrollmentQuality(
    val accepted: Boolean,
    val averageSimilarity: Float,
    val minimumSimilarity: Float,
    val message: String
)

data class VoiceVerification(
    val matched: Boolean,
    val score: Float,
    val threshold: Float,
    val message: String
)

class OwnerVoiceAuthenticator(
    private val engine: SherpaSpeakerEngine,
    private val store: VoiceProfileStore
) {
    fun embeddingFromAudio(raw: FloatArray, sampleRate: Int = VoiceSampleRecorder.SAMPLE_RATE): FloatArray {
        val trimmed = VoiceAudioPreprocessor.trimSilence(raw, sampleRate)
        try {
            require(trimmed.size >= sampleRate) {
                "No detecté suficiente voz. Habla durante al menos un segundo."
            }
            return engine.extract(trimmed, sampleRate)
        } finally {
            trimmed.fill(0f)
        }
    }

    fun evaluateEnrollment(embeddings: List<FloatArray>): EnrollmentQuality {
        if (embeddings.size !in MIN_SAMPLES..MAX_SAMPLES) {
            return EnrollmentQuality(false, 0f, 0f, "Se requieren entre 3 y 5 muestras.")
        }
        if (embeddings.any { it.size != engine.dimension }) {
            return EnrollmentQuality(false, 0f, 0f, "Las muestras no son compatibles.")
        }

        val scores = VoiceMath.pairwiseSimilarities(embeddings)
        val average = scores.average().toFloat()
        val minimum = scores.minOrNull() ?: 0f
        val accepted = average >= ENROLLMENT_MEAN && minimum >= ENROLLMENT_MIN_PAIR

        return EnrollmentQuality(
            accepted,
            average,
            minimum,
            if (accepted) "Las muestras forman un perfil coherente."
            else "Las muestras no son suficientemente parecidas. Repite el registro en un lugar tranquilo."
        )
    }

    fun saveEnrollment(embeddings: List<FloatArray>): EnrollmentQuality {
        val quality = evaluateEnrollment(embeddings)
        if (!quality.accepted) return quality
        store.save(
            VoiceProfile(
                embeddings = embeddings.map { it.copyOf() },
                threshold = DEFAULT_THRESHOLD,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
        return quality
    }

    fun verifyAudio(raw: FloatArray, sampleRate: Int = VoiceSampleRecorder.SAMPLE_RATE): VoiceVerification {
        val profile = store.load()
            ?: return VoiceVerification(false, 0f, DEFAULT_THRESHOLD, "No hay identidad de voz registrada.")

        val query = try {
            embeddingFromAudio(raw, sampleRate)
        } catch (e: Exception) {
            return VoiceVerification(false, 0f, profile.threshold, e.message ?: "No pude analizar la voz.")
        }

        val scores = profile.embeddings.map { VoiceMath.cosineSimilarity(query, it) }.sortedDescending()
        query.fill(0f)
        val best = scores.firstOrNull() ?: 0f
        val corroborated = scores.take(minOf(2, scores.size)).average().toFloat()
        val matched = best >= profile.threshold &&
            corroborated >= profile.threshold - CORROBORATION_MARGIN

        return VoiceVerification(
            matched,
            corroborated,
            profile.threshold,
            if (matched) "Voz del propietario reconocida." else "La voz no coincide con el perfil autorizado."
        )
    }

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_SAMPLES = 5
        const val DEFAULT_THRESHOLD = 0.62f
        const val ENROLLMENT_MEAN = 0.55f
        const val ENROLLMENT_MIN_PAIR = 0.42f
        const val CORROBORATION_MARGIN = 0.03f
    }
}
