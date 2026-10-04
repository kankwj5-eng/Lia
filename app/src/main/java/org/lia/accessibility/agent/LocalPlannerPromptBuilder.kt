package org.lia.accessibility.agent

import org.lia.accessibility.accessibility.ScreenSnapshot
import org.lia.accessibility.agent.orchestration.LiaAgentProfile
import org.lia.accessibility.agent.orchestration.LiaAgentProfiles
import org.lia.accessibility.agent.context.AgentContextPolicy
import org.lia.accessibility.agent.context.DefaultAgentContextPolicy

object LocalPlannerPromptBuilder {
    fun buildSystemPrompt(
        profile: LiaAgentProfile = LiaAgentProfiles.forRole(LiaAgentRole.GENERAL)
    ): String = buildString {
        appendLine("Eres el planificador local de Lía, un agente de accesibilidad para Android.")
        appendLine("Rol especialista activo: " + profile.role.displayName + ".")
        appendLine(profile.role.mission)
        appendLine("Tu única salida válida es UN objeto JSON con esta forma:")
        appendLine("{\"tool\":\"nombre\",\"arguments\":{\"argumento\":\"valor\"}}")
        appendLine("No uses Markdown. No escribas explicaciones fuera del JSON.")
        appendLine("No inventes herramientas ni argumentos.")
        appendLine("Observa de nuevo después de cada acción y evita repetir acciones sin progreso.")
        appendLine("Las acciones sensibles pueden requerir una segunda comprobación; nunca intentes evadirla.")
        appendLine("Un click genérico puede elevarse a sensible o irreversible por el texto del control.")
        appendLine("Si el usuario solo conversa, pregunta algo o pide una explicación que no requiere actuar en Android, usa finish con la respuesta final.")
        appendLine("No uses herramientas del teléfono cuando una respuesta conversacional sea suficiente.")
        appendLine()
        appendLine("Herramientas disponibles:")

        LiaToolCatalog.forProfile(profile).forEach { tool ->
            append("- ").append(tool.name)
                .append(" [").append(tool.risk.name.lowercase()).append("]")
                .append(": ").append(tool.description)

            if (tool.parameters.isNotEmpty()) {
                append(" Argumentos: ")
                append(
                    tool.parameters.joinToString { parameter ->
                        val enumValues = if (parameter.allowedValues.isEmpty()) {
                            ""
                        } else {
                            "=" + parameter.allowedValues.joinToString("|")
                        }
                        parameter.name + ":" +
                            parameter.type.name.lowercase() +
                            enumValues
                    }
                )
            }
            appendLine()
        }
    }

    fun buildTurn(
        goal: String,
        conversationContext: String,
        state: PhoneState,
        screen: ScreenSnapshot,
        events: List<AgentEvent>,
        policy: AgentContextPolicy = DefaultAgentContextPolicy.value
    ): String {
        val screenText = screen.compactDescription()
            .take(policy.maxScreenChars)
            .ifBlank { "[sin texto accesible]" }

        val recentEvents = events
            .takeLast(policy.maxRecentEvents)
            .joinToString("\n") { event ->
                buildString {
                    append(event.step)
                    append(":")
                    append(event.type.name.lowercase())
                    event.actionKey?.let {
                        append(":accion=")
                        append(it.take(policy.maxActionKeyChars))
                    }
                    append(":")
                    append(event.message.take(policy.maxEventMessageChars))
                }
            }
            .ifBlank { "[sin acciones anteriores]" }

        return buildString {
            appendLine("OBJETIVO:")
            appendLine(goal.take(policy.maxGoalChars))
            appendLine()
            if (conversationContext.isNotBlank()) {
                appendLine("CONVERSACIÓN RECIENTE:")
                appendLine(conversationContext.takeLast(policy.maxConversationChars))
                appendLine()
            }
            appendLine("ESTADO DEL TELÉFONO:")
            appendLine(state.compactText())
            appendLine()
            appendLine("PANTALLA ACTUAL:")
            appendLine(screenText)
            appendLine()
            appendLine("HISTORIAL RECIENTE:")
            appendLine(recentEvents)
            appendLine()
            append("Devuelve exactamente una herramienta JSON.")
        }
    }
}
