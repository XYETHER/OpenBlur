package dev.motionblur.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SelectedOutputIntervalTest {
    @Test
    fun endOfStreamUsesExactSelectedDurationAfterLastFrameStart() {
        val interval = SelectedOutputInterval(sourceStartUs = 30_000L, sourceEndUs = 151_000L)

        assertEquals(121_000L, interval.durationUs)
        assertEquals(121_000L, interval.endOfStreamPtsUs(lastFrameOutputPtsUs = 120_000L))
    }

    @Test
    fun frameAtSelectedEndIsOutsideOutputInterval() {
        val interval = SelectedOutputInterval(sourceStartUs = 30_000L, sourceEndUs = 151_000L)

        try {
            interval.outputPtsUs(151_000L)
            fail("Selected end must be exclusive")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun endOfStreamRejectsLastFrameOutsideFinalInterval() {
        val interval = SelectedOutputInterval(sourceStartUs = 30_000L, sourceEndUs = 151_000L)

        try {
            interval.endOfStreamPtsUs(lastFrameOutputPtsUs = 121_000L)
            fail("Last frame start must precede selected duration")
        } catch (_: IllegalArgumentException) {
        }
    }
}