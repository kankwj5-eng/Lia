package org.lia.accessibility.agent.tools

import org.lia.accessibility.accessibility.AndroidUiAction
import org.lia.accessibility.accessibility.ScrollDirection
import org.lia.accessibility.accessibility.UiSelector
import org.lia.accessibility.media.MediaCommand
import org.lia.accessibility.system.SystemCommand

sealed interface RoutedToolAction {
    data class Ui(val action: AndroidUiAction) : RoutedToolAction
    data class System(val command: SystemCommand) : RoutedToolAction
    data object ObserveScreen : RoutedToolAction
    data object ReadScreenOcr : RoutedToolAction
    data object GetLocation : RoutedToolAction
    data object WorldVision : RoutedToolAction
    data class Finish(val result: String) : RoutedToolAction
}

sealed interface ToolRoutingResult {
    data class Routed(val action: RoutedToolAction) : ToolRoutingResult
    data class Rejected(val reason: String) : ToolRoutingResult
}

object LiaToolRouter {
    fun route(call: LiaToolCall): ToolRoutingResult =
        runCatching {
            when (call.name) {
                "observe_screen" ->
                    RoutedToolAction.ObserveScreen

                "read_screen_ocr" ->
                    RoutedToolAction.ReadScreenOcr

                "open_app" ->
                    RoutedToolAction.Ui(
                        AndroidUiAction.OpenApp(call.text("app_name"))
                    )

                "click" -> {
                    val target = call.text("target")
                    RoutedToolAction.Ui(
                        AndroidUiAction.Click(
                            UiSelector(
                                text = target,
                                contentDescription = target,
                                requireClickable = false
                            )
                        )
                    )
                }

                "set_text" -> {
                    val target = call.text("target")
                    RoutedToolAction.Ui(
                        AndroidUiAction.SetText(
                            selector = UiSelector(
                                text = target,
                                contentDescription = target,
                                requireEditable = true
                            ),
                            text = call.text("text")
                        )
                    )
                }

                "scroll" ->
                    RoutedToolAction.Ui(
                        AndroidUiAction.Scroll(
                            direction = when (call.text("direction")) {
                                "forward" -> ScrollDirection.FORWARD
                                "backward" -> ScrollDirection.BACKWARD
                                else -> error("Dirección de scroll no válida.")
                            }
                        )
                    )

                "back" ->
                    RoutedToolAction.Ui(AndroidUiAction.Back)

                "home" ->
                    RoutedToolAction.Ui(AndroidUiAction.Home)

                "recents" ->
                    RoutedToolAction.Ui(AndroidUiAction.Recents)

                "speak" ->
                    RoutedToolAction.System(
                        SystemCommand.Speak(call.text("text"))
                    )

                "set_torch" ->
                    RoutedToolAction.System(
                        SystemCommand.SetTorch(call.boolean("enabled"))
                    )

                "media_control" ->
                    RoutedToolAction.System(
                        SystemCommand.Media(
                            when (call.text("command")) {
                                "play" -> MediaCommand.PLAY
                                "pause" -> MediaCommand.PAUSE
                                "play_pause" -> MediaCommand.PLAY_PAUSE
                                "next" -> MediaCommand.NEXT
                                "previous" -> MediaCommand.PREVIOUS
                                "stop" -> MediaCommand.STOP
                                else -> error("Comando multimedia no válido.")
                            }
                        )
                    )

                "adjust_volume" ->
                    RoutedToolAction.System(
                        SystemCommand.AdjustMediaVolume(
                            when (call.text("direction")) {
                                "up" -> 1
                                "down" -> -1
                                else -> error("Dirección de volumen no válida.")
                            }
                        )
                    )

                "set_timer" ->
                    RoutedToolAction.System(
                        SystemCommand.SetTimer(
                            seconds = call.integer("seconds").toInt()
                        )
                    )

                "set_alarm" ->
                    RoutedToolAction.System(
                        SystemCommand.SetAlarm(
                            hour = call.integer("hour").toInt(),
                            minute = call.integer("minute").toInt()
                        )
                    )

                "read_notifications" ->
                    RoutedToolAction.System(
                        SystemCommand.ReadRecentNotifications()
                    )

                "reply_notification" ->
                    RoutedToolAction.System(
                        SystemCommand.ReplyToNotification(
                            notificationKey = call.text("notification_key"),
                            message = call.text("message")
                        )
                    )

                "dial" ->
                    RoutedToolAction.System(
                        SystemCommand.Dial(call.text("number"))
                    )

                "compose_sms" ->
                    RoutedToolAction.System(
                        SystemCommand.ComposeSms(
                            number = call.text("number"),
                            message = call.text("message")
                        )
                    )

                "open_internet_panel" ->
                    RoutedToolAction.System(SystemCommand.OpenInternetPanel)

                "open_bluetooth_settings" ->
                    RoutedToolAction.System(SystemCommand.OpenBluetoothSettings)

                "open_web" ->
                    RoutedToolAction.System(
                        SystemCommand.OpenWeb(call.text("url"))
                    )

                "search_web" ->
                    RoutedToolAction.System(
                        SystemCommand.SearchWeb(call.text("query"))
                    )

                "copy_text" ->
                    RoutedToolAction.System(
                        SystemCommand.CopyText(call.text("text"))
                    )

                "get_location" ->
                    RoutedToolAction.GetLocation

                "world_vision" ->
                    RoutedToolAction.WorldVision

                "finish" ->
                    RoutedToolAction.Finish(call.text("result"))

                else -> error("Herramienta sin ruta de ejecución: " + call.name)
            }
        }.fold(
            onSuccess = { ToolRoutingResult.Routed(it) },
            onFailure = {
                ToolRoutingResult.Rejected(
                    it.message ?: "No se pudo convertir la herramienta."
                )
            }
        )

    private fun LiaToolCall.text(name: String): String =
        (arguments[name] as? ToolValue.Text)?.value
            ?: error("Falta texto para " + name)

    private fun LiaToolCall.integer(name: String): Long =
        (arguments[name] as? ToolValue.IntegerValue)?.value
            ?: error("Falta entero para " + name)

    private fun LiaToolCall.boolean(name: String): Boolean =
        (arguments[name] as? ToolValue.BooleanValue)?.value
            ?: error("Falta booleano para " + name)
}
