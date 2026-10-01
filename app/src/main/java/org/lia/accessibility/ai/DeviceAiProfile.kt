package org.lia.accessibility.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class LocalModelClass {
    TINY,
    SMALL,
    MEDIUM
}

data class DeviceAiProfile(
    val totalRamMb: Long,
    val lowRamDevice: Boolean,
    val supportedAbis: List<String>,
    val recommendedModelClass: LocalModelClass,
    val plannerMemoryBudgetMb: Long
)

class DeviceAiProfileProvider(
    private val context: Context
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun capture(): DeviceAiProfile {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)

        val totalRamMb = (info.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
        val lowRam = activityManager?.isLowRamDevice == true

        return DeviceAiProfileSelector.select(
            totalRamMb = totalRamMb,
            lowRamDevice = lowRam,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
    }
}

object DeviceAiProfileSelector {
    fun select(
        totalRamMb: Long,
        lowRamDevice: Boolean,
        supportedAbis: List<String>
    ): DeviceAiProfile {
        val safeRam = totalRamMb.coerceAtLeast(0L)

        val modelClass = when {
            lowRamDevice || safeRam in 1..5_499 -> LocalModelClass.TINY
            safeRam in 5_500..8_499 -> LocalModelClass.SMALL
            else -> LocalModelClass.MEDIUM
        }

        val fractionBudget = (safeRam * 30L) / 100L
        val memoryBudget = fractionBudget
            .coerceAtLeast(384L)
            .coerceAtMost(3_072L)

        return DeviceAiProfile(
            totalRamMb = safeRam,
            lowRamDevice = lowRamDevice,
            supportedAbis = supportedAbis,
            recommendedModelClass = modelClass,
            plannerMemoryBudgetMb = memoryBudget
        )
    }
}
