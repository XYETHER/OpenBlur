package dev.motionblur.app.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MvToolsGpuProcessorDeviceTest {
    @Test
    fun compilesAllProgramsAndAllocatesFourLevelOverlappingGrid() {
        GpuFrameBridge(width = 65, height = 37).use { bridge ->
            MvToolsGpuProcessor(bridge, width = 65, height = 37).use { }
        }

        val grid = MvToolsGpuProcessor.gridFor(65, 37)
        assertEquals(16, grid.width)
        assertEquals(9, grid.height)
        assertEquals(4, grid.pitch)
    }
}
