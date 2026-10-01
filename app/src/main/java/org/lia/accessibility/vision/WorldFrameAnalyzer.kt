package org.lia.accessibility.vision

import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

data class WorldVisionResult(
    val text: String,
    val barcodes: List<String>,
    val colorName: String,
    val brightnessPercent: Int,
    val lightState: String
) {
    fun spokenSummary(): String {
        val parts = mutableListOf<String>()
        if (text.isNotBlank()) parts += "Texto: $text"
        if (barcodes.isNotEmpty()) parts += "Código: " + barcodes.joinToString()
        parts += "Color aproximado al centro: $colorName"
        parts += "La escena está $lightState"
        return parts.joinToString(". ")
    }
}

class WorldFrameAnalyzer(
    private val onResult: (WorldVisionResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer, Closeable {
    private val analyzeNext = AtomicBoolean(false)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner = BarcodeScanning.getClient()

    fun requestAnalysis() {
        analyzeNext.set(true)
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!analyzeNext.compareAndSet(true, false)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            onError(IllegalStateException("La cámara no entregó una imagen"))
            return
        }

        val scene = estimateScene(imageProxy)
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val textTask = textRecognizer.process(image)
        val barcodeTask = barcodeScanner.process(image)

        Tasks.whenAllComplete(textTask, barcodeTask)
            .addOnCompleteListener {
                try {
                    val text = if (textTask.isSuccessful) textTask.result.text.trim() else ""
                    val barcodes = if (barcodeTask.isSuccessful) {
                        barcodeTask.result.mapNotNull { it.rawValue }.distinct()
                    } else {
                        emptyList()
                    }

                    onResult(
                        WorldVisionResult(
                            text = text,
                            barcodes = barcodes,
                            colorName = scene.first,
                            brightnessPercent = scene.second,
                            lightState = when {
                                scene.second < 18 -> "muy oscura"
                                scene.second < 42 -> "poco iluminada"
                                else -> "iluminada"
                            }
                        )
                    )
                } catch (t: Throwable) {
                    onError(t)
                } finally {
                    imageProxy.close()
                }
            }
    }

    private fun estimateScene(image: ImageProxy): Pair<String, Int> {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumY = 0L
        var count = 0L

        val xStart = width / 3
        val xEnd = width * 2 / 3
        val yStart = height / 3
        val yEnd = height * 2 / 3
        val step = maxOf(4, minOf(width, height) / 64)

        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                val uvX = x / 2
                val uvY = y / 2
                val uIndex = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                val vIndex = uvY * vPlane.rowStride + uvX * vPlane.pixelStride

                if (yIndex < yPlane.buffer.limit() &&
                    uIndex < uPlane.buffer.limit() &&
                    vIndex < vPlane.buffer.limit()
                ) {
                    val yy = yPlane.buffer.get(yIndex).toInt() and 0xff
                    val uu = (uPlane.buffer.get(uIndex).toInt() and 0xff) - 128
                    val vv = (vPlane.buffer.get(vIndex).toInt() and 0xff) - 128

                    val r = (yy + 1.402 * vv).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772 * uu).toInt().coerceIn(0, 255)

                    sumR += r
                    sumG += g
                    sumB += b
                    sumY += yy
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0L) return "desconocido" to 0
        val r = (sumR / count).toInt()
        val g = (sumG / count).toInt()
        val b = (sumB / count).toInt()
        val brightness = ((sumY / count) * 100 / 255).toInt().coerceIn(0, 100)
        return classifyColor(r, g, b) to brightness
    }

    private fun classifyColor(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        if (value < 0.14f) return "negro"
        if (saturation < 0.16f) {
            return when {
                value > 0.84f -> "blanco"
                value > 0.45f -> "gris"
                else -> "gris oscuro"
            }
        }

        return when {
            hue < 15f || hue >= 345f -> "rojo"
            hue < 40f -> "naranja"
            hue < 70f -> "amarillo"
            hue < 165f -> "verde"
            hue < 200f -> "cian"
            hue < 255f -> "azul"
            hue < 290f -> "violeta"
            hue < 345f -> "magenta"
            else -> "rojo"
        }
    }

    override fun close() {
        textRecognizer.close()
        barcodeScanner.close()
    }
}
