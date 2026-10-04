package org.lia.accessibility.agent

import org.lia.accessibility.agent.orchestration.LiaAgentRole
import org.lia.accessibility.agent.orchestration.LiaAgentRoleSelector

import org.junit.Assert.assertEquals
import org.junit.Test

class LiaAgentRoleSelectorTest {
    @Test
    fun routesFocusedGoalsToSpecialistsAndMixedGoalsToGeneral() {
        assertEquals(
            LiaAgentRole.COMMUNICATION,
            LiaAgentRoleSelector.select("Manda un mensaje por WhatsApp")
        )
        assertEquals(
            LiaAgentRole.VISION,
            LiaAgentRoleSelector.select("Mira con la cámara y dime de qué color es")
        )
        assertEquals(
            LiaAgentRole.DEVICE,
            LiaAgentRoleSelector.select("Enciende la linterna")
        )
        assertEquals(
            LiaAgentRole.NAVIGATION,
            LiaAgentRoleSelector.select("Abre Ajustes y busca batería")
        )
        assertEquals(
            LiaAgentRole.GENERAL,
            LiaAgentRoleSelector.select("Abre WhatsApp y después enciende la linterna")
        )
        assertEquals(
            LiaAgentRole.GENERAL,
            LiaAgentRoleSelector.select("Explícame qué puedes hacer")
        )
    }
}
