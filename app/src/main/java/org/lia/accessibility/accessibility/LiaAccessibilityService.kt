package org.lia.accessibility.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.lia.accessibility.device.HapticFeedback
import org.lia.accessibility.device.HapticSignal
import org.lia.accessibility.safety.HardwareShortcut
import org.lia.accessibility.safety.HardwareShortcutDetector

class LiaAccessibilityService : AccessibilityService() {
    private val shortcuts = HardwareShortcutDetector()
    private lateinit var haptics: HapticFeedback

    override fun onServiceConnected() {
        super.onServiceConnected()
        haptics = HapticFeedback(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Próxima capa: convertir el árbol AccessibilityNodeInfo en un estado semántico.
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (shortcuts.onKeyEvent(event)) {
            HardwareShortcut.SOS -> {
                haptics.signal(HapticSignal.SOS)
                sendBroadcast(
                    Intent(ACTION_SOS_TRIGGERED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SOURCE, "volume_triple_press")
                )
            }
            HardwareShortcut.ACTIVATE_LIA -> {
                haptics.signal(HapticSignal.LISTENING)
                sendBroadcast(
                    Intent(ACTION_ACTIVATE_LIA)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SOURCE, "bluetooth_or_headset_button")
                )
            }
            null -> Unit
        }

        // Observar sin consumir mantiene el comportamiento normal de volumen/media.
        return false
    }

    internal fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    internal fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    internal fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    internal fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null,
            null
        )
    }

    internal fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            null,
            null
        )
    }

    companion object {
        const val ACTION_SOS_TRIGGERED = "org.lia.accessibility.action.SOS_TRIGGERED"
        const val ACTION_ACTIVATE_LIA = "org.lia.accessibility.action.ACTIVATE_LIA"
        const val EXTRA_SOURCE = "source"
    }
}
