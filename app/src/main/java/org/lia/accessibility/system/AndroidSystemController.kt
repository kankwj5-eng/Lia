package org.lia.accessibility.system

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.lia.accessibility.media.MediaCommand
import org.lia.accessibility.media.MediaControlController
import org.lia.accessibility.notifications.NotificationReplyRegistry
import org.lia.accessibility.notifications.NotificationStore
import org.lia.accessibility.security.ActionRisk
import org.lia.accessibility.security.AuthorizationDecision
import org.lia.accessibility.security.CommandAuthorizationGate
import org.lia.accessibility.voice.VoiceVerification

sealed interface SystemCommand {
    val risk: ActionRisk

    data class SetTorch(val enabled: Boolean) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class AdjustMediaVolume(val direction: Int) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class Media(val command: MediaCommand) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class SetTimer(val seconds: Int, val label: String? = null) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data object OpenInternetPanel : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data object OpenBluetoothSettings : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class Dial(val number: String) : SystemCommand {
        override val risk = ActionRisk.SENSITIVE
    }

    data class ComposeSms(val number: String, val message: String) : SystemCommand {
        override val risk = ActionRisk.SENSITIVE
    }

    data class OpenWeb(val url: String) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class SearchWeb(val query: String) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class CopyText(val text: String) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class ReadRecentNotifications(val limit: Int = 10) : SystemCommand {
        override val risk = ActionRisk.ROUTINE
    }

    data class ReplyToNotification(
        val notificationKey: String,
        val message: String
    ) : SystemCommand {
        override val risk = ActionRisk.SENSITIVE
    }
}

data class SystemCommandResult(
    val accepted: Boolean,
    val performed: Boolean,
    val message: String,
    val data: List<String> = emptyList()
)

class AndroidSystemController(
    private val context: Context,
    private val gate: CommandAuthorizationGate = CommandAuthorizationGate()
) {
    private val torch = TorchController(context)
    private val media = MediaControlController(context)
    private val audio = context.getSystemService(AudioManager::class.java)

    fun execute(
        voice: VoiceVerification,
        command: SystemCommand,
        secondFactorSatisfied: Boolean = false,
        explicitConfirmation: Boolean = false
    ): SystemCommandResult {
        val authorization = gate.authorize(
            voice = voice,
            risk = command.risk,
            secondFactorSatisfied = secondFactorSatisfied,
            explicitConfirmation = explicitConfirmation
        )

        if (authorization !is AuthorizationDecision.Allowed) {
            return SystemCommandResult(
                accepted = false,
                performed = false,
                message = authorizationMessage(authorization)
            )
        }

        return when (command) {
            is SystemCommand.SetTorch -> {
                val ok = torch.setEnabled(command.enabled)
                result(ok, if (command.enabled) "Linterna encendida." else "Linterna apagada.")
            }

            is SystemCommand.AdjustMediaVolume -> {
                val direction = when {
                    command.direction > 0 -> AudioManager.ADJUST_RAISE
                    command.direction < 0 -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_SAME
                }
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI
                )
                result(true, "Volumen ajustado.")
            }

            is SystemCommand.Media ->
                result(
                    media.perform(command.command),
                    "Comando multimedia enviado."
                )

            is SystemCommand.SetAlarm -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                command.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                start(intent, "Abriendo configuración de alarma.")
            }

            is SystemCommand.SetTimer -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds.coerceAtLeast(1))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                command.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                start(intent, "Abriendo temporizador.")
            }

            SystemCommand.OpenInternetPanel -> {
                val action = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_WIRELESS_SETTINGS
                }
                start(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Abriendo conexión a internet.")
            }

            SystemCommand.OpenBluetoothSettings ->
                start(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Abriendo Bluetooth."
                )

            is SystemCommand.Dial -> {
                val uri = Uri.fromParts("tel", command.number, null)
                start(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Número preparado en el marcador."
                )
            }

            is SystemCommand.ComposeSms -> {
                val uri = Uri.fromParts("smsto", command.number, null)
                val intent = Intent(Intent.ACTION_SENDTO, uri)
                    .putExtra("sms_body", command.message)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                start(intent, "Mensaje preparado. Aún no se ha enviado.")
            }

            is SystemCommand.OpenWeb -> {
                val uri = runCatching { Uri.parse(command.url) }.getOrNull()
                if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
                    SystemCommandResult(true, false, "La dirección web no es válida.")
                } else {
                    start(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Abriendo enlace."
                    )
                }
            }

            is SystemCommand.SearchWeb -> {
                val intent = Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, command.query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                start(intent, "Abriendo búsqueda.")
            }

            is SystemCommand.CopyText -> {
                val clipboard = ContextCompat.getSystemService(
                    context,
                    android.content.ClipboardManager::class.java
                )
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Lía", command.text)
                )
                result(clipboard != null, "Texto copiado.")
            }

            is SystemCommand.ReadRecentNotifications -> {
                val summaries = NotificationStore.recent(command.limit).map { it.spokenSummary() }
                SystemCommandResult(
                    accepted = true,
                    performed = true,
                    message = if (summaries.isEmpty()) {
                        "No tengo notificaciones recientes disponibles."
                    } else {
                        "Notificaciones recientes recuperadas."
                    },
                    data = summaries
                )
            }

            is SystemCommand.ReplyToNotification -> {
                val notification = NotificationStore.find(command.notificationKey)
                if (notification == null) {
                    SystemCommandResult(
                        accepted = true,
                        performed = false,
                        message = "La notificación ya no está disponible."
                    )
                } else if (!notification.replyAvailable) {
                    SystemCommandResult(
                        accepted = true,
                        performed = false,
                        message = "Esa notificación no permite respuesta rápida."
                    )
                } else {
                    val ok = NotificationReplyRegistry.reply(
                        context = context,
                        notificationKey = command.notificationKey,
                        message = command.message
                    )
                    result(
                        ok,
                        "Respuesta enviada desde la notificación."
                    )
                }
            }
        }
    }

    private fun start(intent: Intent, successMessage: String): SystemCommandResult {
        val ok = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        return result(ok, successMessage)
    }

    private fun result(ok: Boolean, successMessage: String) =
        SystemCommandResult(
            accepted = true,
            performed = ok,
            message = if (ok) successMessage else "Android no pudo completar esta acción."
        )

    private fun authorizationMessage(decision: AuthorizationDecision): String =
        when (decision) {
            AuthorizationDecision.Allowed -> "Autorizado."
            is AuthorizationDecision.Denied -> decision.reason
            is AuthorizationDecision.NeedsSecondFactor -> decision.reason
            is AuthorizationDecision.NeedsExplicitConfirmation -> decision.reason
        }
}
