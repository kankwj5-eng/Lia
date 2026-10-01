package org.lia.accessibility.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import org.lia.accessibility.MainActivity

class LiaVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context) {
    override fun onShow(
        args: Bundle?,
        showFlags: Int
    ) {
        super.onShow(args, showFlags)

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(
                    MainActivity.EXTRA_START_VOICE_COMMAND,
                    args?.getBoolean(
                        LiaVoiceInteractionService.EXTRA_START_LISTENING,
                        true
                    ) ?: true
                )
        )

        // Finishing synchronously inside onShow can invalidate the session token
        // before the framework has completed showing it.
        Handler(Looper.getMainLooper()).post {
            finish()
        }
    }
}
