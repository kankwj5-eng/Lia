package org.lia.accessibility.agent.delegation

/**
 * Implementations must be thread-safe and durable before connecting real providers.
 * A request is immutable. Task IDs and idempotency keys are unique even for terminal jobs.
 * Creation and CAS are atomic, so two coordinators cannot reserve the same active slot.
 */
interface DelegationTaskStore {
    fun createIfIdle(task: DelegationTask): Boolean
    fun get(taskId: String): DelegationTask?

    /** Match existing revision and identity; updated.revision must equal expectedRevision + 1. */
    fun compareAndSet(taskId: String, expectedRevision: Long, updated: DelegationTask): Boolean
}
