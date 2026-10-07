package dev.motionblur.app.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** Native work retains exclusive ownership even if a driver ignores cancellation. */
internal object RenderSupervisor {
    val active = AtomicBoolean(false)

    suspend fun run(
        cancelled: () -> Boolean,
        timeoutMs: Long = 60_000,
        graceMs: Long = 3_000,
        encode: (stopped: () -> Boolean, heartbeat: () -> Unit) -> Unit,
    ) {
        check(active.compareAndSet(false, true)) {
            "The previous GPU worker is still stopping. Wait, or restart OpenBlur before retrying."
        }
        val stop = AtomicBoolean(false)
        val progressAt = AtomicLong(System.nanoTime())
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val worker = Thread({
            try { encode({ stop.get() || cancelled() }, { progressAt.set(System.nanoTime()) }) }
            catch (error: Throwable) { failure = error }
            finally { active.set(false); done.countDown() }
        }, "OpenBlurRender").apply { isDaemon = true }
        try { worker.start() }
        catch (error: Throwable) { active.set(false); throw error }
        try {
            while (done.count > 0) {
                val aborted = cancelled()
                if (aborted || TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - progressAt.get()) >= timeoutMs) {
                    stop.set(true)
                    // Do not interrupt the thread while it waits on the GL owner: cleanup must stay ordered.
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
                    while (done.count > 0 && System.nanoTime() < deadline) delay(20)
                    if (aborted) throw java.util.concurrent.CancellationException("Render cancelled")
                    throw IllegalStateException("Rendering stopped making progress." +
                        if (done.count > 0) " The device driver is still stopping; restart OpenBlur if retry remains unavailable." else " Retry the render.")
                }
                delay(20)
            }
            failure?.let { throw it }
        } finally {
            if (done.count > 0) stop.set(true)
        }
    }
}
