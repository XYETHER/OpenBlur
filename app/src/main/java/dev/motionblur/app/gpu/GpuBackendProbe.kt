package dev.motionblur.app.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import dev.motionblur.app.media.UnsupportedMediaException
import dev.motionblur.app.render.RenderBackend
import dev.motionblur.app.render.RenderBackendCapabilities
import dev.motionblur.app.render.RenderBackendPolicy

/**
 * Worker-thread-owned ES 3 EGL context used by the zero-copy media pipeline.
 *
 * The selected config is both pbuffer and window capable and includes
 * EGL_RECORDABLE_ANDROID, so [createRecordableWindowSurface] can safely wrap a MediaCodec input
 * surface while sharing this exact context and its GL_TEXTURE_2D objects.
 */
internal class GpuBackendProbe : AutoCloseable {
    data class Capabilities(
        val renderer: String,
        val version: String,
        val major: Int,
        val minor: Int,
        val externalOes: Boolean,
        val recordableSurface: Boolean,
    )

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    val capabilities: Capabilities

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { eglError("eglInitialize") }
            config = chooseRecordableEs3Config()
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { eglError("eglCreateContext") }
            pbufferSurface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(pbufferSurface != EGL14.EGL_NO_SURFACE) { eglError("eglCreatePbufferSurface") }
            makeCurrent(pbufferSurface)
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            val match = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(glVersion)
                ?: throw IllegalStateException("Cannot parse GL version: $glVersion")
            val detected = Capabilities(
                renderer = renderer,
                version = glVersion,
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                externalOes = "GL_OES_EGL_image_external" in extensions,
                recordableSurface = true,
            )
            val decision = RenderBackendPolicy.decide(
                RenderBackendCapabilities(
                    esMajor = detected.major,
                    esMinor = detected.minor,
                    externalOes = detected.externalOes,
                    recordable = detected.recordableSurface,
                ),
            )
            if (decision.backend != RenderBackend.GPU) {
                throw UnsupportedMediaException(
                    "GPU rendering unavailable: ${decision.message ?: "the device failed the GPU capability probe"}",
                )
            }
            capabilities = detected
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun createRecordableWindowSurface(surface: Surface): EGLSurface {
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL context is closed" }
        return EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0).also {
            check(it != EGL14.EGL_NO_SURFACE) { eglError("eglCreateWindowSurface(recordable)") }
        }
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { eglError("eglMakeCurrent") }
    }

    fun makePbufferCurrent() = makeCurrent(pbufferSurface)

    fun present(surface: EGLSurface, presentationTimeNs: Long) {
        check(EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)) {
            eglError("eglPresentationTimeANDROID")
        }
        check(EGL14.eglSwapBuffers(display, surface)) { eglError("eglSwapBuffers") }
    }

    fun destroySurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            check(EGL14.eglDestroySurface(display, surface)) { eglError("eglDestroySurface") }
        }
    }

    private fun chooseRecordableEs3Config(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) {
            eglError("eglChooseConfig(recordable ES3)")
        }
        return configs[0]!!
    }

    private fun eglError(operation: String) = "$operation failed: 0x${Integer.toHexString(EGL14.eglGetError())}"

    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbufferSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        pbufferSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    }
}
