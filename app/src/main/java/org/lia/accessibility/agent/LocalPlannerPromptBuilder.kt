package org.lia.accessibility.agent

import org.lia.accessibility.accessibility.ScreenSnapshot

object LocalPlannerPromptBuilder {
    private const val MAX_SCREEN_CHARS = 6_000
    private const val MAX_EVENT_COUNT = 8

    fun buildSystemPrompt(): String = buildString {
        appendLine("Eres el planificador local de Lía, un agente de accesibilidad para Android.")
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

        LiaToolCatalog.tools.forEach { tool ->
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
        state: PhoneState,
        screen: ScreenSnapshot,
        events: List<AgentEvent>
    ): String {
        val screenText = screen.compactDescription()
            .take(MAX_SCREEN_CHARS)
            .ifBlank { "[sin texto accesible]" }

        val recentEvents = events
            .takeLast(MAX_EVENT_COUNT)
            .joinToString("\n") { event ->
                buildString {
                    append(event.step)
                    append(":")
                    append(event.type.name.lowercase())
                    event.actionKey?.let {
                        append(":accion=")
                        append(it.take(120))
                    }
                    append(":")
                    append(event.message.take(320))
                }
            }
            .ifBlank { "[sin acciones anteriores]" }

        return buildString {
            appendLine("OBJETIVO:")
            appendLine(goal.take(2_000))
            appendLine()
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
