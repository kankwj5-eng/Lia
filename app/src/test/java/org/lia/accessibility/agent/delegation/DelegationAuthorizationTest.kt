package org.lia.accessibility.agent.delegation

import org.junit.Assert.*
import org.junit.Test
import org.lia.accessibility.security.AuthorizationDecision

class DelegationAuthorizationTest {
    private val allowed = AuthorizationDecision.Allowed
    private fun grant() = requireNotNull(DelegationAuthorization.grant(request(), 1000, allowed))
    @Test fun deniedDecisionNeverGrants() {
        listOf(AuthorizationDecision.Denied("No"), AuthorizationDecision.NeedsSecondFactor("Check"),
            AuthorizationDecision.NeedsExplicitConfirmation("Confirm")).forEach {
            assertNull(DelegationAuthorization.grant(request(), 1000, it))
        }
    }
    @Test fun changedTopicRejected() { assertFalse(DelegationAuthorization.allows(request("Otro tema"), grant(), 1001)) }
    @Test fun changedProviderRejected() { assertFalse(DelegationAuthorization.allows(request().copy(providerId="other"), grant(), 1001)) }
    @Test fun changedLanguageRejected() { assertFalse(DelegationAuthorization.allows(request().copy(language="en"), grant(), 1001)) }
    @Test fun grantExpiresAtFiveMinutes() {
        assertTrue(DelegationAuthorization.allows(request(), grant(), 300999))
        assertFalse(DelegationAuthorization.allows(request(), grant(), 301000))
    }
    @Test fun futureGrantRejected() { assertFalse(DelegationAuthorization.allows(request(), grant(), 999)) }
    @Test fun wrongTaskRejected() {
        assertFalse(DelegationAuthorization.allows(request().copy(taskId="00000000-0000-0000-0000-000000000003"), grant(), 1001))
    }
    @Test fun hashHasUnambiguousFields() {
        val a = request("ab").copy(providerId="c")
        val b = request("a").copy(providerId="bc")
        assertNotEquals(DelegationAuthorization.requestHash(a), DelegationAuthorization.requestHash(b))
        assertEquals(64, DelegationAuthorization.requestHash(a).length)
    }
    @Test fun changedIdempotencyRejected() {
        assertFalse(DelegationAuthorization.allows(request().copy(idempotencyKey="00000000-0000-0000-0000-000000000003"), grant(), 1001))
    }
    @Test fun overflowAndInvalidGrantRejected() {
        assertNull(DelegationAuthorization.grant(request(), Long.MAX_VALUE - 1, allowed))
        assertFalse(DelegationAuthorization.allows(request(), grant().copy(expiresAtMs=Long.MAX_VALUE), 1001))
        assertNull(DelegationAuthorization.grant(request(), -1, allowed))
    }
}
