package org.lia.accessibility.notifications

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LiaNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()

        val replyAvailable = NotificationReplyRegistry.register(
            notificationKey = sbn.key,
            actions = notification.actions
        )

        val item = LiaNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = bigText ?: normalText,
            subText = subText,
            postedAtEpochMs = sbn.postTime,
            ongoing = sbn.isOngoing,
            replyAvailable = replyAvailable
        )

        NotificationStore.put(item)

        sendBroadcast(
            Intent(ACTION_NOTIFICATION_CONTEXT_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_NOTIFICATION_KEY, sbn.key)
                .putExtra(EXTRA_SOURCE_PACKAGE, sbn.packageName)
                .putExtra(EXTRA_REPLY_AVAILABLE, replyAvailable)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationReplyRegistry.unregister(sbn.key)
        NotificationStore.remove(sbn.key)
    }

    companion object {
        const val ACTION_NOTIFICATION_CONTEXT_CHANGED =
            "org.lia.accessibility.action.NOTIFICATION_CONTEXT_CHANGED"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_REPLY_AVAILABLE = "reply_available"
    }
}
