package dev.motionblur.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

internal fun openExtractor(context: Context, uri: Uri): MediaExtractor {
    val extractor = MediaExtractor()
    try { extractor.setDataSource(context, uri, null); return extractor }
    catch (t: Throwable) { extractor.release(); throw t }
}

internal fun checkSdr(format: MediaFormat) {
    val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
    // Qualcomm exposes a 25-byte all-zero HDR descriptor even for SDR video.
    val hdr = if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO))
        format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.duplicate() else null
    var hasHdrMetadata = false
    while (hdr != null && hdr.hasRemaining()) {
        if (hdr.get().toInt() != 0) { hasHdrMetadata = true; break }
    }
    requireMedia(transfer != MediaFormat.COLOR_TRANSFER_ST2084 &&
        transfer != MediaFormat.COLOR_TRANSFER_HLG && !hasHdrMetadata,
        "HDR input requires a tone-mapping pipeline and is not supported")
    val mime = format.getString(MediaFormat.KEY_MIME)
    requireMedia(mime in setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01", "video/raw"),
        "Unsupported video codec: $mime")
    if (mime in setOf("video/hevc", "video/x-vnd.on2.vp9", "video/av01")) {
        requireMedia(format.containsKey(MediaFormat.KEY_PROFILE),
            "Cannot establish 8-bit input profile; refusing a potentially lossy HDR/10-bit conversion")
    }
    // Whitelist known 8-bit profiles; decoder output is checked again.
    if (format.containsKey(MediaFormat.KEY_PROFILE)) {
        val p = format.getInteger(MediaFormat.KEY_PROFILE)
        val valid = when (mime) {
            "video/avc" -> p in setOf(1, 2, 4, 8, 0x10000, 0x80000)
            "video/hevc" -> p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            "video/x-vnd.on2.vp9" -> p == MediaCodecInfo.CodecProfileLevel.VP9Profile0
            "video/av01" -> p == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
            else -> true
        }
        requireMedia(valid, "Unsupported high-bit-depth or non-420 video profile: $p")
    }
    if (format.containsKey("bit-depth")) requireMedia(format.getInteger("bit-depth") == 8,
        "Only 8-bit video is supported")
}

internal class VideoDecoder(private val extractor: MediaExtractor, track: Int,
                            val format: MediaFormat, private val start: Long,
                            private val end: Long, private val guard: RenderGuard) : AutoCloseable {
    val width = if (format.containsKey("crop-right")) format.getInteger("crop-right") -
        format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
    val height = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") -
        format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
    private val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var previousPts = Long.MIN_VALUE
    private val trimSelector = TrimFrameSelector(start)
    private val pendingFrames = ArrayDeque<I420Frame>()

    init {
        try {
            I420Images.size(width, height)
            checkSdr(format)
            extractor.selectTrack(track)
            extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Rotation is mux metadata, not a pixel transform.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (t: Throwable) { codec.release(); throw t }
    }

    fun next(): I420Frame? {
        if (pendingFrames.isNotEmpty()) return pendingFrames.removeFirst()
        var deadline = guard.deadline()
        while (!outputEnded) {
            guard.waiting(deadline)
            if (feed()) deadline = guard.deadline()
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                checkSdr(codec.outputFormat)
                val color = codec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                requireMedia(color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ||
                    color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                    color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    "Decoder output is not supported 8-bit YUV420: $color")
                deadline = guard.deadline()
            } else if (index >= 0) {
                try {
                    deadline = guard.deadline()
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val pts = info.presentationTimeUs
                        requireMedia(pts > previousPts, "Decoder returned non-increasing presentation timestamps")
                        previousPts = pts
                        if (pts >= end) {
                            outputEnded = true
                            pendingFrames.addAll(trimSelector.finish())
                            return pendingFrames.removeFirstOrNull()
                        }
                        val image = codec.getOutputImage(index)
                            ?: throw UnsupportedMediaException("Decoder cannot expose YUV images")
                        val decoded = image.use { I420Images.read(it, width, height, pts) }
                        pendingFrames.addAll(trimSelector.submit(decoded))
                        if (pendingFrames.isNotEmpty()) return pendingFrames.removeFirst()
                    }
                } finally { codec.releaseOutputBuffer(index, false) }
            }
        }
        return trimSelector.finish().singleOrNull()
    }

    private fun feed(): Boolean {
        if (inputEnded) return false
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index)!!
        buffer.clear()
        // At EOS sampleFlags is -1, not an encrypted-sample flag set.
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
        } else {
            validatedCompressedSampleFlags(extractor.sampleFlags,
                MediaExtractor.SAMPLE_FLAG_ENCRYPTED, MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME, "video")
            requireMedia(size <= buffer.capacity(), "Compressed video sample exceeds decoder buffer")
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
        return true
    }

    override fun close() { try { codec.stop() } finally { codec.release() } }
}
