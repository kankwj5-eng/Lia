package org.lia.accessibility.ai

data class SpeechTranscript(val text: String, val confidence: Float?)

interface LocalSpeechToText {
    suspend fun transcribe(pcm16KhzMono: FloatArray): SpeechTranscript
}

interface LocalTextToSpeech {
    suspend fun speak(text: String)
    fun stop()
}

data class AgentTurn(
    val systemContext: String,
    val userText: String
)

interface LocalLanguageModel {
    suspend fun complete(turn: AgentTurn): String
}

/*
 * Los adaptadores concretos se incorporarán por mediciones reales:
 * - sherpa-onnx para wake word, VAD, STT y TTS cuando sea suficiente;
 * - whisper.cpp como alternativa de STT;
 * - llama.cpp / MNN / LiteRT-LM para el planificador local.
 *
 * El resto de Lía depende de estas interfaces, no de un proveedor concreto.
 */
