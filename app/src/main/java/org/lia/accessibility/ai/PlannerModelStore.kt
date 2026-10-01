package org.lia.accessibility.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class PlannerModelInfo(
    val file: File,
    val format: PlannerModelFormat,
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
    private val metadataFile = File(directory, "planner.json")

    fun isInstalled(): Boolean = installedModel() != null

    fun installedModel(): PlannerModelInfo? {
        if (!metadataFile.isFile) return null

        val metadata = runCatching {
            JSONObject(metadataFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null

        val schema = metadata.optInt("schema", 1)
        val format = PlannerModelFormat.fromId(
            metadata.optString("format", "")
        ) ?: if (schema == 1) {
            PlannerModelFormat.LITERT_LM
        } else {
            return null
        }

        val modelFile = File(directory, format.installedFileName)
        if (!modelFile.isFile || modelFile.length() < MIN_MODEL_BYTES) {
            return null
        }

        val sha256 = metadata.optString("sha256", "")
        val sourceName = metadata.optString(
            "source_name",
            format.installedFileName
        )
        val sizeBytes = metadata.optLong("size_bytes", modelFile.length())

        if (sha256.length != 64 || sizeBytes != modelFile.length()) {
            return null
        }

        val header = runCatching { readHeader(modelFile) }.getOrNull()
            ?: return null

        if (header.format != format) {
            return null
        }

        if (
            format == PlannerModelFormat.LITERT_LM &&
            header.major != SUPPORTED_LITERT_MAJOR_VERSION
        ) {
            return null
        }

        return PlannerModelInfo(
            file = modelFile,
            format = format,
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
        directory.mkdirs()

        val tempModel = File(directory, "planner.import.tmp")
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
                "El archivo seleccionado es demasiado pequeño para ser un modelo local válido."
            }

            val header = readHeader(tempModel)
            if (header.format == PlannerModelFormat.LITERT_LM) {
                require(header.major == SUPPORTED_LITERT_MAJOR_VERSION) {
                    "El modelo usa una versión mayor de LiteRT-LM que Lía no soporta."
                }
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val targetModel = File(
                directory,
                header.format.installedFileName
            )

            tempMetadata.writeText(
                JSONObject()
                    .put("schema", 2)
                    .put("format", header.format.id)
                    .put("sha256", sha256)
                    .put("size_bytes", total)
                    .put("source_name", sourceName.take(MAX_SOURCE_NAME_CHARS))
                    .put("format_major", header.major)
                    .put("format_minor", header.minor)
                    .put("format_patch", header.patch)
                    .put("installed_file", header.format.installedFileName)
                    .toString(2),
                Charsets.UTF_8
            )

            PlannerModelFormat.entries.forEach { format ->
                File(directory, format.installedFileName).delete()
            }
            metadataFile.delete()

            require(tempModel.renameTo(targetModel)) {
                "No se pudo instalar el modelo local."
            }

            require(tempMetadata.renameTo(metadataFile)) {
                targetModel.delete()
                "No se pudo guardar la metadata del modelo."
            }

            return PlannerModelInfo(
                file = targetModel,
                format = header.format,
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
        PlannerModelFormat.entries.forEach { format ->
            File(directory, format.installedFileName).delete()
        }
        metadataFile.delete()
    }

    private fun readHeader(file: File): PlannerModelHeader {
        val prefix = ByteArray(HEADER_BYTES)

        file.inputStream().buffered().use { input ->
            var offset = 0
            while (offset < prefix.size) {
                val count = input.read(prefix, offset, prefix.size - offset)
                require(count > 0) {
                    "El archivo no contiene una cabecera de modelo completa."
                }
                offset += count
            }
        }

        return PlannerModelFormatDetector.detect(prefix)
    }

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
        private const val HEADER_BYTES = 20
        private const val SUPPORTED_LITERT_MAJOR_VERSION = 1

        private const val MIN_MODEL_BYTES = 1L * 1024L * 1024L
        private const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_SOURCE_NAME_CHARS = 180
    }
}
