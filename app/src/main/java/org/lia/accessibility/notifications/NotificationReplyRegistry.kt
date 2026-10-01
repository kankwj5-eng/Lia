package org.lia.accessibility.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

private data class ReplyTarget(
    val action: Notification.Action,
    val remoteInputs: Array<RemoteInput>
)

object NotificationReplyRegistry {
    private val targets = ConcurrentHashMap<String, ReplyTarget>()

    fun register(
        notificationKey: String,
        actions: Array<Notification.Action>?
    ): Boolean {
        val target = actions
            ?.asSequence()
            ?.mapNotNull { action ->
                val inputs = action.remoteInputs ?: return@mapNotNull null
                if (inputs.isEmpty()) null else ReplyTarget(action, inputs)
            }
            ?.firstOrNull()

        if (target == null) {
            targets.remove(notificationKey)
            return false
        }

        targets[notificationKey] = target
        return true
    }

    fun unregister(notificationKey: String) {
        targets.remove(notificationKey)
    }

    fun canReply(notificationKey: String): Boolean =
        targets.containsKey(notificationKey)

    fun reply(
        context: Context,
        notificationKey: String,
        message: String
    ): Boolean {
        if (message.isBlank()) return false
        val target = targets[notificationKey] ?: return false

        return runCatching {
            val fillInIntent = Intent()
            val results = Bundle()

            target.remoteInputs.forEach { remoteInput ->
                results.putCharSequence(remoteInput.resultKey, message)
            }

            RemoteInput.addResultsToIntent(
                target.remoteInputs,
                fillInIntent,
                results
            )

            target.action.actionIntent.send(
                context,
                0,
                fillInIntent
            )

            true
        }.getOrDefault(false)
    }
}
