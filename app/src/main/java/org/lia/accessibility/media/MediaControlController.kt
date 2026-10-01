package org.lia.accessibility.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.lia.accessibility.notifications.LiaNotificationListenerService

enum class MediaCommand {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    STOP
}

class MediaControlController(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent =
        ComponentName(context, LiaNotificationListenerService::class.java)

    fun perform(command: MediaCommand): Boolean =
        runCatching {
            val controller = bestController() ?: return@runCatching false
            val controls = controller.transportControls

            when (command) {
                MediaCommand.PLAY -> controls.play()
                MediaCommand.PAUSE -> controls.pause()
                MediaCommand.PLAY_PAUSE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                MediaCommand.NEXT -> controls.skipToNext()
                MediaCommand.PREVIOUS -> controls.skipToPrevious()
                MediaCommand.STOP -> controls.stop()
            }

            true
        }.getOrDefault(false)

    fun activePackage(): String? =
        runCatching { bestController()?.packageName }.getOrNull()

    private fun bestController(): MediaController? {
        val sessions = manager.getActiveSessions(listenerComponent)
        return sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
    }
}
