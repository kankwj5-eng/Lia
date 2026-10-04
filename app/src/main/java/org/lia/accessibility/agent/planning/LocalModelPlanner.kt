package org.lia.accessibility.agent.planning

import org.lia.accessibility.accessibility.ScreenSnapshot
import org.lia.accessibility.agent.AgentEvent
import org.lia.accessibility.agent.LiaToolCall
import org.lia.accessibility.agent.PhoneState
import org.lia.accessibility.agent.StrictToolProtocol
import org.lia.accessibility.agent.ToolProtocolResult
import org.lia.accessibility.agent.ToolValue
import org.lia.accessibility.ai.AgentTurn
import org.lia.accessibility.ai.LocalLanguageModel
import org.lia.accessibility.agent.orchestration.LiaAgentProfile

sealed interface LocalPlannerDecision {
    data class Act(val call: LiaToolCall) : LocalPlannerDecision
    data class Finish(val result: String) : LocalPlannerDecision
    data class ProtocolError(val reason: String) : LocalPlannerDecision
}

class LocalModelPlanner(
    private val model: LocalLanguageModel,
    private val maxProtocolAttempts: Int = 2
) {
    init {
        require(maxProtocolAttempts in 1..3)
    }

    suspend fun plan(
        goal: String,
        profile: LiaAgentProfile,
        conversationContext: String,
        state: PhoneState,
        screen: ScreenSnapshot,
        events: List<AgentEvent>
    ): LocalPlannerDecision {
        val userTurn = LocalPlannerPromptBuilder.buildTurn(
            goal = goal,
            conversationContext = conversationContext,
            state = state,
            screen = screen,
            events = events
        )

        var correction: String? = null
        var lastReason = "Respuesta inválida."

        repeat(maxProtocolAttempts) {
            val system = buildString {
                append(LocalPlannerPromptBuilder.buildSystemPrompt(profile))
                correction?.let {
                    appendLine()
                    appendLine()
                    appendLine("CORRECCIÓN DE PROTOCOLO:")
                    appendLine(it)
                    append("Devuelve una acción nueva y válida. No expliques el error.")
                }
            }

            val raw = runCatching {
                model.complete(
                    AgentTurn(
                        systemContext = system,
                        userText = userTurn
                    )
                )
            }.getOrElse { error ->
                return LocalPlannerDecision.ProtocolError(
                    "El modelo local falló: " + (error.message ?: "error desconocido")
                )
            }

            when (
                val parsed = StrictToolProtocol.parse(
                    raw = raw,
                    allowedToolNames = profile.toolNames
                )
            ) {
                is ToolProtocolResult.Accepted -> {
                    val call = parsed.call
                    return if (call.name == "finish") {
                        val value = call.arguments["result"] as? ToolValue.Text
                            ?: return LocalPlannerDecision.ProtocolError(
                                "finish no contiene un resultado válido."
                            )
                        LocalPlannerDecision.Finish(value.value)
                    } else {
                        LocalPlannerDecision.Act(call)
                    }
                }

                is ToolProtocolResult.Rejected -> {
                    lastReason = parsed.reason
                    correction =
                        "La respuesta anterior fue rechazada porque: " + parsed.reason
                }
            }
        }

        return LocalPlannerDecision.ProtocolError(
            "El modelo no produjo una herramienta válida tras " +
                maxProtocolAttempts + " intento(s): " + lastReason
        )
    }
}
