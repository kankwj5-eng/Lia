package org.lia.accessibility.voice.listening

sealed interface WakePhraseMatch {
    data object WakeOnly : WakePhraseMatch
    data class Command(val command: String) : WakePhraseMatch
}

object WakePhraseMatcher {
    private val pattern = Regex(
        pattern = """^\s*(?:(?:oye|hola|hey)\s+)?l[ií]a\b[\s,.:;!¿?¡-]*(.*)$""",
        option = RegexOption.IGNORE_CASE
    )

    fun match(transcript: String): WakePhraseMatch? {
        val clean = transcript
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isBlank()) return null

        val result = pattern.matchEntire(clean) ?: return null
        val command = result.groupValues.getOrNull(1)
            .orEmpty()
            .trim()
            .trimStart(',', '.', ':', ';', '-', '¿', '?', '¡', '!')

        return if (command.isBlank()) {
            WakePhraseMatch.WakeOnly
        } else {
            WakePhraseMatch.Command(command)
        }
    }
}
