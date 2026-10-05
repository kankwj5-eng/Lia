package org.lia.accessibility.agent.delegation

enum class RemoteTaskState { RUNNING, COMPLETED, FAILED, CANCELLED }

sealed interface SubmissionResolution {
    data class Found(val remoteId: String) : SubmissionResolution
    data object ConfirmedAbsent : SubmissionResolution
    data object Unknown : SubmissionResolution
}

/**
 * Implementations must deduplicate submit by idempotencyKey, including concurrent requests.
 * Reconciliation is authoritative; an uncertain reply never permits a blind resend.
 */
interface DelegationProvider {
    val providerId: String
    suspend fun submit(request: DelegationRequest): ProviderReply<String>
    suspend fun status(remoteId: String): ProviderReply<RemoteTaskState>
    suspend fun result(remoteId: String): ProviderReply<ResearchResult>
    suspend fun cancel(remoteId: String): ProviderReply<Unit>
    suspend fun reconcile(idempotencyKey: String): ProviderReply<SubmissionResolution>
}
