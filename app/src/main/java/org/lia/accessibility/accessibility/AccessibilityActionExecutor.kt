package org.lia.accessibility.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

enum class ScrollDirection {
    FORWARD,
    BACKWARD
}

sealed interface AndroidUiAction {
    data class Click(val selector: UiSelector) : AndroidUiAction
    data class SetText(val selector: UiSelector, val text: String) : AndroidUiAction
    data class Scroll(
        val selector: UiSelector? = null,
        val direction: ScrollDirection
    ) : AndroidUiAction

    data object Back : AndroidUiAction
    data object Home : AndroidUiAction
    data object Recents : AndroidUiAction
}

data class UiExecutionResult(
    val accepted: Boolean,
    val performed: Boolean,
    val message: String,
    val beforeFingerprint: String? = null
)

class AccessibilityActionExecutor(
    private val service: LiaAccessibilityService,
    private val treeReader: AccessibilityTreeReader
) {
    fun execute(action: AndroidUiAction): UiExecutionResult {
        val root = service.rootInActiveWindow
            ?: return UiExecutionResult(false, false, "No hay una ventana activa accesible.")

        val before = treeReader.capture(root).fingerprint()

        return when (action) {
            is AndroidUiAction.Click -> {
                val node = findBestNode(root, action.selector)
                    ?: return UiExecutionResult(true, false, "No encontré el control solicitado.", before)

                val performed =
                    performOnNodeOrClickableAncestor(node, AccessibilityNodeInfo.ACTION_CLICK) ||
                        tapCenter(node)

                UiExecutionResult(
                    accepted = true,
                    performed = performed,
                    message = if (performed) "Control activado." else "El control no aceptó la acción.",
                    beforeFingerprint = before
                )
            }

            is AndroidUiAction.SetText -> {
                val selector = action.selector.copy(requireEditable = true)
                val node = findBestNode(root, selector)
                    ?: return UiExecutionResult(true, false, "No encontré un campo editable.", before)

                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text
                    )
                }

                val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                UiExecutionResult(
                    true,
                    performed,
                    if (performed) "Texto escrito." else "La aplicación rechazó la escritura.",
                    before
                )
            }

            is AndroidUiAction.Scroll -> {
                val selector = action.selector?.copy(requireScrollable = true)
                val node = if (selector != null) {
                    findBestNode(root, selector)
                } else {
                    findFirstScrollable(root)
                } ?: return UiExecutionResult(true, false, "No encontré una zona desplazable.", before)

                val actionId = when (action.direction) {
                    ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }

                val performed = node.performAction(actionId)
                UiExecutionResult(
                    true,
                    performed,
                    if (performed) "Desplazamiento realizado." else "No se pudo desplazar.",
                    before
                )
            }

            AndroidUiAction.Back ->
                global(service.goBack(), "Atrás ejecutado.", before)

            AndroidUiAction.Home ->
                global(service.goHome(), "Inicio ejecutado.", before)

            AndroidUiAction.Recents ->
                global(service.openRecents(), "Recientes abierto.", before)
        }
    }

    fun hasScreenChanged(beforeFingerprint: String?): Boolean {
        if (beforeFingerprint == null) return false
        val root = service.rootInActiveWindow ?: return false
        return treeReader.capture(root).fingerprint() != beforeFingerprint
    }

    private fun global(performed: Boolean, message: String, before: String) =
        UiExecutionResult(true, performed, if (performed) message else "Android rechazó la acción.", before)

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        selector: UiSelector
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = MIN_SELECTOR_SCORE - 1

        walk(root) { node, path ->
            val snapshot = snapshot(node, path)
            val score = UiSelectorScorer.score(snapshot, selector)
            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }

        return bestNode?.takeIf { bestScore >= MIN_SELECTOR_SCORE }
    }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        walk(root) { node, _ ->
            if (result == null && node.isEnabled && node.isScrollable) result = node
        }
        return result
    }

    private fun performOnNodeOrClickableAncestor(
        start: AccessibilityNodeInfo,
        action: Int
    ): Boolean {
        var current: AccessibilityNodeInfo? = start
        var hops = 0

        while (current != null && hops <= 5) {
            if (current.isEnabled && current.performAction(action)) return true
            current = current.parent
            hops++
        }

        return false
    }

    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return service.tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private inline fun walk(
        root: AccessibilityNodeInfo,
        crossinline block: (AccessibilityNodeInfo, String) -> Unit
    ) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        stack.add(root to "0")
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val (node, path) = stack.removeLast()
            block(node, path)
            visited++

            for (index in node.childCount - 1 downTo 0) {
                val child = node.getChild(index) ?: continue
                stack.add(child to "$path.$index")
            }
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo, path: String): UiNodeSnapshot {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        return UiNodeSnapshot(
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
    }

    companion object {
        private const val MAX_SEARCH_NODES = 350
        private const val MIN_SELECTOR_SCORE = 35
    }
}
