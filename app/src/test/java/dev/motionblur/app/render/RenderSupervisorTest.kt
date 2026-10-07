package dev.motionblur.app.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RenderSupervisorTest {
    private fun idle() {
        val end = System.nanoTime() + 2_000_000_000L
        while (RenderSupervisor.active.get() && System.nanoTime() < end) Thread.sleep(5)
        assertFalse(RenderSupervisor.active.get())
    }
    @Test(timeout = 5000) fun stuckDriverReturnsErrorAndKeepsOwnershipUntilCleanup() = runBlocking {
        val release = CountDownLatch(1)
        try {
            try {
                RenderSupervisor.run({ false }, timeoutMs = 80, graceMs = 40) { stopped, _ ->
                    release.await(); assertTrue(stopped())
                }
                fail("Expected stalled driver")
            } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("stopped making progress")) }
            assertTrue(RenderSupervisor.active.get())
            try { RenderSupervisor.run({ false }) { _, _ -> fail("Must not overlap") }; fail("Expected owner guard") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("still stopping")) }
        } finally { release.countDown(); idle() }
    }
    @Test(timeout = 5000) fun cancelReturnsWithoutInterruptingGpuOwnership() = runBlocking {
        val release = CountDownLatch(1)
        try {
            try { RenderSupervisor.run({ true }, graceMs = 40) { _, _ -> release.await() }; fail("Expected cancellation") }
            catch (_: CancellationException) {}
            assertTrue(RenderSupervisor.active.get())
        } finally { release.countDown(); idle() }
    }
    @Test(timeout = 5000) fun steadyProgressCompletesWithoutFalseTimeout() = runBlocking {
        RenderSupervisor.run({ false }, timeoutMs = 100) { _, heartbeat ->
            repeat(12) { heartbeat(); Thread.sleep(20) }
        }
        idle()
    }
}
