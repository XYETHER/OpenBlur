package dev.motionblur.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenderBackendPolicyTest {
    @Test
    fun renderEngineDefaultRouteIsGpuWhenTheProbePasses() {
        assertEquals(
            RenderBackend.GPU,
            RenderEngine.defaultRoute(
                RenderBackendCapabilities(esMajor = 3, esMinor = 2, externalOes = true, recordable = true),
            ),
        )
    }

    @Test
    fun supportedDeviceSelectsGpuWithoutSilentFallback() {
        val decision = RenderBackendPolicy.decide(
            RenderBackendCapabilities(esMajor = 3, esMinor = 2, externalOes = true, recordable = true),
        )

        assertEquals(RenderBackend.GPU, decision.backend)
        assertFalse(decision.allowAutomaticCpuFallback)
    }

    @Test
    fun unsupportedDeviceReturnsActionableFailureInsteadOfCpuSuccess() {
        val decision = RenderBackendPolicy.decide(
            RenderBackendCapabilities(esMajor = 3, esMinor = 0, externalOes = true, recordable = true),
        )

        assertEquals(RenderBackend.UNAVAILABLE, decision.backend)
        assertFalse(decision.allowAutomaticCpuFallback)
        assertEquals("OpenGL ES 3.1 or newer is required for GPU motion blur.", decision.message)
    }
}
