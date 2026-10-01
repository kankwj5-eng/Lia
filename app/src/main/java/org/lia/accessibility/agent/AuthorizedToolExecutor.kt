package org.lia.accessibility.agent

import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.accessibility.ScreenObservationResult
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
    private val service: LiaAccessibilityService
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

        return when (action) {
            is RoutedToolAction.Ui -> {
                val result = service.performAuthorizedAction(
                    voice = voice,
                    action = action.action,
                    risk = call.risk,
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
                ToolExecutionResult(
                    accepted = true,
                    performed = false,
                    message = "OCR requiere el manejador asíncrono del agente.",
                    requiresAsyncHandler = true
                )

            RoutedToolAction.GetLocation ->
                ToolExecutionResult(
                    accepted = true,
                    performed = false,
                    message = "Ubicación requiere el manejador asíncrono del agente.",
                    requiresAsyncHandler = true
                )

            RoutedToolAction.WorldVision ->
                ToolExecutionResult(
                    accepted = true,
                    performed = false,
                    message = "Visión del entorno requiere el manejador asíncrono del agente.",
                    requiresAsyncHandler = true
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
}
