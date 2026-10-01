package org.lia.accessibility.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiaMicroAgentTest {
    private data class Action(val name: String)

    @Test
    fun completesWhenVerificationSignalsCompletion() {
        var screen = 0

        val env = object : AgentEnvironment<Action> {
            override fun observe() =
                AgentObservation(
                    fingerprint = "screen-$screen",
                    summary = "screen $screen"
                )

            override fun execute(action: Action): ToolOutcome {
                screen++
                return ToolOutcome(true, true, "ok")
            }

            override fun verify(
                before: AgentObservation,
                after: AgentObservation,
                action: Action,
                outcome: ToolOutcome
            ) = VerificationOutcome(
                progress = before.fingerprint != after.fingerprint,
                completed = screen >= 2,
                message = if (screen >= 2) "objetivo completado" else "progreso"
            )
        }

        val agent = LiaMicroAgent(
            environment = env,
            planner = AgentPlanner { AgentDecision.Act(Action("next"), "next") }
        )

        val result = agent.run("haz dos pasos")
        assertTrue(result.success)
        assertTrue(result.events.any { it.type == AgentEventType.COMPLETION })
    }

    @Test
    fun stopsRepeatedActionLoop() {
        val env = object : AgentEnvironment<Action> {
            override fun observe() =
                AgentObservation("same", "misma pantalla")

            override fun execute(action: Action) =
                ToolOutcome(true, true, "ejecutado")

            override fun verify(
                before: AgentObservation,
                after: AgentObservation,
                action: Action,
                outcome: ToolOutcome
            ) = VerificationOutcome(false, message = "sin cambio")
        }

        val agent = LiaMicroAgent(
            environment = env,
            planner = AgentPlanner { AgentDecision.Act(Action("tap"), "tap:100:100") },
            config = AgentConfig(
                maxSteps = 20,
                maxRecoveries = 2,
                maxRepeatedAction = 2,
                maxRepeatedObservation = 3
            )
        )

        val result = agent.run("tarea imposible")
        assertFalse(result.success)
        assertTrue(result.events.any { it.type == AgentEventType.RECOVERY })
    }

    @Test
    fun retriesOnlyWhenPlannerMarksActionSafe() {
        var executions = 0

        val env = object : AgentEnvironment<Action> {
            override fun observe() =
                AgentObservation("screen-$executions", "estado")

            override fun execute(action: Action): ToolOutcome {
                executions++
                return ToolOutcome(
                    dispatched = true,
                    success = executions >= 2,
                    message = if (executions >= 2) "ok" else "falló"
                )
            }

            override fun verify(
                before: AgentObservation,
                after: AgentObservation,
                action: Action,
                outcome: ToolOutcome
            ) = VerificationOutcome(
                progress = outcome.success,
                completed = outcome.success,
                message = outcome.message
            )
        }

        val agent = LiaMicroAgent(
            environment = env,
            planner = AgentPlanner {
                AgentDecision.Act(
                    action = Action("open"),
                    actionKey = "open:settings",
                    safeToRetry = true
                )
            }
        )

        val result = agent.run("abre ajustes")
        assertTrue(result.success)
        assertTrue(executions >= 2)
    }
}
