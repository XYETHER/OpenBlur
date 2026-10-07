package dev.motionblur.app.media

/** Packed 8-bit Y, U, V planes. Inputs belong to the pipeline: do not mutate or retain. */
data class I420Frame(val width: Int, val height: Int, val presentationTimeUs: Long, val data: ByteArray)

/** Actual source timing; duplicate boundary neighbors have identical PTS. Caller owns native resources. */
fun interface FrameEffect {
    fun apply(previous: I420Frame, current: I420Frame, next: I420Frame): ByteArray
}

data class RenderResult(val file: java.io.File, val frameCount: Long, val firstSourcePtsUs: Long,
                        val lastSourcePtsUs: Long, val audioCopied: Boolean)

class UnsupportedMediaException(message: String) : java.io.IOException(message)

internal fun requireMedia(condition: Boolean, message: String) {
    if (!condition) throw UnsupportedMediaException(message)
}

internal class RenderGuard(private val cancelled: () -> Boolean) {
    fun check() {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Render cancelled")
        }
    }
    fun deadline() = android.os.SystemClock.elapsedRealtime() + 30_000L
    fun waiting(deadline: Long) {
        check()
        check(android.os.SystemClock.elapsedRealtime() < deadline) { "MediaCodec stalled for 30 seconds" }
    }
}
