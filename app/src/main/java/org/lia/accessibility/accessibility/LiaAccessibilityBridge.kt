package org.lia.accessibility.accessibility

import java.lang.ref.WeakReference
import org.lia.accessibility.device.HapticSignal
import org.lia.accessibility.voice.VoiceVerification

sealed interface AccessibilityBridgeResult {
    data object Started : AccessibilityBridgeResult
    data class Unavailable(val reason: String) : AccessibilityBridgeResult
}

object LiaAccessibilityBridge {
    @Volatile
    private var serviceReference: WeakReference<LiaAccessibilityService>? = null

    internal fun attach(service: LiaAccessibilityService) {
        serviceReference = WeakReference(service)
    }

    internal fun detach(service: LiaAccessibilityService) {
        val current = serviceReference?.get()
        if (current === service) {
            serviceReference?.clear()
            serviceReference = null
        }
    }

    fun isConnected(): Boolean =
        serviceReference?.get() != null

    fun startGoal(
        goal: String,
        verification: VoiceVerification
    ): AccessibilityBridgeResult {
        if (goal.isBlank()) {
            return AccessibilityBridgeResult.Unavailable(
                "La orden está vacía."
            )
        }

        val service = serviceReference?.get()
            ?: return AccessibilityBridgeResult.Unavailable(
                "Activa primero el servicio de accesibilidad de Lía."
            )

        service.startLocalAgentGoal(
            goal = goal,
            voice = verification
        )

        return AccessibilityBridgeResult.Started
    }

    fun setBubbleEnabled(enabled: Boolean): Boolean {
        val service = serviceReference?.get() ?: return false
        service.setFloatingBubbleEnabled(enabled)
        return true
    }

    fun signalListening(): Boolean {
        val service = serviceReference?.get() ?: return false
        return service.signalHaptic(HapticSignal.LISTENING)
    }

    fun cancel(): Boolean {
        val service = serviceReference?.get() ?: return false
        service.cancelLocalAgentGoal()
        return true
    }
}
