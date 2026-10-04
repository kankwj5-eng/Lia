package org.lia.accessibility.agent

import org.lia.accessibility.agent.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lia.accessibility.security.ActionRisk

class StrictToolProtocolTest {
    @Test
    fun acceptsKnownTypedTool() {
        val result = StrictToolProtocol.validate(
            "set_alarm",
            mapOf(
                "hour" to ToolValue.IntegerValue(7),
                "minute" to ToolValue.IntegerValue(30)
            )
        )

        assertTrue(result is ToolProtocolResult.Accepted)
        val call = (result as ToolProtocolResult.Accepted).call
        assertEquals(ActionRisk.ROUTINE, call.risk)
    }

    @Test
    fun rejectsUnknownTool() {
        val result = StrictToolProtocol.validate("adb_shell", emptyMap())
        assertTrue(result is ToolProtocolResult.Rejected)
    }

    @Test
    fun rejectsUnknownArguments() {
        val result = StrictToolProtocol.validate(
            "home",
            mapOf("command" to ToolValue.Text("argumento_desconocido"))
        )
        assertTrue(result is ToolProtocolResult.Rejected)
    }

    @Test
    fun rejectsSensitiveToolTypeMismatch() {
        val result = StrictToolProtocol.validate(
            "reply_notification",
            mapOf(
                "notification_key" to ToolValue.Text("abc"),
                "message" to ToolValue.BooleanValue(true)
            )
        )
        assertTrue(result is ToolProtocolResult.Rejected)
    }

    @Test
    fun rejectsAlarmOutsideSafeRange() {
        val result = StrictToolProtocol.validate(
            "set_alarm",
            mapOf(
                "hour" to ToolValue.IntegerValue(99),
                "minute" to ToolValue.IntegerValue(0)
            )
        )
        assertTrue(result is ToolProtocolResult.Rejected)
    }

    @Test
    fun stripsQwenThinkingWrapperBeforeProtocolParsing() {
        val normalized = StrictToolProtocol.normalizeModelResponse(
            "<think>razonamiento interno</think>\n" +
                "{\"tool\":\"home\",\"arguments\":{}}"
        )

        assertEquals(
            "{\"tool\":\"home\",\"arguments\":{}}",
            normalized
        )
    }

    @Test
    fun actionKeyIsDeterministic() {
        val a = StrictToolProtocol.validate(
            "compose_sms",
            linkedMapOf(
                "message" to ToolValue.Text("hola"),
                "number" to ToolValue.Text("123")
            )
        ) as ToolProtocolResult.Accepted

        val b = StrictToolProtocol.validate(
            "compose_sms",
            linkedMapOf(
                "number" to ToolValue.Text("123"),
                "message" to ToolValue.Text("hola")
            )
        ) as ToolProtocolResult.Accepted

        assertEquals(a.call.actionKey, b.call.actionKey)
    }
}
