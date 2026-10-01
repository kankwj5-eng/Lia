package org.lia.accessibility.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class LiteRtPlannerRuntimeConfig(
    val maxNumTokens: Int,
    val maxOutputTokens: Int,
    val cpuThreads: Int
)

object LiteRtPlannerRuntimeSelector {
    fun forDevice(
        profile: DeviceAiProfile,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors()
    ): LiteRtPlannerRuntimeConfig {
        val cores = availableProcessors.coerceAtLeast(1)

        return when (profile.recommendedModelClass) {
            LocalModelClass.TINY ->
                LiteRtPlannerRuntimeConfig(
                    maxNumTokens = 2_048,
                    maxOutputTokens = 256,
                    cpuThreads = cores.coerceAtMost(2)
                )

            LocalModelClass.SMALL ->
                LiteRtPlannerRuntimeConfig(
                    maxNumTokens = 4_096,
                    maxOutputTokens = 384,
                    cpuThreads = cores.coerceAtMost(4)
                )

            LocalModelClass.MEDIUM ->
                LiteRtPlannerRuntimeConfig(
                    maxNumTokens = 6_144,
                    maxOutputTokens = 512,
                    cpuThreads = cores.coerceAtMost(6)
                )
        }
    }
}

class LiteRtLmLanguageModel(
    context: Context,
    private val modelFile: File,
    private val runtimeConfig: LiteRtPlannerRuntimeConfig =
        LiteRtPlannerRuntimeSelector.forDevice(
            DeviceAiProfileProvider(context.applicationContext).capture()
        )
) : ManagedLocalLanguageModel {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lia-litert-planner").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val lock = Any()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var closed = false

    override suspend fun complete(turn: AgentTurn): String =
        suspendCoroutine { continuation ->
            worker.execute {
                runCatching {
                    check(!closed) { "El motor local de Lía está cerrado." }
                    check(modelFile.isFile && modelFile.canRead()) {
                        "El modelo local de Lía no está disponible."
                    }

                    val activeEngine = engine()
                    activeEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(turn.systemContext),
                            samplerConfig = SamplerConfig(
                                topK = 1,
                                topP = 1.0,
                                temperature = 0.0
                            ),
                            automaticToolCalling = false,
                            channels = emptyList()
                        )
                    ).use { conversation ->
                        conversation.sendMessage(turn.userText)
                            .toString()
                            .trim()
                    }
                }.onSuccess(continuation::resume)
                    .onFailure(continuation::resumeWithException)
            }
        }

    fun isInitialized(): Boolean = engine?.isInitialized() == true

    private fun engine(): Engine {
        engine?.takeIf { it.isInitialized() }?.let { return it }

        synchronized(lock) {
            engine?.takeIf { it.isInitialized() }?.let { return it }

            check(!closed) { "El motor local de Lía está cerrado." }

            val cacheDirectory = File(appContext.cacheDir, "litertlm").apply {
                mkdirs()
            }

            val created = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(numOfThreads = runtimeConfig.cpuThreads),
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = runtimeConfig.maxNumTokens,
                    cacheDir = cacheDirectory.absolutePath
                )
            )

            try {
                created.initialize()
            } catch (error: Throwable) {
                runCatching {
                    if (created.isInitialized()) {
                        created.close()
                    }
                }
                throw error
            }

            engine = created
            return created
        }
    }

    override fun close() {
        closed = true

        synchronized(lock) {
            val current = engine
            engine = null

            if (current?.isInitialized() == true) {
                runCatching { current.close() }
            }
        }

        worker.shutdownNow()
    }
}
