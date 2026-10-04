package org.lia.accessibility.agent

import org.lia.accessibility.agent.orchestration.LiaAgentProfile
import org.lia.accessibility.security.ActionRisk

enum class ToolParameterType {
    STRING,
    INTEGER,
    BOOLEAN,
    ENUM
}

data class ToolParameterSpec(
    val name: String,
    val type: ToolParameterType,
    val required: Boolean = true,
    val description: String,
    val allowedValues: List<String> = emptyList()
)

data class LiaToolSpec(
    val name: String,
    val description: String,
    val risk: ActionRisk,
    val safeToRetry: Boolean,
    val parameters: List<ToolParameterSpec> = emptyList()
)

object LiaToolCatalog {
    val tools: List<LiaToolSpec> = listOf(
        LiaToolSpec(
            name = "observe_screen",
            description = "Lee el árbol semántico de la pantalla actual.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "read_screen_ocr",
            description = "Captura temporalmente la pantalla y extrae texto visual mediante OCR.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "open_app",
            description = "Abre una aplicación instalada por su nombre visible.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "app_name",
                    ToolParameterType.STRING,
                    description = "Nombre visible de la aplicación."
                )
            )
        ),
        LiaToolSpec(
            name = "click",
            description = "Activa un control de la pantalla identificado semánticamente.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "target",
                    ToolParameterType.STRING,
                    description = "Texto, descripción o identificador del control."
                )
            )
        ),
        LiaToolSpec(
            name = "set_text",
            description = "Escribe texto en un campo editable.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "target",
                    ToolParameterType.STRING,
                    description = "Campo editable objetivo."
                ),
                ToolParameterSpec(
                    "text",
                    ToolParameterType.STRING,
                    description = "Texto que debe escribirse."
                )
            )
        ),
        LiaToolSpec(
            name = "scroll",
            description = "Desplaza una región de la interfaz.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "direction",
                    ToolParameterType.ENUM,
                    description = "Dirección del desplazamiento.",
                    allowedValues = listOf("forward", "backward")
                )
            )
        ),
        LiaToolSpec(
            name = "back",
            description = "Ejecuta Atrás de Android.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = false
        ),
        LiaToolSpec(
            name = "home",
            description = "Va a la pantalla de inicio.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "recents",
            description = "Abre aplicaciones recientes.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "speak",
            description = "Pronuncia una respuesta usando voz local del dispositivo.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "text",
                    ToolParameterType.STRING,
                    description = "Texto que Lía debe pronunciar."
                )
            )
        ),
        LiaToolSpec(
            name = "set_torch",
            description = "Enciende o apaga la linterna.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "enabled",
                    ToolParameterType.BOOLEAN,
                    description = "Estado deseado de la linterna."
                )
            )
        ),
        LiaToolSpec(
            name = "media_control",
            description = "Controla la sesión multimedia activa.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "command",
                    ToolParameterType.ENUM,
                    description = "Acción multimedia.",
                    allowedValues = listOf(
                        "play",
                        "pause",
                        "play_pause",
                        "next",
                        "previous",
                        "stop"
                    )
                )
            )
        ),
        LiaToolSpec(
            name = "adjust_volume",
            description = "Sube o baja el volumen multimedia.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "direction",
                    ToolParameterType.ENUM,
                    description = "Dirección del volumen.",
                    allowedValues = listOf("up", "down")
                )
            )
        ),
        LiaToolSpec(
            name = "set_timer",
            description = "Prepara un temporizador en la aplicación de reloj.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "seconds",
                    ToolParameterType.INTEGER,
                    description = "Duración en segundos."
                )
            )
        ),
        LiaToolSpec(
            name = "set_alarm",
            description = "Prepara una alarma.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "hour",
                    ToolParameterType.INTEGER,
                    description = "Hora de 0 a 23."
                ),
                ToolParameterSpec(
                    "minute",
                    ToolParameterType.INTEGER,
                    description = "Minuto de 0 a 59."
                )
            )
        ),
        LiaToolSpec(
            name = "read_notifications",
            description = "Recupera las notificaciones recientes que el usuario permitió leer.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "reply_notification",
            description = "Responde mediante RemoteInput a una notificación compatible.",
            risk = ActionRisk.SENSITIVE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "notification_key",
                    ToolParameterType.STRING,
                    description = "Identificador de la notificación."
                ),
                ToolParameterSpec(
                    "message",
                    ToolParameterType.STRING,
                    description = "Mensaje a enviar."
                )
            )
        ),
        LiaToolSpec(
            name = "dial",
            description = "Prepara un número en el marcador del teléfono.",
            risk = ActionRisk.SENSITIVE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "number",
                    ToolParameterType.STRING,
                    description = "Número telefónico."
                )
            )
        ),
        LiaToolSpec(
            name = "compose_sms",
            description = "Prepara un SMS; no lo envía silenciosamente.",
            risk = ActionRisk.SENSITIVE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "number",
                    ToolParameterType.STRING,
                    description = "Número destinatario."
                ),
                ToolParameterSpec(
                    "message",
                    ToolParameterType.STRING,
                    description = "Texto del mensaje."
                )
            )
        ),
        LiaToolSpec(
            name = "open_internet_panel",
            description = "Abre el panel de conectividad de Android.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "open_bluetooth_settings",
            description = "Abre la configuración de Bluetooth de Android.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "open_web",
            description = "Abre una dirección web HTTP o HTTPS en el navegador.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "url",
                    ToolParameterType.STRING,
                    description = "Dirección web completa."
                )
            )
        ),
        LiaToolSpec(
            name = "search_web",
            description = "Inicia una búsqueda web con la consulta indicada.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "query",
                    ToolParameterType.STRING,
                    description = "Texto que se debe buscar."
                )
            )
        ),
        LiaToolSpec(
            name = "copy_text",
            description = "Copia texto al portapapeles del dispositivo.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true,
            parameters = listOf(
                ToolParameterSpec(
                    "text",
                    ToolParameterType.STRING,
                    description = "Texto que se copiará."
                )
            )
        ),
        LiaToolSpec(
            name = "get_location",
            description = "Obtiene la ubicación actual autorizada y su precisión.",
            risk = ActionRisk.SENSITIVE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "world_vision",
            description = "Analiza bajo demanda lo que ve la cámara trasera.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = true
        ),
        LiaToolSpec(
            name = "finish",
            description = "Declara que el objetivo está completado; el runtime debe verificar antes de aceptar.",
            risk = ActionRisk.ROUTINE,
            safeToRetry = false,
            parameters = listOf(
                ToolParameterSpec(
                    "result",
                    ToolParameterType.STRING,
                    description = "Resultado que se comunicará al usuario."
                )
            )
        )
    )

    init {
        require(tools.map { it.name }.distinct().size == tools.size) {
            "El catálogo de herramientas contiene nombres duplicados."
        }
    }

    fun find(name: String): LiaToolSpec? =
        tools.firstOrNull { it.name == name }

    fun forProfile(profile: LiaAgentProfile): List<LiaToolSpec> =
        tools.filter { it.name in profile.toolNames }
}
