package dev.motionblur.app.render

import dev.motionblur.app.media.I420Frame

/** A render-owned native session. Never shared across jobs or UI threads. */
internal class NativeMotionBlur(width: Int, height: Int, quality: Int, strength: Float) : AutoCloseable {
    private var handle: Long = create(width, height, quality, strength).also {
        check(it != 0L) { "MVTools could not create a render session" }
    }

    fun apply(previous: I420Frame, current: I420Frame, next: I420Frame): ByteArray {
        check(handle != 0L) { "Native session is closed" }
        val elapsedUs = if (current.presentationTimeUs > previous.presentationTimeUs)
            current.presentationTimeUs - previous.presentationTimeUs
        else next.presentationTimeUs - current.presentationTimeUs
        return process(handle, previous.data, current.data, next.data, elapsedUs.coerceAtLeast(1) / 1_000_000.0)
    }

    override fun close() {
        if (handle != 0L) { destroy(handle); handle = 0 }
    }

    private external fun create(width: Int, height: Int, quality: Int, maxStrength: Float): Long
    private external fun process(handle: Long, previous: ByteArray, current: ByteArray, next: ByteArray,
                                 elapsedSeconds: Double): ByteArray
    private external fun destroy(handle: Long)

    companion object {
        init { System.loadLibrary("openblur_mvtools") }
    }
}
