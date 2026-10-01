package org.lia.accessibility.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService

class LiaVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        launchLia()
    }

    private fun launchLia() {
        showSession(
            Bundle().apply {
                putBoolean(EXTRA_START_LISTENING, true)
            },
            0
        )
    }

    companion object {
        const val EXTRA_START_LISTENING = "lia_start_listening"
    }
}
