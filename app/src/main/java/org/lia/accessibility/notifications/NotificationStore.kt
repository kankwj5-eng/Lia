package org.lia.accessibility.notifications

import java.util.concurrent.ConcurrentHashMap

data class LiaNotification(
    val key: String,
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val postedAtEpochMs: Long,
    val ongoing: Boolean
) {
    fun spokenSummary(): String {
        val source = appLabel ?: packageName
        val parts = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            text?.takeIf { it.isNotBlank() },
            subText?.takeIf { it.isNotBlank() }
        ).distinct()
        return if (parts.isEmpty()) source else source + ": " + parts.joinToString(". ")
    }
}

object NotificationStore {
    private const val MAX_ITEMS = 100
    private val items = ConcurrentHashMap<String, LiaNotification>()

    fun put(notification: LiaNotification) {
        items[notification.key] = notification
        trim()
    }

    fun remove(key: String) {
        items.remove(key)
    }

    fun recent(limit: Int = 20): List<LiaNotification> =
        items.values
            .sortedByDescending { it.postedAtEpochMs }
            .take(limit.coerceIn(1, MAX_ITEMS))

    fun recentFromPackage(packageName: String, limit: Int = 20): List<LiaNotification> =
        recent(MAX_ITEMS)
            .filter { it.packageName == packageName }
            .take(limit.coerceIn(1, MAX_ITEMS))

    private fun trim() {
        val overflow = items.size - MAX_ITEMS
        if (overflow <= 0) return

        items.values
            .sortedBy { it.postedAtEpochMs }
            .take(overflow)
            .forEach { items.remove(it.key) }
    }
}
