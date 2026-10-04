package org.lia.accessibility.agent.orchestration

/**
 * Contrato de capacidades de un especialista.
 *
 * Los perfiles mantienen el cerebro compartido, pero reducen las herramientas
 * visibles y ejecutables por cada agente. El coordinador sigue siendo el único
 * dueño del ciclo de ejecución.
 */
data class LiaAgentProfile(
    val role: LiaAgentRole,
    val toolNames: Set<String>,
    val maxSteps: Int,
    val maxRecoveries: Int
)

object LiaAgentProfiles {
    private val commonUi = setOf(
        "observe_screen",
        "read_screen_ocr",
        "open_app",
        "click",
        "set_text",
        "scroll",
        "back",
        "home",
        "recents",
        "speak",
        "finish"
    )

    private val profiles = mapOf(
        LiaAgentRole.NAVIGATION to LiaAgentProfile(
            role = LiaAgentRole.NAVIGATION,
            toolNames = commonUi + setOf(
                "open_web",
                "search_web",
                "copy_text"
            ),
            maxSteps = 24,
            maxRecoveries = 5
        ),
        LiaAgentRole.COMMUNICATION to LiaAgentProfile(
            role = LiaAgentRole.COMMUNICATION,
            toolNames = commonUi + setOf(
                "read_notifications",
                "reply_notification",
                "dial",
                "compose_sms"
            ),
            maxSteps = 24,
            maxRecoveries = 4
        ),
        LiaAgentRole.VISION to LiaAgentProfile(
            role = LiaAgentRole.VISION,
            toolNames = setOf(
                "observe_screen",
                "read_screen_ocr",
                "world_vision",
                "speak",
                "finish"
            ),
            maxSteps = 16,
            maxRecoveries = 3
        ),
        LiaAgentRole.DEVICE to LiaAgentProfile(
            role = LiaAgentRole.DEVICE,
            toolNames = setOf(
                "observe_screen",
                "set_torch",
                "media_control",
                "adjust_volume",
                "set_timer",
                "set_alarm",
                "open_internet_panel",
                "open_bluetooth_settings",
                "speak",
                "finish"
            ),
            maxSteps = 14,
            maxRecoveries = 3
        ),
        LiaAgentRole.GENERAL to LiaAgentProfile(
            role = LiaAgentRole.GENERAL,
            toolNames = commonUi + setOf(
                "set_torch",
                "media_control",
                "adjust_volume",
                "set_timer",
                "set_alarm",
                "read_notifications",
                "dial",
                "compose_sms",
                "get_location",
                "world_vision",
                "open_internet_panel",
                "open_bluetooth_settings",
                "open_web",
                "search_web",
                "copy_text"
            ),
            maxSteps = 28,
            maxRecoveries = 5
        )
    )

    fun forRole(role: LiaAgentRole): LiaAgentProfile =
        profiles.getValue(role)
}
