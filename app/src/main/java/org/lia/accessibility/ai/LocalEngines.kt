package org.lia.accessibility.ai

import java.io.Closeable

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

/**
 * Modelo local con ciclo de vida explícito.
 *
 * Lía puede cambiar de motor (LiteRT-LM, llama.cpp/GGUF, etc.) sin que el
 * agente ni las herramientas tengan que conocer el proveedor concreto.
 */
interface ManagedLocalLanguageModel : LocalLanguageModel, Closeable

/*
 * Adaptadores concretos:
 * - sherpa-onnx para identidad de voz, VAD/STT/TTS cuando sea suficiente;
 * - whisper.cpp/sherpa-onnx para reconocimiento de órdenes;
 * - llama.cpp para modelos GGUF como Qwen;
 * - LiteRT-LM para modelos .litertlm.
 *
 * El resto de Lía depende de estas interfaces, no de una API remota.
 */
