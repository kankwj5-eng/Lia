package org.lia.accessibility.ai

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import java.io.File

data class LlamaCppPlannerRuntimeConfig(
    val contextSize: Int,
    val maxOutputTokens: Int,
    val cpuThreads: Int
)

object LlamaCppPlannerRuntimeSelector {
    fun forDevice(
        profile: DeviceAiProfile,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors()
    ): LlamaCppPlannerRuntimeConfig {
        val cores = availableProcessors.coerceAtLeast(1)

        return when (profile.recommendedModelClass) {
            LocalModelClass.TINY ->
                LlamaCppPlannerRuntimeConfig(
                    contextSize = 4_096,
                    maxOutputTokens = 256,
                    cpuThreads = cores.coerceAtMost(2)
                )

            LocalModelClass.SMALL ->
                LlamaCppPlannerRuntimeConfig(
                    contextSize = 6_144,
                    maxOutputTokens = 384,
                    cpuThreads = cores.coerceAtMost(4)
                )

            LocalModelClass.MEDIUM ->
                LlamaCppPlannerRuntimeConfig(
                    contextSize = 8_192,
                    maxOutputTokens = 512,
                    cpuThreads = cores.coerceAtMost(6)
                )
        }
    }
}

/**
 * Adaptador GGUF de Lía sobre llama.cpp.
 *
 * El archivo se carga perezosamente la primera vez que el agente necesita
 * razonar. No usa red ni claves API.
 */
class LlamaCppLanguageModel(
    context: android.content.Context,
    private val modelFile: File,
    private val sourceName: String = modelFile.name,
    private val runtimeConfig: LlamaCppPlannerRuntimeConfig =
        LlamaCppPlannerRuntimeSelector.forDevice(
            DeviceAiProfileProvider(context.applicationContext).capture()
        )
) : ManagedLocalLanguageModel {
    @Volatile
    private var model: LlamaModel? = null

    @Volatile
    private var closed = false

    override suspend fun complete(turn: AgentTurn): String {
        check(!closed) { "El motor GGUF de Lía está cerrado." }
        check(modelFile.isFile && modelFile.canRead()) {
            "El modelo GGUF de Lía no está disponible."
        }

        val activeModel = loadedModel()
        val prompt = if (looksLikeQwen(sourceName)) {
            // Qwen3 admite el interruptor suave /no_think. Para un agente de
            // herramientas nos interesa reservar los tokens para el JSON final.
            turn.userText + "\n/no_think"
        } else {
            turn.userText
        }

        return Llama.complete(
            model = activeModel,
            prompt = prompt,
            systemPrompt = turn.systemContext,
            maxTokens = runtimeConfig.maxOutputTokens
        ).text.trim()
    }

    private suspend fun loadedModel(): LlamaModel {
        model?.takeIf { it.isLoaded }?.let { return it }

        check(!closed) { "El motor GGUF de Lía está cerrado." }

        val created = Llama.loadModel(
            modelPath = modelFile.absolutePath,
            config = LlamaConfig(
                contextSize = runtimeConfig.contextSize,
                threads = runtimeConfig.cpuThreads,
                gpuLayers = 0,
                temperature = 0.0f,
                topP = 1.0f,
                topK = 1,
                seed = 0
            )
        )

        if (closed) {
            Llama.releaseModel(created)
            error("El motor GGUF de Lía se cerró mientras cargaba el modelo.")
        }

        val existing = model
        return if (existing?.isLoaded == true) {
            Llama.releaseModel(created)
            existing
        } else {
            model = created
            created
        }
    }

    override fun close() {
        closed = true

        val current = model
        model = null

        if (current != null) {
            runCatching { Llama.releaseModel(current) }
        }
    }

    private fun looksLikeQwen(name: String): Boolean =
        name.lowercase().contains("qwen")
}
