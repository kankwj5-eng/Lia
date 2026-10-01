package org.lia.accessibility.agent

import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.accessibility.ScreenObservationResult
import org.lia.accessibility.security.AuthorizationDecision
import org.lia.accessibility.security.CommandAuthorizationGate
import org.lia.accessibility.voice.VoiceVerification

data class ToolExecutionResult(
    val accepted: Boolean,
    val performed: Boolean,
    val message: String,
    val data: List<String> = emptyList(),
    val requiresAsyncHandler: Boolean = false,
    val completionCandidate: String? = null
)

class AuthorizedToolExecutor(
    private val service: LiaAccessibilityService,
    private val authorizationGate: CommandAuthorizationGate = CommandAuthorizationGate()
) {
    fun execute(
        call: LiaToolCall,
        voice: VoiceVerification,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): ToolExecutionResult {
        val routed = LiaToolRouter.route(call)

        if (routed is ToolRoutingResult.Rejected) {
            return ToolExecutionResult(
                accepted = false,
                performed = false,
                message = routed.reason
            )
        }

        val action = (routed as ToolRoutingResult.Routed).action
        val effectiveRisk = EffectiveToolRiskPolicy.effectiveRisk(call)

        return when (action) {
            is RoutedToolAction.Ui -> {
                val result = service.performAuthorizedAction(
                    voice = voice,
                    action = action.action,
                    risk = effectiveRisk,
                    secondFactorSatisfied = secondFactorSatisfied,
                    explicitConfirmation = explicitConfirmation
                )

                ToolExecutionResult(
                    accepted = result.accepted,
                    performed = result.performed,
                    message = result.message
                )
            }

            is RoutedToolAction.System -> {
                val result = service.performSystemCommand(
                    voice = voice,
                    command = action.command,
                    secondFactorSatisfied = secondFactorSatisfied,
                    explicitConfirmation = explicitConfirmation
                )

                ToolExecutionResult(
                    accepted = result.accepted,
                    performed = result.performed,
                    message = result.message,
                    data = result.data
                )
            }

            RoutedToolAction.ObserveScreen -> {
                when (val observation = service.observeScreen(voice)) {
                    is ScreenObservationResult.Allowed ->
                        ToolExecutionResult(
                            accepted = true,
                            performed = true,
                            message = "Pantalla observada.",
                            data = listOf(
                                observation.snapshot.compactDescription()
                                    .ifBlank { "[sin texto accesible]" }
                            )
                        )

                    is ScreenObservationResult.Denied ->
                        ToolExecutionResult(
                            accepted = false,
                            performed = false,
                            message = observation.reason
                        )
                }
            }

            RoutedToolAction.ReadScreenOcr ->
                asyncResult(
                    voice = voice,
                    risk = effectiveRisk,
                    secondFactorSatisfied = secondFactorSatisfied,
                    explicitConfirmation = explicitConfirmation,
                    message = "OCR requiere el manejador asíncrono del agente."
                )

            RoutedToolAction.GetLocation ->
                asyncResult(
                    voice = voice,
                    risk = effectiveRisk,
                    secondFactorSatisfied = secondFactorSatisfied,
                    explicitConfirmation = explicitConfirmation,
                    message = "Ubicación requiere el manejador asíncrono del agente."
                )

            RoutedToolAction.WorldVision ->
                asyncResult(
                    voice = voice,
                    risk = effectiveRisk,
                    secondFactorSatisfied = secondFactorSatisfied,
                    explicitConfirmation = explicitConfirmation,
                    message = "Visión del entorno requiere el manejador asíncrono del agente."
                )

            is RoutedToolAction.Finish ->
                ToolExecutionResult(
                    accepted = true,
                    performed = false,
                    message = "Finalización propuesta; falta verificación del entorno.",
                    completionCandidate = action.result
                )
        }
    }

    private fun asyncResult(
        voice: VoiceVerification,
        risk: org.lia.accessibility.security.ActionRisk,
        secondFactorSatisfied: Boolean,
        explicitConfirmation: Boolean,
        message: String
    ): ToolExecutionResult {
        val authorization = authorizationGate.authorize(
            voice = voice,
            risk = risk,
            secondFactorSatisfied = secondFactorSatisfied,
            explicitConfirmation = explicitConfirmation
        )

        return if (authorization is AuthorizationDecision.Allowed) {
            ToolExecutionResult(
                accepted = true,
                performed = false,
                message = message,
                requiresAsyncHandler = true
            )
        } else {
            ToolExecutionResult(
                accepted = false,
                performed = false,
                message = authorizationMessage(authorization)
            )
        }
    }

    private fun authorizationMessage(decision: AuthorizationDecision): String =
        when (decision) {
            AuthorizationDecision.Allowed -> "Autorizado."
            is AuthorizationDecision.Denied -> decision.reason
            is AuthorizationDecision.NeedsSecondFactor -> decision.reason
            is AuthorizationDecision.NeedsExplicitConfirmation -> decision.reason
        }
}
