package org.lia.accessibility.agent

import org.lia.accessibility.agent.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lia.accessibility.accessibility.AndroidUiAction
import org.lia.accessibility.media.MediaCommand
import org.lia.accessibility.system.SystemCommand

class LiaToolRouterTest {
    @Test
    fun routesOpenAppToUiAction() {
        val call = accepted(
            "open_app",
            mapOf("app_name" to ToolValue.Text("WhatsApp"))
        )

        val routed = LiaToolRouter.route(call)
        assertTrue(routed is ToolRoutingResult.Routed)

        val action = (routed as ToolRoutingResult.Routed).action
        assertTrue(action is RoutedToolAction.Ui)
        assertEquals(
            AndroidUiAction.OpenApp("WhatsApp"),
            (action as RoutedToolAction.Ui).action
        )
    }

    @Test
    fun routesMediaWithoutArbitraryCommandExecution() {
        val call = accepted(
            "media_control",
            mapOf("command" to ToolValue.Text("pause"))
        )

        val action = (LiaToolRouter.route(call) as ToolRoutingResult.Routed).action
        assertTrue(action is RoutedToolAction.System)
        assertEquals(
            SystemCommand.Media(MediaCommand.PAUSE),
            (action as RoutedToolAction.System).command
        )
    }

    @Test
    fun routesSensitiveNotificationReplyToSystemCommand() {
        val call = accepted(
            "reply_notification",
            mapOf(
                "notification_key" to ToolValue.Text("key"),
                "message" to ToolValue.Text("Hola")
            )
        )

        val action = (LiaToolRouter.route(call) as ToolRoutingResult.Routed).action
        assertTrue(action is RoutedToolAction.System)
        assertEquals(
            SystemCommand.ReplyToNotification("key", "Hola"),
            (action as RoutedToolAction.System).command
        )
    }

    private fun accepted(
        name: String,
        arguments: Map<String, ToolValue>
    ): LiaToolCall =
        (StrictToolProtocol.validate(name, arguments) as ToolProtocolResult.Accepted).call
}
