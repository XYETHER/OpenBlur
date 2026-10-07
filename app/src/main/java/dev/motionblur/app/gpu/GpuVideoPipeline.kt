package dev.motionblur.app.gpu

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.motionblur.app.logic.EditorDraft
import dev.motionblur.app.media.SelectedOutputInterval
import dev.motionblur.app.media.RenderGuard
import dev.motionblur.app.media.RenderResult
import dev.motionblur.app.media.UnsupportedMediaException
import dev.motionblur.app.media.checkSdr
import dev.motionblur.app.media.openExtractor
import dev.motionblur.app.media.requireMedia
import java.io.File
import java.nio.ByteBuffer

/** Surface decoder -> GLES texture -> Surface encoder pipeline. Compressed AAC is muxed unchanged. */
internal object GpuVideoPipeline {
    fun render(
        context: Context,
        uri: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        draft: EditorDraft = EditorDraft(),
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): RenderResult {
        require(startMs >= 0L && endMs > startMs && endMs <= Long.MAX_VALUE / 1_000L) {
            "Invalid trim range"
        }
        require(!output.exists()) { "Refusing to overwrite an existing output" }
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L
        val guard = RenderGuard(cancelled)
        guard.check()

        val videoExtractor = openExtractor(context, uri)
        var audioCopy: AacCopy? = null
        var muxer: MediaMuxer? = null
        var createdOutput = false
        var success = false
        try {
            val videoTracks = mutableListOf<Int>()
            val audioTracks = mutableListOf<Int>()
            for (track in 0 until videoExtractor.trackCount) {
                when {
                    videoExtractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true -> videoTracks += track
                    videoExtractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true -> audioTracks += track
                }
            }
            requireMedia(videoTracks.size == 1, "Exactly one video track is required")
            requireMedia(audioTracks.size <= 1, "Multiple audio tracks are unsupported; refusing to drop tracks")

            val videoTrack = videoTracks.single()
            val sourceFormat = videoExtractor.getTrackFormat(videoTrack)
            val dimensions = validateVideo(sourceFormat)
            val sourceRotation = sourceFormat.integerOr(MediaFormat.KEY_ROTATION, 0)
            val rotation = muxerRotation(sourceRotation)
            Log.i(TAG, "sourceRotation=$sourceRotation muxerRotation=$rotation mime=${sourceFormat.getString(MediaFormat.KEY_MIME)}")
            audioCopy = audioTracks.firstOrNull()?.let {
                AacCopy.open(context, uri, it, startUs, endUs, guard)
            }

            output.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create output directory" } }
            check(output.createNewFile()) { "Output appeared while render was starting" }
            createdOutput = true
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            activeMuxer.setOrientationHint(rotation)
            val audioTrack = audioCopy?.let { activeMuxer.addTrack(it.format) } ?: -1

            val outcome = decodeAndEncode(
                extractor = videoExtractor,
                track = videoTrack,
                sourceFormat = sourceFormat,
                width = dimensions.first,
                height = dimensions.second,
                muxer = activeMuxer,
                audio = audioCopy,
                audioTrack = audioTrack,
                startUs = startUs,
                endUs = endUs,
                draft = draft,
                guard = guard,
                progress = progress,
            )
            guard.check()
            activeMuxer.release()
            muxer = null
            check(output.length() > 0L) { "Output MP4 is empty" }
            progress(1f)
            success = true
            return RenderResult(
                file = output,
                frameCount = outcome.frameCount,
                firstSourcePtsUs = outcome.firstSourcePtsUs,
                lastSourcePtsUs = outcome.lastSourcePtsUs,
                audioCopied = outcome.audioCopied,
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { audioCopy?.close() }
            videoExtractor.release()
            if (!success && createdOutput) output.delete()
        }
    }

    private fun decodeAndEncode(
        extractor: MediaExtractor,
        track: Int,
        sourceFormat: MediaFormat,
        width: Int,
        height: Int,
        muxer: MediaMuxer,
        audio: AacCopy?,
        audioTrack: Int,
        startUs: Long,
        endUs: Long,
        draft: EditorDraft,
        guard: RenderGuard,
        progress: (Float) -> Unit,
    ): Outcome {
        extractor.selectTrack(track)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoderFormat = sourceFormat.apply { setInteger(MediaFormat.KEY_ROTATION, 0) }
        var submitted = 0L
        var firstSourcePtsUs = Long.MIN_VALUE
        var lastSourcePtsUs = Long.MIN_VALUE
        var publishedAtMs = 0L
        var decodeNanos = 0L
        var motionNanos = 0L
        var encodeNanos = 0L
        progress(0f)

        GpuFrameBridge(width, height).use { bridge ->
            val renderer = bridge.withSharedEglContext { egl ->
                egl.capabilities.renderer.also {
                    Log.i(TAG, "renderer=$it stage=egl-probe elapsedMs=0")
                }
            }
            val decoder = MediaCodec.createDecoderByType(requireNotNull(decoderFormat.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(decoderFormat, bridge.decoderSurface, null, 0)
                decoder.start()
                GpuTextureEncoder(bridge, muxer, encoderConfig(sourceFormat, width, height, draft), guard).use { encoder ->
                    MvToolsGpuProcessor(bridge, width, height).use { processor ->
                        val motionConfig = motionConfig(draft)
                    val info = MediaCodec.BufferInfo()
                    var inputEos = false
                    var outputEos = false
                    var previousDecodedPtsUs = Long.MIN_VALUE
                    var heldFrame: GpuFrameBridge.GpuFrame? = null
                    var lastRing: GpuFrameBridge.FrameRing? = null
                    var selectedFrames = 0L
                    var deadline = guard.deadline()

                    fun submit(textureId: Int, sourcePtsUs: Long, outputPtsUs: Long) {
                        guard.check()
                        val started = System.nanoTime()
                        encoder.encodeTexture(textureId, outputPtsUs * 1_000L)
                        encodeNanos += System.nanoTime() - started
                        if (submitted == 0L) firstSourcePtsUs = sourcePtsUs
                        lastSourcePtsUs = sourcePtsUs
                        submitted++
                        val now = SystemClock.elapsedRealtime()
                        if (now - publishedAtMs >= PROGRESS_INTERVAL_MS) {
                            progress(
                                ((sourcePtsUs - startUs).toDouble() / (endUs - startUs))
                                    .toFloat()
                                    .coerceIn(0f, 0.99f),
                            )
                            publishedAtMs = now
                        }
                    }

                    fun submitSelected(frame: GpuFrameBridge.GpuFrame, ring: GpuFrameBridge.FrameRing) {
                        val processRing = if (selectedFrames == 0L) {
                            // The first selected frame has no selected predecessor. Do not let a
                            // pre-trim decode warm the motion estimator or change its pixels.
                            GpuFrameBridge.FrameRing(previous = null, current = frame, next = ring.next)
                        } else {
                            ring
                        }
                        val started = System.nanoTime()
                        val texture = processor.process(processRing, motionConfig)
                        motionNanos += System.nanoTime() - started
                        submit(texture, frame.presentationTimeUs, frame.presentationTimeUs - startUs)
                        selectedFrames++
                    }

                    while (!outputEos) {
                        guard.waiting(deadline)
                        if (!inputEos) {
                            val inputIndex = decoder.dequeueInputBuffer(0)
                            if (inputIndex >= 0) {
                                val sampleTime = extractor.sampleTime
                                if (sampleTime < 0L || sampleTime >= endUs) {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        endUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputEos = true
                                } else {
                                    requireMedia(
                                        extractor.sampleFlags and (
                                            MediaExtractor.SAMPLE_FLAG_ENCRYPTED or
                                                MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
                                            ) == 0,
                                        "Encrypted/partial video is unsupported",
                                    )
                                    val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                                    input.clear()
                                    val size = extractor.readSampleData(input, 0)
                                    requireMedia(size >= 0 && size <= input.capacity(), "Compressed video sample exceeds decoder buffer")
                                    decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                                    extractor.advance()
                                }
                                deadline = guard.deadline()
                            }
                        }

                        when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                checkSdr(decoder.outputFormat)
                                val decoded = dimensions(decoder.outputFormat)
                                requireMedia(decoded.first == width && decoded.second == height, "Decoder changed frame dimensions")
                                deadline = guard.deadline()
                            }
                            else -> if (outputIndex >= 0) {
                                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                val hasFrame = info.size > 0 && !codecConfig && info.presentationTimeUs >= 0L
                                val sourcePtsUs = info.presentationTimeUs
                                // Import pre-trim frames too: the final one remains visually held at
                                // the trim boundary until the first frame whose timestamp is >= start.
                                val render = hasFrame && sourcePtsUs < endUs
                                if (hasFrame) {
                                    requireMedia(sourcePtsUs > previousDecodedPtsUs, "Decoder returned non-increasing presentation timestamps")
                                    previousDecodedPtsUs = sourcePtsUs
                                }
                                decoder.releaseOutputBuffer(outputIndex, render)
                                if (render) {
                                    val decodeStarted = System.nanoTime()
                                    val ring = awaitFrame(bridge, guard, sourcePtsUs)
                                    decodeNanos += System.nanoTime() - decodeStarted
                                    lastRing = ring
                                    if (sourcePtsUs < startUs) {
                                        heldFrame = ring.next
                                    } else {
                                        if (submitted == 0L && sourcePtsUs > startUs) {
                                            val currentPts = ring.current?.presentationTimeUs
                                            // If the decoder has now exposed an exact trim-start frame as
                                            // ring.current, it replaces the held pre-trim frame. Otherwise
                                            // emit the held frame at output PTS zero before the first
                                            // strictly-later source frame.
                                            if (currentPts == null || currentPts > startUs) {
                                                heldFrame?.let { held ->
                                                    submit(held.textureId, startUs, 0L)
                                                }
                                            }
                                        }
                                        // The incoming texture is ring.next. Encode ring.current
                                        // only after its next neighbor exists so the processor gets
                                        // previous/current/next textures in temporal order.
                                        ring.current?.takeIf { it.presentationTimeUs >= startUs }?.let { current ->
                                            submitSelected(current, ring)
                                        }
                                    }
                                }
                                outputEos = endOfStream
                                if (outputEos) {
                                    lastRing?.next?.takeIf { it.presentationTimeUs >= startUs && it.presentationTimeUs < endUs }?.let { tail ->
                                        submitSelected(
                                            tail,
                                            GpuFrameBridge.FrameRing(
                                                previous = lastRing?.current,
                                                current = tail,
                                                next = null,
                                            ),
                                        )
                                    }
                                    if (submitted == 0L) {
                                        heldFrame?.let { held ->
                                            submit(held.textureId, startUs, 0L)
                                        }
                                    }
                                }
                                deadline = guard.deadline()
                            }
                        }
                    }
                    requireMedia(submitted > 0L, "No frames inside selected trim range")
                    encoder.finish(SelectedOutputInterval(startUs, endUs).durationUs)
                    check(encoder.frames == submitted) {
                        "Encoder dropped frames: submitted $submitted, wrote ${encoder.frames}"
                    }
                    val copiedAudio = if (audio != null) {
                        val audioStarted = System.nanoTime()
                        audio.copyTo(muxer, audioTrack)
                        Log.i(TAG, "renderer=$renderer stage=audio elapsedMs=${elapsedMs(System.nanoTime() - audioStarted)}")
                        true
                    } else {
                        false
                    }
                    muxer.stop()
                    Log.i(TAG, "renderer=$renderer stage=decode elapsedMs=${elapsedMs(decodeNanos)} frames=$submitted")
                    Log.i(TAG, "renderer=$renderer stage=motion elapsedMs=${elapsedMs(motionNanos)}")
                    Log.i(TAG, "renderer=$renderer stage=encode elapsedMs=${elapsedMs(encodeNanos)}")
                    return Outcome(encoder.frames, firstSourcePtsUs, lastSourcePtsUs, copiedAudio)
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        }
    }

    private fun awaitFrame(
        bridge: GpuFrameBridge,
        guard: RenderGuard,
        presentationTimeUs: Long,
    ): GpuFrameBridge.FrameRing {
        val deadline = guard.deadline()
        while (true) {
            guard.waiting(deadline)
            try {
                bridge.awaitFrame(
                    FRAME_WAIT_SLICE_MS,
                    cancelled = {
                        runCatching { guard.check() }.isFailure
                    },
                    presentationTimeUs = presentationTimeUs,
                )?.let { return it }
            } catch (interrupted: InterruptedException) {
                guard.check()
                throw IllegalStateException("Interrupted while importing decoder frame", interrupted)
            }
        }
    }

    private fun muxerRotation(sourceRotation: Int): Int =
        sourceRotation.also {
            requireMedia(it in setOf(0, 90, 180, 270), "Unsupported rotation metadata")
        }

    private fun validateVideo(format: MediaFormat): Pair<Int, Int> {
        checkSdr(format)
        val sarWidth = format.integerOr("sar-width", 1)
        val sarHeight = format.integerOr("sar-height", 1)
        requireMedia(sarWidth == sarHeight, "Non-square-pixel video is unsupported")
        val rotation = format.integerOr(MediaFormat.KEY_ROTATION, 0)
        requireMedia(rotation in setOf(0, 90, 180, 270), "Unsupported rotation metadata")
        return dimensions(format)
    }

    private fun dimensions(format: MediaFormat): Pair<Int, Int> {
        val width = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        } else {
            format.getInteger(MediaFormat.KEY_WIDTH)
        }
        val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        requireMedia(width > 0 && height > 0, "Invalid video dimensions")
        return width to height
    }

    private fun encoderConfig(source: MediaFormat, width: Int, height: Int, draft: EditorDraft): GpuTextureEncoder.Config {
        val frameRate = if (source.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                source.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (_: ClassCastException) {
                source.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
            }
        } else {
            30
        }.coerceIn(1, 240)
        fun color(key: String) = if (source.containsKey(key)) source.getInteger(key) else null
        val sourceBitRate = if (source.containsKey(MediaFormat.KEY_BIT_RATE)) {
            source.getInteger(MediaFormat.KEY_BIT_RATE)
        } else {
            null
        }
        return GpuTextureEncoder.Config(
            width = width,
            height = height,
            bitRate = draft.bitrateMbps?.let { (it * 1_000_000f).toInt() }
                ?: GpuEncodingPolicy.targetBitRate(width, height, frameRate, sourceBitRate),
            mime = draft.videoCodec.mime,
            encodingEffort = draft.encodingEffort,
            frameRate = frameRate,
            colorStandard = color(MediaFormat.KEY_COLOR_STANDARD),
            colorRange = color(MediaFormat.KEY_COLOR_RANGE),
            colorTransfer = color(MediaFormat.KEY_COLOR_TRANSFER),
        )
    }

    private fun motionConfig(draft: EditorDraft): GpuMotionBlurConfig {
        require(draft.times == 1) { "GPU motion blur supports one dynamic pass; repeated passes are not available" }
        val quality = when (draft.quality) {
            "Fast" -> MotionQuality.FAST
            "Balanced" -> MotionQuality.BALANCED
            "Quality" -> MotionQuality.QUALITY
            "Ultra quality" -> MotionQuality.ULTRA
            else -> throw IllegalArgumentException("Unknown GPU motion quality: ${draft.quality}")
        }
        val level = when (draft.preset) {
            "Dynamic Light" -> DynamicBlurLevel.LIGHT
            "Dynamic Medium" -> DynamicBlurLevel.MEDIUM
            "Dynamic Strong" -> DynamicBlurLevel.STRONG
            "Dynamic Extreme" -> DynamicBlurLevel.EXTREME
            "Custom" -> when {
                draft.strength <= DynamicBlurLevel.LIGHT.strengthCap -> DynamicBlurLevel.LIGHT
                draft.strength <= DynamicBlurLevel.MEDIUM.strengthCap -> DynamicBlurLevel.MEDIUM
                draft.strength <= DynamicBlurLevel.STRONG.strengthCap -> DynamicBlurLevel.STRONG
                else -> DynamicBlurLevel.EXTREME
            }
            else -> throw IllegalArgumentException("GPU requires a dynamic blur preset: ${draft.preset}")
        }
        require(draft.strength.isFinite() && draft.strength in 10f..200f) {
            "Invalid GPU blur strength: ${draft.strength}"
        }
        return GpuMotionBlurConfig(quality, level, draft.strength, dynamicBlur = draft.dynamicBlur)
    }

    private fun elapsedMs(nanos: Long): Long = nanos / 1_000_000L

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private data class Outcome(
        val frameCount: Long,
        val firstSourcePtsUs: Long,
        val lastSourcePtsUs: Long,
        val audioCopied: Boolean,
    )

    private class AacCopy private constructor(
        private val extractor: MediaExtractor,
        val format: MediaFormat,
        private val startUs: Long,
        private val endUs: Long,
        private val guard: RenderGuard,
    ) : AutoCloseable {
        private val buffer: ByteBuffer
        private val info = MediaCodec.BufferInfo()

        init {
            val maximum = format.integerOr(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_AUDIO_BUFFER_BYTES)
            requireMedia(maximum in 1..MAX_AUDIO_BUFFER_BYTES, "Audio packet allocation exceeds safety limit")
            buffer = ByteBuffer.allocateDirect(maximum)
        }

        fun copyTo(muxer: MediaMuxer, track: Int) {
            check(track >= 0) { "AAC mux track was not added" }
            var lastPtsUs = Long.MIN_VALUE
            var samples = 0L
            while (extractor.sampleTime >= startUs && extractor.sampleTime < endUs) {
                guard.check()
                val sourcePtsUs = extractor.sampleTime
                requireMedia(sourcePtsUs > lastPtsUs, "Non-increasing audio timestamps")
                requireMedia(
                    extractor.sampleFlags and (
                        MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
                        ) == 0,
                    "Encrypted/partial audio is unsupported",
                )
                buffer.clear()
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (error: IllegalArgumentException) {
                    throw UnsupportedMediaException("Cannot read audio packet within bounded buffer: ${error.message}")
                }
                requireMedia(size in 0..buffer.capacity(), "Audio packet exceeds declared maximum")
                val muxerFlags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, sourcePtsUs - startUs, muxerFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(track, buffer, info)
                lastPtsUs = sourcePtsUs
                samples++
                extractor.advance()
            }
            check(samples > 0L) { "Preflighted AAC track became empty" }
            val sourceEnd = if (format.containsKey(MediaFormat.KEY_DURATION))
                minOf(endUs, format.getLong(MediaFormat.KEY_DURATION)) else endUs
            val outputEnd = sourceEnd - startUs
            if (outputEnd > lastPtsUs - startUs) {
                info.set(0, 0, outputEnd, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), info)
            }
        }

        override fun close() = extractor.release()

        companion object {
            fun open(
                context: Context,
                uri: Uri,
                track: Int,
                startUs: Long,
                endUs: Long,
                guard: RenderGuard,
            ): AacCopy? {
                val extractor = openExtractor(context, uri)
                try {
                    val format = extractor.getTrackFormat(track)
                    requireMedia(
                        format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC,
                        "Only AAC compressed audio passthrough is supported; audio will not be silently removed",
                    )
                    extractor.selectTrack(track)
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (extractor.sampleTime >= 0L && extractor.sampleTime < startUs) {
                        guard.check()
                        extractor.advance()
                    }
                    if (extractor.sampleTime < 0L || extractor.sampleTime >= endUs) {
                        extractor.release()
                        return null
                    }
                    return AacCopy(extractor, format, startUs, endUs, guard)
                } catch (failure: Throwable) {
                    extractor.release()
                    throw failure
                }
            }
        }
    }

    private const val TAG = "OpenBlurS24"
    private const val FRAME_WAIT_SLICE_MS = 100L
    private const val PROGRESS_INTERVAL_MS = 150L
    private const val DEFAULT_AUDIO_BUFFER_BYTES = 1_048_576
    private const val MAX_AUDIO_BUFFER_BYTES = 16_777_216
}
