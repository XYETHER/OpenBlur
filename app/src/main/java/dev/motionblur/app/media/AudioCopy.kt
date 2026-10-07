package dev.motionblur.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/** AAC access-unit passthrough. Trim is packet-granular, never disguised as sample-accurate. */
internal class AudioCopy(private val extractor: MediaExtractor, track: Int,
                         private val start: Long, private val end: Long,
                         private val guard: RenderGuard) {
    val format: MediaFormat = extractor.getTrackFormat(track)
    private val buffer: ByteBuffer
    private val info = MediaCodec.BufferInfo()
    private var lastPts = Long.MIN_VALUE
    val hasSelectedSamples: Boolean
    var samples = 0L
        private set

    init {
        requireMedia(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm",
            "Only AAC compressed audio passthrough is supported; audio will not be silently removed")
        val maximum = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1_048_576
        requireMedia(maximum in 1..16_777_216, "Audio packet allocation exceeds safety limit")
        buffer = ByteBuffer.allocateDirect(maximum)
        extractor.selectTrack(track)
        hasSelectedSamples = preflight()
        extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    fun pump(muxer: MediaMuxer, track: Int, untilSourcePts: Long) {
        while (extractor.sampleTime >= 0 && extractor.sampleTime < end &&
            extractor.sampleTime <= untilSourcePts) {
            guard.check()
            val pts = extractor.sampleTime
            if (pts >= start) {
                requireMedia(pts > lastPts, "Non-increasing audio timestamps")
                val flags = validatedCompressedSampleFlags(extractor.sampleFlags,
                    MediaExtractor.SAMPLE_FLAG_ENCRYPTED, MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME, "audio")
                buffer.clear()
                val count = try { extractor.readSampleData(buffer, 0) }
                    catch (e: IllegalArgumentException) {
                        throw UnsupportedMediaException("Cannot read audio packet within bounded buffer: ${e.message}")
                    }
                requireMedia(count <= buffer.capacity(), "Audio packet exceeds declared maximum")
                check(count >= 0) { "Unexpected audio end of stream" }
                info.set(0, count, pts - start, flags)
                buffer.position(0); buffer.limit(count)
                muxer.writeSampleData(track, buffer, info)
                lastPts = pts
                samples++
            }
            extractor.advance()
        }
    }

    fun finish(muxer: MediaMuxer, track: Int) {
        pump(muxer, track, Long.MAX_VALUE)
        val sourceEnd = if (format.containsKey(MediaFormat.KEY_DURATION))
            minOf(end, format.getLong(MediaFormat.KEY_DURATION)) else end
        val outputEnd = sourceEnd - start
        if (samples > 0 && outputEnd > lastPts - start) {
            info.set(0, 0, outputEnd, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), info)
        }
    }

    private fun preflight(): Boolean {
        extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var found = false
        while (extractor.sampleTime >= 0 && extractor.sampleTime < end) {
            guard.check()
            if (extractor.sampleTime >= start) {
                validatedCompressedSampleFlags(extractor.sampleFlags,
                    MediaExtractor.SAMPLE_FLAG_ENCRYPTED, MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME, "audio")
                found = true
            }
            extractor.advance()
        }
        return found
    }
}
