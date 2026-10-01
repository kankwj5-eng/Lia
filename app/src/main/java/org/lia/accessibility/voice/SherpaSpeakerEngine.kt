package org.lia.accessibility.voice

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.File

class SherpaSpeakerEngine(modelFile: File) : Closeable {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(
            model = modelFile.absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
    )

    val dimension: Int get() = extractor.dim()

    fun extract(samples: FloatArray, sampleRate: Int): FloatArray {
        require(samples.isNotEmpty()) { "No hay voz suficiente" }
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "La muestra es demasiado corta" }
            extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}
