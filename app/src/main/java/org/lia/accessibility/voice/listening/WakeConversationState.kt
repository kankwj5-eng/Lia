package org.lia.accessibility.voice.listening

sealed interface WakeConversationDecision {
    data object Ignore : WakeConversationDecision
    data object AwaitCommand : WakeConversationDecision
    data class Command(val text: String) : WakeConversationDecision
}

class WakeConversationState(
    private val followUpWindowMs: Long = DEFAULT_FOLLOW_UP_WINDOW_MS
) {
    private var followUpDeadlineMs: Long = 0L

    fun accept(
        transcript: String,
        nowElapsedMs: Long
    ): WakeConversationDecision {
        val clean = transcript.trim()
        if (clean.isBlank()) return WakeConversationDecision.Ignore

        if (
            followUpDeadlineMs > 0L &&
            nowElapsedMs <= followUpDeadlineMs
        ) {
            followUpDeadlineMs = 0L
            return WakeConversationDecision.Command(clean)
        }

        followUpDeadlineMs = 0L

        return when (val wake = WakePhraseMatcher.match(clean)) {
            null -> WakeConversationDecision.Ignore

            WakePhraseMatch.WakeOnly -> {
                followUpDeadlineMs = nowElapsedMs + followUpWindowMs
                WakeConversationDecision.AwaitCommand
            }

            is WakePhraseMatch.Command ->
                WakeConversationDecision.Command(wake.command)
        }
    }

    fun reset() {
        followUpDeadlineMs = 0L
    }

    companion object {
        const val DEFAULT_FOLLOW_UP_WINDOW_MS = 8_000L
    }
}
