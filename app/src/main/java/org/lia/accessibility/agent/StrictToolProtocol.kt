package org.lia.accessibility.agent

import org.json.JSONObject
import org.lia.accessibility.security.ActionRisk

sealed interface ToolValue {
    data class Text(val value: String) : ToolValue
    data class IntegerValue(val value: Long) : ToolValue
    data class BooleanValue(val value: Boolean) : ToolValue
}

data class LiaToolCall(
    val name: String,
    val arguments: Map<String, ToolValue>,
    val risk: ActionRisk,
    val safeToRetry: Boolean
) {
    val actionKey: String
        get() = buildString {
            append(name)
            arguments.toSortedMap().forEach { (key, value) ->
                append('|').append(key).append('=')
                when (value) {
                    is ToolValue.Text -> append(value.value)
                    is ToolValue.IntegerValue -> append(value.value)
                    is ToolValue.BooleanValue -> append(value.value)
                }
            }
        }
}

sealed interface ToolProtocolResult {
    data class Accepted(val call: LiaToolCall) : ToolProtocolResult
    data class Rejected(val reason: String) : ToolProtocolResult
}

object StrictToolProtocol {
    private const val MAX_RESPONSE_CHARS = 8_000
    private const val MAX_TEXT_ARGUMENT_CHARS = 2_000
    private val topLevelKeys = setOf("tool", "arguments")

    fun parse(raw: String): ToolProtocolResult {
        val text = normalizeModelResponse(raw)

        if (text.isEmpty()) {
            return ToolProtocolResult.Rejected("El modelo no devolvió ninguna acción.")
        }

        if (text.length > MAX_RESPONSE_CHARS) {
            return ToolProtocolResult.Rejected("La respuesta del modelo es demasiado grande.")
        }

        if (!text.startsWith('{') || !text.endsWith('}') || text.contains(96.toChar().toString().repeat(3))) {
            return ToolProtocolResult.Rejected(
                "El modelo debe devolver exactamente un objeto JSON, sin texto adicional."
            )
        }

        val root = runCatching { JSONObject(text) }.getOrElse {
            return ToolProtocolResult.Rejected("La acción no contiene JSON válido.")
        }

        val unexpectedTopKeys = root.keys().asSequence().filter { it !in topLevelKeys }.toList()
        if (unexpectedTopKeys.isNotEmpty()) {
            return ToolProtocolResult.Rejected(
                "Campos superiores no permitidos: " + unexpectedTopKeys.joinToString()
            )
        }

        val toolName = root.optString("tool", "").trim()
        if (toolName.isEmpty()) {
            return ToolProtocolResult.Rejected("Falta el nombre de la herramienta.")
        }

        val argumentsObject = when {
            !root.has("arguments") -> JSONObject()
            root.opt("arguments") is JSONObject -> root.getJSONObject("arguments")
            else -> return ToolProtocolResult.Rejected(
                "El campo arguments debe ser un objeto JSON."
            )
        }

        val values = linkedMapOf<String, ToolValue>()
        val iterator = argumentsObject.keys()

        while (iterator.hasNext()) {
            val key = iterator.next()
            val converted = convert(argumentsObject.opt(key))
                ?: return ToolProtocolResult.Rejected(
                    "El argumento " + key + " tiene un tipo no permitido."
                )
            values[key] = converted
        }

        return validate(toolName, values)
    }

    fun validate(
        toolName: String,
        arguments: Map<String, ToolValue>
    ): ToolProtocolResult {
        val spec = LiaToolCatalog.find(toolName)
            ?: return ToolProtocolResult.Rejected("Herramienta desconocida: " + toolName)

        val expectedNames = spec.parameters.map { it.name }.toSet()
        val unknown = arguments.keys - expectedNames

        if (unknown.isNotEmpty()) {
            return ToolProtocolResult.Rejected(
                "Argumentos no permitidos para " + toolName + ": " + unknown.joinToString()
            )
        }

        val missing = spec.parameters
            .filter { it.required && it.name !in arguments }
            .map { it.name }

        if (missing.isNotEmpty()) {
            return ToolProtocolResult.Rejected(
                "Faltan argumentos para " + toolName + ": " + missing.joinToString()
            )
        }

        for (parameter in spec.parameters) {
            val value = arguments[parameter.name] ?: continue

            val typeMatches = when (parameter.type) {
                ToolParameterType.STRING,
                ToolParameterType.ENUM -> value is ToolValue.Text

                ToolParameterType.INTEGER -> value is ToolValue.IntegerValue
                ToolParameterType.BOOLEAN -> value is ToolValue.BooleanValue
            }

            if (!typeMatches) {
                return ToolProtocolResult.Rejected(
                    "El argumento " + parameter.name + " tiene un tipo incorrecto."
                )
            }

            if (value is ToolValue.Text) {
                if (value.value.length > MAX_TEXT_ARGUMENT_CHARS) {
                    return ToolProtocolResult.Rejected(
                        "El argumento " + parameter.name + " supera el tamaño permitido."
                    )
                }

                if (
                    parameter.type == ToolParameterType.ENUM &&
                    value.value !in parameter.allowedValues
                ) {
                    return ToolProtocolResult.Rejected(
                        "Valor no permitido para " + parameter.name + ": " + value.value
                    )
                }
            }
        }

        return validateRanges(
            LiaToolCall(
                name = spec.name,
                arguments = arguments.toMap(),
                risk = spec.risk,
                safeToRetry = spec.safeToRetry
            )
        )
    }

    private fun validateRanges(call: LiaToolCall): ToolProtocolResult {
        fun integer(name: String): Long? =
            (call.arguments[name] as? ToolValue.IntegerValue)?.value

        val invalid = when (call.name) {
            "set_alarm" -> {
                val hour = integer("hour")
                    ?: return ToolProtocolResult.Rejected("Falta hour.")
                val minute = integer("minute")
                    ?: return ToolProtocolResult.Rejected("Falta minute.")
                hour !in 0..23 || minute !in 0..59
            }

            "set_timer" -> {
                val seconds = integer("seconds")
                    ?: return ToolProtocolResult.Rejected("Falta seconds.")
                seconds !in 1..86_400
            }

            else -> false
        }

        if (invalid) {
            return ToolProtocolResult.Rejected(
                "Uno o más argumentos están fuera del rango permitido."
            )
        }

        return ToolProtocolResult.Accepted(call)
    }

    internal fun normalizeModelResponse(raw: String): String {
        var text = raw.trim()

        // Algunos modelos de razonamiento (por ejemplo ciertas variantes de
        // Qwen) pueden anteponer un bloque <think> aunque se les pida JSON.
        // Lía ignora únicamente ese envoltorio conocido y mantiene el resto
        // del protocolo estrictamente JSON.
        val thinkingEnd = text.lastIndexOf("</think>")
        if (thinkingEnd >= 0) {
            text = text
                .substring(thinkingEnd + "</think>".length)
                .trim()
        }

        return text
    }

    private fun convert(value: Any?): ToolValue? =
        when (value) {
            is String -> ToolValue.Text(value)
            is Boolean -> ToolValue.BooleanValue(value)
            is Byte -> ToolValue.IntegerValue(value.toLong())
            is Short -> ToolValue.IntegerValue(value.toLong())
            is Int -> ToolValue.IntegerValue(value.toLong())
            is Long -> ToolValue.IntegerValue(value)
            else -> null
        }
}
