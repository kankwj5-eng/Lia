package org.lia.accessibility.agent.delegation

import org.junit.Assert.*
import org.junit.Test
import org.lia.accessibility.security.AuthorizationDecision

class DelegationCoordinatorTest {
    private val store = InMemoryDelegationTaskStore()
    private val provider = FakeDelegationProvider()
    private var now = 1000L
    private val coordinator by lazy { DelegationCoordinator(store, provider) { now } }
    private val id = request().taskId
    private fun grant(at: Long = now) = requireNotNull(DelegationAuthorization.grant(request(), at, AuthorizationDecision.Allowed))
    private fun start() { coordinator.create(request()); runNow { coordinator.submit(id, grant()) } }
    private fun current() = requireNotNull(store.get(id))

    @Test fun noGrantNoNetwork() {
        coordinator.create(request())
        val g = grant().copy(requestHash = "bad")
        assertTrue(runNow { coordinator.submit(id, g) } is CoordinatorResult.Rejected)
        assertEquals(DelegationState.AWAITING_AUTHORIZATION, current().state)
        assertEquals(0, provider.submits)
    }
    @Test fun onlyOneSubmission() {
        start(); runNow { coordinator.submit(id, grant()) }
        assertEquals(1, provider.submits); assertEquals(DelegationState.RUNNING, current().state)
    }
    @Test fun persistBeforeNetwork() {
        provider.onSubmit = { assertEquals(DelegationState.SUBMITTING, current().state) }
        start(); assertEquals("remote-1", current().remoteId)
    }
    @Test fun timeoutDoesNotResubmit() {
        provider.submitReply = ProviderReply.Failure(DelegationError.TIMEOUT)
        start(); runNow { coordinator.refresh(id) }; runNow { coordinator.submit(id, grant()) }
        assertEquals(1, provider.submits)
        assertEquals(DelegationError.UNCERTAIN_SUBMISSION, current().error)
    }
    @Test fun reconcileFoundRecovers() {
        provider.submitReply = ProviderReply.Failure(DelegationError.TIMEOUT); start()
        provider.resolution = ProviderReply.Success(SubmissionResolution.Found("remote-recovered"))
        runNow { coordinator.refresh(id) }
        assertEquals("remote-recovered", current().remoteId)
        assertEquals(DelegationState.RUNNING, current().state)
        assertEquals(1, provider.submits)
    }
    @Test fun unknownDoesNotResubmit() {
        store.createIfIdle(task(DelegationState.SUBMITTING))
        runNow { coordinator.refresh(id) }; runNow { coordinator.submit(id, grant()) }
        assertEquals(0, provider.submits)
        assertEquals(DelegationError.UNCERTAIN_SUBMISSION, current().error)
    }
    @Test fun confirmedAbsentRequiresValidGrantBeforeResubmit() {
        store.createIfIdle(task(DelegationState.SUBMITTING))
        provider.resolution = ProviderReply.Success(SubmissionResolution.ConfirmedAbsent)
        runNow { coordinator.refresh(id) }; assertEquals(0, provider.submits)
        now = 301000
        assertTrue(runNow { coordinator.submit(id, grant(1000)) } is CoordinatorResult.Rejected)
        assertEquals(0, provider.submits)
        runNow { coordinator.submit(id, grant()) }
        assertEquals(1, provider.submits); assertEquals(DelegationState.RUNNING, current().state)
    }
    @Test fun lateResultCannotUndoCancellation() {
        start(); provider.statusReply = ProviderReply.Success(RemoteTaskState.COMPLETED)
        provider.onResult = { runNow { coordinator.cancel(id) } }
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.CANCELLED, current().state)
        assertNull(current().result)
    }
    @Test fun cancelFailureStaysCancelled() {
        start(); provider.cancelReply = ProviderReply.Failure(DelegationError.CANCEL_NOT_CONFIRMED)
        runNow { coordinator.cancel(id) }; runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.CANCELLED, current().state)
    }
    @Test fun networkRestoreKeepsRemoteId() {
        start(); provider.statusReply = ProviderReply.Failure(DelegationError.NETWORK)
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.WAITING_NETWORK, current().state)
        provider.statusReply = ProviderReply.Success(RemoteTaskState.RUNNING)
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.RUNNING, current().state)
        assertEquals("remote-1", current().remoteId); assertEquals(1, provider.submits)
    }
    @Test fun fifteenMinuteDeadlineFails() {
        start(); now = 901000
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.FAILED, current().state)
        assertEquals(DelegationError.TIMEOUT, current().error)
    }
    @Test fun remoteSuccessWaitsForDocumentVerification() {
        start(); provider.statusReply = ProviderReply.Success(RemoteTaskState.COMPLETED)
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.VERIFYING, current().state)
        assertNull(current().documentId)
    }
    @Test fun lateSubmitIsCancelledRemotely() {
        provider.onSubmit = { runNow { coordinator.cancel(id) } }
        start()
        assertEquals(DelegationState.CANCELLED, current().state)
        assertEquals(1, provider.cancellations)
    }
    @Test fun twoCoordinatorsCannotSubmitSimultaneously() {
        val other = DelegationCoordinator(store, provider) { now }
        provider.onSubmit = { runNow { other.submit(id, grant()) } }
        start(); assertEquals(1, provider.submits)
    }
    @Test fun onlyOneActiveTaskAndUniqueIdempotency() {
        coordinator.create(request())
        val other = request().copy(taskId = "00000000-0000-0000-0000-000000000004")
        assertTrue(coordinator.create(other) is CoordinatorResult.Rejected)
        runNow { coordinator.cancel(id) }
        assertTrue(coordinator.create(other) is CoordinatorResult.Rejected)
        val fresh = other.copy(idempotencyKey="00000000-0000-0000-0000-000000000005")
        assertTrue(coordinator.create(fresh) is CoordinatorResult.Updated)
    }
    @Test fun casConflictDoesNotOverwriteCancellation() {
        val stale = task()
        store.createIfIdle(stale)
        runNow { coordinator.cancel(id) }
        assertFalse(store.compareAndSet(id, 0, transition(stale, DelegationEvent.Authorized)))
        assertEquals(DelegationState.CANCELLED, current().state)
    }
    @Test fun wrongProviderNeverReceivesRequest() {
        val other = request().copy(providerId="other")
        assertTrue(coordinator.create(other) is CoordinatorResult.Rejected)
        assertEquals(0, provider.submits)
    }
    @Test fun thrownTransportErrorLeavesRecoverableSubmission() {
        provider.onSubmit = { throw java.io.IOException("secret-token-must-not-leak") }
        start()
        assertEquals(DelegationError.UNCERTAIN_SUBMISSION, current().error)
        assertFalse(current().state.terminal)
    }
    @Test fun invalidRemoteIdIsNeverUsed() {
        provider.submitReply = ProviderReply.Success(" "); start()
        assertNull(current().remoteId)
        assertEquals(DelegationError.UNCERTAIN_SUBMISSION, current().error)
    }
    @Test(timeout = 1000) fun cancellationAtMaximumRevisionDoesNotSpin() {
        store.createIfIdle(task().copy(revision = Long.MAX_VALUE))
        assertTrue(runNow { coordinator.cancel(id) } is CoordinatorResult.Rejected)
    }
    @Test fun cancelledUncertainSubmissionCanBeReconciledForCleanup() {
        provider.submitReply = ProviderReply.Failure(DelegationError.TIMEOUT)
        start(); runNow { coordinator.cancel(id) }
        provider.resolution = ProviderReply.Success(SubmissionResolution.Found("late-job"))
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.CANCELLED, current().state)
        assertEquals(1, provider.cancellations)
    }
    @Test fun cancellingUnsentTaskDoesNotUseNetwork() {
        coordinator.create(request())
        provider.resolution = ProviderReply.Success(SubmissionResolution.Found("not-ours"))
        runNow { coordinator.cancel(id) }; runNow { coordinator.refresh(id) }
        assertEquals(0, provider.cancellations)
    }
    @Test fun timeoutDuringStatusCannotReturnActiveTask() {
        start(); provider.onStatus = { now = 901000 }
        runNow { coordinator.refresh(id) }
        assertEquals(DelegationState.FAILED, current().state)
        assertEquals(DelegationError.TIMEOUT, current().error)
    }
}
