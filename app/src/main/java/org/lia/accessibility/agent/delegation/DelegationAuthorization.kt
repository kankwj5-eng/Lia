package org.lia.accessibility.agent.delegation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.lia.accessibility.security.AuthorizationDecision

data class DelegationGrant(
    val taskId: String,
    val providerId: String,
    val requestHash: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long
)

/** Called only after the Android authorization gate, never with model-supplied decisions. */
object DelegationAuthorization {
    fun requestHash(request: DelegationRequest): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf("lia-delegation-v1", request.taskId, request.idempotencyKey,
                request.topic, request.providerId, request.language).forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun grant(request: DelegationRequest, nowMs: Long, decision: AuthorizationDecision): DelegationGrant? {
        if (decision !is AuthorizationDecision.Allowed || nowMs < 0 ||
            nowMs > Long.MAX_VALUE - DelegationLimits.GRANT_LIFETIME_MS) return null
        return DelegationGrant(request.taskId, request.providerId, requestHash(request),
            nowMs, nowMs + DelegationLimits.GRANT_LIFETIME_MS)
    }

    fun allows(request: DelegationRequest, grant: DelegationGrant, nowMs: Long): Boolean =
        grant.taskId == request.taskId && grant.providerId == request.providerId &&
            grant.requestHash == requestHash(request) && grant.issuedAtMs >= 0 &&
            grant.issuedAtMs <= Long.MAX_VALUE - DelegationLimits.GRANT_LIFETIME_MS &&
            grant.expiresAtMs == grant.issuedAtMs + DelegationLimits.GRANT_LIFETIME_MS &&
            nowMs >= grant.issuedAtMs && nowMs < grant.expiresAtMs
}
