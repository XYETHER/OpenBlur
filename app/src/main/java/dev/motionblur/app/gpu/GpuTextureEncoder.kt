package dev.motionblur.app.gpu

import dev.motionblur.app.media.RenderGuard
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes bridge-owned GL_TEXTURE_2D frames into an AVC MP4 without copying pixels to CPU memory.
 *
 * Every EGL, GLES, and MediaCodec call runs on the owning [GpuFrameBridge] worker.
 * The encoder EGL window surface is created from the bridge's EGL display/config/context, so texture
 * names returned by [GpuFrameBridge.awaitFrame] can be sampled directly. Call [finish] once after
 * the last frame, then [close] before closing the bridge. The caller owns [muxer], may add other
 * tracks before video output format is available, and must stop/release it after [finish].
 */
internal class GpuTextureEncoder(
    private val bridge: GpuFrameBridge,
    private val muxer: MediaMuxer,
    private val config: Config,
    private val guard: RenderGuard = RenderGuard { false },
) : AutoCloseable {
    data class Config(
        val width: Int,
        val height: Int,
        val bitRate: Int,
        val frameRate: Int,
        val iFrameIntervalSeconds: Int = 1,
        val profile: Int? = null,
        val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        val encodingEffort: Float? = null,
        val colorStandard: Int? = null,
        val colorRange: Int? = null,
        val colorTransfer: Int? = null,
    ) {
        init {
            require(width > 0 && height > 0) { "Encoder dimensions must be positive" }
            require(bitRate > 0) { "Encoder bitrate must be positive" }
            require(frameRate > 0) { "Encoder frame rate must be positive" }
            require(iFrameIntervalSeconds >= 0) { "I-frame interval must not be negative" }
        }
    }

    private val closed = AtomicBoolean(false)
    private lateinit var resources: Resources

    val frames: Long
        get() = if (::resources.isInitialized) resources.frames else 0L

    init {
        try {
            resources = bridge.withSharedEglContext { egl -> Resources(egl) }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** Draws [textureId] as a full-frame GL_TEXTURE_2D and submits [presentationTimeNs] to AVC. */
    fun encodeTexture(textureId: Int, presentationTimeNs: Long) {
        check(!closed.get()) { "GpuTextureEncoder is closed" }
        require(textureId != 0) { "textureId must be a valid GL_TEXTURE_2D name" }
        require(presentationTimeNs >= 0L) { "presentationTimeNs must not be negative" }
        bridge.withSharedEglContext { resources.encode(textureId, presentationTimeNs) }
    }

    /** Signals MediaCodec input EOS once and drains through codec EOS, finalizing the MP4 muxer. */
    fun finish(selectedDurationUs: Long? = null) {
        check(!closed.get()) { "GpuTextureEncoder is closed" }
        bridge.withSharedEglContext { resources.finish(selectedDurationUs) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!::resources.isInitialized) return
        runCatching { bridge.withSharedEglContext { resources.close() } }
    }

    private inner class Resources(private val egl: GpuBackendProbe) : AutoCloseable {
        private val drainState = EncoderDrainState()
        private val bufferInfo = MediaCodec.BufferInfo()
        private var codec: MediaCodec? = null
        private var inputSurface: Surface? = null
        private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var renderer: Texture2dRenderer? = null
        private var muxerTrack = -1
        private var muxerStarted = false
        private var released = false
        private var finished = false
        private var lastInputPresentationTimeNs = -1L
        private var lastOutputPtsUs = Long.MIN_VALUE
        var frames = 0L
            private set

        init {
            try {
                val format = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
                    config.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    config.colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                    config.colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                    config.colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                }
                val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
                    candidate.isEncoder && hardwareEncoding(candidate) && candidate.supportedTypes.any { it.equals(config.mime, true) } &&
                        runCatching { candidate.getCapabilitiesForType(config.mime).let { caps ->
                            caps.isFormatSupported(format) && (config.encodingEffort == null || caps.encoderCapabilities.complexityRange.let { it.upper > it.lower })
                        } }.getOrDefault(false)
                } ?: throw dev.motionblur.app.media.UnsupportedMediaException("No compatible hardware ${if (config.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264"} encoder for these dimensions and bitrate")
                val caps = info.getCapabilitiesForType(config.mime)
                val preferredProfile = if (config.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                if (config.profile == null && caps.profileLevels.any { it.profile == preferredProfile }) format.setInteger(MediaFormat.KEY_PROFILE, preferredProfile)
                if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                applyEncodingEffort(format, info, config.encodingEffort)
                codec = MediaCodec.createByCodecName(info.name).also {
                    it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    inputSurface = it.createInputSurface()
                    encoderSurface = egl.createRecordableWindowSurface(requireNotNull(inputSurface))
                    egl.makeCurrent(encoderSurface)
                    renderer = Texture2dRenderer()
                    it.start()
                }
                egl.makePbufferCurrent()
                drain(endOfStream = false)
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        fun encode(textureId: Int, presentationTimeNs: Long) {
            check(!released) { "Encoder resources are closed" }
            check(!finished) { "Cannot encode after finish" }
            require(presentationTimeNs > lastInputPresentationTimeNs) {
                "Encoder input PTS must strictly increase"
            }
            egl.makeCurrent(encoderSurface)
            renderer!!.draw(textureId, config.width, config.height)
            egl.present(encoderSurface, presentationTimeNs)
            lastInputPresentationTimeNs = presentationTimeNs
            egl.makePbufferCurrent()
            drain(endOfStream = false)
        }

        fun finish(selectedDurationUs: Long?) {
            check(!released) { "Encoder resources are closed" }
            if (finished) return
            if (drainState.requestInputEos()) codec!!.signalEndOfInputStream()
            drain(endOfStream = true)
            check(finished) { "Encoder did not return EOS" }
            if (selectedDurationUs != null && selectedDurationUs > lastOutputPtsUs && frames > 0) {
                bufferInfo.set(0, 0, selectedDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(muxerTrack, java.nio.ByteBuffer.allocateDirect(0), bufferInfo)
            }
        }

        private fun drain(endOfStream: Boolean) {
            var deadline = guard.deadline()
            while (true) {
                if (endOfStream) guard.waiting(deadline) else guard.check()
                val outputIndex = codec!!.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed twice" }
                        muxerTrack = muxer.addTrack(codec!!.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        deadline = guard.deadline()
                        val outputBuffer = requireNotNull(codec!!.getOutputBuffer(outputIndex))
                        try {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                                check(muxerStarted) { "Encoded data arrived before muxer format" }
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                check(bufferInfo.presentationTimeUs > lastOutputPtsUs) { "Encoder returned non-increasing timestamps" }
                                muxer.writeSampleData(muxerTrack, outputBuffer, bufferInfo)
                                lastOutputPtsUs = bufferInfo.presentationTimeUs
                                frames++
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                check(drainState.acceptCodecEos()) { "Unexpected encoder EOS" }
                                finished = true
                            }
                        } finally {
                            codec!!.releaseOutputBuffer(outputIndex, false)
                        }
                        if (finished) return
                    }
                    else -> throw IllegalStateException("Unexpected MediaCodec dequeue result: $outputIndex")
                }
            }
        }

        override fun close() {
            if (released) return
            released = true
            runCatching { egl.makePbufferCurrent() }
            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { egl.destroySurface(encoderSurface) }
                encoderSurface = EGL14.EGL_NO_SURFACE
            }
            renderer?.close()
            renderer = null
            inputSurface?.release()
            inputSurface = null
            codec?.let { encoder ->
                runCatching { encoder.stop() }
                encoder.release()
            }
            codec = null
        }
    }

    /** Full-screen GLES renderer for bridge-owned GL_TEXTURE_2D textures. */
    private class Texture2dRenderer : AutoCloseable {
        private val program: Int
        private val sourceLocation: Int

        init {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES30.glCreateProgram().also { created ->
                try {
                    GLES30.glAttachShader(created, vertex)
                    GLES30.glAttachShader(created, fragment)
                    GLES30.glLinkProgram(created)
                    val status = IntArray(1)
                    GLES30.glGetProgramiv(created, GLES30.GL_LINK_STATUS, status, 0)
                    check(status[0] != 0) { "Texture renderer link failed: ${GLES30.glGetProgramInfoLog(created)}" }
                } finally {
                    GLES30.glDeleteShader(vertex)
                    GLES30.glDeleteShader(fragment)
                }
            }
            sourceLocation = GLES30.glGetUniformLocation(program, "uSource")
            check(sourceLocation >= 0) { "Texture renderer source uniform is unavailable" }
        }

        fun draw(textureId: Int, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glUniform1i(sourceLocation, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGl("draw texture")
        }

        override fun close() {
            GLES30.glDeleteProgram(program)
        }

        private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Texture renderer shader failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
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
            precision mediump float;
            uniform sampler2D uSource;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = texture(uSource, vTexCoord); }
        """

        fun checkGl(operation: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$operation failed: 0x${Integer.toHexString(error)}" }
        }
    }
}
