package dev.motionblur.app.gpu

/** Pure configuration for the bounded GPU patch matcher. */
internal data class MotionSearchConfig(
    val searchRadius: Int,
    val coarseStep: Int,
    val refinementRadius: Int,
    val patchRadius: Int,
) {
    init {
        require(searchRadius in 1..48)
        require(coarseStep in 1..2)
        require(refinementRadius in 0..4)
        require(patchRadius in 1..2)
    }

    val coarseScale: Int = 2

    val estimatedComparisons: Int
        get() {
            val coarseRadius = (searchRadius + coarseScale - 1) / coarseScale
            val coarseAxis = coarseRadius * 2 / coarseStep + 1
            val coarsePatchSamples = square(3)
            val refinementAxis = refinementRadius * 2 + 1
            val fullPatchSamples = square(patchRadius * 2 + 1)
            return square(coarseAxis) * coarsePatchSamples + square(refinementAxis) * fullPatchSamples
        }

    private fun square(value: Int) = value * value
}

internal enum class MotionQuality(
    val search: MotionSearchConfig,
    val pyramidLevels: Int,
    val blockHalfSize: Int,
) {
    FAST(MotionSearchConfig(searchRadius = 8, coarseStep = 2, refinementRadius = 0, patchRadius = 1), pyramidLevels = 4, blockHalfSize = 4),
    BALANCED(MotionSearchConfig(searchRadius = 16, coarseStep = 2, refinementRadius = 1, patchRadius = 1), pyramidLevels = 4, blockHalfSize = 4),
    QUALITY(MotionSearchConfig(searchRadius = 24, coarseStep = 1, refinementRadius = 2, patchRadius = 2), pyramidLevels = 4, blockHalfSize = 4),
    ULTRA(MotionSearchConfig(searchRadius = 48, coarseStep = 1, refinementRadius = 4, patchRadius = 2), pyramidLevels = 6, blockHalfSize = 5),
}

internal enum class DynamicBlurLevel(
    val strengthCap: Float,
    val sceneProtection: SceneProtectionPolicy,
) {
    LIGHT(25f, SceneProtectionPolicy(minimumConfidence = 0.45f, maximumConsistencyError = 0.55f, recoveryDurationNs = 240_000_000L)),
    MEDIUM(50f, SceneProtectionPolicy(minimumConfidence = 0.35f, maximumConsistencyError = 0.75f, recoveryDurationNs = 200_000_000L)),
    STRONG(100f, SceneProtectionPolicy(minimumConfidence = 0.26f, maximumConsistencyError = 0.95f, recoveryDurationNs = 170_000_000L)),
    EXTREME(200f, SceneProtectionPolicy(minimumConfidence = 0.18f, maximumConsistencyError = 1.15f, recoveryDurationNs = 140_000_000L));

    fun clamp(requestedStrength: Float): Float {
        require(requestedStrength.isFinite() && requestedStrength >= 0f)
        return requestedStrength.coerceAtMost(strengthCap)
    }
}

internal data class GpuMotionBlurConfig(
    val quality: MotionQuality,
    val level: DynamicBlurLevel,
    val requestedStrength: Float,
    val dynamicBlur: Boolean = true,
    val sceneProtection: SceneProtectionPolicy = level.sceneProtection,
) {
    init {
        require(requestedStrength.isFinite() && requestedStrength >= 0f)
    }

    val search: MotionSearchConfig get() = quality.search
    val effectiveStrength: Float get() = level.clamp(requestedStrength)
    val usesAdaptiveSceneProtection: Boolean get() = dynamicBlur
    val blurSamples: Int
        get() = when (quality) {
            MotionQuality.FAST -> 6
            MotionQuality.BALANCED -> 8
            MotionQuality.QUALITY -> 12
            MotionQuality.ULTRA -> 16
        }
}

internal data class SceneEvidence(
    val previousConfidence: Float,
    val nextConfidence: Float,
    val motionConsistencyError: Float,
) {
    init {
        require(previousConfidence in 0f..1f)
        require(nextConfidence in 0f..1f)
        require(motionConsistencyError.isFinite() && motionConsistencyError >= 0f)
    }
}

internal data class SceneProtectionPolicy(
    val minimumConfidence: Float = 0.35f,
    val maximumConsistencyError: Float = 0.75f,
    val recoveryDurationNs: Long = 200_000_000L,
    val hardCutMinimumConfidence: Float = 0.05f,
    val hardCutMaximumConsistencyError: Float = 1.5f,
) {
    init {
        require(minimumConfidence in 0f..1f)
        require(maximumConsistencyError >= 0f)
        require(recoveryDurationNs > 0L)
        require(hardCutMinimumConfidence in 0f..minimumConfidence)
        require(hardCutMaximumConsistencyError >= maximumConsistencyError)
    }

    fun isMismatch(evidence: SceneEvidence): Boolean =
        evidence.previousConfidence < hardCutMinimumConfidence ||
            evidence.nextConfidence < hardCutMinimumConfidence ||
            evidence.motionConsistencyError > hardCutMaximumConsistencyError ||
            evidence.previousConfidence < minimumConfidence ||
            evidence.nextConfidence < minimumConfidence ||
            evidence.motionConsistencyError > maximumConsistencyError
}

/** CPU-testable twin of the recovery rule executed per block by the GPU recovery shader. */
internal class SceneRecoveryState(private val policy: SceneProtectionPolicy) {
    private var lastTimestampNs: Long? = null
    private var gate = 1f

    fun update(timestampNs: Long, evidence: SceneEvidence): Float {
        require(timestampNs >= 0L)
        val previousTimestamp = lastTimestampNs
        lastTimestampNs = timestampNs
        if (policy.isMismatch(evidence)) {
            gate = 0f
            return gate
        }
        if (previousTimestamp == null) return gate
        val elapsed = (timestampNs - previousTimestamp).coerceAtLeast(0L)
        gate = (gate + elapsed.toFloat() / policy.recoveryDurationNs.toFloat()).coerceAtMost(1f)
        return gate
    }
}
