package org.lia.accessibility.voice.listening

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AttentiveAudioLoop(
    context: Context,
    private val onUtterance: (FloatArray) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lia-attentive-audio")
    }

    @Volatile
    private var mutedUntilElapsedMs: Long = 0L

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker.execute(::captureLoop)
        return true
    }

    fun muteFor(durationMs: Long) {
        mutedUntilElapsedMs =
            SystemClock.elapsedRealtime() + durationMs.coerceIn(0L, 30_000L)
    }

    fun isRunning(): Boolean = running.get()

    private fun captureLoop() {
        var recorder: AudioRecord? = null

        try {
            check(
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                "Lía necesita permiso de micrófono para el modo siempre atento."
            }

            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBuffer > 0) {
                "El micrófono no admite captura continua a 16 kHz."
            }

            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimumBuffer * 2, FRAME_SAMPLES * 8))
                .build()

            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "No se pudo inicializar el micrófono continuo de Lía."
            }

            val frame = ShortArray(FRAME_SAMPLES)
            val capture = FloatArray(MAX_UTTERANCE_SAMPLES)
            val gate = AdaptiveSpeechGate()
            var captured = 0
            var collecting = false

            recorder.startRecording()

            while (running.get()) {
                val count = recorder.read(
                    frame,
                    0,
                    frame.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count <= 0) continue

                if (SystemClock.elapsedRealtime() < mutedUntilElapsedMs) {
                    gate.reset()
                    collecting = false
                    captured = 0
                    continue
                }

                val rms = rms(frame, count)
                when (gate.accept(rms)) {
                    SpeechGateEvent.QUIET -> Unit

                    SpeechGateEvent.STARTED -> {
                        collecting = true
                        captured = appendFrame(
                            destination = capture,
                            offset = 0,
                            frame = frame,
                            count = count
                        )
                    }

                    SpeechGateEvent.ACTIVE -> {
                        if (collecting) {
                            captured = appendFrame(
                                destination = capture,
                                offset = captured,
                                frame = frame,
                                count = count
                            )
                        }
                    }

                    SpeechGateEvent.ENDED -> {
                        if (collecting && captured >= MIN_UTTERANCE_SAMPLES) {
                            onUtterance(capture.copyOf(captured))
                        }
                        collecting = false
                        captured = 0
                    }
                }

                if (collecting && captured >= capture.size) {
                    if (captured >= MIN_UTTERANCE_SAMPLES) {
                        onUtterance(capture.copyOf(captured))
                    }
                    gate.reset()
                    collecting = false
                    captured = 0
                }
            }

            frame.fill(0)
            capture.fill(0f)
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            running.set(false)
            recorder?.let { audio ->
                runCatching {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audio.stop()
                    }
                }
                audio.release()
            }
        }
    }

    private fun appendFrame(
        destination: FloatArray,
        offset: Int,
        frame: ShortArray,
        count: Int
    ): Int {
        var next = offset
        val limit = minOf(count, destination.size - offset)
        for (index in 0 until limit) {
            destination[next++] = frame[index] / 32768.0f
        }
        return next
    }

    private fun rms(frame: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var energy = 0.0
        for (index in 0 until count) {
            val value = frame[index] / 32768.0
            energy += value * value
        }
        return sqrt(energy / count).toFloat()
    }

    override fun close() {
        running.set(false)
        worker.shutdownNow()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val MIN_UTTERANCE_SAMPLES = SAMPLE_RATE / 2
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 6
    }
}
