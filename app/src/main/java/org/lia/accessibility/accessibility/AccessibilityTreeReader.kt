package org.lia.accessibility.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class AccessibilityTreeReader(
    private val maxNodes: Int = 350,
    private val maxDepth: Int = 35
) {
    fun capture(
        root: AccessibilityNodeInfo?,
        window: AccessibilityWindowInfo? = null
    ): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(
                packageName = null,
                windowTitle = window?.title?.toString(),
                nodes = emptyList()
            )
        }

        val nodes = ArrayList<UiNodeSnapshot>(minOf(maxNodes, 128))
        visit(root, path = "0", depth = 0, output = nodes)

        return ScreenSnapshot(
            packageName = root.packageName?.toString(),
            windowTitle = window?.title?.toString(),
            nodes = nodes
        )
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        output: MutableList<UiNodeSnapshot>
    ) {
        if (depth > maxDepth || output.size >= maxNodes) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        output += UiNodeSnapshot(
            path = path,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            packageName = node.packageName?.toString(),
            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checked = node.isChecked,
            actionIds = node.actionList.map { it.id }.toSet()
        )

        for (index in 0 until node.childCount) {
            if (output.size >= maxNodes) break
            val child = node.getChild(index) ?: continue
            visit(child, "$path.$index", depth + 1, output)
        }
    }
}
