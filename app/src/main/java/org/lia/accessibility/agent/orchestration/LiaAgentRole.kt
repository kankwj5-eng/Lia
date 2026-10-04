package org.lia.accessibility.agent.orchestration

import java.text.Normalizer
import java.util.Locale

enum class LiaAgentRole(
    val displayName: String,
    val mission: String
) {
    GENERAL(
        displayName = "General",
        mission = "Coordina conversaciones y tareas mixtas. Mantén una sola línea de control y usa el mínimo número de herramientas."
    ),
    NAVIGATION(
        displayName = "Navegación",
        mission = "Especialista en interfaces Android. Prioriza el árbol semántico, acciones precisas y verificación tras cada cambio de pantalla."
    ),
    COMMUNICATION(
        displayName = "Comunicación",
        mission = "Especialista en mensajes, llamadas y notificaciones. Conserva el destinatario correcto y respeta siempre las autorizaciones sensibles."
    ),
    VISION(
        displayName = "Visión",
        mission = "Especialista en OCR, cámara y percepción. Observa antes de concluir y declara incertidumbre cuando la evidencia visual no sea suficiente."
    ),
    DEVICE(
        displayName = "Dispositivo",
        mission = "Especialista en utilidades del teléfono: conectividad, Bluetooth, linterna, volumen, multimedia, alarmas y temporizadores."
    )
}

object LiaAgentRoleSelector {
    private val signals = mapOf(
        LiaAgentRole.VISION to setOf(
            "camara", "ver entorno", "que ves", "leer pantalla", "ocr",
            "objeto", "producto", "billete", "color", "foto", "imagen"
        ),
        LiaAgentRole.COMMUNICATION to setOf(
            "whatsapp", "mensaje", "sms", "notificacion", "responder",
            "llamar", "llamada", "contacto"
        ),
        LiaAgentRole.DEVICE to setOf(
            "bluetooth", "internet", "wifi", "linterna", "volumen",
            "musica", "reproduc", "alarma", "temporizador"
        ),
        LiaAgentRole.NAVIGATION to setOf(
            "abre", "abrir", "busca", "buscar", "toca", "pulsa",
            "escribe", "scroll", "atras", "inicio", "recientes", "app"
        )
    )

    fun select(goal: String): LiaAgentRole {
        val text = normalize(goal)
        val scores = signals.mapValues { (_, terms) ->
            terms.count { termMatches(text, it) }
        }

        val active = scores
            .filterValues { it > 0 }
            .entries
            .sortedByDescending { it.value }

        if (active.isEmpty()) return LiaAgentRole.GENERAL

        // Una orden que cruza dominios queda en el coordinador general.
        val strongest = active.first()
        val otherDomains = active.drop(1).count { it.value > 0 }
        if (otherDomains > 0) return LiaAgentRole.GENERAL

        return strongest.key
    }

    private fun termMatches(text: String, term: String): Boolean {
        val normalizedTerm = normalize(term)
        return if (' ' in normalizedTerm) {
            text.contains(normalizedTerm)
        } else {
            Regex("(^|\\s)" + Regex.escape(normalizedTerm) + "(\\s|$)")
                .containsMatchIn(text)
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
}
