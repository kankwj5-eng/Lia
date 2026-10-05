package org.lia.accessibility.agent.delegation

import kotlin.coroutines.*

internal fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return requireNotNull(outcome) { "La prueba requiere completar sin esperas reales" }.getOrThrow()
}

internal class FakeDelegationProvider : DelegationProvider {
    override val providerId = "openai"
    var submits = 0
    var statuses = 0
    var cancellations = 0
    var submitReply: ProviderReply<String> = ProviderReply.Success("remote-1")
    var statusReply: ProviderReply<RemoteTaskState> = ProviderReply.Success(RemoteTaskState.RUNNING)
    var resultReply: ProviderReply<ResearchResult> = ProviderReply.Success(research())
    var cancelReply: ProviderReply<Unit> = ProviderReply.Success(Unit)
    var resolution: ProviderReply<SubmissionResolution> = ProviderReply.Success(SubmissionResolution.Unknown)
    var onSubmit: (() -> Unit)? = null
    var onResult: (() -> Unit)? = null
    var onStatus: (() -> Unit)? = null
    override suspend fun submit(request: DelegationRequest): ProviderReply<String> { submits++; onSubmit?.invoke(); return submitReply }
    override suspend fun status(remoteId: String): ProviderReply<RemoteTaskState> { statuses++; onStatus?.invoke(); return statusReply }
    override suspend fun result(remoteId: String): ProviderReply<ResearchResult> { onResult?.invoke(); return resultReply }
    override suspend fun cancel(remoteId: String): ProviderReply<Unit> { cancellations++; return cancelReply }
    override suspend fun reconcile(idempotencyKey: String) = resolution
}
