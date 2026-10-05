package org.lia.accessibility.agent.delegation

import java.net.URI
import java.util.Collections
import java.util.UUID

object DelegationLimits {
    const val MAX_TOPIC_CHARS = 4_000
    const val MAX_RESULT_CHARS = 80_000
    const val MAX_SOURCES = 40
    const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024L
    const val GRANT_LIFETIME_MS = 300_000L
    const val TASK_TIMEOUT_MS = 900_000L
}

internal fun isUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

data class DelegationRequest(
    val taskId: String,
    val idempotencyKey: String,
    val topic: String,
    val providerId: String,
    val language: String = "es"
) {
    init {
        require(isUuid(taskId) && isUuid(idempotencyKey)) { "Identificador de tarea inválido." }
        require(topic.isNotBlank() && topic.length <= DelegationLimits.MAX_TOPIC_CHARS) { "Objetivo vacío o demasiado largo." }
        require(providerId.isNotBlank() && language.isNotBlank()) { "Proveedor o idioma vacío." }
        require(providerId.length <= 128 && language.length <= 32) { "Proveedor o idioma demasiado largo." }
    }
}

data class ResearchSection(val heading: String, val text: String) {
    init { require(heading.isNotBlank() && text.isNotBlank()) { "Sección vacía." } }
}

data class ResearchSource(val title: String, val url: String) {
    init {
        require(title.isNotBlank()) { "Fuente sin título." }
        val parsed = runCatching { URI(url) }.getOrNull()
        require(parsed != null && parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null) { "URL de fuente inválida." }
    }
}

/** Remote prose is data, never an executable tool call. Snapshots prevent validation bypass. */
class ResearchResult(
    val title: String,
    sections: List<ResearchSection>,
    sources: List<ResearchSource>,
    val spokenSummary: String
) {
    val sections: List<ResearchSection> = Collections.unmodifiableList(sections.toList())
    val sources: List<ResearchSource> = Collections.unmodifiableList(sources.toList())

    init {
        require(title.isNotBlank() && spokenSummary.isNotBlank() && this.sections.isNotEmpty()) { "Informe incompleto." }
        require(this.sources.size <= DelegationLimits.MAX_SOURCES) { "Demasiadas fuentes." }
        val chars = title.length.toLong() + spokenSummary.length +
            this.sections.sumOf { it.heading.length.toLong() + it.text.length } +
            this.sources.sumOf { it.title.length.toLong() + it.url.length }
        require(chars <= DelegationLimits.MAX_RESULT_CHARS) { "Informe demasiado largo." }
    }

    fun copy(
        title: String = this.title,
        sections: List<ResearchSection> = this.sections,
        sources: List<ResearchSource> = this.sources,
        spokenSummary: String = this.spokenSummary
    ) = ResearchResult(title, sections, sources, spokenSummary)
}

enum class DelegationError {
    NOT_CONFIGURED, AUTHENTICATION, RATE_LIMIT, NETWORK, TIMEOUT,
    INVALID_RESPONSE, UNCERTAIN_SUBMISSION, CANCEL_NOT_CONFIRMED
}

sealed interface ProviderReply<out T> {
    data class Success<T>(val value: T) : ProviderReply<T>
    data class Failure(val error: DelegationError) : ProviderReply<Nothing>
}
