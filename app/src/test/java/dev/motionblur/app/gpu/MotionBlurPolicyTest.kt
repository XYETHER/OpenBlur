package dev.motionblur.app.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBlurPolicyTest {
    @Test
    fun qualityTiersHaveExplicitIncreasingSearchAndRefinementCosts() {
        val fast = MotionQuality.FAST.search
        val balanced = MotionQuality.BALANCED.search
        val quality = MotionQuality.QUALITY.search

        assertEquals(MotionSearchConfig(searchRadius = 8, coarseStep = 2, refinementRadius = 0, patchRadius = 1), fast)
        assertEquals(MotionSearchConfig(searchRadius = 16, coarseStep = 2, refinementRadius = 1, patchRadius = 1), balanced)
        assertEquals(MotionSearchConfig(searchRadius = 24, coarseStep = 1, refinementRadius = 2, patchRadius = 2), quality)
        assertTrue(fast.estimatedComparisons < balanced.estimatedComparisons)
        assertTrue(balanced.estimatedComparisons < quality.estimatedComparisons)
        assertEquals(2, quality.coarseScale)
        assertEquals(6_250, quality.estimatedComparisons)
    }

    @Test
    fun ultraQualityUsesTheReservedHighQualityVectorBudget() {
        val ultra = MotionQuality.ULTRA.search

        assertEquals(MotionSearchConfig(searchRadius = 48, coarseStep = 1, refinementRadius = 4, patchRadius = 2), ultra)
        assertTrue(ultra.estimatedComparisons >= MotionQuality.QUALITY.search.estimatedComparisons * 3)
        assertEquals(5, MotionQuality.ULTRA.blockHalfSize)
        assertEquals(6, MotionQuality.ULTRA.pyramidLevels)
    }

    @Test
    fun dynamicLevelsClampRequestedStrengthToTheirCaps() {
        assertEquals(25f, DynamicBlurLevel.LIGHT.clamp(40f), 0f)
        assertEquals(50f, DynamicBlurLevel.MEDIUM.clamp(80f), 0f)
        assertEquals(100f, DynamicBlurLevel.STRONG.clamp(150f), 0f)
        assertEquals(200f, DynamicBlurLevel.EXTREME.clamp(250f), 0f)
        assertEquals(10f, DynamicBlurLevel.EXTREME.clamp(10f), 0f)
    }

    @Test
    fun processorConfigCombinesQualityLevelAndValidatedStrength() {
        val config = GpuMotionBlurConfig(
            quality = MotionQuality.QUALITY,
            level = DynamicBlurLevel.MEDIUM,
            requestedStrength = 80f,
        )

        assertEquals(MotionQuality.QUALITY.search, config.search)
        assertEquals(50f, config.effectiveStrength, 0f)
        assertEquals(12, config.blurSamples)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GpuMotionBlurConfig(MotionQuality.FAST, DynamicBlurLevel.LIGHT, Float.NaN)
        }
    }

    @Test
    fun fixedMvtoolsModeKeepsStrengthButDisablesAdaptiveSceneProtection() {
        val fixed = GpuMotionBlurConfig(MotionQuality.QUALITY, DynamicBlurLevel.STRONG, 100f, dynamicBlur = false)

        assertEquals(100f, fixed.effectiveStrength, 0f)
        assertTrue(!fixed.usesAdaptiveSceneProtection)
    }

    @Test
    fun strongerDynamicLevelsIncreaseBlurAndRelaxOnlySoftSceneProtection() {
        val levels = DynamicBlurLevel.entries
        val policies = levels.map { it.sceneProtection }

        assertEquals(listOf(25f, 50f, 100f, 200f), levels.map { it.strengthCap })
        assertTrue(policies.zipWithNext().all { (a, b) -> a.minimumConfidence > b.minimumConfidence })
        assertTrue(policies.zipWithNext().all { (a, b) -> a.maximumConsistencyError < b.maximumConsistencyError })
        assertTrue(policies.zipWithNext().all { (a, b) -> a.recoveryDurationNs >= b.recoveryDurationNs })
        assertTrue(policies.all { it.hardCutMinimumConfidence == policies.first().hardCutMinimumConfidence })
        assertTrue(policies.all { it.hardCutMaximumConsistencyError == policies.first().hardCutMaximumConsistencyError })
    }

    @Test
    fun extremeAllowsDifficultMotionButEveryLevelStillRejectsHardCuts() {
        val difficultMotion = SceneEvidence(0.22f, 0.24f, 1.0f)
        assertTrue(DynamicBlurLevel.LIGHT.sceneProtection.isMismatch(difficultMotion))
        assertTrue(!DynamicBlurLevel.EXTREME.sceneProtection.isMismatch(difficultMotion))

        val hardCut = SceneEvidence(0.01f, 0.95f, 0.1f)
        DynamicBlurLevel.entries.forEach { level ->
            assertTrue(level.sceneProtection.isMismatch(hardCut))
        }
    }


    @Test
    fun sceneMismatchSuppressesBlurImmediately() {
        val policy = SceneProtectionPolicy()
        val state = SceneRecoveryState(policy)

        assertEquals(1f, state.update(0L, SceneEvidence(0.9f, 0.9f, 0.05f)), 0.001f)
        assertEquals(0f, state.update(33_000_000L, SceneEvidence(0.1f, 0.9f, 0.05f)), 0.001f)
    }

    @Test
    fun confidenceRecoversUsingTimestampsOverAboutPointTwoSeconds() {
        val state = SceneRecoveryState(SceneProtectionPolicy(recoveryDurationNs = 200_000_000L))
        val safe = SceneEvidence(0.9f, 0.9f, 0.05f)
        val cut = SceneEvidence(0.1f, 0.1f, 1f)

        state.update(0L, cut)
        assertEquals(0.5f, state.update(100_000_000L, safe), 0.001f)
        assertEquals(1f, state.update(200_000_000L, safe), 0.001f)
    }
}
