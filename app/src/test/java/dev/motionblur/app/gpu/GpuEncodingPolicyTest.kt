package dev.motionblur.app.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuEncodingPolicyTest {
    @Test
    fun hevcSourceGetsEnoughAvcBitrateToAvoidQualityRegression() {
        assertEquals(
            13_203_522,
            GpuEncodingPolicy.targetBitRate(
                width = 960,
                height = 720,
                frameRate = 24,
                sourceBitRate = 8_802_348,
            ),
        )
    }

    @Test
    fun missingSourceBitrateUsesHighQualityPixelsPerFrameFloor() {
        assertEquals(
            9_953_280,
            GpuEncodingPolicy.targetBitRate(
                width = 960,
                height = 720,
                frameRate = 24,
                sourceBitRate = null,
            ),
        )
    }
}
