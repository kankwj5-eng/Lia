package org.lia.accessibility.agent.runtime

enum class AgentPhase {
    IDLE,
    OBSERVING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class AgentEventType {
    PHASE,
    OBSERVATION,
    DECISION,
    ACTION,
    VERIFICATION,
    RECOVERY,
    COMPLETION,
    FAILURE
}

data class AgentObservation(
    val fingerprint: String,
    val summary: String,
    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

data class ToolOutcome(
    val dispatched: Boolean,
    val success: Boolean,
    val message: String,
    val screenMayHaveChanged: Boolean = true
)

data class VerificationOutcome(
    val progress: Boolean,
    val completed: Boolean = false,
    val message: String,
    val safeToRetry: Boolean = false
)

data class AgentEvent(
    val type: AgentEventType,
    val phase: AgentPhase,
    val step: Int,
    val message: String,
    val fingerprint: String? = null,
    val actionKey: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class AgentRunResult(
    val success: Boolean,
    val result: String,
    val steps: Int,
    val recoveries: Int,
    val events: List<AgentEvent>
)

data class AgentConfig(
    val maxSteps: Int = 30,
    val maxRecoveries: Int = 5,
    val maxRepeatedAction: Int = 3,
    val maxRepeatedObservation: Int = 4,
    val preActionFreshnessGuard: Boolean = true
) {
    init {
        require(maxSteps in 1..200)
        require(maxRecoveries in 0..50)
        require(maxRepeatedAction in 1..20)
        require(maxRepeatedObservation in 1..20)
    }
}

sealed interface AgentDecision<out A> {
    data class Act<A>(
        val action: A,
        val actionKey: String,
        val safeToRetry: Boolean = false
    ) : AgentDecision<A>

    data class Finish(val result: String) : AgentDecision<Nothing>
    data class Fail(val reason: String) : AgentDecision<Nothing>
}

data class AgentContext<A>(
    val goal: String,
    val step: Int,
    val observation: AgentObservation,
    val previousActions: List<A>,
    val previousEvents: List<AgentEvent>
)

fun interface AgentPlanner<A> {
    fun decide(context: AgentContext<A>): AgentDecision<A>
}

interface AgentEnvironment<A> {
    fun observe(): AgentObservation
    fun execute(action: A): ToolOutcome

    fun verify(
        before: AgentObservation,
        after: AgentObservation,
        action: A,
        outcome: ToolOutcome
    ): VerificationOutcome

    fun verifyCompletion(
        observation: AgentObservation,
        proposedResult: String
    ): VerificationOutcome =
        VerificationOutcome(
            progress = true,
            completed = true,
            message = proposedResult
        )
}

class LiaMicroAgent<A>(
    private val environment: AgentEnvironment<A>,
    private val planner: AgentPlanner<A>,
    private val config: AgentConfig = AgentConfig()
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(goal: String): AgentRunResult {
        require(goal.isNotBlank()) { "El objetivo del agente no puede estar vacío." }

        cancelled = false
        val events = mutableListOf<AgentEvent>()
        val actions = mutableListOf<A>()
        val recentActionKeys = ArrayDeque<String>()
        val recentFingerprints = ArrayDeque<String>()
        var recoveries = 0
        var phase = AgentPhase.IDLE

        fun emit(
            type: AgentEventType,
            step: Int,
            message: String,
            fingerprint: String? = null,
            actionKey: String? = null
        ) {
            events += AgentEvent(
                type = type,
                phase = phase,
                step = step,
                message = message,
                fingerprint = fingerprint,
                actionKey = actionKey
            )
        }

        for (step in 1..config.maxSteps) {
            if (cancelled) {
                phase = AgentPhase.CANCELLED
                emit(AgentEventType.FAILURE, step, "La tarea fue cancelada.")
                return AgentRunResult(false, "Tarea cancelada.", step - 1, recoveries, events.toList())
            }

            phase = AgentPhase.OBSERVING
            emit(AgentEventType.PHASE, step, "Observando.")
            val before = runCatching { environment.observe() }.getOrElse { error ->
                phase = AgentPhase.FAILED
                emit(AgentEventType.FAILURE, step, "No se pudo observar: " + error.message)
                return AgentRunResult(false, "No se pudo observar el teléfono.", step, recoveries, events.toList())
            }

            emit(
                AgentEventType.OBSERVATION,
                step,
                before.summary,
                fingerprint = before.fingerprint
            )

            recentFingerprints.addLast(before.fingerprint)
            while (recentFingerprints.size > config.maxRepeatedObservation) {
                recentFingerprints.removeFirst()
            }

            if (
                recentFingerprints.size == config.maxRepeatedObservation &&
                recentFingerprints.all { it == before.fingerprint }
            ) {
                recoveries++
                phase = AgentPhase.RECOVERING
                emit(
                    AgentEventType.RECOVERY,
                    step,
                    "La pantalla no cambia; se fuerza una replanificación."
                )

                recentFingerprints.clear()

                if (recoveries > config.maxRecoveries) {
                    phase = AgentPhase.FAILED
                    emit(
                        AgentEventType.FAILURE,
                        step,
                        "Se agotó el presupuesto de recuperación por estancamiento."
                    )
                    return AgentRunResult(
                        false,
                        "Lía se quedó atascada y detuvo la tarea de forma segura.",
                        step,
                        recoveries,
                        events.toList()
                    )
                }
            }

            phase = AgentPhase.PLANNING
            emit(AgentEventType.PHASE, step, "Planificando siguiente acción.")

            val decision = runCatching {
                planner.decide(
                    AgentContext(
                        goal = goal,
                        step = step,
                        observation = before,
                        previousActions = actions.toList(),
                        previousEvents = events.toList()
                    )
                )
            }.getOrElse { error ->
                phase = AgentPhase.FAILED
                emit(AgentEventType.FAILURE, step, "El planificador falló: " + error.message)
                return AgentRunResult(false, "Falló el planificador.", step, recoveries, events.toList())
            }

            when (decision) {
                is AgentDecision.Finish -> {
                    phase = AgentPhase.VERIFYING
                    emit(
                        AgentEventType.PHASE,
                        step,
                        "Verificando que el objetivo realmente esté completo."
                    )

                    val finalObservation = runCatching {
                        environment.observe()
                    }.getOrElse { before }

                    val completion = runCatching {
                        environment.verifyCompletion(
                            observation = finalObservation,
                            proposedResult = decision.result
                        )
                    }.getOrElse { error ->
                        VerificationOutcome(
                            progress = false,
                            completed = false,
                            message = error.message ?: "No se pudo verificar la finalización."
                        )
                    }

                    emit(
                        AgentEventType.VERIFICATION,
                        step,
                        completion.message,
                        fingerprint = finalObservation.fingerprint
                    )

                    if (completion.completed) {
                        phase = AgentPhase.COMPLETED
                        emit(
                            AgentEventType.COMPLETION,
                            step,
                            completion.message,
                            fingerprint = finalObservation.fingerprint
                        )
                        return AgentRunResult(
                            true,
                            completion.message,
                            step,
                            recoveries,
                            events.toList()
                        )
                    }

                    recoveries++
                    phase = AgentPhase.RECOVERING
                    emit(
                        AgentEventType.RECOVERY,
                        step,
                        "El planificador declaró éxito, pero el entorno no lo confirmó."
                    )

                    if (recoveries > config.maxRecoveries) {
                        phase = AgentPhase.FAILED
                        emit(
                            AgentEventType.FAILURE,
                            step,
                            "No fue posible verificar la finalización de la tarea."
                        )
                        return AgentRunResult(
                            false,
                            "Lía no pudo confirmar que la tarea terminó correctamente.",
                            step,
                            recoveries,
                            events.toList()
                        )
                    }

                    continue
                }

                is AgentDecision.Fail -> {
                    phase = AgentPhase.FAILED
                    emit(AgentEventType.FAILURE, step, decision.reason)
                    return AgentRunResult(false, decision.reason, step, recoveries, events.toList())
                }

                is AgentDecision.Act -> {
                    emit(
                        AgentEventType.DECISION,
                        step,
                        "Acción aceptada por el micro-agente.",
                        actionKey = decision.actionKey
                    )

                    recentActionKeys.addLast(decision.actionKey)
                    while (recentActionKeys.size > config.maxRepeatedAction) {
                        recentActionKeys.removeFirst()
                    }

                    if (
                        recentActionKeys.size == config.maxRepeatedAction &&
                        recentActionKeys.all { it == decision.actionKey }
                    ) {
                        recoveries++
                        phase = AgentPhase.RECOVERING
                        emit(
                            AgentEventType.RECOVERY,
                            step,
                            "La misma acción se repitió demasiadas veces.",
                            actionKey = decision.actionKey
                        )
                        recentActionKeys.clear()

                        if (recoveries > config.maxRecoveries) {
                            phase = AgentPhase.FAILED
                            emit(
                                AgentEventType.FAILURE,
                                step,
                                "Se agotó el presupuesto de recuperación por acciones repetidas."
                            )
                            return AgentRunResult(
                                false,
                                "Lía detectó un bucle de acciones y detuvo la tarea.",
                                step,
                                recoveries,
                                events.toList()
                            )
                        }

                        continue
                    }

                    if (config.preActionFreshnessGuard) {
                        val freshObservation = runCatching {
                            environment.observe()
                        }.getOrElse { before }

                        if (freshObservation.fingerprint != before.fingerprint) {
                            recoveries++
                            phase = AgentPhase.RECOVERING
                            emit(
                                AgentEventType.RECOVERY,
                                step,
                                "La pantalla cambió después de planificar; se canceló la acción obsoleta.",
                                fingerprint = freshObservation.fingerprint,
                                actionKey = decision.actionKey
                            )

                            if (recoveries > config.maxRecoveries) {
                                phase = AgentPhase.FAILED
                                emit(
                                    AgentEventType.FAILURE,
                                    step,
                                    "Se agotó el presupuesto de recuperación por cambios de pantalla."
                                )
                                return AgentRunResult(
                                    false,
                                    "La interfaz cambió demasiadas veces antes de actuar.",
                                    step,
                                    recoveries,
                                    events.toList()
                                )
                            }

                            continue
                        }
                    }

                    phase = AgentPhase.EXECUTING
                    emit(
                        AgentEventType.ACTION,
                        step,
                        "Ejecutando acción.",
                        actionKey = decision.actionKey
                    )

                    val firstOutcome = runCatching {
                        environment.execute(decision.action)
                    }.getOrElse { error ->
                        ToolOutcome(
                            dispatched = false,
                            success = false,
                            message = error.message ?: "Error al ejecutar la acción.",
                            screenMayHaveChanged = false
                        )
                    }

                    var outcome = firstOutcome

                    if (!outcome.success && decision.safeToRetry) {
                        recoveries++
                        if (recoveries <= config.maxRecoveries) {
                            phase = AgentPhase.RECOVERING
                            emit(
                                AgentEventType.RECOVERY,
                                step,
                                "Reintentando una acción marcada como segura.",
                                actionKey = decision.actionKey
                            )
                            outcome = runCatching {
                                environment.execute(decision.action)
                            }.getOrElse { error ->
                                ToolOutcome(
                                    dispatched = false,
                                    success = false,
                                    message = error.message ?: "Falló el reintento.",
                                    screenMayHaveChanged = false
                                )
                            }
                        }
                    }

                    actions += decision.action

                    phase = AgentPhase.VERIFYING
                    val after = runCatching { environment.observe() }.getOrElse { before }
                    val verification = runCatching {
                        environment.verify(
                            before = before,
                            after = after,
                            action = decision.action,
                            outcome = outcome
                        )
                    }.getOrElse { error ->
                        VerificationOutcome(
                            progress = false,
                            message = error.message ?: "No se pudo verificar la acción."
                        )
                    }

                    emit(
                        AgentEventType.VERIFICATION,
                        step,
                        verification.message,
                        fingerprint = after.fingerprint,
                        actionKey = decision.actionKey
                    )

                    if (verification.completed) {
                        phase = AgentPhase.COMPLETED
                        emit(
                            AgentEventType.COMPLETION,
                            step,
                            verification.message,
                            fingerprint = after.fingerprint
                        )
                        return AgentRunResult(
                            true,
                            verification.message,
                            step,
                            recoveries,
                            events.toList()
                        )
                    }

                    if (!verification.progress) {
                        recoveries++
                        phase = AgentPhase.RECOVERING
                        emit(
                            AgentEventType.RECOVERY,
                            step,
                            "La acción no produjo progreso verificable.",
                            fingerprint = after.fingerprint,
                            actionKey = decision.actionKey
                        )

                        if (recoveries > config.maxRecoveries) {
                            phase = AgentPhase.FAILED
                            emit(
                                AgentEventType.FAILURE,
                                step,
                                "Se agotó el presupuesto de recuperación."
                            )
                            return AgentRunResult(
                                false,
                                "No pude completar la tarea con suficiente confianza.",
                                step,
                                recoveries,
                                events.toList()
                            )
                        }
                    }
                }
            }
        }

        phase = AgentPhase.FAILED
        emit(
            AgentEventType.FAILURE,
            config.maxSteps,
            "Se alcanzó el máximo de pasos sin completar la tarea."
        )

        return AgentRunResult(
            false,
            "Se alcanzó el límite de pasos.",
            config.maxSteps,
            recoveries,
            events.toList()
        )
    }
}
