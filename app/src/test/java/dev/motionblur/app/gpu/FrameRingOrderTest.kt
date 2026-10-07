package dev.motionblur.app.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRingOrderTest {
    @Test
    fun advancesPreviousCurrentNextWithoutAllocatingMoreSlots() {
        val ring = FrameRingOrder(3)

        assertEquals(FrameRingOrder.Indices(null, null, 0), ring.push())
        assertEquals(FrameRingOrder.Indices(null, 0, 1), ring.push())
        assertEquals(FrameRingOrder.Indices(0, 1, 2), ring.push())
        assertEquals(FrameRingOrder.Indices(1, 2, 0), ring.push())
        assertEquals(FrameRingOrder.Indices(2, 0, 1), ring.push())
    }

    @Test
    fun rejectsRingSizesOtherThanThree() {
        val error = runCatching { FrameRingOrder(2) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertNull(error?.cause)
    }
}
