package org.lia.accessibility.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lia.accessibility.security.ActionRisk

class EffectiveToolRiskPolicyTest {
    @Test
    fun ordinaryClickStaysRoutine() {
        val call = accepted(
            "click",
            mapOf("target" to ToolValue.Text("Configuración"))
        )

        assertEquals(
            ActionRisk.ROUTINE,
            EffectiveToolRiskPolicy.effectiveRisk(call)
        )
    }

    @Test
    fun sendClickBecomesSensitive() {
        val call = accepted(
            "click",
            mapOf("target" to ToolValue.Text("Enviar"))
        )

        assertEquals(
            ActionRisk.SENSITIVE,
            EffectiveToolRiskPolicy.effectiveRisk(call)
        )
    }

    @Test
    fun paymentClickBecomesIrreversible() {
        val call = accepted(
            "click",
            mapOf("target" to ToolValue.Text("Confirmar compra"))
        )

        assertEquals(
            ActionRisk.IRREVERSIBLE,
            EffectiveToolRiskPolicy.effectiveRisk(call)
        )
    }

    @Test
    fun passwordFieldBecomesSensitive() {
        val call = accepted(
            "set_text",
            mapOf(
                "target" to ToolValue.Text("Contraseña"),
                "text" to ToolValue.Text("secreto")
            )
        )

        assertEquals(
            ActionRisk.SENSITIVE,
            EffectiveToolRiskPolicy.effectiveRisk(call)
        )
    }

    @Test
    fun catalogRiskCannotBeDowngraded() {
        val call = accepted(
            "compose_sms",
            mapOf(
                "number" to ToolValue.Text("123"),
                "message" to ToolValue.Text("hola")
            )
        )

        assertEquals(
            ActionRisk.SENSITIVE,
            EffectiveToolRiskPolicy.effectiveRisk(call)
        )
    }

    private fun accepted(
        name: String,
        arguments: Map<String, ToolValue>
    ): LiaToolCall =
        (StrictToolProtocol.validate(name, arguments) as ToolProtocolResult.Accepted).call
}
