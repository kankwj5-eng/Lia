package org.lia.accessibility.agent

import org.lia.accessibility.agent.orchestration.LiaAgentRole
import org.lia.accessibility.agent.orchestration.LiaAgentRoleSelector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lia.accessibility.agent.orchestration.LiaAgentProfiles

class LiaAgentProfileTest {
    @Test
    fun specialistsExposeOnlyTheirOwnToolSurface() {
        val vision = LiaAgentProfiles.forRole(LiaAgentRole.VISION)
        assertTrue("world_vision" in vision.toolNames)
        assertTrue("read_screen_ocr" in vision.toolNames)
        assertFalse("reply_notification" in vision.toolNames)
        assertFalse("set_torch" in vision.toolNames)

        val communication = LiaAgentProfiles.forRole(LiaAgentRole.COMMUNICATION)
        assertTrue("reply_notification" in communication.toolNames)
        assertTrue("compose_sms" in communication.toolNames)
        assertFalse("world_vision" in communication.toolNames)
    }

    @Test
    fun strictProtocolRejectsToolsOutsideActiveProfile() {
        val vision = LiaAgentProfiles.forRole(LiaAgentRole.VISION)
        val result = StrictToolProtocol.validate(
            toolName = "set_torch",
            arguments = mapOf("enabled" to ToolValue.BooleanValue(true)),
            allowedToolNames = vision.toolNames
        )
        assertTrue(result is ToolProtocolResult.Rejected)
    }
}
