package org.lia.accessibility.accessibility

import org.lia.accessibility.security.ActionRisk
import org.lia.accessibility.security.AuthorizationDecision
import org.lia.accessibility.security.CommandAuthorizationGate
import org.lia.accessibility.voice.VoiceVerification

sealed interface ScreenObservationResult {
    data class Allowed(val snapshot: ScreenSnapshot) : ScreenObservationResult
    data class Denied(val reason: String) : ScreenObservationResult
}

class AuthorizedAndroidController(
    private val service: LiaAccessibilityService,
    private val treeReader: AccessibilityTreeReader = AccessibilityTreeReader(),
    private val authorizationGate: CommandAuthorizationGate = CommandAuthorizationGate()
) {
    private val executor = AccessibilityActionExecutor(service, treeReader)

    fun observe(voice: VoiceVerification): ScreenObservationResult {
        val authorization = authorizationGate.authorize(
            voice = voice,
            risk = ActionRisk.ROUTINE
        )

        if (authorization !is AuthorizationDecision.Allowed) {
            return ScreenObservationResult.Denied(reasonFor(authorization))
        }

        val root = service.rootInActiveWindow
            ?: return ScreenObservationResult.Denied(
                "Lía no puede leer la ventana activa en este momento."
            )

        return ScreenObservationResult.Allowed(
            treeReader.capture(root, service.windows.firstOrNull { it.isActive })
        )
    }

    fun execute(
        voice: VoiceVerification,
        action: AndroidUiAction,
        risk: ActionRisk = ActionRisk.ROUTINE,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): UiExecutionResult {
        val authorization = authorizationGate.authorize(
            voice = voice,
            risk = risk,
            secondFactorSatisfied = secondFactorSatisfied,
            explicitConfirmation = explicitConfirmation
        )

        if (authorization !is AuthorizationDecision.Allowed) {
            return UiExecutionResult(
                accepted = false,
                performed = false,
                message = reasonFor(authorization)
            )
        }

        return executor.execute(action)
    }

    fun verifyStateChanged(result: UiExecutionResult): Boolean =
        result.performed && executor.hasScreenChanged(result.beforeFingerprint)

    private fun reasonFor(decision: AuthorizationDecision): String =
        when (decision) {
            AuthorizationDecision.Allowed -> "Autorizado."
            is AuthorizationDecision.Denied -> decision.reason
            is AuthorizationDecision.NeedsSecondFactor -> decision.reason
            is AuthorizationDecision.NeedsExplicitConfirmation -> decision.reason
        }
}
