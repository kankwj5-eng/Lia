package org.lia.accessibility.safety

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareShortcutDetectorTest {
    @Test
    fun tripleVolumeDownTriggersSos() {
        var now = 1_000L
        val detector = HardwareShortcutDetector { now }

        assertNull(detector.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        now += 300
        assertNull(detector.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        now += 300
        assertEquals(
            HardwareShortcut.SOS,
            detector.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN)
        )
    }
}
