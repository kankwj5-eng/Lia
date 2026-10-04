package org.lia.accessibility.voice.listening

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.lia.accessibility.LiaPreferences
import org.lia.accessibility.voice.OwnerVoiceAuthenticator
import org.lia.accessibility.voice.SherpaSpeakerEngine
import org.lia.accessibility.voice.SherpaWhisperCommandTranscriber
import org.lia.accessibility.voice.VoiceModelProvisioner
import org.lia.accessibility.voice.VoiceProfileStore
import org.lia.accessibility.voice.VoiceVerification
import org.lia.accessibility.voice.WhisperCommandModelProvisioner
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class AttentionState {
    STOPPED,
    LISTENING,
    PROCESSING,
    NEEDS_SETUP,
    ERROR
}

data class AttentionStatus(
    val state: AttentionState,
    val message: String
)

sealed interface AttentionStartResult {
    data object Started : AttentionStartResult
    data object Disabled : AttentionStartResult
    data class NeedsSetup(val reason: String) : AttentionStartResult
}

class AttentiveVoiceController(
    context: Context,
    private val onCommand: (String, VoiceVerification) -> Unit,
    private val onWakeOnly: () -> Unit,
    private val onStatus: (AttentionStatus) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val preferences = LiaPreferences(appContext)
    private val commandModels = WhisperCommandModelProvisioner(appContext)
    private val voiceModels = VoiceModelProvisioner(appContext)
    private val voiceProfiles = VoiceProfileStore(appContext)
    private val processing = AtomicBoolean(false)
    private val processor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lia-attentive-processing")
    }

    private var audioLoop: AttentiveAudioLoop? = null
    private var transcriber: SherpaWhisperCommandTranscriber? = null
    private var speakerEngine: SherpaSpeakerEngine? = null

    @Volatile
    private var followUpDeadlineElapsedMs: Long = 0L

    fun start(): AttentionStartResult {
        if (!preferences.alwaysListeningEnabled) {
            onStatus(
                AttentionStatus(
                    AttentionState.STOPPED,
                    "Modo siempre atento desactivado."
                )
            )
            return AttentionStartResult.Disabled
        }

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return needsSetup("Falta permiso de micrófono para el modo siempre atento.")
        }

        val whisperModel = commandModels.installedModel()
            ?: return needsSetup(
                "Prepara primero la escucha offline para activar el modo siempre atento."
            )

        if (preferences.voiceProtectionEnabled) {
            if (!voiceProfiles.hasProfile()) {
                return needsSetup(
                    "Registra tu identidad de voz antes de usar escucha continua protegida."
                )
            }
            if (!voiceModels.isInstalled()) {
                return needsSetup(
                    "Falta el modelo local de identidad de voz."
                )
            }
        }

        if (audioLoop?.isRunning() == true) {
            return AttentionStartResult.Started
        }

        transcriber = SherpaWhisperCommandTranscriber(whisperModel)
        audioLoop = AttentiveAudioLoop(
            context = appContext,
            onUtterance = ::enqueueUtterance,
            onError = { error ->
                onStatus(
                    AttentionStatus(
                        AttentionState.ERROR,
                        error.message ?: "Falló la escucha continua."
                    )
                )
            }
        ).also { it.start() }

        onStatus(
            AttentionStatus(
                AttentionState.LISTENING,
                "Lía está atenta localmente a la frase de activación."
            )
        )
        return AttentionStartResult.Started
    }

    private fun enqueueUtterance(audio: FloatArray) {
        if (!processing.compareAndSet(false, true)) {
            audio.fill(0f)
            return
        }

        processor.execute {
            try {
                onStatus(
                    AttentionStatus(
                        AttentionState.PROCESSING,
                        "Analizando una frase localmente."
                    )
                )

                val speechToText = transcriber ?: return@execute
                val transcript = speechToText.transcribe(audio)
                val now = SystemClock.elapsedRealtime()
                val followUpActive =
                    followUpDeadlineElapsedMs > 0L &&
                        now <= followUpDeadlineElapsedMs

                if (followUpActive && transcript.text.isNotBlank()) {
                    followUpDeadlineElapsedMs = 0L
                    val verification = verifyIfNeeded(audio)
                    if (verification.matched) {
                        audioLoop?.muteFor(COMMAND_COOLDOWN_MS)
                        onCommand(transcript.text.trim(), verification)
                    }
                } else {
                    if (followUpDeadlineElapsedMs > 0L) {
                        followUpDeadlineElapsedMs = 0L
                    }

                    when (val wake = WakePhraseMatcher.match(transcript.text)) {
                        null -> Unit

                        WakePhraseMatch.WakeOnly -> {
                            followUpDeadlineElapsedMs =
                                SystemClock.elapsedRealtime() + FOLLOW_UP_WINDOW_MS
                            audioLoop?.muteFor(WAKE_ACK_MUTE_MS)
                            onWakeOnly()
                        }

                        is WakePhraseMatch.Command -> {
                            val verification = verifyIfNeeded(audio)
                            if (verification.matched) {
                                audioLoop?.muteFor(COMMAND_COOLDOWN_MS)
                                onCommand(wake.command, verification)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                onStatus(
                    AttentionStatus(
                        AttentionState.ERROR,
                        error.message ?: "No pude procesar la escucha continua."
                    )
                )
            } finally {
                audio.fill(0f)
                processing.set(false)
                if (audioLoop?.isRunning() == true) {
                    onStatus(
                        AttentionStatus(
                            AttentionState.LISTENING,
                            "Lía está atenta localmente."
                        )
                    )
                }
            }
        }
    }

    private fun verifyIfNeeded(audio: FloatArray): VoiceVerification {
        if (!preferences.voiceProtectionEnabled) {
            return VoiceVerification(
                matched = true,
                score = 1f,
                threshold = 0f,
                message = "Protección por voz desactivada por la persona usuaria."
            )
        }

        val engine = speakerEngine ?: SherpaSpeakerEngine(voiceModels.modelFile)
            .also { speakerEngine = it }

        return OwnerVoiceAuthenticator(
            engine = engine,
            store = voiceProfiles
        ).verifyAudio(audio)
    }

    private fun needsSetup(reason: String): AttentionStartResult.NeedsSetup {
        onStatus(AttentionStatus(AttentionState.NEEDS_SETUP, reason))
        return AttentionStartResult.NeedsSetup(reason)
    }

    override fun close() {
        audioLoop?.close()
        audioLoop = null
        transcriber?.close()
        transcriber = null
        speakerEngine?.close()
        speakerEngine = null
        followUpDeadlineElapsedMs = 0L
        processing.set(false)
        processor.shutdownNow()
        onStatus(
            AttentionStatus(
                AttentionState.STOPPED,
                "Escucha continua detenida."
            )
        )
    }

    companion object {
        private const val FOLLOW_UP_WINDOW_MS = 8_000L
        private const val WAKE_ACK_MUTE_MS = 350L
        private const val COMMAND_COOLDOWN_MS = 2_500L
    }
}
