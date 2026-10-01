package org.lia.accessibility.voice

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

data class WhisperCommandModelInfo(
    val directory: File,
    val encoder: File,
    val decoder: File,
    val tokens: File,
    val archiveSha256: String,
    val installedAtEpochMs: Long
)

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val expectedBytes: Long
) {
    val percent: Int
        get() = if (expectedBytes <= 0L) {
            0
        } else {
            ((downloadedBytes * 100L) / expectedBytes)
                .coerceIn(0L, 100L)
                .toInt()
        }
}

class WhisperCommandModelProvisioner(
    context: Context
) {
    private val root = File(context.noBackupFilesDir, "voice/whisper-command")
    private val installedDirectory = File(root, "model")
    private val metadataFile = File(installedDirectory, METADATA_NAME)

    fun isInstalled(): Boolean =
        installedModel() != null

    fun installedModel(): WhisperCommandModelInfo? {
        val encoder = File(installedDirectory, ENCODER_NAME)
        val decoder = File(installedDirectory, DECODER_NAME)
        val tokens = File(installedDirectory, TOKENS_NAME)

        if (!encoder.isFile || encoder.length() < MIN_ONNX_BYTES) return null
        if (!decoder.isFile || decoder.length() < MIN_ONNX_BYTES) return null
        if (!tokens.isFile || tokens.length() < MIN_TOKENS_BYTES) return null
        if (!metadataFile.isFile) return null

        val metadata = runCatching {
            JSONObject(metadataFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null

        val archiveSha = metadata.optString("archive_sha256", "")
        val installedAt = metadata.optLong("installed_at_epoch_ms", 0L)

        if (archiveSha.length != 64 || installedAt <= 0L) return null

        if (metadata.optLong("encoder_size", -1L) != encoder.length()) return null
        if (metadata.optLong("decoder_size", -1L) != decoder.length()) return null
        if (metadata.optLong("tokens_size", -1L) != tokens.length()) return null

        return WhisperCommandModelInfo(
            directory = installedDirectory,
            encoder = encoder,
            decoder = decoder,
            tokens = tokens,
            archiveSha256 = archiveSha,
            installedAtEpochMs = installedAt
        )
    }

    fun install(
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): WhisperCommandModelInfo {
        root.mkdirs()

        val archive = File(root, "whisper-tiny.tar.bz2.tmp")
        val staging = File(root, "model.tmp")

        archive.delete()
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            val archiveSha = downloadArchive(archive, onProgress)

            require(archive.length() == EXPECTED_ARCHIVE_BYTES) {
                "La descarga del modelo de voz tiene un tamaño inesperado."
            }

            val extracted = extractExpectedFiles(
                archive = archive,
                destination = staging
            )

            val installedAt = System.currentTimeMillis()
            File(staging, METADATA_NAME).writeText(
                JSONObject()
                    .put("schema", 1)
                    .put("source_url", MODEL_URL)
                    .put("archive_size", archive.length())
                    .put("archive_sha256", archiveSha)
                    .put("encoder_size", extracted.encoder.length())
                    .put("encoder_sha256", sha256(extracted.encoder))
                    .put("decoder_size", extracted.decoder.length())
                    .put("decoder_sha256", sha256(extracted.decoder))
                    .put("tokens_size", extracted.tokens.length())
                    .put("tokens_sha256", sha256(extracted.tokens))
                    .put("installed_at_epoch_ms", installedAt)
                    .toString(2),
                Charsets.UTF_8
            )

            if (installedDirectory.exists()) {
                require(installedDirectory.deleteRecursively()) {
                    "No se pudo reemplazar el modelo de voz anterior."
                }
            }

            require(staging.renameTo(installedDirectory)) {
                "No se pudo activar el modelo de voz descargado."
            }

            return installedModel()
                ?: error("El modelo de voz quedó incompleto tras la instalación.")
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    fun verifyInstalledFiles(): Boolean {
        val info = installedModel() ?: return false
        val metadata = runCatching {
            JSONObject(metadataFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return false

        return verifyDigest(info.encoder, metadata.optString("encoder_sha256")) &&
            verifyDigest(info.decoder, metadata.optString("decoder_sha256")) &&
            verifyDigest(info.tokens, metadata.optString("tokens_sha256"))
    }

    fun remove() {
        installedDirectory.deleteRecursively()
    }

    private fun downloadArchive(
        destination: File,
        onProgress: (ModelDownloadProgress) -> Unit
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = openHttps(MODEL_URL)
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var downloaded = 0L

        try {
            require(connection.responseCode in 200..299) {
                "El servidor del modelo respondió con HTTP " + connection.responseCode + "."
            }

            val reportedLength = connection.contentLengthLong
            if (reportedLength > 0L) {
                require(reportedLength == EXPECTED_ARCHIVE_BYTES) {
                    "El servidor anunció un tamaño de modelo distinto al esperado."
                }
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue

                        downloaded += count
                        require(downloaded <= MAX_ARCHIVE_BYTES) {
                            "La descarga del modelo excedió el límite permitido."
                        }

                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)

                        if (
                            downloaded == count.toLong() ||
                            downloaded % PROGRESS_GRANULARITY_BYTES < count
                        ) {
                            onProgress(
                                ModelDownloadProgress(
                                    downloadedBytes = downloaded,
                                    expectedBytes = EXPECTED_ARCHIVE_BYTES
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            buffer.fill(0)
            connection.disconnect()
        }

        require(downloaded == EXPECTED_ARCHIVE_BYTES) {
            "La descarga del modelo de voz terminó incompleta."
        }

        onProgress(
            ModelDownloadProgress(
                downloadedBytes = downloaded,
                expectedBytes = EXPECTED_ARCHIVE_BYTES
            )
        )

        return hex(digest.digest())
    }

    private fun openHttps(url: String): HttpURLConnection {
        var current = URI(url)
        var redirects = 0

        while (true) {
            require(current.scheme.equals("https", ignoreCase = true)) {
                "Lía solo permite descargar modelos mediante HTTPS."
            }

            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Lia-Accessibility/0.1")
            connection.connect()

            if (connection.responseCode !in 300..399) {
                return connection
            }

            val location = connection.getHeaderField("Location")
            connection.disconnect()

            require(!location.isNullOrBlank()) {
                "El servidor redirigió la descarga sin indicar destino."
            }

            redirects++
            require(redirects <= MAX_REDIRECTS) {
                "La descarga del modelo tuvo demasiadas redirecciones."
            }

            current = current.resolve(location)
        }
    }

    private fun extractExpectedFiles(
        archive: File,
        destination: File
    ): ExtractedFiles {
        val expected = mapOf(
            ARCHIVE_ENCODER_PATH to ENCODER_NAME,
            ARCHIVE_DECODER_PATH to DECODER_NAME,
            ARCHIVE_TOKENS_PATH to TOKENS_NAME
        )
        val extractedNames = mutableSetOf<String>()

        FileInputStream(archive).buffered().use { raw ->
            BZip2CompressorInputStream(raw, true).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile) continue

                        val outputName = expected[entry.name] ?: continue
                        require(extractedNames.add(outputName)) {
                            "El paquete contiene archivos de modelo duplicados."
                        }

                        val output = File(destination, outputName)
                        val maximum = when (outputName) {
                            TOKENS_NAME -> MAX_TOKENS_BYTES
                            else -> MAX_ONNX_BYTES
                        }

                        FileOutputStream(output).buffered().use { stream ->
                            copyBounded(
                                input = tar,
                                output = stream,
                                maximumBytes = maximum
                            )
                        }
                    }
                }
            }
        }

        require(extractedNames == expected.values.toSet()) {
            "El paquete descargado no contiene todos los archivos Whisper esperados."
        }

        val encoder = File(destination, ENCODER_NAME)
        val decoder = File(destination, DECODER_NAME)
        val tokens = File(destination, TOKENS_NAME)

        require(encoder.length() >= MIN_ONNX_BYTES) {
            "El encoder Whisper parece incompleto."
        }
        require(decoder.length() >= MIN_ONNX_BYTES) {
            "El decoder Whisper parece incompleto."
        }
        require(tokens.length() >= MIN_TOKENS_BYTES) {
            "El vocabulario Whisper parece incompleto."
        }

        return ExtractedFiles(
            encoder = encoder,
            decoder = decoder,
            tokens = tokens
        )
    }

    private fun copyBounded(
        input: TarArchiveInputStream,
        output: java.io.OutputStream,
        maximumBytes: Long
    ) {
        val buffer = ByteArray(EXTRACT_BUFFER_BYTES)
        var total = 0L

        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue

                total += count
                require(total <= maximumBytes) {
                    "Un archivo interno del modelo excedió el límite permitido."
                }

                output.write(buffer, 0, count)
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun verifyDigest(file: File, expected: String): Boolean =
        expected.length == 64 &&
            sha256(file).equals(expected, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(EXTRACT_BUFFER_BYTES)

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

        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private data class ExtractedFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-whisper-tiny.tar.bz2"

        const val EXPECTED_ARCHIVE_BYTES = 116_204_861L

        private const val ARCHIVE_ENCODER_PATH =
            "sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx"
        private const val ARCHIVE_DECODER_PATH =
            "sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx"
        private const val ARCHIVE_TOKENS_PATH =
            "sherpa-onnx-whisper-tiny/tiny-tokens.txt"

        private const val ENCODER_NAME = "tiny-encoder.int8.onnx"
        private const val DECODER_NAME = "tiny-decoder.int8.onnx"
        private const val TOKENS_NAME = "tiny-tokens.txt"
        private const val METADATA_NAME = "metadata.json"

        private const val MIN_ONNX_BYTES = 1L * 1024L * 1024L
        private const val MIN_TOKENS_BYTES = 10L * 1024L
        private const val MAX_ONNX_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOKENS_BYTES = 32L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 130L * 1024L * 1024L

        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val EXTRACT_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_GRANULARITY_BYTES = 2L * 1024L * 1024L

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_REDIRECTS = 5
    }
}
