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

        val launchIntent = Intent(context, MainActivity::class.java)
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

        args?.getString(LiaVoiceInteractionService.EXTRA_TRANSCRIPT)
            ?.takeIf { it.isNotBlank() }
            ?.let { transcript ->
                launchIntent
                    .putExtra(MainActivity.EXTRA_VOICE_TRANSCRIPT, transcript)
                    .putExtra(
                        MainActivity.EXTRA_VOICE_MATCHED,
                        args.getBoolean(
                            LiaVoiceInteractionService.EXTRA_VOICE_MATCHED,
                            false
                        )
                    )
                    .putExtra(
                        MainActivity.EXTRA_VOICE_SCORE,
                        args.getFloat(
                            LiaVoiceInteractionService.EXTRA_VOICE_SCORE,
                            0f
                        )
                    )
                    .putExtra(
                        MainActivity.EXTRA_VOICE_THRESHOLD,
                        args.getFloat(
                            LiaVoiceInteractionService.EXTRA_VOICE_THRESHOLD,
                            0f
                        )
                    )
            }

        context.startActivity(launchIntent)

        // Finishing synchronously inside onShow can invalidate the session token
        // before the framework has completed showing it.
        Handler(Looper.getMainLooper()).post {
            finish()
        }
    }
}
