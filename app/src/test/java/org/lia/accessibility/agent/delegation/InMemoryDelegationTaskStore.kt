package org.lia.accessibility.agent.delegation

internal class InMemoryDelegationTaskStore : DelegationTaskStore {
    private val tasks = mutableMapOf<String, DelegationTask>()
    @Synchronized override fun createIfIdle(task: DelegationTask): Boolean {
        if (tasks.values.any { !it.state.terminal || it.request.taskId == task.request.taskId ||
                it.request.idempotencyKey == task.request.idempotencyKey }) return false
        tasks[task.request.taskId] = task
        return true
    }
    @Synchronized override fun get(taskId: String): DelegationTask? = tasks[taskId]
    @Synchronized override fun compareAndSet(taskId: String, expectedRevision: Long, updated: DelegationTask): Boolean {
        val current = tasks[taskId] ?: return false
        if (current.revision != expectedRevision || current.state.terminal ||
            updated.revision != expectedRevision + 1 || current.request != updated.request ||
            updated.request.taskId != taskId || current.createdAtMs != updated.createdAtMs ||
            updated.updatedAtMs < current.updatedAtMs) return false
        tasks[taskId] = updated
        return true
    }
}
