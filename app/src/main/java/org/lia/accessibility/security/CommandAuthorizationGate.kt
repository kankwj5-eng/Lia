package org.lia.accessibility.security

import org.lia.accessibility.voice.VoiceVerification

enum class ActionRisk { ROUTINE, SENSITIVE, IRREVERSIBLE }

sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision
    data class Denied(val reason: String) : AuthorizationDecision
    data class NeedsSecondFactor(val reason: String) : AuthorizationDecision
    data class NeedsExplicitConfirmation(val reason: String) : AuthorizationDecision
}

class CommandAuthorizationGate {
    fun authorize(
        voice: VoiceVerification,
        risk: ActionRisk,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): AuthorizationDecision {
        if (!voice.matched) {
            return AuthorizationDecision.Denied(
                "Lía no ejecutará la orden porque la voz no está autorizada."
            )
        }
        return when (risk) {
            ActionRisk.ROUTINE -> AuthorizationDecision.Allowed
            ActionRisk.SENSITIVE ->
                if (secondFactorSatisfied) AuthorizationDecision.Allowed
                else AuthorizationDecision.NeedsSecondFactor("Esta acción necesita una comprobación adicional.")
            ActionRisk.IRREVERSIBLE -> when {
                !secondFactorSatisfied -> AuthorizationDecision.NeedsSecondFactor(
                    "Esta acción necesita una comprobación adicional."
                )
                !explicitConfirmation -> AuthorizationDecision.NeedsExplicitConfirmation(
                    "Confirma explícitamente la acción antes de continuar."
                )
                else -> AuthorizationDecision.Allowed
            }
        }
    }
}
