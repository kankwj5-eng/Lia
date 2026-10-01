package org.lia.accessibility.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAiProfileSelectorTest {
    @Test
    fun fourGigabyteClassDeviceUsesTinyProfile() {
        val profile = DeviceAiProfileSelector.select(
            totalRamMb = 4_096,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals(LocalModelClass.TINY, profile.recommendedModelClass)
        assertEquals(1_228L, profile.plannerMemoryBudgetMb)
    }

    @Test
    fun lowRamFlagForcesTinyProfile() {
        val profile = DeviceAiProfileSelector.select(
            totalRamMb = 8_192,
            lowRamDevice = true,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals(LocalModelClass.TINY, profile.recommendedModelClass)
    }

    @Test
    fun eightGigabyteClassDeviceUsesSmallProfile() {
        val profile = DeviceAiProfileSelector.select(
            totalRamMb = 8_000,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals(LocalModelClass.SMALL, profile.recommendedModelClass)
    }

    @Test
    fun largeDeviceKeepsMemoryBudgetBounded() {
        val profile = DeviceAiProfileSelector.select(
            totalRamMb = 16_384,
            lowRamDevice = false,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals(LocalModelClass.MEDIUM, profile.recommendedModelClass)
        assertEquals(3_072L, profile.plannerMemoryBudgetMb)
    }
}
