package org.lia.accessibility.agent.delegation

object DelegationStateMachine {
    fun apply(task: DelegationTask, event: DelegationEvent, nowMs: Long): TransitionResult {
        if (task.state.terminal || task.revision == Long.MAX_VALUE) {
            return TransitionResult.Rejected("La tarea terminó o agotó su revisión.")
        }
        val next = when (event) {
            DelegationEvent.Authorized -> if (task.state == DelegationState.AWAITING_AUTHORIZATION)
                task.copy(state = DelegationState.QUEUED) else null
            DelegationEvent.BeginSubmit -> if (task.state == DelegationState.QUEUED)
                task.copy(state = DelegationState.SUBMITTING, submissionStarted = true) else null
            is DelegationEvent.Submitted -> if (task.state == DelegationState.SUBMITTING &&
                event.remoteId.isNotBlank() && event.remoteId.length <= 512)
                task.copy(state = DelegationState.RUNNING, remoteId = event.remoteId, error = null) else null
            DelegationEvent.NetworkLost -> if (task.state in setOf(
                DelegationState.QUEUED, DelegationState.SUBMITTING, DelegationState.RUNNING))
                task.copy(state = DelegationState.WAITING_NETWORK, waitingFrom = task.state) else null
            DelegationEvent.NetworkRestored -> if (task.state == DelegationState.WAITING_NETWORK)
                task.copy(state = requireNotNull(task.waitingFrom), waitingFrom = null) else null
            is DelegationEvent.ResultReceived -> if (task.state == DelegationState.RUNNING)
                task.copy(state = DelegationState.VERIFYING, result = event.result, error = null) else null
            is DelegationEvent.DocumentVerified -> if (task.state == DelegationState.VERIFYING &&
                task.result != null && isUuid(event.documentId))
                task.copy(state = DelegationState.COMPLETED, documentId = event.documentId) else null
            is DelegationEvent.Fail -> task.copy(state = DelegationState.FAILED, waitingFrom = null, error = event.error)
            DelegationEvent.Cancel -> task.copy(state = DelegationState.CANCELLED, waitingFrom = null)
        } ?: return TransitionResult.Rejected("Transición no permitida para esta tarea.")
        return TransitionResult.Applied(next.copy(revision = task.revision + 1,
            updatedAtMs = maxOf(nowMs, task.updatedAtMs)))
    }
}
