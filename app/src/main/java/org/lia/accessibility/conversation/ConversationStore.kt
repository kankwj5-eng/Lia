package org.lia.accessibility.conversation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ConversationSpeaker(val wireName: String) {
    USER("user"),
    LIA("lia"),
    SYSTEM("system");

    companion object {
        fun fromWireName(value: String): ConversationSpeaker? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class ConversationMessage(
    val speaker: ConversationSpeaker,
    val text: String,
    val timestampEpochMs: Long
)

class ConversationStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversation")
    private val file = File(directory, "recent.json")

    @Synchronized
    fun add(
        speaker: ConversationSpeaker,
        text: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) {
        val clean = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_MESSAGE_CHARS)

        if (clean.isBlank()) return

        val next = (readInternal() + ConversationMessage(speaker, clean, timestampEpochMs))
            .takeLast(MAX_MESSAGES)

        writeInternal(next)
    }

    @Synchronized
    fun recent(limit: Int = MAX_MESSAGES): List<ConversationMessage> =
        readInternal().takeLast(limit.coerceIn(1, MAX_MESSAGES))

    @Synchronized
    fun contextForAgent(
        currentGoal: String,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): String {
        val messages = readInternal().toMutableList()

        val last = messages.lastOrNull()
        if (
            last?.speaker == ConversationSpeaker.USER &&
            last.text == currentGoal.trim()
        ) {
            messages.removeLast()
        }

        if (messages.isEmpty()) return ""

        val lines = messages.takeLast(CONTEXT_MESSAGE_LIMIT).map { message ->
            val author = when (message.speaker) {
                ConversationSpeaker.USER -> "Usuario"
                ConversationSpeaker.LIA -> "Lía"
                ConversationSpeaker.SYSTEM -> "Sistema"
            }
            "${author}: ${message.text}"
        }

        val selected = ArrayDeque<String>()
        var used = 0

        for (line in lines.asReversed()) {
            val cost = line.length + 1
            if (selected.isNotEmpty() && used + cost > maxChars) break
            selected.addFirst(line.take(maxChars))
            used += cost
            if (used >= maxChars) break
        }

        return selected.joinToString("\n").takeLast(maxChars)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readInternal(): List<ConversationMessage> {
        if (!file.isFile) return emptyList()

        val array = runCatching {
            JSONArray(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val speaker = ConversationSpeaker.fromWireName(
                    item.optString("speaker", "")
                ) ?: continue
                val text = item.optString("text", "").trim()
                if (text.isBlank()) continue
                add(
                    ConversationMessage(
                        speaker = speaker,
                        text = text.take(MAX_MESSAGE_CHARS),
                        timestampEpochMs = item.optLong("timestamp_epoch_ms", 0L)
                    )
                )
            }
        }.takeLast(MAX_MESSAGES)
    }

    private fun writeInternal(messages: List<ConversationMessage>) {
        directory.mkdirs()

        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("speaker", message.speaker.wireName)
                    .put("text", message.text)
                    .put("timestamp_epoch_ms", message.timestampEpochMs)
            )
        }

        val temp = File(directory, "recent.json.tmp")
        temp.writeText(array.toString(), Charsets.UTF_8)

        if (file.exists()) file.delete()
        check(temp.renameTo(file)) {
            temp.delete()
            "No se pudo guardar el contexto reciente de Lía."
        }
    }

    companion object {
        private const val MAX_MESSAGES = 40
        private const val CONTEXT_MESSAGE_LIMIT = 12
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_CONTEXT_CHARS = 3_000
    }
}
