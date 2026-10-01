package org.lia.accessibility.accessibility

import android.graphics.Rect
import java.security.MessageDigest

data class UiNodeSnapshot(
    val path: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val packageName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val actionIds: Set<Int>
) {
    val spokenLabel: String?
        get() = listOfNotNull(text, contentDescription)
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
}

data class ScreenSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val nodes: List<UiNodeSnapshot>,
    val capturedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun fingerprint(): String {
        val material = buildString {
            append(packageName.orEmpty()).append('|')
            append(windowTitle.orEmpty()).append('|')
            nodes.forEach { node ->
                append(node.path).append(':')
                append(node.text.orEmpty()).append(':')
                append(node.contentDescription.orEmpty()).append(':')
                append(node.viewId.orEmpty()).append(':')
                append(node.selected).append(':')
                append(node.checked).append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun compactDescription(limit: Int = 80): String =
        nodes.asSequence()
            .filter { it.enabled }
            .mapNotNull { node ->
                val label = node.spokenLabel ?: return@mapNotNull null
                val role = node.className?.substringAfterLast('.')?.removeSuffix("View").orEmpty()
                if (role.isBlank()) label else "$role: $label"
            }
            .distinct()
            .take(limit)
            .joinToString("\n")
}

data class UiSelector(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val path: String? = null,
    val requireClickable: Boolean = false,
    val requireEditable: Boolean = false,
    val requireScrollable: Boolean = false
)

object UiSelectorScorer {
    fun score(node: UiNodeSnapshot, selector: UiSelector): Int {
        if (!node.enabled) return Int.MIN_VALUE
        if (selector.requireClickable && !node.clickable) return Int.MIN_VALUE
        if (selector.requireEditable && !node.editable) return Int.MIN_VALUE
        if (selector.requireScrollable && !node.scrollable) return Int.MIN_VALUE

        var score = 0
        var criteria = 0

        selector.path?.let {
            criteria++
            if (node.path == it) score += 140 else score -= 25
        }

        selector.viewId?.let {
            criteria++
            if (node.viewId == it) score += 120
            else if (node.viewId?.endsWith(it) == true) score += 80
            else score -= 20
        }

        selector.text?.trim()?.takeIf(String::isNotEmpty)?.let { wanted ->
            criteria++
            val actual = node.text.orEmpty().trim()
            score += when {
                actual.equals(wanted, ignoreCase = true) -> 100
                actual.contains(wanted, ignoreCase = true) -> 70
                wanted.contains(actual, ignoreCase = true) && actual.isNotEmpty() -> 45
                else -> -20
            }
        }

        selector.contentDescription?.trim()?.takeIf(String::isNotEmpty)?.let { wanted ->
            criteria++
            val actual = node.contentDescription.orEmpty().trim()
            score += when {
                actual.equals(wanted, ignoreCase = true) -> 100
                actual.contains(wanted, ignoreCase = true) -> 70
                else -> -20
            }
        }

        selector.className?.let { wanted ->
            criteria++
            score += when {
                node.className == wanted -> 50
                node.className?.endsWith(wanted) == true -> 35
                else -> -10
            }
        }

        if (criteria == 0) return Int.MIN_VALUE
        if (node.clickable) score += 4
        if (node.editable) score += 4
        return score
    }
}
