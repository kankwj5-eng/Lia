package org.lia.accessibility.agent.orchestration

import androidx.core.content.ContextCompat
import org.lia.accessibility.accessibility.LiaAccessibilityService
import org.lia.accessibility.agent.LiaLocalAgentRuntime
import org.lia.accessibility.agent.LocalAgentOutcome
import org.lia.accessibility.ai.LiteRtLmLanguageModel
import org.lia.accessibility.ai.LlamaCppLanguageModel
import org.lia.accessibility.ai.ManagedLocalLanguageModel
import org.lia.accessibility.ai.PlannerModelFormat
import org.lia.accessibility.ai.PlannerModelStore
import org.lia.accessibility.voice.VoiceVerification
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class LiaAgentCoordinator(
    private val service: LiaAccessibilityService,
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(service)
) : Closeable {
    private val launcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lia-agent-launcher")
    }
    private val generation = AtomicLong(0L)
    private val modelStore = PlannerModelStore(service)

    @Volatile
    private var currentRuntime: LiaLocalAgentRuntime? = null

    @Volatile
    private var currentModel: ManagedLocalLanguageModel? = null

    fun start(
        goal: String,
        voice: VoiceVerification,
        conversationContext: String = "",
        callback: (LocalAgentOutcome) -> Unit
    ) {
        val runGeneration = generation.incrementAndGet()
        currentRuntime?.cancel()

        launcher.execute {
            if (runGeneration != generation.get()) return@execute

            val modelInfo = modelStore.installedModel()
            if (modelInfo == null) {
                deliver(
                    runGeneration,
                    LocalAgentOutcome.Failed(
                        reason = "No hay un cerebro local instalado. Importa un modelo GGUF o LiteRT-LM.",
                        events = emptyList()
                    ),
                    callback
                )
                return@execute
            }

            val model: ManagedLocalLanguageModel = when (modelInfo.format) {
                PlannerModelFormat.GGUF ->
                    LlamaCppLanguageModel(
                        context = service,
                        modelFile = modelInfo.file,
                        sourceName = modelInfo.sourceName
                    )

                PlannerModelFormat.LITERT_LM ->
                    LiteRtLmLanguageModel(
                        context = service,
                        modelFile = modelInfo.file
                    )
            }

            val runtime = LiaLocalAgentRuntime(
                service = service,
                model = model,
                voice = voice
            )

            currentModel = model
            currentRuntime = runtime

            val block: suspend () -> LocalAgentOutcome = {
                runtime.run(
                    goal = goal,
                    conversationContext = conversationContext
                )
            }

            block.startCoroutine(
                object : Continuation<LocalAgentOutcome> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<LocalAgentOutcome>) {
                        val outcome = result.getOrElse { error ->
                            LocalAgentOutcome.Failed(
                                reason = error.message ?: "Falló el agente local.",
                                events = emptyList()
                            )
                        }

                        if (currentRuntime === runtime) {
                            currentRuntime = null
                        }
                        if (currentModel === model) {
                            currentModel = null
                        }

                        runCatching { model.close() }
                        deliver(runGeneration, outcome, callback)
                    }
                }
            )
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        currentRuntime?.cancel()
    }

    fun hasInstalledModel(): Boolean =
        modelStore.isInstalled()

    private fun deliver(
        runGeneration: Long,
        outcome: LocalAgentOutcome,
        callback: (LocalAgentOutcome) -> Unit
    ) {
        if (runGeneration != generation.get()) return

        callbackExecutor.execute {
            if (runGeneration == generation.get()) {
                callback(outcome)
            }
        }
    }

    override fun close() {
        cancel()
        currentRuntime = null

        val model = currentModel
        currentModel = null

        if (model != null) {
            runCatching { model.close() }
        }

        launcher.shutdownNow()
    }
}
