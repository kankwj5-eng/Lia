package org.lia.accessibility.agent.delegation

enum class DelegationState {
    AWAITING_AUTHORIZATION, QUEUED, SUBMITTING, RUNNING, WAITING_NETWORK,
    VERIFYING, COMPLETED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class DelegationTask(
    val request: DelegationRequest,
    val state: DelegationState,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val remoteId: String? = null,
    val waitingFrom: DelegationState? = null,
    val result: ResearchResult? = null,
    val error: DelegationError? = null,
    val documentId: String? = null,
    val submissionStarted: Boolean = remoteId != null || state == DelegationState.SUBMITTING ||
        waitingFrom == DelegationState.SUBMITTING
) {
    init {
        require(revision >= 0 && createdAtMs >= 0 && updatedAtMs >= createdAtMs)
        require(remoteId == null || (remoteId.isNotBlank() && remoteId.length <= 512))
        require(documentId == null || isUuid(documentId))
        if (state == DelegationState.WAITING_NETWORK) {
            require(waitingFrom in setOf(DelegationState.QUEUED, DelegationState.SUBMITTING, DelegationState.RUNNING))
        } else require(waitingFrom == null)
        if (state in setOf(DelegationState.RUNNING, DelegationState.VERIFYING, DelegationState.COMPLETED)) require(remoteId != null)
        if (state in setOf(DelegationState.VERIFYING, DelegationState.COMPLETED)) require(result != null)
        if (state == DelegationState.COMPLETED) require(documentId != null)
    }
}

sealed interface DelegationEvent {
    data object Authorized : DelegationEvent
    data object BeginSubmit : DelegationEvent
    data class Submitted(val remoteId: String) : DelegationEvent
    data object NetworkLost : DelegationEvent
    data object NetworkRestored : DelegationEvent
    data class ResultReceived(val result: ResearchResult) : DelegationEvent
    data class DocumentVerified(val documentId: String) : DelegationEvent
    data class Fail(val error: DelegationError) : DelegationEvent
    data object Cancel : DelegationEvent
}

sealed interface TransitionResult {
    data class Applied(val task: DelegationTask) : TransitionResult
    data class Rejected(val reason: String) : TransitionResult
}
