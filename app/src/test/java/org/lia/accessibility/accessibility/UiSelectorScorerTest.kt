package org.lia.accessibility.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSelectorScorerTest {
    private fun node(
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        clickable: Boolean = true,
        editable: Boolean = false,
        enabled: Boolean = true
    ) = UiNodeSnapshot(
        path = "0.1",
        text = text,
        contentDescription = description,
        className = "android.widget.Button",
        viewId = viewId,
        packageName = "example.app",
        bounds = Rect(0, 0, 100, 100),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = enabled,
        selected = false,
        checked = false,
        actionIds = emptySet()
    )

    @Test
    fun exactTextScoresAbovePartialText() {
        val selector = UiSelector(text = "Enviar")
        val exact = UiSelectorScorer.score(node(text = "Enviar"), selector)
        val partial = UiSelectorScorer.score(node(text = "Enviar mensaje"), selector)
        assertTrue(exact > partial)
    }

    @Test
    fun viewIdIsStrongSignal() {
        val selector = UiSelector(viewId = "com.example:id/send")
        assertTrue(
            UiSelectorScorer.score(node(viewId = "com.example:id/send"), selector) >= 120
        )
    }

    @Test
    fun disabledNodeIsRejected() {
        assertEquals(
            Int.MIN_VALUE,
            UiSelectorScorer.score(node(text = "Enviar", enabled = false), UiSelector(text = "Enviar"))
        )
    }

    @Test
    fun editableRequirementRejectsButton() {
        assertEquals(
            Int.MIN_VALUE,
            UiSelectorScorer.score(
                node(text = "Mensaje", editable = false),
                UiSelector(text = "Mensaje", requireEditable = true)
            )
        )
    }
}
