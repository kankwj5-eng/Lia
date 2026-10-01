package org.lia.accessibility.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display

class AccessibilityScreenshotProvider(
    private val service: AccessibilityService
) {
    fun capture(callback: (Result<Bitmap>) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            callback(
                Result.failure(
                    UnsupportedOperationException(
                        "La captura visual de pantalla requiere Android 11 o posterior."
                    )
                )
            )
            return
        }

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(
                    screenshot: AccessibilityService.ScreenshotResult
                ) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            screenshot.colorSpace
                        )

                        if (hardwareBitmap == null) {
                            callback(
                                Result.failure(
                                    IllegalStateException(
                                        "Android no pudo convertir la captura de pantalla."
                                    )
                                )
                            )
                            return
                        }

                        val safeCopy = hardwareBitmap.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )

                        if (safeCopy == null) {
                            callback(
                                Result.failure(
                                    IllegalStateException(
                                        "No se pudo crear una copia segura de la captura."
                                    )
                                )
                            )
                        } else {
                            callback(Result.success(safeCopy))
                        }
                    } finally {
                        hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callback(
                        Result.failure(
                            IllegalStateException(
                                screenshotErrorMessage(errorCode)
                            )
                        )
                    )
                }
            }
        )
    }

    private fun screenshotErrorMessage(errorCode: Int): String =
        when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "Lía no tiene acceso de accesibilidad para capturar la pantalla."

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                "La captura anterior fue demasiado reciente. Inténtalo de nuevo."

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                "Android indicó que la pantalla solicitada no es válida."

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                "Android produjo un error interno al capturar la pantalla."

            else -> {
                if (Build.VERSION.SDK_INT >= 34 &&
                    errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                ) {
                    "Esta pantalla está protegida por la aplicación y Lía no puede capturarla."
                } else {
                    "No se pudo capturar la pantalla. Código de error: " + errorCode
                }
            }
        }
}
