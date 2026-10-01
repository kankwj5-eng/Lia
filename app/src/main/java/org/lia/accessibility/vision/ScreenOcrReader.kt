package org.lia.accessibility.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

class ScreenOcrReader : Closeable {
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(
        bitmap: Bitmap,
        callback: (Result<String>) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                callback(Result.success(result.text.trim()))
            }
            .addOnFailureListener { error ->
                callback(Result.failure(error))
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    override fun close() {
        recognizer.close()
    }
}
