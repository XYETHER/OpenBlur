package dev.motionblur.app.logic

import org.junit.Assert.*
import org.junit.Test

class EditorModelTest {
    @Test fun dynamicLevelsKeepQualityIndependent() {
        val extreme = EditorDraft().choosePreset("Dynamic Extreme")
        assertEquals(200f, extreme.strength)
        assertEquals("Balanced", extreme.quality)
        assertEquals("Dynamic Medium", extreme.choosePreset("Dynamic Medium").preset)
        assertThrows(IllegalArgumentException::class.java) { extreme.choosePreset("Heavy") }
    }
    @Test fun parsesSecondsWithoutAcceptingNonFiniteOrNegativeTimes() {
        assertEquals(1250L, parseSeconds("1.25"))
        assertEquals(0L, parseSeconds("0"))
        assertNull(parseSeconds("NaN"))
        assertNull(parseSeconds("Infinity"))
        assertNull(parseSeconds("-1"))
        assertNull(parseSeconds(""))
    }
    @Test fun trimClampsWindowAndPreservesFullShortRange() {
        val moved = Timeline(12000, previewStart = 8000).trim(1000, 7000)
        assertEquals(3000L, moved.previewStart)
        val short = moved.trim(2000, 3500)
        assertEquals(1500L, short.windowLength)
        assertEquals(2000L, short.previewStart)
        assertEquals(3499L, short.clampPlayhead(9000))
    }
    @Test fun previewGrabKeepsOffsetAndDoesNotChangeTrim() {
        val timeline = Timeline(12000, 1000, 11000, 3000)
        val moved = timeline.dragPreview(pointer = 8000, grabOffset = 1500)
        assertEquals(6500L, moved.previewStart)
        assertEquals(1000L, moved.start)
        assertEquals(11000L, moved.end)
        assertEquals(7000L, timeline.dragPreview(15000, 1500).previewStart)
        assertEquals(1000L, timeline.dragPreview(0, 1500).previewStart)
    }
    @Test fun rejectsInvalidTrimAndFiniteDraftStrength() {
        assertThrows(IllegalArgumentException::class.java) { Timeline(1000).trim(900, 800) }
        assertThrows(IllegalArgumentException::class.java) { Timeline(0) }
        assertThrows(IllegalArgumentException::class.java) { EditorDraft(strength = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { EditorDraft(times = 5) }
    }
    @Test fun fullSourceAndIndependentDefaults() {
        val timeline = Timeline(12345)
        assertEquals(0L, timeline.start)
        assertEquals(12345L, timeline.end)
        assertEquals(4000L, timeline.windowLength)
        val draft = EditorDraft()
        assertEquals("Dynamic Medium", draft.preset)
        assertEquals("Balanced", draft.quality)
        assertEquals(1, draft.times)
        assertEquals("Auto", draft.compute)
        assertEquals("Ask before restarting", draft.fallback)
        assertTrue(draft.dynamicBlur)
    }

    @Test fun dynamicBlurCanBeDisabledWithoutChangingTheSelectedStrengthOrQuality() {
        val fixed = EditorDraft().choosePreset("Dynamic Strong").copy(dynamicBlur = false, quality = "Ultra quality")

        assertFalse(fixed.dynamicBlur)
        assertEquals(100f, fixed.strength)
        assertEquals("Ultra quality", fixed.quality)
    }
    @Test fun advancedIsPresentationOnlyButEncodingSettingsAffectRendering() {
        val original = EditorDraft()
        assertTrue(original.sameRenderSettings(original.copy(advanced = true)))
        assertFalse(original.sameRenderSettings(original.copy(bitrateMbps = 8f)))
        assertFalse(original.sameRenderSettings(original.copy(videoCodec = VideoCodec.H265)))
        assertFalse(original.sameRenderSettings(original.copy(encodingEffort = .9f)))
    }

}
