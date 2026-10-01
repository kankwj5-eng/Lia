package org.lia.accessibility.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.lia.accessibility.security.ActionRisk

class LiaToolCatalogTest {
    @Test
    fun toolNamesAreUnique() {
        val names = LiaToolCatalog.tools.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun messagingToolsAreSensitiveAndNotRetryable() {
        val reply = LiaToolCatalog.find("reply_notification")
        val sms = LiaToolCatalog.find("compose_sms")

        assertNotNull(reply)
        assertNotNull(sms)
        assertEquals(ActionRisk.SENSITIVE, reply?.risk)
        assertEquals(ActionRisk.SENSITIVE, sms?.risk)
        assertFalse(reply!!.safeToRetry)
        assertFalse(sms!!.safeToRetry)
    }

    @Test
    fun noToolExposesArbitraryShell() {
        val forbidden = setOf("shell", "exec", "run_command", "adb_shell")
        assertFalse(LiaToolCatalog.tools.any { it.name in forbidden })
    }
}
