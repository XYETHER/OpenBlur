package dev.motionblur.app.gpu

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Imports decoder frames directly into three reusable 2D GLES textures.
 *
 * All EGL and GLES calls are confined to [worker]. Feed [decoderSurface] to a MediaCodec decoder,
 * release decoder output buffers with `render = true`, then call [awaitFrame] from any thread. The
 * returned textures are owned by this bridge and remain valid until their slot is reused (at most
 * three subsequent successful imports). Call [close] before releasing the decoder Surface.
 */
internal class GpuFrameBridge(
    width: Int,
    height: Int,
) : AutoCloseable {
    data class FrameRing(
        val previous: GpuFrame?,
        val current: GpuFrame?,
        val next: GpuFrame?,
    )

    /** Metadata for a GL_TEXTURE_2D frame. Its transform applies to the source OES import. */
    class GpuFrame internal constructor(val textureId: Int) {
        var timestampNs: Long = Long.MIN_VALUE
            internal set
        /** Decoder presentation timestamp, in microseconds, supplied by the orchestrator. */
        var presentationTimeUs: Long = Long.MIN_VALUE
            internal set
        val transformMatrix: FloatArray = FloatArray(16)
    }

    private val worker = HandlerThread("OpenBlurGpuFrameBridge")
    private val closed = AtomicBoolean(false)
    private val frameLock = Object()
    private lateinit var handler: Handler
    private lateinit var resources: Resources

    /** Surface passed as the output target when configuring the decoder. */
    val decoderSurface: Surface
        get() {
            check(!closed.get()) { "GpuFrameBridge is closed" }
            return resources.decoderSurface
        }

    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        worker.start()
        handler = Handler(worker.looper)
        try {
            resources = onWorker { Resources(width, height) }
        } catch (t: Throwable) {
            closed.set(true)
            worker.quitSafely()
            worker.joinUnchecked()
            throw t
        }
    }

    /**
     * Waits for one decoder frame, imports it with updateTexImage, and returns the temporal ring.
     * Waiting uses short bounded intervals so [cancelled] is observed even when no frame arrives.
     * A timeout returns null; cancellation throws [InterruptedException].
     */
    @Throws(InterruptedException::class)
    fun awaitFrame(
        timeoutMs: Long,
        cancelled: () -> Boolean = { false },
        presentationTimeUs: Long? = null,
    ): FrameRing? {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        check(!closed.get()) { "GpuFrameBridge is closed" }
        return try {
            onWorker { resources.awaitFrame(timeoutMs, cancelled, presentationTimeUs) }
        } catch (t: Throwable) {
            if (t !is InterruptedException) close()
            throw t
        }
    }

    /**
     * Runs GPU work on the bridge's owning thread with its ES3 context current.
     *
     * This is intentionally internal: GL texture names are context-local and must not escape to a
     * second EGL context or arbitrary thread. [GpuTextureEncoder] uses it to attach a recordable
     * window surface to this context, preserving zero-copy texture sharing.
     */
    internal fun <T> withSharedEglContext(block: (GpuBackendProbe) -> T): T {
        check(!closed.get()) { "GpuFrameBridge is closed" }
        return onWorker { resources.withEgl(block) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            onWorker { resources.close() }
        } finally {
            worker.quitSafely()
            worker.joinUnchecked()
        }
    }

    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread() === worker) return block()
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        check(handler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }) { "GPU worker is not accepting work" }
        try {
            done.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for GPU worker", e)
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private inner class Resources(private val width: Int, private val height: Int) : AutoCloseable {
        private lateinit var egl: GpuBackendProbe
        private var oesTexture = 0
        private lateinit var surfaceTexture: SurfaceTexture
        lateinit var decoderSurface: Surface
            private set
        private val framebuffer = IntArray(1)
        private val textures = IntArray(FrameRingOrder.SLOT_COUNT)
        private lateinit var frames: Array<GpuFrame>
        private val ringOrder = FrameRingOrder(FrameRingOrder.SLOT_COUNT)
        private var observedSignal = 0L
        private var consumedSignal = 0L
        private var closedResources = false
        private var program = 0
        private var sourceLocation = -1
        private var transformLocation = -1

        init {
            try {
                egl = GpuBackendProbe()
                oesTexture = createExternalTexture()
                surfaceTexture = SurfaceTexture(oesTexture)
                decoderSurface = Surface(surfaceTexture)
                surfaceTexture.setDefaultBufferSize(width, height)
                surfaceTexture.setOnFrameAvailableListener {
                    synchronized(frameLock) {
                        observedSignal++
                        frameLock.notifyAll()
                    }
                }
                program = createProgram()
                sourceLocation = GLES30.glGetUniformLocation(program, "uSource")
                transformLocation = GLES30.glGetUniformLocation(program, "uTransform")
                check(sourceLocation >= 0 && transformLocation >= 0) { "GPU blit uniforms are unavailable" }
                GLES30.glGenFramebuffers(1, framebuffer, 0)
                checkGl("glGenFramebuffers")
                GLES30.glGenTextures(textures.size, textures, 0)
                checkGl("glGenTextures")
                textures.forEach { texture -> allocateTexture(texture, width, height) }
                frames = Array(textures.size) { GpuFrame(textures[it]) }
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        fun <T> withEgl(block: (GpuBackendProbe) -> T): T {
            checkWorker()
            check(!closedResources) { "GPU resources are closed" }
            egl.makePbufferCurrent()
            return block(egl)
        }

        @Throws(InterruptedException::class)
        fun awaitFrame(
            timeoutMs: Long,
            cancelled: () -> Boolean,
            presentationTimeUs: Long?,
        ): FrameRing? {
            checkWorker()
            val deadlineNs = SystemClock.elapsedRealtimeNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            synchronized(frameLock) {
                while (observedSignal == consumedSignal) {
                    check(!closed.get()) { "GpuFrameBridge is closed" }
                    if (cancelled()) throw InterruptedException("GPU frame wait cancelled")
                    val remainingNs = deadlineNs - SystemClock.elapsedRealtimeNanos()
                    if (remainingNs <= 0L) return null
                    val waitMs = min(TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L), WAIT_SLICE_MS)
                    frameLock.wait(waitMs)
                }
                if (cancelled()) throw InterruptedException("GPU frame wait cancelled")
                consumedSignal = observedSignal
            }
            surfaceTexture.updateTexImage()
            checkGl("updateTexImage")
            val indices = ringOrder.push()
            val incoming = indices.next ?: error("Frame ring did not provide an incoming slot")
            val frame = frames[incoming]
            surfaceTexture.getTransformMatrix(frame.transformMatrix)
            frame.timestampNs = surfaceTexture.timestamp
            frame.presentationTimeUs = presentationTimeUs ?: frame.timestampNs / 1_000L
            blitOesTo2d(frame.textureId, frame.transformMatrix)
            return FrameRing(
                previous = indices.previous?.let(frames::get),
                current = indices.current?.let(frames::get),
                next = frame,
            )
        }

        private fun blitOesTo2d(destination: Int, transform: FloatArray) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                destination,
                0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "GPU frame framebuffer is incomplete"
            }
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(sourceLocation, 0)
            GLES30.glUniformMatrix4fv(transformLocation, 1, false, transform, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            checkGl("blitOesTo2d")
        }

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            checkGl("createExternalTexture")
            return ids[0]
        }

        private fun allocateTexture(texture: Int, width: Int, height: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGl("allocateTexture")
        }

        private fun createProgram(): Int {
            val vertex = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            return GLES30.glCreateProgram().also { created ->
                try {
                    GLES30.glAttachShader(created, vertex)
                    GLES30.glAttachShader(created, fragment)
                    GLES30.glLinkProgram(created)
                    val linked = IntArray(1)
                    GLES30.glGetProgramiv(created, GLES30.GL_LINK_STATUS, linked, 0)
                    check(linked[0] != 0) { "GPU blit link failed: ${GLES30.glGetProgramInfoLog(created)}" }
                } finally {
                    GLES30.glDeleteShader(vertex)
                    GLES30.glDeleteShader(fragment)
                }
            }
        }

        private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { "GPU blit shader failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        }

        override fun close() {
            if (closedResources) return
            closedResources = true
            synchronized(frameLock) { frameLock.notifyAll() }
            if (::surfaceTexture.isInitialized) {
                surfaceTexture.setOnFrameAvailableListener(null)
                if (::decoderSurface.isInitialized) decoderSurface.release()
                surfaceTexture.release()
            }
            if (::egl.isInitialized) {
                if (program != 0) GLES30.glDeleteProgram(program)
                if (framebuffer[0] != 0) GLES30.glDeleteFramebuffers(1, framebuffer, 0)
                GLES30.glDeleteTextures(textures.size, textures, 0)
                if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                egl.close()
            }
        }

        private fun checkWorker() = check(Thread.currentThread() === worker) { "GLES accessed off GPU worker" }
    }

    private fun HandlerThread.joinUnchecked() {
        try {
            join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$operation failed: 0x${Integer.toHexString(error)}" }
    }

    private companion object {
        const val WAIT_SLICE_MS = 25L
        const val VERTEX_SHADER = """
            #version 300 es
            out vec2 vTexCoord;
            void main() {
                vec2 position = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID & 2) - 1));
                vTexCoord = position * 0.5 + 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """
        const val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uSource;
            uniform mat4 uTransform;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = texture(uSource, (uTransform * vec4(vTexCoord, 0.0, 1.0)).xy); }
        """
    }
}
