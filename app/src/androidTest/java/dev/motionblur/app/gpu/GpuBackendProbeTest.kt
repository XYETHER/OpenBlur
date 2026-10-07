package dev.motionblur.app.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuBackendProbeTest {
    @Test
    fun createsEs31ContextOnPhysicalDevice() {
        GpuBackendProbe().use { probe ->
            val capabilities = probe.capabilities
            assertTrue("GLES 3.1 is required: ${capabilities.version}", capabilities.major > 3 ||
                capabilities.major == 3 && capabilities.minor >= 1)
            assertTrue("Renderer must be reported", capabilities.renderer.isNotBlank())
            assertTrue("External OES textures are required", capabilities.externalOes)
            assertTrue("Recordable EGL is required", capabilities.recordableSurface)
        }
    }
}
