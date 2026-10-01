package org.lia.accessibility.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class VoiceModelProvisioner(context: Context) {
    private val modelDir = File(context.filesDir, "models")
    val modelFile: File get() = File(modelDir, MODEL_FILENAME)

    fun isInstalled(): Boolean = modelFile.isFile && sha256(modelFile) == MODEL_SHA256

    fun install(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        modelDir.mkdirs()
        val temp = File(modelDir, "$MODEL_FILENAME.part")
        temp.delete()

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Lia-Android/0.1")

        try {
            conn.connect()
            check(conn.responseCode in 200..299) { "Descarga del modelo falló: HTTP " + conn.responseCode }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val total = conn.contentLengthLong

            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == MODEL_SHA256) { "La firma SHA-256 del modelo no coincide" }

            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
            }
        } finally {
            conn.disconnect()
            if (temp.exists() && !modelFile.exists()) temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_FILENAME = "speaker-campplus-en-16k.onnx"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
        const val MODEL_SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    }
}
