package org.lia.accessibility.agent

import android.content.Intent
import android.os.SystemClock
import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.accessibility.ScreenObservationResult
import org.lia.accessibility.accessibility.ScreenSnapshot
import org.lia.accessibility.ai.LocalLanguageModel
import org.lia.accessibility.location.LocationContextProvider
import org.lia.accessibility.security.ActionRisk
import org.lia.accessibility.vision.WorldVisionActivity
import org.lia.accessibility.voice.VoiceVerification
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class LocalAgentConfig(
    val maxSteps: Int = 24,
    val maxRecoveries: Int = 5,
    val maxRepeatedAction: Int = 3,
    val uiSettleDelayMs: Long = 250L
) {
    init {
        require(maxSteps in 1..100)
        require(maxRecoveries in 0..20)
        require(maxRepeatedAction in 1..10)
        require(uiSettleDelayMs in 0L..2_000L)
    }
}

sealed interface LocalAgentOutcome {
    val events: List<AgentEvent>

    data class Completed(
        val result: String,
        override val events: List<AgentEvent>
    ) : LocalAgentOutcome

    data class NeedsAuthorization(
        val call: LiaToolCall,
        val risk: ActionRisk,
        val reason: String,
        override val events: List<AgentEvent>
    ) : LocalAgentOutcome

    data class Failed(
        val reason: String,
        override val events: List<AgentEvent>
    ) : LocalAgentOutcome

    data class Cancelled(
        override val events: List<AgentEvent>
    ) : LocalAgentOutcome
}

class LiaLocalAgentRuntime(
    private val service: LiaAccessibilityService,
    model: LocalLanguageModel,
    private val voice: VoiceVerification,
    private val config: LocalAgentConfig = LocalAgentConfig()
) {
    private val planner = LocalModelPlanner(model)
    private val tools = AuthorizedToolExecutor(service)
    private val location = LocationContextProvider(service)
    private val recentActionKeys = ArrayDeque<String>()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun run(goal: String): LocalAgentOutcome {
        if (goal.isBlank()) {
            return LocalAgentOutcome.Failed(
                reason = "El objetivo está vacío.",
                events = emptyList()
            )
        }

        if (!voice.matched) {
            return LocalAgentOutcome.Failed(
                reason = "La voz actual no está autorizada.",
                events = emptyList()
            )
        }

        cancelled = false
        recentActionKeys.clear()

        val events = mutableListOf<AgentEvent>()
        var recoveries = 0
        var lastFingerprint: String? = null
        var repeatedObservationCount = 0

        fun emit(
            type: AgentEventType,
            phase: AgentPhase,
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
                emit(
                    AgentEventType.FAILURE,
                    AgentPhase.CANCELLED,
                    step,
                    "La tarea fue cancelada."
                )
                return LocalAgentOutcome.Cancelled(events.toList())
            }

            val before = observe()
                ?: return LocalAgentOutcome.Failed(
                    reason = "Lía no puede observar la pantalla activa.",
                    events = events.toList()
                )

            emit(
                AgentEventType.OBSERVATION,
                AgentPhase.OBSERVING,
                step,
                before.compactDescription().ifBlank { "[sin texto accesible]" },
                fingerprint = before.fingerprint()
            )

            val fingerprint = before.fingerprint()
            if (fingerprint == lastFingerprint) {
                repeatedObservationCount++
            } else {
                repeatedObservationCount = 1
                lastFingerprint = fingerprint
            }

            if (repeatedObservationCount >= 4) {
                recoveries++
                repeatedObservationCount = 0
                emit(
                    AgentEventType.RECOVERY,
                    AgentPhase.RECOVERING,
                    step,
                    "La interfaz lleva varios pasos sin cambiar.",
                    fingerprint = fingerprint
                )

                if (recoveries > config.maxRecoveries) {
                    return LocalAgentOutcome.Failed(
                        reason = "Lía detectó estancamiento y detuvo la tarea.",
                        events = events.toList()
                    )
                }
            }

            val state = service.capturePhoneState()

            emit(
                AgentEventType.PHASE,
                AgentPhase.PLANNING,
                step,
                "Planificando una herramienta."
            )

            when (
                val decision = planner.plan(
                    goal = goal,
                    state = state,
                    screen = before,
                    events = events
                )
            ) {
                is LocalPlannerDecision.ProtocolError -> {
                    recoveries++
                    emit(
                        AgentEventType.RECOVERY,
                        AgentPhase.RECOVERING,
                        step,
                        decision.reason
                    )

                    if (recoveries > config.maxRecoveries) {
                        return LocalAgentOutcome.Failed(
                            reason = decision.reason,
                            events = events.toList()
                        )
                    }
                }

                is LocalPlannerDecision.Finish -> {
                    val finalScreen = observe()
                    if (finalScreen == null) {
                        recoveries++
                        emit(
                            AgentEventType.RECOVERY,
                            AgentPhase.RECOVERING,
                            step,
                            "No se pudo verificar la pantalla final."
                        )
                        continue
                    }

                    emit(
                        AgentEventType.COMPLETION,
                        AgentPhase.COMPLETED,
                        step,
                        decision.result,
                        fingerprint = finalScreen.fingerprint()
                    )

                    return LocalAgentOutcome.Completed(
                        result = decision.result,
                        events = events.toList()
                    )
                }

                is LocalPlannerDecision.Act -> {
                    val call = decision.call
                    val effectiveRisk = EffectiveToolRiskPolicy.effectiveRisk(call)

                    emit(
                        AgentEventType.DECISION,
                        AgentPhase.PLANNING,
                        step,
                        "Herramienta seleccionada: " + call.name,
                        fingerprint = fingerprint,
                        actionKey = call.actionKey
                    )

                    if (isRepeated(call.actionKey)) {
                        recoveries++
                        emit(
                            AgentEventType.RECOVERY,
                            AgentPhase.RECOVERING,
                            step,
                            "La misma herramienta se repitió demasiadas veces.",
                            fingerprint = fingerprint,
                            actionKey = call.actionKey
                        )

                        if (recoveries > config.maxRecoveries) {
                            return LocalAgentOutcome.Failed(
                                reason = "Lía detectó un bucle de herramientas.",
                                events = events.toList()
                            )
                        }
                        continue
                    }

                    val fresh = observe()
                    if (fresh == null || fresh.fingerprint() != fingerprint) {
                        recoveries++
                        emit(
                            AgentEventType.RECOVERY,
                            AgentPhase.RECOVERING,
                            step,
                            "La pantalla cambió después de planificar; la acción fue descartada.",
                            fingerprint = fresh?.fingerprint(),
                            actionKey = call.actionKey
                        )
                        continue
                    }

                    if (effectiveRisk != ActionRisk.ROUTINE) {
                        emit(
                            AgentEventType.FAILURE,
                            AgentPhase.FAILED,
                            step,
                            "La herramienta requiere autorización adicional.",
                            fingerprint = fingerprint,
                            actionKey = call.actionKey
                        )

                        return LocalAgentOutcome.NeedsAuthorization(
                            call = call,
                            risk = effectiveRisk,
                            reason = when (effectiveRisk) {
                                ActionRisk.SENSITIVE ->
                                    "Esta acción necesita una comprobación adicional."
                                ActionRisk.IRREVERSIBLE ->
                                    "Esta acción necesita una comprobación adicional y confirmación explícita."
                                ActionRisk.ROUTINE ->
                                    "Autorización rutinaria."
                            },
                            events = events.toList()
                        )
                    }

                    emit(
                        AgentEventType.ACTION,
                        AgentPhase.EXECUTING,
                        step,
                        "Ejecutando " + call.name + ".",
                        fingerprint = fingerprint,
                        actionKey = call.actionKey
                    )

                    val execution = executeRoutine(call)

                    emit(
                        AgentEventType.VERIFICATION,
                        AgentPhase.VERIFYING,
                        step,
                        execution.message,
                        actionKey = call.actionKey
                    )

                    if (!execution.accepted || !execution.performed) {
                        recoveries++
                        if (recoveries > config.maxRecoveries) {
                            return LocalAgentOutcome.Failed(
                                reason = execution.message,
                                events = events.toList()
                            )
                        }
                        continue
                    }

                    if (expectsScreenChange(call.name)) {
                        if (config.uiSettleDelayMs > 0) {
                            SystemClock.sleep(config.uiSettleDelayMs)
                        }

                        val after = observe()
                        val changed =
                            after != null &&
                                after.fingerprint() != fingerprint

                        if (!changed) {
                            recoveries++
                            emit(
                                AgentEventType.RECOVERY,
                                AgentPhase.RECOVERING,
                                step,
                                "La herramienta se ejecutó pero no produjo un cambio verificable.",
                                fingerprint = after?.fingerprint(),
                                actionKey = call.actionKey
                            )

                            if (recoveries > config.maxRecoveries) {
                                return LocalAgentOutcome.Failed(
                                    reason = "La interfaz no respondió a las acciones de Lía.",
                                    events = events.toList()
                                )
                            }
                        }
                    }
                }
            }
        }

        return LocalAgentOutcome.Failed(
            reason = "Se alcanzó el límite de pasos sin completar la tarea.",
            events = events.toList()
        )
    }

    private fun observe(): ScreenSnapshot? =
        when (val result = service.observeScreen(voice)) {
            is ScreenObservationResult.Allowed -> result.snapshot
            is ScreenObservationResult.Denied -> null
        }

    private fun isRepeated(actionKey: String): Boolean {
        recentActionKeys.addLast(actionKey)
        while (recentActionKeys.size > config.maxRepeatedAction) {
            recentActionKeys.removeFirst()
        }

        if (
            recentActionKeys.size == config.maxRepeatedAction &&
            recentActionKeys.all { it == actionKey }
        ) {
            recentActionKeys.clear()
            return true
        }

        return false
    }

    private suspend fun executeRoutine(
        call: LiaToolCall
    ): ToolExecutionResult {
        val initial = tools.execute(
            call = call,
            voice = voice
        )

        if (!initial.requiresAsyncHandler) {
            return initial.withDataInMessage()
        }

        return when (call.name) {
            "read_screen_ocr" -> readScreenOcr()
            "get_location" -> currentLocation()
            "world_vision" -> openWorldVision()
            else ->
                ToolExecutionResult(
                    accepted = false,
                    performed = false,
                    message = "No existe manejador asíncrono para " + call.name + "."
                )
        }
    }

    private suspend fun readScreenOcr(): ToolExecutionResult =
        suspendCoroutine { continuation ->
            service.readScreenVisually(voice) { result ->
                val mapped = result.fold(
                    onSuccess = { text ->
                        ToolExecutionResult(
                            accepted = true,
                            performed = true,
                            message = if (text.isBlank()) {
                                "OCR completado, pero no encontré texto visible."
                            } else {
                                "OCR: " + text.take(MAX_TOOL_RESULT_CHARS)
                            }
                        )
                    },
                    onFailure = { error ->
                        ToolExecutionResult(
                            accepted = true,
                            performed = false,
                            message = error.message ?: "Falló el OCR de pantalla."
                        )
                    }
                )
                continuation.resume(mapped)
            }
        }

    private suspend fun currentLocation(): ToolExecutionResult =
        suspendCoroutine { continuation ->
            location.currentLocation { result ->
                val mapped = result.fold(
                    onSuccess = { current ->
                        val description = buildString {
                            append(current.address ?: "Ubicación sin dirección postal")
                            append(". Precisión aproximada: ")
                            append(current.accuracyMeters.toInt())
                            append(" metros.")
                        }

                        ToolExecutionResult(
                            accepted = true,
                            performed = true,
                            message = description
                        )
                    },
                    onFailure = { error ->
                        ToolExecutionResult(
                            accepted = true,
                            performed = false,
                            message = error.message ?: "No pude obtener la ubicación."
                        )
                    }
                )
                continuation.resume(mapped)
            }
        }

    private fun openWorldVision(): ToolExecutionResult =
        runCatching {
            service.startActivity(
                Intent(service, WorldVisionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            ToolExecutionResult(
                accepted = true,
                performed = true,
                message = "Abrí la cámara de visión del entorno."
            )
        }.getOrElse { error ->
            ToolExecutionResult(
                accepted = true,
                performed = false,
                message = error.message ?: "No pude abrir la visión del entorno."
            )
        }

    private fun ToolExecutionResult.withDataInMessage(): ToolExecutionResult {
        if (data.isEmpty()) return this

        val combined = data
            .joinToString(" | ")
            .take(MAX_TOOL_RESULT_CHARS)

        return copy(
            message = message + " " + combined
        )
    }

    private fun expectsScreenChange(toolName: String): Boolean =
        toolName in SCREEN_CHANGING_TOOLS

    companion object {
        private const val MAX_TOOL_RESULT_CHARS = 1_200

        private val SCREEN_CHANGING_TOOLS = setOf(
            "open_app",
            "click",
            "set_text",
            "scroll",
            "back",
            "home",
            "recents",
            "set_timer",
            "set_alarm",
            "world_vision"
        )
    }
}
