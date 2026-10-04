package org.lia.accessibility.agent

import java.text.Normalizer
import java.util.Locale

enum class LiaAgentRole(
    val displayName: String,
    val mission: String
) {
    GENERAL(
        displayName = "General",
        mission = "Resuelve conversaciones y tareas mixtas. Usa el mínimo número de herramientas necesarias."
    ),
    NAVIGATION(
        displayName = "Navegación",
        mission = "Especialista en interfaces Android. Prioriza el árbol semántico, acciones precisas y verificación tras cada cambio de pantalla."
    ),
    COMMUNICATION(
        displayName = "Comunicación",
        mission = "Especialista en mensajes, llamadas y notificaciones. Conserva el contexto del destinatario y respeta siempre las autorizaciones sensibles."
    ),
    VISION(
        displayName = "Visión",
        mission = "Especialista en OCR, cámara y comprensión visual. Prefiere observar antes de actuar y declara incertidumbre cuando la percepción no sea suficiente."
    ),
    DEVICE(
        displayName = "Dispositivo",
        mission = "Especialista en ajustes y utilidades del teléfono: conectividad, Bluetooth, linterna, volumen, multimedia, alarmas y temporizadores."
    )
}

object LiaAgentRoleSelector {
    fun select(goal: String): LiaAgentRole {
        val text = normalize(goal)

        return when {
            containsAny(
                text,
                "camara", "ver entorno", "que ves", "leer pantalla", "ocr",
                "objeto", "producto", "billete", "color", "foto", "imagen"
            ) -> LiaAgentRole.VISION

            containsAny(
                text,
                "whatsapp", "mensaje", "sms", "notificacion", "responder",
                "llamar", "llamada", "contacto"
            ) -> LiaAgentRole.COMMUNICATION

            containsAny(
                text,
                "bluetooth", "internet", "wifi", "linterna", "volumen",
                "musica", "reproduc", "alarma", "temporizador"
            ) -> LiaAgentRole.DEVICE

            containsAny(
                text,
                "abre", "abrir", "busca", "buscar", "toca", "pulsa",
                "escribe", "scroll", "atras", "inicio", "recientes", "app"
            ) -> LiaAgentRole.NAVIGATION

            else -> LiaAgentRole.GENERAL
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean =
        terms.any { text.contains(it) }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
}
