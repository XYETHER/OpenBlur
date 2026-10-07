package dev.motionblur.app.media

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import java.io.File

/** Blocking worker-thread pipeline. No UI, hidden rescaling, synthetic timestamps or fake effect. */
object VideoPipeline {
    fun render(context: Context, uri: Uri, output: File, startMs: Long, endMs: Long,
               effect: FrameEffect, cancelled: () -> Boolean = { false },
               progress: (Float) -> Unit = {}): RenderResult {
        require(startMs >= 0 && endMs > startMs && endMs <= Long.MAX_VALUE / 1000) { "Invalid trim range" }
        require(!output.exists()) { "Refusing to overwrite an existing output" }
        val start = startMs * 1000
        val end = endMs * 1000
        val interval = SelectedOutputInterval(start, end)
        val guard = RenderGuard(cancelled)
        guard.check()
        val extractor = openExtractor(context, uri)
        var audioExtractor: android.media.MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var success = false
        var createdOutput = false
        try {
            val videos = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audios = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            requireMedia(videos.size == 1, "Exactly one video track is required")
            requireMedia(audios.size <= 1, "Multiple audio tracks are unsupported; refusing to drop tracks")
            val format = extractor.getTrackFormat(videos.single())
            checkSdr(format)
            val sarW = if (format.containsKey("sar-width")) format.getInteger("sar-width") else 1
            val sarH = if (format.containsKey("sar-height")) format.getInteger("sar-height") else 1
            requireMedia(sarW == sarH, "Non-square-pixel video is unsupported")
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            requireMedia(rotation in setOf(0, 90, 180, 270), "Unsupported rotation metadata")
            val audio = audios.firstOrNull()?.let { track ->
                val opened = openExtractor(context, uri)
                audioExtractor = opened
                val candidate = AudioCopy(opened, track, start, end, guard)
                if (candidate.hasSelectedSamples) candidate else {
                    opened.release()
                    audioExtractor = null
                    null
                }
            }
            output.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create output directory" } }
            check(output.createNewFile()) { "Output appeared while render was starting" }
            createdOutput = true
            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            mux.setOrientationHint(rotation)
            val result = VideoDecoder(extractor, videos.single(), format, start, end, guard).use { decoder ->
                VideoEncoder(decoder.width, decoder.height, format, mux, audio, start, guard).use { encoder ->
                    renderFrames(decoder, encoder, effect, interval, guard, progress, output, audio)
                }
            }
            guard.check()
            mux.release()
            muxer = null
            check(output.length() > 0) { "Output MP4 is empty" }
            progress(1f)
            success = true
            return result
        } finally {
            try { muxer?.release() } catch (_: Exception) { /* Preserve the primary failure. */ }
            try { audioExtractor?.release() } finally {
                try { extractor.release() } finally { if (!success && createdOutput) output.delete() }
            }
        }
    }

    private fun renderFrames(decoder: VideoDecoder, encoder: VideoEncoder, effect: FrameEffect,
                             interval: SelectedOutputInterval, guard: RenderGuard, progress: (Float) -> Unit,
                             output: File, audio: AudioCopy?): RenderResult {
        var current = decoder.next() ?: throw UnsupportedMediaException("No frames inside selected trim range")
        var previous = current
        val first = current.presentationTimeUs
        var last: Long
        var submitted = 0L
        var publishedAt = 0L
        progress(0f)
        while (true) {
            guard.check()
            val next = decoder.next()
            val pixels = effect.apply(previous, current, next ?: current)
            guard.check()
            require(pixels.size == I420Images.size(current.width, current.height)) { "Invalid effect output size" }
            encoder.frame(pixels, interval.outputPtsUs(current.presentationTimeUs))
            submitted++
            last = current.presentationTimeUs
            val now = SystemClock.elapsedRealtime()
            if (now - publishedAt >= 150) {
                progress(((last - interval.sourceStartUs).toDouble() / interval.durationUs).toFloat().coerceIn(0f, 0.99f))
                publishedAt = now
            }
            if (next == null) break
            previous = current
            current = next
        }
        encoder.finish(interval.durationUs)
        check(encoder.frames == submitted) { "Encoder dropped frames: submitted $submitted, wrote ${encoder.frames}" }
        return RenderResult(output, encoder.frames, first, last, (audio?.samples ?: 0) > 0)
    }
}
