package dev.motionblur.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipMetadataTest {
    @Test
    fun parsesFractionalFrameRatesWithoutFloatingPointDrift() {
        val rate = ClipMetadata.parseRate("30000/1001")
        requireNotNull(rate)
        assertEquals(30000L, rate.numerator)
        assertEquals(1001L, rate.denominator)
    }

    @Test
    fun rejectsInvalidFrameRates() {
        assertNull(ClipMetadata.parseRate(null))
        assertNull(ClipMetadata.parseRate("0/30"))
        assertNull(ClipMetadata.parseRate("30/0"))
        assertNull(ClipMetadata.parseRate("not-a-rate"))
    }
}
