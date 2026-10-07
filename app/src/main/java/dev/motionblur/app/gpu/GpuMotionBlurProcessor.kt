package dev.motionblur.app.gpu

import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Resident GLES 3.1 motion estimator and same-frame motion-blur synthesizer.
 *
 * Input texture names must come from [GpuFrameBridge.FrameRing]. Processing runs in the bridge's
 * owning EGL context and never maps or reads pixels on the CPU. The returned GL_TEXTURE_2D name is
 * valid until the next [process] call and can be passed directly to [GpuTextureEncoder]. Programs,
 * textures, and framebuffers are allocated once at construction.
 */
internal class GpuMotionBlurProcessor(
    private val bridge: GpuFrameBridge,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val resources: Resources

    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        resources = bridge.withSharedEglContext { Resources(width, height) }
    }

    /** Returns a bridge-context GL_TEXTURE_2D containing the processed current frame. */
    fun process(ring: GpuFrameBridge.FrameRing, config: GpuMotionBlurConfig): Int {
        check(!closed.get()) { "GpuMotionBlurProcessor is closed" }
        val current = ring.current ?: ring.next ?: error("Frame ring contains no current frame")
        val previous = ring.previous
        val next = ring.next
        if (previous == null || next == null || current === next || config.effectiveStrength == 0f) {
            bridge.withSharedEglContext { resources.resetTimeline() }
            return current.textureId
        }
        require(previous.textureId != 0 && current.textureId != 0 && next.textureId != 0)
        require(current.timestampNs >= 0L) { "Current frame has no presentation timestamp" }
        return bridge.withSharedEglContext {
            resources.process(previous.textureId, current.textureId, next.textureId, current.timestampNs, config).also {
                debugObserverForTests?.invoke(resources.debugSnapshot())
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { bridge.withSharedEglContext { resources.close() } }
    }

    private class Resources(width: Int, height: Int) : AutoCloseable {
        private val frameWidth = width
        private val frameHeight = height
        private val halfWidth = ceilDiv(width, 2)
        private val halfHeight = ceilDiv(height, 2)
        private val blockSize = BLOCK_SIZE
        private val gridWidth = ceilDiv(width, blockSize)
        private val gridHeight = ceilDiv(height, blockSize)
        private val programs = GlesMotionPrograms()
        private val lumaTextures = IntArray(3)
        private val halfLumaTextures = IntArray(3)
        private val motionTexture = IntArray(1)
        private val confidenceTexture = IntArray(1)
        private val recoveryTextures = IntArray(2)
        private val outputTexture = IntArray(1)
        private val framebuffer = IntArray(1)
        private var recoveryIndex = 0
        private var lastTimestampNs: Long? = null
        private var released = false

        private val lumaSizeLocation = GLES30.glGetUniformLocation(programs.luma, "uSize")
        private val lumaHalfSizeLocation = GLES30.glGetUniformLocation(programs.luma, "uHalfSize")
        private val matchFrameSizeLocation = GLES30.glGetUniformLocation(programs.match, "uFrameSize")
        private val matchGridSizeLocation = GLES30.glGetUniformLocation(programs.match, "uGridSize")
        private val matchBlockSizeLocation = GLES30.glGetUniformLocation(programs.match, "uBlockSize")
        private val searchRadiusLocation = GLES30.glGetUniformLocation(programs.match, "uSearchRadius")
        private val coarseStepLocation = GLES30.glGetUniformLocation(programs.match, "uCoarseStep")
        private val refinementRadiusLocation = GLES30.glGetUniformLocation(programs.match, "uRefinementRadius")
        private val patchRadiusLocation = GLES30.glGetUniformLocation(programs.match, "uPatchRadius")
        private val recoveryGridSizeLocation = GLES30.glGetUniformLocation(programs.recovery, "uGridSize")
        private val minimumConfidenceLocation = GLES30.glGetUniformLocation(programs.recovery, "uMinimumConfidence")
        private val maximumConsistencyLocation = GLES30.glGetUniformLocation(programs.recovery, "uMaximumConsistencyError")
        private val hardCutMinimumConfidenceLocation = GLES30.glGetUniformLocation(programs.recovery, "uHardCutMinimumConfidence")
        private val hardCutMaximumConsistencyLocation = GLES30.glGetUniformLocation(programs.recovery, "uHardCutMaximumConsistencyError")
        private val recoveryStepLocation = GLES30.glGetUniformLocation(programs.recovery, "uRecoveryStep")
        private val recoveryResetLocation = GLES30.glGetUniformLocation(programs.recovery, "uReset")
        private val searchDiameterLocation = GLES30.glGetUniformLocation(programs.recovery, "uSearchDiameter")
        private val synthesisFrameSizeLocation = GLES30.glGetUniformLocation(programs.synthesis, "uFrameSize")
        private val synthesisGridSizeLocation = GLES30.glGetUniformLocation(programs.synthesis, "uGridSize")
        private val strengthLocation = GLES30.glGetUniformLocation(programs.synthesis, "uStrength")
        private val pelLocation = GLES30.glGetUniformLocation(programs.synthesis, "uPel")
        private val precisionLocation = GLES30.glGetUniformLocation(programs.synthesis, "uPrecision")

        init {
            GLES30.glGenTextures(lumaTextures.size, lumaTextures, 0)
            lumaTextures.forEach { allocateTexture(it, GLES30.GL_R32F, GLES30.GL_RED, width, height, GLES30.GL_NEAREST) }
            GLES30.glGenTextures(halfLumaTextures.size, halfLumaTextures, 0)
            halfLumaTextures.forEach {
                allocateTexture(it, GLES30.GL_R32F, GLES30.GL_RED, halfWidth, halfHeight, GLES30.GL_NEAREST)
            }
            GLES30.glGenTextures(1, motionTexture, 0)
            allocateTexture(motionTexture[0], GLES30.GL_RGBA16F, GLES30.GL_RGBA, gridWidth, gridHeight, GLES30.GL_LINEAR)
            GLES30.glGenTextures(1, confidenceTexture, 0)
            allocateTexture(confidenceTexture[0], GLES30.GL_RGBA16F, GLES30.GL_RGBA, gridWidth, gridHeight, GLES30.GL_NEAREST)
            GLES30.glGenTextures(recoveryTextures.size, recoveryTextures, 0)
            recoveryTextures.forEach { allocateTexture(it, GLES30.GL_R32F, GLES30.GL_RED, gridWidth, gridHeight, GLES30.GL_NEAREST) }
            GLES30.glGenTextures(1, outputTexture, 0)
            allocateTexture(outputTexture[0], GLES30.GL_RGBA8, GLES30.GL_RGBA, width, height, GLES30.GL_LINEAR)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            bindFixedSamplers()
            checkGl("initialize motion blur resources")
        }

        fun resetTimeline() {
            lastTimestampNs = null
        }

        fun process(previous: Int, current: Int, next: Int, timestampNs: Long, config: GpuMotionBlurConfig): Int {
            check(!released) { "Motion blur resources are closed" }
            computeLuma(previous, current, next)
            computeMotion(config.search)
            updateRecovery(timestampNs, config)
            synthesize(current, config)
            checkGl("process motion blur frame")
            return outputTexture[0]
        }

        private fun computeLuma(previous: Int, current: Int, next: Int) {
            GLES30.glUseProgram(programs.luma)
            bindTexture(0, previous)
            bindTexture(1, current)
            bindTexture(2, next)
            lumaTextures.forEachIndexed { index, texture ->
                GLES31.glBindImageTexture(index, texture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
            }
            halfLumaTextures.forEachIndexed { index, texture ->
                GLES31.glBindImageTexture(index + 3, texture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
            }
            GLES30.glUniform2i(lumaSizeLocation, frameWidth, frameHeight)
            GLES30.glUniform2i(lumaHalfSizeLocation, halfWidth, halfHeight)
            GLES31.glDispatchCompute(ceilDiv(frameWidth, 16), ceilDiv(frameHeight, 16), 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }

        private fun computeMotion(search: MotionSearchConfig) {
            GLES30.glUseProgram(programs.match)
            lumaTextures.forEachIndexed { index, texture -> bindTexture(index, texture) }
            halfLumaTextures.forEachIndexed { index, texture -> bindTexture(index + 3, texture) }
            GLES31.glBindImageTexture(0, motionTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES31.glBindImageTexture(1, confidenceTexture[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES30.glUniform2i(matchFrameSizeLocation, frameWidth, frameHeight)
            GLES30.glUniform2i(matchGridSizeLocation, gridWidth, gridHeight)
            GLES30.glUniform1i(matchBlockSizeLocation, blockSize)
            GLES30.glUniform1i(searchRadiusLocation, search.searchRadius)
            GLES30.glUniform1i(coarseStepLocation, search.coarseStep)
            GLES30.glUniform1i(refinementRadiusLocation, search.refinementRadius)
            GLES30.glUniform1i(patchRadiusLocation, search.patchRadius)
            GLES31.glDispatchCompute(ceilDiv(gridWidth, 8), ceilDiv(gridHeight, 8), 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }

        private fun updateRecovery(timestampNs: Long, config: GpuMotionBlurConfig) {
            val previousTimestamp = lastTimestampNs
            val reset = previousTimestamp == null || timestampNs <= previousTimestamp
            val elapsedNs = if (reset) 0L else timestampNs - previousTimestamp!!
            val recoveryStep = (elapsedNs.toDouble() / config.sceneProtection.recoveryDurationNs.toDouble())
                .coerceIn(0.0, 1.0).toFloat()
            val nextRecoveryIndex = 1 - recoveryIndex

            GLES30.glUseProgram(programs.recovery)
            bindTexture(0, motionTexture[0])
            bindTexture(1, confidenceTexture[0])
            bindTexture(2, recoveryTextures[recoveryIndex])
            GLES31.glBindImageTexture(
                0, recoveryTextures[nextRecoveryIndex], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F,
            )
            GLES30.glUniform2i(recoveryGridSizeLocation, gridWidth, gridHeight)
            GLES30.glUniform1f(minimumConfidenceLocation, config.sceneProtection.minimumConfidence)
            GLES30.glUniform1f(maximumConsistencyLocation, config.sceneProtection.maximumConsistencyError)
            GLES30.glUniform1f(hardCutMinimumConfidenceLocation, config.sceneProtection.hardCutMinimumConfidence)
            GLES30.glUniform1f(hardCutMaximumConsistencyLocation, config.sceneProtection.hardCutMaximumConsistencyError)
            GLES30.glUniform1f(recoveryStepLocation, recoveryStep)
            GLES30.glUniform1i(recoveryResetLocation, if (reset) 1 else 0)
            GLES30.glUniform1f(searchDiameterLocation, (config.search.searchRadius * 2).toFloat())
            GLES31.glDispatchCompute(ceilDiv(gridWidth, 8), ceilDiv(gridHeight, 8), 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

            recoveryIndex = nextRecoveryIndex
            lastTimestampNs = timestampNs
        }

        private fun synthesize(current: Int, config: GpuMotionBlurConfig) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outputTexture[0], 0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Motion blur output framebuffer is incomplete"
            }
            GLES30.glViewport(0, 0, frameWidth, frameHeight)
            GLES30.glUseProgram(programs.synthesis)
            bindTexture(0, current)
            bindTexture(1, motionTexture[0])
            bindTexture(2, confidenceTexture[0])
            bindTexture(3, recoveryTextures[recoveryIndex])
            GLES30.glUniform2f(synthesisFrameSizeLocation, frameWidth.toFloat(), frameHeight.toFloat())
            GLES30.glUniform2i(synthesisGridSizeLocation, gridWidth, gridHeight)
            // Upstream MVFlowBlur uses blur256 = strength * 256 / 200.
            GLES30.glUniform1f(strengthLocation, config.effectiveStrength / 200f)
            GLES30.glUniform1i(pelLocation, if (config.quality == MotionQuality.QUALITY) 4 else 2)
            GLES30.glUniform1i(precisionLocation, if (config.quality == MotionQuality.FAST) 2 else 1)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            for (unit in 0..5) bindTexture(unit, 0)
        }

        fun debugSnapshot(): MotionDebugSnapshot {
            fun read(texture: Int, format: Int, channels: Int): FloatArray {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
                )
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
                val values = FloatArray(gridWidth * gridHeight * channels)
                val bytes = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, gridWidth, gridHeight, format, GLES30.GL_FLOAT, bytes)
                bytes.asFloatBuffer().get(values)
                checkGl("read motion diagnostics")
                return values
            }

            val motion = read(motionTexture[0], GLES30.GL_RGBA, 4)
            val confidence = read(confidenceTexture[0], GLES30.GL_RGBA, 4)
            val recovery = read(recoveryTextures[recoveryIndex], GLES30.GL_RED, 1)
            var magnitude = 0.0
            var effectiveGate = 0.0
            var active = 0
            for (block in recovery.indices) {
                val index = block * 4
                val vx = (motion[index + 2] - motion[index]) * 0.5f
                val vy = (motion[index + 3] - motion[index + 1]) * 0.5f
                magnitude += sqrt((vx * vx + vy * vy).toDouble())
                val gate = recovery[block] * minOf(confidence[index], confidence[index + 1])
                effectiveGate += gate
                if (gate > 0.05f) active++
            }
            val snapshot = MotionDebugSnapshot(
                meanVelocityPixels = magnitude / recovery.size,
                meanEffectiveGate = effectiveGate / recovery.size,
                activeBlockFraction = active.toDouble() / recovery.size,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return snapshot
        }

        private fun bindFixedSamplers() {
            setSampler(programs.luma, "uPrevious", 0)
            setSampler(programs.luma, "uCurrent", 1)
            setSampler(programs.luma, "uNext", 2)
            setSampler(programs.match, "uPreviousLuma", 0)
            setSampler(programs.match, "uCurrentLuma", 1)
            setSampler(programs.match, "uNextLuma", 2)
            setSampler(programs.match, "uPreviousHalf", 3)
            setSampler(programs.match, "uCurrentHalf", 4)
            setSampler(programs.match, "uNextHalf", 5)
            setSampler(programs.recovery, "uMotion", 0)
            setSampler(programs.recovery, "uConfidence", 1)
            setSampler(programs.recovery, "uOldRecovery", 2)
            setSampler(programs.synthesis, "uCurrent", 0)
            setSampler(programs.synthesis, "uMotion", 1)
            setSampler(programs.synthesis, "uRecovery", 3)
        }

        private fun setSampler(program: Int, name: String, unit: Int) {
            val location = GLES30.glGetUniformLocation(program, name)
            check(location >= 0) { "Motion blur sampler $name is unavailable" }
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(location, unit)
        }

        private fun bindTexture(unit: Int, texture: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        }

        private fun allocateTexture(
            texture: Int,
            internalFormat: Int,
            format: Int,
            width: Int,
            height: Int,
            filtering: Int,
        ) {
            check(texture != 0) { "Unable to allocate motion blur texture" }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filtering)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filtering)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, width, height)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }

        override fun close() {
            if (released) return
            released = true
            programs.close()
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
            GLES30.glDeleteTextures(lumaTextures.size, lumaTextures, 0)
            GLES30.glDeleteTextures(halfLumaTextures.size, halfLumaTextures, 0)
            GLES30.glDeleteTextures(1, motionTexture, 0)
            GLES30.glDeleteTextures(1, confidenceTexture, 0)
            GLES30.glDeleteTextures(recoveryTextures.size, recoveryTextures, 0)
            GLES30.glDeleteTextures(1, outputTexture, 0)
        }
    }

    internal data class MotionDebugSnapshot(
        val meanVelocityPixels: Double,
        val meanEffectiveGate: Double,
        val activeBlockFraction: Double,
    )

    companion object {
        const val BLOCK_SIZE = 16

        @Volatile
        internal var debugObserverForTests: ((MotionDebugSnapshot) -> Unit)? = null

        fun ceilDiv(value: Int, divisor: Int): Int = max(1, (value + divisor - 1) / divisor)

        fun checkGl(operation: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$operation failed: 0x${Integer.toHexString(error)}" }
        }
    }
}
