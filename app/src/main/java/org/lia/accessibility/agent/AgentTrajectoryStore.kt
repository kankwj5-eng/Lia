package org.lia.accessibility.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class TrajectoryPrivacyMode {
    METADATA_ONLY,
    REDACTED_TEXT
}

/**
 * Stores agent runs in app-private storage for debugging and regression analysis.
 *
 * Default mode never persists the user's raw goal, screen text or messages. It stores
 * hashes and structural execution evidence instead.
 */
class AgentTrajectoryStore(
    context: Context,
    private val privacyMode: TrajectoryPrivacyMode = TrajectoryPrivacyMode.METADATA_ONLY,
    private val maxFiles: Int = 20
) {
    private val directory = File(context.noBackupFilesDir, "agent-trajectories")

    fun save(
        goal: String,
        result: AgentRunResult,
        runId: String = UUID.randomUUID().toString()
    ): File {
        directory.mkdirs()

        val root = JSONObject()
            .put("schema", 1)
            .put("run_id", runId)
            .put("goal_sha256", sha256(goal))
            .put("success", result.success)
            .put("steps", result.steps)
            .put("recoveries", result.recoveries)
            .put("result_sha256", sha256(result.result))
            .put("events", JSONArray().apply {
                result.events.forEach { put(eventJson(it)) }
            })

        if (privacyMode == TrajectoryPrivacyMode.REDACTED_TEXT) {
            root.put("goal_preview", redact(goal))
            root.put("result_preview", redact(result.result))
        }

        val finalFile = File(directory, "trajectory_$runId.json")
        val tempFile = File(directory, "trajectory_$runId.json.tmp")

        tempFile.writeText(root.toString(2), Charsets.UTF_8)

        if (finalFile.exists()) {
            finalFile.delete()
        }

        check(tempFile.renameTo(finalFile)) {
            "No se pudo guardar la trayectoria del agente."
        }

        rotate()
        return finalFile
    }

    fun list(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("trajectory_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun eventJson(event: AgentEvent): JSONObject =
        JSONObject()
            .put("type", event.type.name)
            .put("phase", event.phase.name)
            .put("step", event.step)
            .put("timestamp_epoch_ms", event.timestampEpochMs)
            .put("message_sha256", sha256(event.message))
            .putOpt("fingerprint", event.fingerprint)
            .putOpt("action_key", event.actionKey)
            .also { json ->
                if (privacyMode == TrajectoryPrivacyMode.REDACTED_TEXT) {
                    json.put("message_preview", redact(event.message))
                }
            }

    private fun rotate() {
        if (maxFiles <= 0) {
            clear()
            return
        }

        val stale = list().drop(maxFiles)
        stale.forEach { it.delete() }
    }

    private fun redact(text: String): String {
        val normalized = text
            .replace(Regex("\\b[0-9]{4,}\\b"), "[número]")
            .replace(
                Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                "[correo]"
            )
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.take(MAX_PREVIEW_CHARS)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_PREVIEW_CHARS = 160
    }
}
