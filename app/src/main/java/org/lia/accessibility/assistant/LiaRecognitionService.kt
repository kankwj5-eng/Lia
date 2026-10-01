package org.lia.accessibility.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class LiaRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback
    ) {
        // Lía uses its own owner-verified sherpa-onnx pipeline instead.
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
