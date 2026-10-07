package dev.motionblur.app.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvToolsGpuProcessorPolicyTest {
    @Test
    fun overlapGridUsesEightPixelBlocksAtFourPixelPitchAndCoversEdges() {
        val grid = MvToolsGpuProcessor.gridFor(width = 17, height = 10)

        assertEquals(8, grid.blockSize)
        assertEquals(4, grid.overlap)
        assertEquals(4, grid.pitch)
        assertEquals(4, grid.width)
        assertEquals(2, grid.height)
    }

    @Test
    fun pyramidKeepsOddEdgesAcrossFourLevels() {
        assertEquals(
            listOf(
                MvToolsGpuProcessor.Level(1919, 1079),
                MvToolsGpuProcessor.Level(960, 540),
                MvToolsGpuProcessor.Level(480, 270),
                MvToolsGpuProcessor.Level(240, 135),
            ),
            MvToolsGpuProcessor.pyramidFor(1919, 1079),
        )
    }

    @Test
    fun eachPyramidLevelUsesItsOwnSmallerVectorGrid() {
        assertEquals(
            listOf(
                MvToolsGpuProcessor.Grid(479, 269),
                MvToolsGpuProcessor.Grid(239, 134),
                MvToolsGpuProcessor.Grid(119, 67),
                MvToolsGpuProcessor.Grid(59, 33),
            ),
            MvToolsGpuProcessor.gridsForPyramid(1919, 1079),
        )
    }

    @Test
    fun flowBlurParametersFollowMvToolsStrengthPelPrecSemantics() {
        val quality = MvToolsGpuProcessor.flowParameters(
            GpuMotionBlurConfig(MotionQuality.QUALITY, DynamicBlurLevel.EXTREME, 200f),
        )
        val fast = MvToolsGpuProcessor.flowParameters(
            GpuMotionBlurConfig(MotionQuality.FAST, DynamicBlurLevel.STRONG, 80f),
        )

        assertEquals(256, quality.blur256)
        assertEquals(2, quality.pel)
        assertEquals(1, quality.prec)
        assertEquals(102, fast.blur256)
        assertEquals(2, fast.pel)
        assertEquals(2, fast.prec)
    }

    @Test
    fun hardSceneCutRequiresGlobalBadEvidenceRatherThanOneBadVector() {
        assertFalse(MvToolsGpuProcessor.isHardSceneCut(badVectorFraction = 0.20f, meanConfidence = 0.03f))
        assertTrue(MvToolsGpuProcessor.isHardSceneCut(badVectorFraction = 0.76f, meanConfidence = 0.03f))
        assertTrue(MvToolsGpuProcessor.isHardSceneCut(badVectorFraction = 0.90f, meanConfidence = 0.30f))
    }

    @Test
    fun hardSceneEvidenceUsesConfiguredFixedThresholds() {
        val policy = SceneProtectionPolicy(
            minimumConfidence = .4f,
            maximumConsistencyError = .7f,
            hardCutMinimumConfidence = .10f,
            hardCutMaximumConsistencyError = 1.1f,
        )

        assertTrue(MvToolsGpuProcessor.isHardVector(.09f, .2f, policy))
        assertTrue(MvToolsGpuProcessor.isHardVector(.8f, 1.11f, policy))
        assertFalse(MvToolsGpuProcessor.isHardVector(.11f, 1.0f, policy))
    }
}
