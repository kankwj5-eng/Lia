package org.lia.accessibility.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import org.lia.accessibility.LiaPreferences
import org.lia.accessibility.accessibility.AccessibilityBridgeResult
import org.lia.accessibility.accessibility.LiaAccessibilityBridge
import org.lia.accessibility.voice.AndroidTtsFallback
import org.lia.accessibility.voice.VoiceVerification
import org.lia.accessibility.voice.listening.AttentionState
import org.lia.accessibility.voice.listening.AttentionStatus
import org.lia.accessibility.voice.listening.AttentiveVoiceController

class LiaVoiceInteractionService : VoiceInteractionService() {
    private var attentionController: AttentiveVoiceController? = null
    private var fallbackTts: AndroidTtsFallback? = null

    @Volatile
    private var shuttingDown = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH_ATTENTION) {
                refreshAttention()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        shuttingDown = false
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            IntentFilter(ACTION_REFRESH_ATTENTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onReady() {
        super.onReady()
        if (shuttingDown) return
        refreshAttention()
    }

    override fun onShutdown() {
        shuttingDown = true
        stopAttention()
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        launchLia()
    }

    private fun refreshAttention() {
        if (shuttingDown) return

        stopAttention()

        if (!LiaPreferences(this).alwaysListeningEnabled) {
            emitAttentionStatus(
                AttentionStatus(
                    state = AttentionState.STOPPED,
                    message = "Modo siempre atento desactivado."
                )
            )
            return
        }

        attentionController = AttentiveVoiceController(
            context = this,
            onCommand = ::handleWakeCommand,
            onWakeOnly = {
                LiaAccessibilityBridge.signalListening()
                launchLia()
            },
            onStatus = ::emitAttentionStatus
        ).also { controller ->
            controller.start()
        }
    }

    private fun handleWakeCommand(
        command: String,
        verification: VoiceVerification
    ) {
        LiaAccessibilityBridge.signalListening()

        when (
            val result = LiaAccessibilityBridge.startGoal(
                goal = command,
                verification = verification
            )
        ) {
            AccessibilityBridgeResult.Started -> {
                emitAttentionStatus(
                    AttentionStatus(
                        state = AttentionState.LISTENING,
                        message = "Orden enviada al agente: " + command.take(120)
                    )
                )
            }

            is AccessibilityBridgeResult.Unavailable -> {
                speakFallback(result.reason)
                launchLia(
                    transcript = command,
                    verification = verification
                )
            }
        }
    }

    private fun launchLia(
        transcript: String? = null,
        verification: VoiceVerification? = null
    ) {
        showSession(
            Bundle().apply {
                putBoolean(EXTRA_START_LISTENING, transcript.isNullOrBlank())
                transcript?.takeIf { it.isNotBlank() }?.let {
                    putString(EXTRA_TRANSCRIPT, it)
                }
                verification?.let {
                    putBoolean(EXTRA_VOICE_MATCHED, it.matched)
                    putFloat(EXTRA_VOICE_SCORE, it.score)
                    putFloat(EXTRA_VOICE_THRESHOLD, it.threshold)
                }
            },
            0
        )
    }

    private fun emitAttentionStatus(status: AttentionStatus) {
        sendBroadcast(
            Intent(ACTION_ATTENTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ATTENTION_STATE, status.state.name)
                .putExtra(EXTRA_ATTENTION_MESSAGE, status.message)
        )
    }

    private fun speakFallback(text: String) {
        if (text.isBlank()) return
        val speaker = fallbackTts ?: AndroidTtsFallback(this)
            .also { fallbackTts = it }
        speaker.speak(text)
    }

    private fun stopAttention() {
        attentionController?.close()
        attentionController = null
    }

    override fun onDestroy() {
        shuttingDown = true
        stopAttention()
        runCatching { unregisterReceiver(refreshReceiver) }
        fallbackTts?.close()
        fallbackTts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_LISTENING = "lia_start_listening"
        const val EXTRA_TRANSCRIPT = "lia_voice_transcript"
        const val EXTRA_VOICE_MATCHED = "lia_voice_matched"
        const val EXTRA_VOICE_SCORE = "lia_voice_score"
        const val EXTRA_VOICE_THRESHOLD = "lia_voice_threshold"

        const val ACTION_REFRESH_ATTENTION =
            "org.lia.accessibility.action.REFRESH_ATTENTION"
        const val ACTION_ATTENTION_STATE =
            "org.lia.accessibility.action.ATTENTION_STATE"
        const val EXTRA_ATTENTION_STATE = "attention_state"
        const val EXTRA_ATTENTION_MESSAGE = "attention_message"
    }
}
