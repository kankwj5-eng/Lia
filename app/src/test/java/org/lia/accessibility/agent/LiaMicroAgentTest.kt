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
    fun cancelsStaleActionWhenScreenChangesAfterPlanning() {
        var observations = 0
        var executions = 0

        val env = object : AgentEnvironment<Action> {
            override fun observe(): AgentObservation {
                observations++
                val fingerprint = if (observations == 1) "screen-a" else "screen-b"
                return AgentObservation(fingerprint, fingerprint)
            }

            override fun execute(action: Action): ToolOutcome {
                executions++
                return ToolOutcome(true, true, "no debería ejecutarse")
            }

            override fun verify(
                before: AgentObservation,
                after: AgentObservation,
                action: Action,
                outcome: ToolOutcome
            ) = VerificationOutcome(false, message = "sin progreso")
        }

        var plannerCalls = 0
        val agent = LiaMicroAgent(
            environment = env,
            planner = AgentPlanner {
                plannerCalls++
                if (plannerCalls == 1) {
                    AgentDecision.Act(Action("tap"), "tap:stale")
                } else {
                    AgentDecision.Fail("detener prueba")
                }
            },
            config = AgentConfig(maxSteps = 3, maxRecoveries = 3)
        )

        val result = agent.run("prueba de frescura")
        assertFalse(result.success)
        assertTrue(executions == 0)
        assertTrue(
            result.events.any {
                it.type == AgentEventType.RECOVERY &&
                    it.message.contains("acción obsoleta")
            }
        )
    }

    @Test
    fun plannerFinishRequiresEnvironmentVerification() {
        var finishChecks = 0

        val env = object : AgentEnvironment<Action> {
            override fun observe() =
                AgentObservation("screen", "estado")

            override fun execute(action: Action) =
                ToolOutcome(true, true, "ok")

            override fun verify(
                before: AgentObservation,
                after: AgentObservation,
                action: Action,
                outcome: ToolOutcome
            ) = VerificationOutcome(true, message = "progreso")

            override fun verifyCompletion(
                observation: AgentObservation,
                proposedResult: String
            ): VerificationOutcome {
                finishChecks++
                return VerificationOutcome(
                    progress = false,
                    completed = false,
                    message = "el entorno no confirma"
                )
            }
        }

        val agent = LiaMicroAgent(
            environment = env,
            planner = AgentPlanner { AgentDecision.Finish("listo") },
            config = AgentConfig(maxSteps = 2, maxRecoveries = 0)
        )

        val result = agent.run("objetivo")
        assertFalse(result.success)
        assertTrue(finishChecks >= 1)
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
