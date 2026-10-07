package dev.motionblur.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.nio.ByteBuffer

internal class VideoEncoder(private val width: Int, private val height: Int,
                            source: MediaFormat, private val muxer: MediaMuxer,
                            private val audio: AudioCopy?, private val start: Long,
                            private val guard: RenderGuard) : AutoCloseable {
    private val info = MediaCodec.BufferInfo()
    private val codec: MediaCodec
    private var videoTrack = -1
    private val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
    private var started = false
    private var ended = false
    private var lastOutputPts = -1L
    private var lastInputPts = -1L
    var frames = 0L
        private set

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        val fps = if (source.containsKey(MediaFormat.KEY_FRAME_RATE))
            (try { source.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            catch (_: ClassCastException) { source.getFloat(MediaFormat.KEY_FRAME_RATE) }).coerceIn(1f, 240f) else 30f
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        // FRAME_RATE is a rate-control hint only. queueInputBuffer always receives original PTS.
        format.setFloat(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE,
            (width.toDouble() * height * fps * 0.20).toLong().coerceIn(1_000_000, 100_000_000).toInt())
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        for (key in listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)) {
            if (source.containsKey(key)) format.setInteger(key, source.getInteger(key))
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw UnsupportedMediaException("No H.264 flexible-YUV encoder supports ${width}x$height at source resolution")
        codec = MediaCodec.createByCodecName(name)
        try { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() }
        catch (t: Throwable) { codec.release(); throw t }
    }

    fun frame(bytes: ByteArray, pts: Long) {
        guard.check()
        require(pts > lastInputPts) { "Encoder input PTS must strictly increase" }
        val index = input()
        val image = codec.getInputImage(index)
            ?: throw UnsupportedMediaException("Encoder cannot expose flexible-YUV input images")
        image.use { I420Images.write(it, bytes, width, height) }
        codec.queueInputBuffer(index, 0, I420Images.size(width, height), pts, 0)
        lastInputPts = pts
        drain(false)
    }

    private fun input(): Int {
        val deadline = guard.deadline()
        while (true) {
            guard.waiting(deadline)
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(false)
        }
    }

    fun finish(selectedDurationUs: Long) {
        val eosPtsUs = SelectedOutputInterval(0L, selectedDurationUs)
            .endOfStreamPtsUs(lastInputPts)
        val index = input()
        codec.queueInputBuffer(index, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        var deadline = guard.deadline()
        while (!ended) {
            guard.waiting(deadline)
            if (drain(true)) deadline = guard.deadline()
        }
        check(started && frames > 0) { "Encoder produced no video" }
        if (selectedDurationUs > lastOutputPts) {
            info.set(0, 0, selectedDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(videoTrack, ByteBuffer.allocateDirect(0), info)
        }
        audio?.finish(muxer, audioTrack)
        muxer.stop()
        started = false
    }

    private fun drain(wait: Boolean): Boolean {
        var activity = false
        while (!ended) {
            guard.check()
            val index = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return activity
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Encoder changed format after mux start" }
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start(); started = true; activity = true
                }
                index >= 0 -> {
                    try {
                        activity = true
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started) { "Encoded frame arrived before format" }
                            requireMedia(info.presentationTimeUs > lastOutputPts,
                                "Encoder reordered presentation timestamps despite baseline profile")
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            audio?.pump(muxer, audioTrack, info.presentationTimeUs + start)
                            muxer.writeSampleData(videoTrack, buffer, info)
                            lastOutputPts = info.presentationTimeUs
                            frames++
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
                else -> return activity
            }
        }
        return activity
    }

    override fun close() {
        try { codec.stop() } finally { codec.release() }
    }
}
