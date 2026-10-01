package org.lia.accessibility.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.Closeable

class SherpaWhisperCommandTranscriber(
    model: WhisperCommandModelInfo,
    numThreads: Int = Runtime.getRuntime()
        .availableProcessors()
        .coerceIn(1, 2)
) : LocalSpeechToTextAdapter, Closeable {
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = VoiceSampleRecorder.SAMPLE_RATE,
                featureDim = 80,
                dither = 0.0f
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    language = "es",
                    task = "transcribe",
                    tailPaddings = 300
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
                tokens = model.tokens.absolutePath
            ),
            decodingMethod = "greedy_search",
            maxActivePaths = 1
        )
    )

    override fun transcribe(
        pcm16KhzMono: FloatArray
    ): CommandTranscript {
        require(pcm16KhzMono.isNotEmpty()) {
            "No hay audio para transcribir."
        }

        val stream = recognizer.createStream()

        return try {
            stream.acceptWaveform(
                samples = pcm16KhzMono,
                sampleRate = VoiceSampleRecorder.SAMPLE_RATE
            )
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text
                .replace(Regex("\\s+"), " ")
                .trim()

            CommandTranscript(
                text = text,
                language = result.lang.takeIf { it.isNotBlank() } ?: "es"
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}

data class CommandTranscript(
    val text: String,
    val language: String
)

interface LocalSpeechToTextAdapter {
    fun transcribe(
        pcm16KhzMono: FloatArray
    ): CommandTranscript
}
