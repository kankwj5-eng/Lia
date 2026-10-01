package org.lia.accessibility.safety

import android.view.KeyEvent
import java.util.ArrayDeque

enum class HardwareShortcut {
    SOS,
    ACTIVATE_LIA
}

class HardwareShortcutDetector(
    private val now: () -> Long = System::currentTimeMillis
) {
    private val volumeDownTimes = ArrayDeque<Long>()

    fun onKeyEvent(event: KeyEvent): HardwareShortcut? {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null

        if (event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        ) {
            return HardwareShortcut.ACTIVATE_LIA
        }

        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return null

        val current = now()
        volumeDownTimes.addLast(current)
        while (volumeDownTimes.isNotEmpty() && current - volumeDownTimes.first() > SOS_WINDOW_MS) {
            volumeDownTimes.removeFirst()
        }

        return if (volumeDownTimes.size >= 3) {
            volumeDownTimes.clear()
            HardwareShortcut.SOS
        } else {
            null
        }
    }

    companion object {
        const val SOS_WINDOW_MS = 1_500L
    }
}
