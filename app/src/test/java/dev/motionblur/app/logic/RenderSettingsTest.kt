package dev.motionblur.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RenderSettingsTest {
    @Test
    fun presetStrengthsUseTheCurrentReferenceValues() {
        assertEquals(25f, RenderSettings.preset(BlurPreset.DYNAMIC_LIGHT).strength, 0.001f)
        assertEquals(50f, RenderSettings.preset(BlurPreset.DYNAMIC_MEDIUM).strength, 0.001f)
        assertEquals(100f, RenderSettings.preset(BlurPreset.DYNAMIC_STRONG).strength, 0.001f)
        assertEquals(200f, RenderSettings.preset(BlurPreset.DYNAMIC_EXTREME).strength, 0.001f)
    }

    @Test
    fun blurTimesIsBoundedToTheVisibleOptions() {
        assertEquals(4, RenderSettings(strength = 50f, times = 4).times)
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(strength = 50f, times = 0) }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(strength = 50f, times = 5) }
    }

    @Test
    fun strengthAcceptsExtremeReferenceButRejectsInvalidNumbers() {
        assertEquals(200f, RenderSettings(strength = 200f, times = 1).strength, 0.001f)
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(strength = -1f, times = 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderSettings(strength = 201f, times = 1) }
    }
}
