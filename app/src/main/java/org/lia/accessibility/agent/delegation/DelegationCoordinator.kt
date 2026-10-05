package org.lia.accessibility.agent.delegation

import java.io.IOException
import java.util.concurrent.CancellationException

sealed interface CoordinatorResult {
    data class Updated(val task: DelegationTask, val remoteError: DelegationError? = null) : CoordinatorResult
    data class Rejected(val reason: String) : CoordinatorResult
}

/** Pure job lifecycle. No screen observation, file access, Android action or model invocation. */
class DelegationCoordinator(
    private val store: DelegationTaskStore,
    private val provider: DelegationProvider,
    private val clock: () -> Long
) {
    private val operations = mutableSetOf<String>()

    fun create(request: DelegationRequest): CoordinatorResult {
        if (request.providerId != provider.providerId) return CoordinatorResult.Rejected("Proveedor no disponible.")
        val now = clock()
        if (now < 0) return CoordinatorResult.Rejected("Reloj inválido.")
        val task = DelegationTask(request, DelegationState.AWAITING_AUTHORIZATION, 0, now, now)
        return if (store.createIfIdle(task)) CoordinatorResult.Updated(task)
        else CoordinatorResult.Rejected("Hay una tarea activa o un identificador ya utilizado.")
    }

    suspend fun submit(taskId: String, grant: DelegationGrant): CoordinatorResult = exclusive(taskId) {
        var task = store.get(taskId) ?: return@exclusive missing()
        if (task.request.providerId != provider.providerId) return@exclusive CoordinatorResult.Rejected("Proveedor incompatible.")
        if (!DelegationAuthorization.allows(task.request, grant, clock()))
            return@exclusive CoordinatorResult.Rejected("Autorización ausente, modificada o caducada.")
        if (task.state.terminal) return@exclusive terminalResult(task)
        if (expired(task)) return@exclusive timeout(task)
        if (task.state == DelegationState.WAITING_NETWORK) {
            task = move(task, DelegationEvent.NetworkRestored) ?: return@exclusive latest(taskId)
        }
        if (task.state == DelegationState.SUBMITTING) {
            // A fresh pending send may still be in flight in another coordinator.
            if (task.error != DelegationError.UNCERTAIN_SUBMISSION) return@exclusive CoordinatorResult.Rejected("Envío pendiente de reconciliación.")
            when (val reply = safely { provider.reconcile(task.request.idempotencyKey) }) {
                is ProviderReply.Success -> when (val found = reply.value) {
                    is SubmissionResolution.Found -> return@exclusive recordSubmission(task, found.remoteId)
                    SubmissionResolution.Unknown -> return@exclusive mark(task, DelegationError.UNCERTAIN_SUBMISSION)
                    SubmissionResolution.ConfirmedAbsent -> {
                        // Claim a new revision before sending; the same key is always retained.
                        task = save(task, task.copy(error = null)) ?: return@exclusive latest(taskId)
                    }
                }
                is ProviderReply.Failure -> return@exclusive mark(task, DelegationError.UNCERTAIN_SUBMISSION)
            }
        } else {
            if (task.state == DelegationState.AWAITING_AUTHORIZATION)
                task = move(task, DelegationEvent.Authorized) ?: return@exclusive latest(taskId)
            if (task.state != DelegationState.QUEUED) return@exclusive CoordinatorResult.Rejected("La tarea ya fue enviada.")
            task = move(task, DelegationEvent.BeginSubmit) ?: return@exclusive latest(taskId)
        }
        // Recheck after any suspending reconciliation and immediately before network disclosure.
        val latest = store.get(taskId) ?: return@exclusive missing()
        if (latest.revision != task.revision || latest.state != DelegationState.SUBMITTING) return@exclusive latest(taskId)
        if (!DelegationAuthorization.allows(task.request, grant, clock()))
            return@exclusive mark(task, DelegationError.UNCERTAIN_SUBMISSION)
        if (expired(task)) return@exclusive timeout(task)
        when (val reply = safely { provider.submit(task.request) }) {
            is ProviderReply.Success -> recordSubmission(task, reply.value)
            is ProviderReply.Failure -> when (reply.error) {
                DelegationError.NOT_CONFIGURED, DelegationError.AUTHENTICATION, DelegationError.RATE_LIMIT ->
                    failed(task, reply.error)
                else -> mark(task, DelegationError.UNCERTAIN_SUBMISSION)
            }
        }
    }

    suspend fun refresh(taskId: String): CoordinatorResult = exclusive(taskId) {
        var task = store.get(taskId) ?: return@exclusive missing()
        if (task.request.providerId != provider.providerId) return@exclusive CoordinatorResult.Rejected("Proveedor incompatible.")
        if (task.state.terminal) return@exclusive terminalResult(task)
        if (expired(task)) return@exclusive timeout(task)
        if (task.state == DelegationState.WAITING_NETWORK)
            task = move(task, DelegationEvent.NetworkRestored) ?: return@exclusive latest(taskId)
        if (task.state == DelegationState.SUBMITTING) {
            return@exclusive when (val reply = safely { provider.reconcile(task.request.idempotencyKey) }) {
                is ProviderReply.Success -> when (val resolution = reply.value) {
                    is SubmissionResolution.Found -> recordSubmission(task, resolution.remoteId)
                    else -> mark(task, DelegationError.UNCERTAIN_SUBMISSION)
                }
                is ProviderReply.Failure -> mark(task, DelegationError.UNCERTAIN_SUBMISSION)
            }
        }
        if (task.state != DelegationState.RUNNING) return@exclusive CoordinatorResult.Updated(task)
        val remoteId = requireNotNull(task.remoteId)
        val status = safely { provider.status(remoteId) }
        val afterStatus = store.get(taskId) ?: return@exclusive missing()
        if (afterStatus.revision != task.revision) return@exclusive latest(taskId)
        if (expired(afterStatus)) return@exclusive timeout(afterStatus)
        when (status) {
            is ProviderReply.Failure -> remoteFailure(task, status.error)
            is ProviderReply.Success -> when (status.value) {
                RemoteTaskState.RUNNING -> latest(taskId)
                RemoteTaskState.FAILED -> failed(task, DelegationError.INVALID_RESPONSE)
                RemoteTaskState.CANCELLED -> moved(task, DelegationEvent.Cancel)
                RemoteTaskState.COMPLETED -> {
                    // A cancellation during the status call must stop result retrieval too.
                    val beforeResult = store.get(taskId) ?: return@exclusive missing()
                    if (beforeResult.revision != task.revision) return@exclusive latest(taskId)
                    if (expired(beforeResult)) return@exclusive timeout(beforeResult)
                    when (val result = safely { provider.result(remoteId) }) {
                        is ProviderReply.Success -> {
                            val current = store.get(taskId) ?: return@exclusive missing()
                            if (current.revision != task.revision) latest(taskId)
                            else if (expired(current)) timeout(current)
                            else moved(current, DelegationEvent.ResultReceived(result.value))
                        }
                        is ProviderReply.Failure -> remoteFailure(task, result.error)
                    }
                }
            }
        }
    }

    suspend fun cancel(taskId: String): CoordinatorResult {
        // Cancellation intentionally bypasses the network-operation guard.
        repeat(16) {
            val task = store.get(taskId) ?: return missing()
            if (task.state.terminal) return terminalResult(task)
            if (task.revision == Long.MAX_VALUE) return CoordinatorResult.Rejected("Revisión agotada.")
            val cancelled = move(task, DelegationEvent.Cancel)
            if (cancelled != null) return cleanupRemote(cancelled)
        }
        return CoordinatorResult.Rejected("La tarea cambió durante la cancelación; vuelve a intentarlo.")
    }

    private suspend fun terminalResult(task: DelegationTask): CoordinatorResult =
        if (task.state == DelegationState.CANCELLED ||
            (task.state == DelegationState.FAILED && task.error == DelegationError.TIMEOUT))
            cleanupRemote(task) else CoordinatorResult.Updated(task)

    private suspend fun cleanupRemote(task: DelegationTask): CoordinatorResult {
        if (!task.submissionStarted) return CoordinatorResult.Updated(task)
        val id = task.remoteId ?: when (val reply = safely { provider.reconcile(task.request.idempotencyKey) }) {
            is ProviderReply.Failure -> return CoordinatorResult.Updated(task, reply.error)
            is ProviderReply.Success -> when (val resolution = reply.value) {
                is SubmissionResolution.Found -> resolution.remoteId
                SubmissionResolution.ConfirmedAbsent -> return CoordinatorResult.Updated(task)
                SubmissionResolution.Unknown -> return CoordinatorResult.Updated(task, DelegationError.UNCERTAIN_SUBMISSION)
            }
        }
        if (id.isBlank() || id.length > 512) return CoordinatorResult.Updated(task, DelegationError.INVALID_RESPONSE)
        val error = (safely { provider.cancel(id) } as? ProviderReply.Failure)?.error
        return CoordinatorResult.Updated(task, error)
    }

    private suspend fun recordSubmission(task: DelegationTask, remoteId: String): CoordinatorResult {
        if (remoteId.isBlank() || remoteId.length > 512) return mark(task, DelegationError.UNCERTAIN_SUBMISSION)
        val recorded = move(task, DelegationEvent.Submitted(remoteId))
        if (recorded != null) {
            if (expired(recorded)) return timeout(recorded)
            return CoordinatorResult.Updated(recorded)
        }
        val current = store.get(task.request.taskId) ?: return missing()
        if (current.state.terminal) {
            val failure = (safely { provider.cancel(remoteId) } as? ProviderReply.Failure)?.error
            return CoordinatorResult.Updated(current, failure)
        }
        // A racing poll may have already recorded the same remote job. Never resend.
        return CoordinatorResult.Updated(current)
    }

    private fun remoteFailure(task: DelegationTask, error: DelegationError): CoordinatorResult =
        if (error == DelegationError.NETWORK) {
            val waiting = move(task, DelegationEvent.NetworkLost)
            if (waiting != null) mark(waiting, error) else latest(task.request.taskId)
        } else failed(task, error)

    private suspend fun timeout(task: DelegationTask): CoordinatorResult {
        val next = move(task, DelegationEvent.Fail(DelegationError.TIMEOUT))
            ?: return latest(task.request.taskId)
        return cleanupRemote(next)
    }

    private fun expired(task: DelegationTask): Boolean {
        val now = clock()
        return now >= task.createdAtMs && now - task.createdAtMs >= DelegationLimits.TASK_TIMEOUT_MS
    }

    private fun failed(task: DelegationTask, error: DelegationError) = moved(task, DelegationEvent.Fail(error))
    private fun moved(task: DelegationTask, event: DelegationEvent): CoordinatorResult =
        move(task, event)?.let { CoordinatorResult.Updated(it) } ?: latest(task.request.taskId)

    private fun move(task: DelegationTask, event: DelegationEvent): DelegationTask? {
        val applied = DelegationStateMachine.apply(task, event, clock()) as? TransitionResult.Applied ?: return null
        return if (store.compareAndSet(task.request.taskId, task.revision, applied.task)) applied.task else null
    }

    private fun mark(task: DelegationTask, error: DelegationError): CoordinatorResult =
        save(task, task.copy(error = error))?.let { CoordinatorResult.Updated(it) } ?: latest(task.request.taskId)

    private fun save(before: DelegationTask, next: DelegationTask): DelegationTask? {
        if (before.state.terminal || before.revision == Long.MAX_VALUE) return null
        val updated = next.copy(revision = before.revision + 1, updatedAtMs = maxOf(clock(), before.updatedAtMs))
        return if (store.compareAndSet(before.request.taskId, before.revision, updated)) updated else null
    }

    private fun latest(taskId: String): CoordinatorResult = store.get(taskId)?.let { CoordinatorResult.Updated(it) } ?: missing()
    private fun missing() = CoordinatorResult.Rejected("Tarea inexistente.")

    private suspend fun exclusive(taskId: String, operation: suspend () -> CoordinatorResult): CoordinatorResult {
        if (!synchronized(operations) { operations.add(taskId) }) return CoordinatorResult.Rejected("Operación en curso.")
        return try { operation() } finally { synchronized(operations) { operations.remove(taskId) } }
    }

    private suspend fun <T> safely(operation: suspend () -> ProviderReply<T>): ProviderReply<T> = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled // persisted SUBMITTING is reconciled after process/coroutine recovery
    } catch (_: IOException) {
        ProviderReply.Failure(DelegationError.NETWORK)
    } catch (_: Exception) {
        ProviderReply.Failure(DelegationError.INVALID_RESPONSE)
    }
}
