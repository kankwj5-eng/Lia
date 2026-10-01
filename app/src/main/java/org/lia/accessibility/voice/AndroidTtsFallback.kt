package org.lia.accessibility.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AndroidTtsFallback(
    context: Context
) : TextToSpeech.OnInitListener, Closeable {
    private val ready = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<String>()
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.set(false)
            return
        }

        val nicaragua = Locale("es", "NI")
        val result = engine.setLanguage(nicaragua)

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale("es"))
        }

        ready.set(true)

        while (true) {
            val text = pending.poll() ?: break
            speakNow(text)
        }
    }

    fun speak(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false

        return if (ready.get()) {
            speakNow(normalized)
        } else {
            pending.offer(normalized)
            true
        }
    }

    fun stop() {
        pending.clear()
        engine.stop()
    }

    private fun speakNow(text: String): Boolean {
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            null,
            "lia-" + System.nanoTime()
        )
        return result == TextToSpeech.SUCCESS
    }

    override fun close() {
        pending.clear()
        engine.stop()
        engine.shutdown()
        ready.set(false)
    }
}
