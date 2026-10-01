package org.lia.accessibility.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

class VoiceSampleRecorder(
    private val context: Context
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val DEFAULT_DURATION_MS = 4_000
    }

    fun record(durationMs: Int = DEFAULT_DURATION_MS): FloatArray {
        checkMicrophonePermission()

        val targetSamples = SAMPLE_RATE * durationMs / 1000
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "El micrófono no admite la configuración requerida" }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 4096))
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "No se pudo inicializar el micrófono"
        }

        val output = FloatArray(targetSamples)
        val scratch = ShortArray(1024)
        var written = 0

        try {
            recorder.startRecording()

            while (written < targetSamples) {
                val wanted = minOf(scratch.size, targetSamples - written)
                val count = recorder.read(
                    scratch,
                    0,
                    wanted,
                    AudioRecord.READ_BLOCKING
                )

                if (count < 0) {
                    error("Error de captura de audio: " + count)
                }

                for (i in 0 until count) {
                    output[written + i] = scratch[i] / 32768.0f
                }

                written += count
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
            scratch.fill(0)
        }

        return if (written == output.size) {
            output
        } else {
            output.copyOf(written)
        }
    }

    private fun checkMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Lía no puede usar el micrófono sin permiso de grabación de audio."
            )
        }
    }
}
