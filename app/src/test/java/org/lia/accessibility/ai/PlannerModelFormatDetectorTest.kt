package org.lia.accessibility.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlannerModelFormatDetectorTest {
    @Test
    fun detectsGgufV3() {
        val prefix = ByteArray(20)
        "GGUF".toByteArray(Charsets.US_ASCII)
            .copyInto(prefix, destinationOffset = 0)

        ByteBuffer.wrap(prefix, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3)

        val header = PlannerModelFormatDetector.detect(prefix)

        assertEquals(PlannerModelFormat.GGUF, header.format)
        assertEquals(3, header.major)
    }

    @Test
    fun detectsLiteRtLmV1() {
        val prefix = ByteArray(20)
        "LITERTLM".toByteArray(Charsets.US_ASCII)
            .copyInto(prefix, destinationOffset = 0)

        ByteBuffer.wrap(prefix, 8, 12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putInt(2)
            .putInt(3)

        val header = PlannerModelFormatDetector.detect(prefix)

        assertEquals(PlannerModelFormat.LITERT_LM, header.format)
        assertEquals(1, header.major)
        assertEquals(2, header.minor)
        assertEquals(3, header.patch)
    }

    @Test
    fun rejectsUnknownFormat() {
        assertFailsWith<IllegalStateException> {
            PlannerModelFormatDetector.detect(ByteArray(20))
        }
    }
}
