package dev.motionblur.app.gpu

import android.opengl.GLES30
import android.opengl.GLES31
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Experimental resident-GPU approximation of MVTools Quality-1.
 *
 * The normal path consumes and returns GL_TEXTURE_2D names only. No pixel mapping, Image,
 * ByteArray, JNI, or glReadPixels is used. This class is deliberately not wired into RenderEngine.
 */
internal class MvToolsGpuProcessor(
    private val bridge: GpuFrameBridge,
    width: Int,
    height: Int,
    private val timingObserver: ((StageTiming) -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val resources: Resources

    init {
        require(width > 0 && height > 0)
        resources = bridge.withSharedEglContext { Resources(width, height, timingObserver) }
    }

    fun process(ring: GpuFrameBridge.FrameRing, config: GpuMotionBlurConfig): Int {
        check(!closed.get()) { "MvToolsGpuProcessor is closed" }
        val current = ring.current ?: ring.next ?: error("Frame ring contains no current frame")
        val previous = ring.previous
        val next = ring.next
        if (previous == null || next == null || current === next || config.effectiveStrength == 0f) {
            return current.textureId
        }
        require(previous.textureId != 0 && current.textureId != 0 && next.textureId != 0)
        return bridge.withSharedEglContext {
            resources.process(previous.textureId, current.textureId, next.textureId, config)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { bridge.withSharedEglContext { resources.close() } }
    }

    data class StageTiming(val stage: Stage, val gpuCompletionNs: Long)
    enum class Stage { LUMA_PYRAMID, BACKWARD_SEARCH, FORWARD_SEARCH, VALIDATE_AND_SCENE, FLOW_BLUR }
    data class Level(val width: Int, val height: Int)
    data class Grid(val width: Int, val height: Int, val blockSize: Int = BLOCK_SIZE, val overlap: Int = OVERLAP) {
        val pitch: Int get() = blockSize - overlap
    }
    data class FlowParameters(val blur256: Int, val pel: Int, val prec: Int)

    private class Resources(
        private val width: Int,
        private val height: Int,
        private val timingObserver: ((StageTiming) -> Unit)?,
    ) : AutoCloseable {
        private val levels = pyramidFor(width, height, MAX_PYRAMID_LEVELS)
        private val grids = gridsForPyramid(width, height, MAX_PYRAMID_LEVELS)
        private val grid = grids.first()
        private val programs = MvToolsGpuPrograms()
        private val luma = Array(3) { IntArray(levels.size) }
        private val backward = IntArray(levels.size)
        private val forward = IntArray(levels.size)
        private val field = IntArray(1)
        private val evidence = IntArray(1)
        private val sceneGate = IntArray(1)
        private val output = IntArray(1)
        private val framebuffer = IntArray(1)
        private var released = false

        init {
            luma.forEach { chain ->
                GLES30.glGenTextures(chain.size, chain, 0)
                chain.forEachIndexed { index, texture -> allocate(texture, GLES30.GL_R32F, GLES30.GL_RED, levels[index], GLES30.GL_LINEAR) }
            }
            GLES30.glGenTextures(backward.size, backward, 0)
            GLES30.glGenTextures(forward.size, forward, 0)
            backward.forEachIndexed { index, texture ->
                allocate(texture, GLES30.GL_RGBA32F, GLES30.GL_RGBA, Level(grids[index].width, grids[index].height), GLES30.GL_NEAREST)
            }
            forward.forEachIndexed { index, texture ->
                allocate(texture, GLES30.GL_RGBA32F, GLES30.GL_RGBA, Level(grids[index].width, grids[index].height), GLES30.GL_NEAREST)
            }
            GLES30.glGenTextures(1, field, 0)
            allocate(field[0], GLES30.GL_RGBA16F, GLES30.GL_RGBA, Level(grid.width, grid.height), GLES30.GL_LINEAR)
            GLES30.glGenTextures(1, evidence, 0)
            allocate(evidence[0], GLES30.GL_RGBA32F, GLES30.GL_RGBA, Level(grid.width, grid.height), GLES30.GL_LINEAR)
            GLES30.glGenTextures(1, sceneGate, 0)
            allocate(sceneGate[0], GLES30.GL_R32F, GLES30.GL_RED, Level(1, 1), GLES30.GL_NEAREST)
            GLES30.glGenTextures(1, output, 0)
            allocate(output[0], GLES30.GL_RGBA8, GLES30.GL_RGBA, Level(width, height), GLES30.GL_LINEAR)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            fixedSamplers()
            checkGl("initialize MVTools resources")
        }

        fun process(previous: Int, current: Int, next: Int, config: GpuMotionBlurConfig): Int {
            check(!released)
            timed(Stage.LUMA_PYRAMID) { buildPyramids(intArrayOf(previous, current, next)) }
            timed(Stage.BACKWARD_SEARCH) { search(neighbor = 0, textures = backward, config = config) }
            timed(Stage.FORWARD_SEARCH) { search(neighbor = 2, textures = forward, config = config) }
            timed(Stage.VALIDATE_AND_SCENE) { validateAndGate(config) }
            timed(Stage.FLOW_BLUR) { synthesize(current, flowParameters(config), config.sceneProtection, config.usesAdaptiveSceneProtection) }
            checkGl("process MVTools frame")
            return output[0]
        }

        private fun buildPyramids(inputs: IntArray) {
            for (frame in inputs.indices) {
                GLES30.glUseProgram(programs.luma)
                bind(0, inputs[frame])
                GLES31.glBindImageTexture(0, luma[frame][0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
                uniform2i(programs.luma, "uSize", levels[0])
                dispatch(levels[0], 16)
                for (level in 1 until levels.size) {
                    GLES30.glUseProgram(programs.downsample)
                    bind(0, luma[frame][level - 1])
                    GLES31.glBindImageTexture(0, luma[frame][level], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
                    uniform2i(programs.downsample, "uSize", levels[level])
                    dispatch(levels[level], 16)
                }
            }
        }

        private fun search(neighbor: Int, textures: IntArray, config: GpuMotionBlurConfig) {
            val lastLevel = config.quality.pyramidLevels - 1
            for (level in lastLevel downTo 0) {
                GLES30.glUseProgram(programs.search)
                bind(0, luma[1][level]); bind(1, luma[neighbor][level])
                bind(2, if (level == lastLevel) textures[level] else textures[level + 1])
                GLES31.glBindImageTexture(0, textures[level], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
                uniform2i(programs.search, "uImageSize", levels[level])
                GLES30.glUniform2i(location(programs.search, "uGridSize"), grids[level].width, grids[level].height)
                val seedGrid = if (level == lastLevel) grids[level] else grids[level + 1]
                GLES30.glUniform2i(location(programs.search, "uSeedGridSize"), seedGrid.width, seedGrid.height)
                val radius = if (level == lastLevel) minOf(4, ceil(config.search.searchRadius / (1 shl level).toDouble()).toInt()) else config.search.refinementRadius
                GLES30.glUniform1i(location(programs.search, "uRadius"), radius)
                GLES30.glUniform1i(location(programs.search, "uBlockHalfSize"), config.quality.blockHalfSize)
                GLES30.glUniform1i(location(programs.search, "uHasSeed"), if (level == lastLevel) 0 else 1)
                dispatch(Level(grids[level].width, grids[level].height), 8)
            }
        }

        private fun validateAndGate(config: GpuMotionBlurConfig) {
            GLES30.glUseProgram(programs.validate)
            bind(0, backward[0]); bind(1, forward[0]); bind(2, luma[1][0]); bind(3, luma[0][0]); bind(4, luma[2][0])
            GLES31.glBindImageTexture(0, field[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
            GLES31.glBindImageTexture(1, evidence[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
            GLES30.glUniform2i(location(programs.validate, "uGridSize"), grid.width, grid.height)
            GLES30.glUniform2i(location(programs.validate, "uFrameSize"), width, height)
            GLES30.glUniform1f(location(programs.validate, "uSearchDiameter"), config.search.searchRadius * 2f)
            dispatch(Level(grid.width, grid.height), 8)

            if (!config.usesAdaptiveSceneProtection) return
            GLES30.glUseProgram(programs.sceneGate)
            bind(0, evidence[0])
            GLES31.glBindImageTexture(0, sceneGate[0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
            GLES30.glUniform2i(location(programs.sceneGate, "uGridSize"), grid.width, grid.height)
            GLES30.glUniform1f(
                location(programs.sceneGate, "uHardCutMinimumConfidence"),
                config.sceneProtection.hardCutMinimumConfidence,
            )
            GLES30.glUniform1f(
                location(programs.sceneGate, "uHardCutMaximumConsistency"),
                config.sceneProtection.hardCutMaximumConsistencyError,
            )
            GLES31.glDispatchCompute(1, 1, 1)
            barrier()
        }

        private fun synthesize(current: Int, parameters: FlowParameters, scenePolicy: SceneProtectionPolicy, dynamicBlur: Boolean = true) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, output[0], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(programs.synthesis)
            bind(0, current); bind(1, field[0]); bind(2, evidence[0]); bind(3, sceneGate[0])
            GLES30.glUniform2f(location(programs.synthesis, "uFrameSize"), width.toFloat(), height.toFloat())
            GLES30.glUniform2i(location(programs.synthesis, "uGridSize"), grid.width, grid.height)
            GLES30.glUniform1i(location(programs.synthesis, "uBlur256"), parameters.blur256)
            GLES30.glUniform1i(location(programs.synthesis, "uPel"), parameters.pel)
            GLES30.glUniform1i(location(programs.synthesis, "uPrec"), parameters.prec)
            GLES30.glUniform1f(location(programs.synthesis, "uMinimumConfidence"), scenePolicy.minimumConfidence)
            GLES30.glUniform1f(location(programs.synthesis, "uMaximumConsistency"), scenePolicy.maximumConsistencyError)
            GLES30.glUniform1i(location(programs.synthesis, "uDynamicBlur"), if (dynamicBlur) 1 else 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        private inline fun timed(stage: Stage, block: () -> Unit) {
            if (timingObserver == null) return block()
            val start = System.nanoTime()
            block()
            // Observer mode intentionally fences, so the value represents completed GPU work.
            GLES30.glFinish()
            timingObserver.invoke(StageTiming(stage, System.nanoTime() - start))
        }

        private fun fixedSamplers() {
            sampler(programs.luma, "uSource", 0); sampler(programs.downsample, "uFine", 0)
            sampler(programs.search, "uCurrent", 0); sampler(programs.search, "uNeighbor", 1); sampler(programs.search, "uSeed", 2)
            sampler(programs.validate, "uBackward", 0); sampler(programs.validate, "uForward", 1); sampler(programs.validate, "uCurrent", 2)
            sampler(programs.validate, "uPrevious", 3); sampler(programs.validate, "uNext", 4)
            sampler(programs.sceneGate, "uEvidence", 0)
            sampler(programs.synthesis, "uCurrent", 0); sampler(programs.synthesis, "uField", 1)
            sampler(programs.synthesis, "uEvidence", 2); sampler(programs.synthesis, "uSceneGate", 3)
        }

        private fun sampler(program: Int, name: String, unit: Int) { GLES30.glUseProgram(program); GLES30.glUniform1i(location(program, name), unit) }
        private fun location(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name).also { check(it >= 0) { "Missing uniform $name" } }
        private fun uniform2i(program: Int, name: String, level: Level) = GLES30.glUniform2i(location(program, name), level.width, level.height)
        private fun bind(unit: Int, texture: Int) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture) }
        private fun dispatch(size: Level, local: Int) { GLES31.glDispatchCompute(ceilDiv(size.width, local), ceilDiv(size.height, local), 1); barrier() }
        private fun barrier() = GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)

        private fun allocate(texture: Int, internal: Int, format: Int, size: Level, filter: Int) {
            check(texture != 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internal, size.width, size.height)
        }

        override fun close() {
            if (released) return
            released = true
            programs.close()
            luma.forEach { GLES30.glDeleteTextures(it.size, it, 0) }
            GLES30.glDeleteTextures(backward.size, backward, 0); GLES30.glDeleteTextures(forward.size, forward, 0)
            GLES30.glDeleteTextures(1, field, 0); GLES30.glDeleteTextures(1, evidence, 0)
            GLES30.glDeleteTextures(1, sceneGate, 0); GLES30.glDeleteTextures(1, output, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    companion object {
        const val BLOCK_SIZE = 8
        const val OVERLAP = 4
        const val PYRAMID_LEVELS = 4
        const val MAX_PYRAMID_LEVELS = 6

        fun gridFor(width: Int, height: Int): Grid {
            require(width > 0 && height > 0)
            val pitch = BLOCK_SIZE - OVERLAP
            return Grid(maxOf(1, ceilDiv(width - OVERLAP, pitch)), maxOf(1, ceilDiv(height - OVERLAP, pitch)))
        }

        fun pyramidFor(width: Int, height: Int, levelCount: Int = PYRAMID_LEVELS): List<Level> {
            require(width > 0 && height > 0)
            require(levelCount in 1..MAX_PYRAMID_LEVELS)
            return buildList {
                var w = width; var h = height
                repeat(levelCount) { add(Level(w, h)); w = ceilDiv(w, 2); h = ceilDiv(h, 2) }
            }
        }

        fun gridsForPyramid(width: Int, height: Int, levelCount: Int = PYRAMID_LEVELS): List<Grid> =
            pyramidFor(width, height, levelCount).map { gridFor(it.width, it.height) }

        fun flowParameters(config: GpuMotionBlurConfig): FlowParameters = FlowParameters(
            blur256 = (config.effectiveStrength * 256f / 200f).toInt(),
            pel = 2,
            prec = if (config.quality == MotionQuality.FAST) 2 else 1,
        )

        fun isHardSceneCut(badVectorFraction: Float, meanConfidence: Float): Boolean {
            require(badVectorFraction in 0f..1f && meanConfidence in 0f..1f)
            return badVectorFraction >= .75f || (badVectorFraction >= .50f && meanConfidence < .12f)
        }

        fun isHardVector(confidence: Float, consistencyError: Float, policy: SceneProtectionPolicy): Boolean {
            require(confidence in 0f..1f && consistencyError.isFinite() && consistencyError >= 0f)
            return confidence < policy.hardCutMinimumConfidence ||
                consistencyError > policy.hardCutMaximumConsistencyError
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = maxOf(1, (value + divisor - 1) / divisor)
        private fun checkGl(operation: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$operation failed: 0x${Integer.toHexString(error)}" }
        }
    }
}
