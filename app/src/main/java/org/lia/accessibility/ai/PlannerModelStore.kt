package org.lia.accessibility.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class PlannerModelInfo(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val sourceName: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val patchVersion: Int
)

class PlannerModelStore(
    context: Context
) {
    private val directory = File(context.noBackupFilesDir, "ai/planner")
    private val modelFile = File(directory, "planner.litertlm")
    private val metadataFile = File(directory, "planner.json")

    fun isInstalled(): Boolean =
        modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES && metadataFile.isFile

    fun installedModel(): PlannerModelInfo? {
        if (!isInstalled()) return null

        val metadata = runCatching {
            JSONObject(metadataFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null

        val sha256 = metadata.optString("sha256", "")
        val sourceName = metadata.optString("source_name", "planner.litertlm")
        val sizeBytes = metadata.optLong("size_bytes", modelFile.length())
        val header = runCatching { readHeader(modelFile) }.getOrNull() ?: return null

        if (sha256.length != 64 || sizeBytes != modelFile.length()) {
            return null
        }

        if (header.major != SUPPORTED_MAJOR_VERSION) {
            return null
        }

        return PlannerModelInfo(
            file = modelFile,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            sourceName = sourceName,
            majorVersion = header.major,
            minorVersion = header.minor,
            patchVersion = header.patch
        )
    }

    fun install(
        sourceName: String,
        input: InputStream
    ): PlannerModelInfo {
        require(sourceName.lowercase().endsWith(".litertlm")) {
            "El archivo debe tener extensión .litertlm."
        }

        directory.mkdirs()

        val tempModel = File(directory, "planner.litertlm.tmp")
        val tempMetadata = File(directory, "planner.json.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        try {
            tempModel.outputStream().buffered().use { output ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    total += count
                    require(total <= MAX_MODEL_BYTES) {
                        "El modelo supera el tamaño máximo permitido para Lía."
                    }

                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }

            require(total >= MIN_MODEL_BYTES) {
                "El archivo seleccionado es demasiado pequeño para ser un modelo LiteRT-LM válido."
            }

            val header = readHeader(tempModel)
            require(header.major == SUPPORTED_MAJOR_VERSION) {
                "El modelo usa una versión mayor de LiteRT-LM que Lía no soporta."
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

            tempMetadata.writeText(
                JSONObject()
                    .put("schema", 1)
                    .put("sha256", sha256)
                    .put("size_bytes", total)
                    .put("source_name", sourceName.take(MAX_SOURCE_NAME_CHARS))
                    .put("format_major", header.major)
                    .put("format_minor", header.minor)
                    .put("format_patch", header.patch)
                    .toString(2),
                Charsets.UTF_8
            )

            if (modelFile.exists()) {
                require(modelFile.delete()) {
                    "No se pudo reemplazar el modelo local anterior."
                }
            }

            require(tempModel.renameTo(modelFile)) {
                "No se pudo instalar el modelo local."
            }

            if (metadataFile.exists()) {
                metadataFile.delete()
            }

            require(tempMetadata.renameTo(metadataFile)) {
                modelFile.delete()
                "No se pudo guardar la metadata del modelo."
            }

            return PlannerModelInfo(
                file = modelFile,
                sha256 = sha256,
                sizeBytes = total,
                sourceName = sourceName,
                majorVersion = header.major,
                minorVersion = header.minor,
                patchVersion = header.patch
            )
        } finally {
            buffer.fill(0)
            tempModel.delete()
            tempMetadata.delete()
        }
    }

    fun verifyInstalledModel(): Boolean {
        val info = installedModel() ?: return false
        val actual = sha256(info.file)
        return actual.equals(info.sha256, ignoreCase = true)
    }

    fun remove() {
        modelFile.delete()
        metadataFile.delete()
    }

    private fun readHeader(file: File): LiteRtLmHeader {
        val header = ByteArray(HEADER_BYTES)

        file.inputStream().buffered().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                require(count > 0) {
                    "El archivo no contiene una cabecera LiteRT-LM completa."
                }
                offset += count
            }
        }

        val magic = header.copyOfRange(0, MAGIC_BYTES.size)
            .toString(Charsets.US_ASCII)

        require(magic == MAGIC) {
            "El archivo seleccionado no es un contenedor LiteRT-LM válido."
        }

        val versions = ByteBuffer
            .wrap(header, MAGIC_BYTES.size, VERSION_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        return LiteRtLmHeader(
            major = versions.int,
            minor = versions.int,
            patch = versions.int
        )
    }

    private data class LiteRtLmHeader(
        val major: Int,
        val minor: Int,
        val patch: Int
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        try {
            file.inputStream().buffered().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                }
            }
        } finally {
            buffer.fill(0)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAGIC = "LITERTLM"
        private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
        private const val VERSION_BYTES = 12
        private const val HEADER_BYTES = 20
        private const val SUPPORTED_MAJOR_VERSION = 1

        private const val MIN_MODEL_BYTES = 1L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_SOURCE_NAME_CHARS = 180
    }
}
