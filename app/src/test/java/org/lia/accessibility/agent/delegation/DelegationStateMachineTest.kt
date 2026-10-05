package org.lia.accessibility.agent.delegation

import org.junit.Assert.*
import org.junit.Test

internal fun task(state: DelegationState = DelegationState.AWAITING_AUTHORIZATION) = DelegationTask(
    request(), state, 0, 1000, 1000,
    remoteId = if (state in setOf(DelegationState.RUNNING, DelegationState.VERIFYING, DelegationState.COMPLETED)) "remote-1" else null,
    result = if (state in setOf(DelegationState.VERIFYING, DelegationState.COMPLETED)) research() else null,
    documentId = if (state == DelegationState.COMPLETED) "00000000-0000-0000-0000-000000000003" else null
)
internal fun transition(t: DelegationTask, e: DelegationEvent, at: Long = 1001) =
    (DelegationStateMachine.apply(t, e, at) as TransitionResult.Applied).task

class DelegationStateMachineTest {
    @Test fun happyPathWaitsForDocumentVerification() {
        var t = task()
        t = transition(t, DelegationEvent.Authorized)
        assertEquals(DelegationState.QUEUED, t.state)
        t = transition(t, DelegationEvent.BeginSubmit)
        assertEquals(DelegationState.SUBMITTING, t.state)
        t = transition(t, DelegationEvent.Submitted("remote-1"))
        assertEquals(DelegationState.RUNNING, t.state)
        t = transition(t, DelegationEvent.ResultReceived(research()))
        assertEquals(DelegationState.VERIFYING, t.state)
        t = transition(t, DelegationEvent.DocumentVerified("00000000-0000-0000-0000-000000000003"))
        assertEquals(DelegationState.COMPLETED, t.state)
        assertEquals(5, t.revision)
        assertNotNull(t.documentId)
    }
    @Test fun terminalStatesAreImmutable() {
        val events = listOf(DelegationEvent.Authorized, DelegationEvent.BeginSubmit, DelegationEvent.Cancel,
            DelegationEvent.NetworkLost, DelegationEvent.Fail(DelegationError.TIMEOUT))
        listOf(DelegationState.COMPLETED, DelegationState.CANCELLED, DelegationState.FAILED).forEach { state ->
            events.forEach { assertTrue(DelegationStateMachine.apply(task(state), it, 1002) is TransitionResult.Rejected) }
        }
    }
    @Test fun networkRestoreReturnsToPreviousState() {
        listOf(DelegationState.QUEUED, DelegationState.SUBMITTING, DelegationState.RUNNING).forEach { state ->
            val before = task(state)
            val waiting = transition(before, DelegationEvent.NetworkLost)
            assertEquals(DelegationState.WAITING_NETWORK, waiting.state)
            val restored = transition(waiting, DelegationEvent.NetworkRestored)
            assertEquals(state, restored.state); assertEquals(before.remoteId, restored.remoteId)
            assertNull(restored.waitingFrom)
        }
    }
    @Test fun submitBeforeAuthorizationRejected() {
        assertTrue(DelegationStateMachine.apply(task(), DelegationEvent.BeginSubmit, 1001) is TransitionResult.Rejected)
    }
    @Test fun verifiedWithoutResultRejected() {
        assertTrue(DelegationStateMachine.apply(task(DelegationState.RUNNING),
            DelegationEvent.DocumentVerified("00000000-0000-0000-0000-000000000003"),1001) is TransitionResult.Rejected)
    }
    @Test fun cancelFromAllActiveStates() {
        DelegationState.entries.filter { !it.terminal && it != DelegationState.WAITING_NETWORK }.forEach {
            assertEquals(DelegationState.CANCELLED, transition(task(it), DelegationEvent.Cancel).state)
        }
        val waiting = transition(task(DelegationState.RUNNING), DelegationEvent.NetworkLost)
        assertEquals(DelegationState.CANCELLED, transition(waiting, DelegationEvent.Cancel).state)
    }
    @Test fun invalidRemoteAndDocumentIdsRejected() {
        assertTrue(DelegationStateMachine.apply(task(DelegationState.SUBMITTING), DelegationEvent.Submitted(" "), 1001) is TransitionResult.Rejected)
        assertTrue(DelegationStateMachine.apply(task(DelegationState.VERIFYING), DelegationEvent.DocumentVerified("../../other"),1001) is TransitionResult.Rejected)
    }
    @Test fun timestampsNeverRegress() {
        assertEquals(1000, transition(task(), DelegationEvent.Authorized, 999).updatedAtMs)
    }
    @Test fun outOfOrderResultsRejected() {
        assertTrue(DelegationStateMachine.apply(task(DelegationState.SUBMITTING), DelegationEvent.ResultReceived(research()),1001) is TransitionResult.Rejected)
    }
    @Test fun failurePreservesTypedError() {
        val failed = transition(task(DelegationState.RUNNING), DelegationEvent.Fail(DelegationError.AUTHENTICATION))
        assertEquals(DelegationState.FAILED, failed.state)
        assertEquals(DelegationError.AUTHENTICATION, failed.error)
    }
}
