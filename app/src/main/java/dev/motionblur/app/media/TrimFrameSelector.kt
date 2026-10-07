package dev.motionblur.app.media

/**
 * Selects decoded frames for a trim range without losing a variable-frame-rate image that is
 * already visible at the requested trim start. The held frame is retimestamped to trim start;
 * its pixels remain untouched.
 */
internal class TrimFrameSelector(private val startUs: Long) {
    private var heldBeforeStart: I420Frame? = null
    private var emittedStartHold = false

    fun submit(frame: I420Frame): List<I420Frame> {
        if (frame.presentationTimeUs < startUs) {
            heldBeforeStart = frame
            return emptyList()
        }
        val held = heldBeforeStart
        if (!emittedStartHold && held != null && frame.presentationTimeUs > startUs) {
            emittedStartHold = true
            heldBeforeStart = null
            return listOf(held.copy(presentationTimeUs = startUs), frame)
        }
        heldBeforeStart = null
        return listOf(frame)
    }

    fun finish(): List<I420Frame> {
        val held = heldBeforeStart ?: return emptyList()
        if (emittedStartHold) return emptyList()
        emittedStartHold = true
        heldBeforeStart = null
        return listOf(held.copy(presentationTimeUs = startUs))
    }
}
