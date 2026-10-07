package dev.motionblur.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrimFrameSelectorTest {
    @Test
    fun holdsFrameSpanningTrimStartAtOutputZero() {
        val selector = TrimFrameSelector(startUs = 30_000L)
        val held = frame(0L, 1)
        val firstInside = frame(40_000L, 2)

        assertEquals(emptyList<I420Frame>(), selector.submit(held))

        val selected = selector.submit(firstInside)
        assertEquals(listOf(30_000L, 40_000L), selected.map { it.presentationTimeUs })
        assertSame(held.data, selected[0].data)
        assertSame(firstInside, selected[1])
    }

    @Test
    fun doesNotDuplicateFrameExactlyAtTrimStart() {
        val selector = TrimFrameSelector(startUs = 30_000L)
        val exact = frame(30_000L, 3)

        assertEquals(listOf(exact), selector.submit(exact))
    }

    @Test
    fun discardsHeldFrameWhenDecodeLandsExactlyOnTrimStart() {
        val selector = TrimFrameSelector(startUs = 30_000L)
        selector.submit(frame(0L, 5))
        val exact = frame(30_000L, 6)

        assertEquals(listOf(exact), selector.submit(exact))
    }

    @Test
    fun emitsHeldFrameWhenTrimEndsBeforeNextDecodeTimestamp() {
        val selector = TrimFrameSelector(startUs = 30_000L)
        val held = frame(0L, 4)

        selector.submit(held)

        val selected = selector.finish()
        assertEquals(listOf(30_000L), selected.map { it.presentationTimeUs })
        assertSame(held.data, selected.single().data)
    }

    private fun frame(pts: Long, value: Int) = I420Frame(
        width = 64,
        height = 64,
        presentationTimeUs = pts,
        data = ByteArray(I420Images.size(64, 64)) { value.toByte() },
    )
}
