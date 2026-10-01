package org.lia.accessibility.system

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

class TorchController(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    fun setEnabled(enabled: Boolean): Boolean =
        runCatching {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@runCatching false

            cameraManager.setTorchMode(cameraId, enabled)
            true
        }.getOrDefault(false)
}
