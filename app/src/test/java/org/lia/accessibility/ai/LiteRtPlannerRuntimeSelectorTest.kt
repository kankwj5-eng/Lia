package org.lia.accessibility.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtPlannerRuntimeSelectorTest {
    @Test
    fun tinyProfileKeepsContextAndThreadsSmall() {
        val profile = DeviceAiProfile(
            totalRamMb = 4_096,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a"),
            recommendedModelClass = LocalModelClass.TINY,
            plannerMemoryBudgetMb = 1_228
        )

        val config = LiteRtPlannerRuntimeSelector.forDevice(
            profile = profile,
            availableProcessors = 8
        )

        assertEquals(2_048, config.maxNumTokens)
        assertEquals(256, config.maxOutputTokens)
        assertEquals(2, config.cpuThreads)
    }

    @Test
    fun smallProfileCapsCpuThreads() {
        val profile = DeviceAiProfile(
            totalRamMb = 8_000,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a"),
            recommendedModelClass = LocalModelClass.SMALL,
            plannerMemoryBudgetMb = 2_400
        )

        val config = LiteRtPlannerRuntimeSelector.forDevice(
            profile = profile,
            availableProcessors = 12
        )

        assertEquals(4_096, config.maxNumTokens)
        assertEquals(4, config.cpuThreads)
    }

    @Test
    fun singleCoreDeviceStillGetsOneWorkerThread() {
        val profile = DeviceAiProfile(
            totalRamMb = 4_096,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a"),
            recommendedModelClass = LocalModelClass.TINY,
            plannerMemoryBudgetMb = 1_228
        )

        val config = LiteRtPlannerRuntimeSelector.forDevice(
            profile = profile,
            availableProcessors = 0
        )

        assertEquals(1, config.cpuThreads)
    }
}
