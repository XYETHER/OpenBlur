package dev.motionblur.app.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuMotionBlurProcessorTest {
    @Test
    fun compilesProgramsAndAllocatesResidentPyramidOnDevice() {
        GpuFrameBridge(width = 64, height = 64).use { bridge ->
            GpuMotionBlurProcessor(bridge, width = 64, height = 64).use { }
        }
    }
}
