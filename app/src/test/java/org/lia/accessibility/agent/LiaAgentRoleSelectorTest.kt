package org.lia.accessibility.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class LiaAgentRoleSelectorTest {
    @Test
    fun routesCommonGoalsToSpecialists() {
        assertEquals(
            LiaAgentRole.COMMUNICATION,
            LiaAgentRoleSelector.select("Abre WhatsApp y manda un mensaje")
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
            LiaAgentRoleSelector.select("Explícame qué puedes hacer")
        )
    }
}
