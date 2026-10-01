package org.lia.accessibility.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PlannerModelFormat(
    val id: String,
    val displayName: String,
    val installedFileName: String
) {
    GGUF(
        id = "gguf",
        displayName = "GGUF · llama.cpp",
        installedFileName = "planner.gguf"
    ),
    LITERT_LM(
        id = "litertlm",
        displayName = "LiteRT-LM",
        installedFileName = "planner.litertlm"
    );

    companion object {
        fun fromId(value: String?): PlannerModelFormat? =
            entries.firstOrNull { it.id == value }
    }
}

data class PlannerModelHeader(
    val format: PlannerModelFormat,
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0
)

internal object PlannerModelFormatDetector {
    private val litertMagic = "LITERTLM".toByteArray(Charsets.US_ASCII)
    private val ggufMagic = "GGUF".toByteArray(Charsets.US_ASCII)

    fun detect(prefix: ByteArray): PlannerModelHeader {
        require(prefix.size >= 8) {
            "El archivo es demasiado pequeño para contener una cabecera de modelo válida."
        }

        if (prefix.startsWith(ggufMagic)) {
            val version = ByteBuffer
                .wrap(prefix, ggufMagic.size, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            require(version in 1..3) {
                "La versión GGUF $version todavía no está soportada por Lía."
            }

            return PlannerModelHeader(
                format = PlannerModelFormat.GGUF,
                major = version
            )
        }

        if (prefix.startsWith(litertMagic)) {
            require(prefix.size >= 20) {
                "El archivo no contiene una cabecera LiteRT-LM completa."
            }

            val versions = ByteBuffer
                .wrap(prefix, litertMagic.size, 12)
                .order(ByteOrder.LITTLE_ENDIAN)

            return PlannerModelHeader(
                format = PlannerModelFormat.LITERT_LM,
                major = versions.int,
                minor = versions.int,
                patch = versions.int
            )
        }

        error(
            "No reconozco este modelo. Lía admite modelos GGUF y LiteRT-LM."
        )
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }
}
