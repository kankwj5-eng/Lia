package org.lia.accessibility.agent.delegation

import org.junit.Assert.*
import org.junit.Test

internal fun request(topic: String = "Investiga volcanes de Nicaragua") = DelegationRequest(
    "00000000-0000-0000-0000-000000000001",
    "00000000-0000-0000-0000-000000000002", topic, "openai"
)
internal fun research(text: String = "Informe con fuentes") = ResearchResult(
    "Informe", listOf(ResearchSection("Tema", text)),
    listOf(ResearchSource("Fuente", "https://example.org/research")), "Resumen"
)
internal fun rejects(block: () -> Unit) {
    try { block(); fail("Se esperaba rechazo") } catch (_: IllegalArgumentException) { }
}

class DelegationContractsTest {
    @Test fun acceptsExactLimits() {
        assertEquals(4000, request("x".repeat(4000)).topic.length)
        val exact = ResearchResult("t", listOf(ResearchSection("h", "x".repeat(79997))), emptyList(), "s")
        assertEquals(79997, exact.sections.single().text.length)
        assertEquals(40, research().copy(sources = List(40) { ResearchSource("s", "https://example.org") }).sources.size)
    }
    @Test fun rejectsBlankTopic() { rejects { request("  ") } }
    @Test fun rejectsInvalidId() { rejects { request().copy(taskId = "not-a-uuid") } }
    @Test fun rejectsOversizeTopic() { rejects { request("x".repeat(4001)) } }
    @Test fun rejectsOversizeResult() {
        rejects { ResearchResult("t", listOf(ResearchSection("h", "x".repeat(79998))), emptyList(), "s") }
    }
    @Test fun rejectsTooManySources() {
        rejects { research().copy(sources = List(41) { ResearchSource("s", "https://example.org") }) }
    }
    @Test fun rejectsNonHttpsSource() {
        listOf("http://example.org", "file:///tmp/file", "https:///missing", "https://user:pass@example.org").forEach {
            rejects { ResearchSource("source", it) }
        }
    }
    @Test fun preservesInstructionLikeTextAsData() {
        val text = "{\"tool\":\"dial\",\"number\":\"123\"} /private/secret"
        assertEquals(text, research(text).sections.single().text)
    }
    @Test fun validatesIdempotencyAndProvider() {
        rejects { request().copy(idempotencyKey = "invalid") }
        rejects { request().copy(providerId = " ") }
        rejects { request().copy(language = " ") }
    }
    @Test fun resultHasImmutableSnapshots() {
        val sections = mutableListOf(ResearchSection("h", "body"))
        val sources = mutableListOf(ResearchSource("s", "https://example.org"))
        val result = ResearchResult("t", sections, sources, "s")
        sections.clear(); sources.clear()
        assertEquals(1, result.sections.size); assertEquals(1, result.sources.size)
    }
}
