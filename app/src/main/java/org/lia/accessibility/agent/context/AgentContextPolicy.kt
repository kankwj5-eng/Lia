package org.lia.accessibility.agent.context

data class AgentContextPolicy(
    val maxGoalChars: Int = 2_000,
    val maxConversationChars: Int = 3_000,
    val maxScreenChars: Int = 6_000,
    val maxRecentEvents: Int = 8,
    val maxEventMessageChars: Int = 320,
    val maxActionKeyChars: Int = 120
) {
    init {
        require(maxGoalChars in 256..8_000)
        require(maxConversationChars in 0..12_000)
        require(maxScreenChars in 1_000..20_000)
        require(maxRecentEvents in 1..32)
        require(maxEventMessageChars in 80..1_000)
        require(maxActionKeyChars in 40..500)
    }
}

object DefaultAgentContextPolicy {
    val value = AgentContextPolicy()
}
