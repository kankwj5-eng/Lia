package org.lia.accessibility.permissions

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.lia.accessibility.notifications.LiaNotificationListenerService

class AndroidPermissionNavigator(
    private val context: Context
) {
    fun requiresRestrictedSettingsGuidance(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isNotificationAccessGranted(): Boolean {
        val component = ComponentName(
            context,
            LiaNotificationListenerService::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(component)
        }

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabled
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
    }

    fun appDetailsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")
        )

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun generalSettingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS)
}
