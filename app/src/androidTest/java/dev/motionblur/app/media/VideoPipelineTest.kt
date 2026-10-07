package dev.motionblur.app.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** Device tests: synthetic fixtures and identity are test-only, not a production blur engine. */
class VideoPipelineTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stridedPlanesPackAndUnpackWithoutTouchingPadding() {
        val source = ByteBuffer.allocate(40)
        for (i in 0 until 40) source.put(i, i.toByte())
        val packed = ByteArray(6)
        I420Images.copyPlane(source, 3, 10, 2, packed, 0, 3, 2, false)
        assertArrayEquals(byteArrayOf(3, 5, 7, 13, 15, 17), packed)
        val target = ByteBuffer.allocate(40)
        I420Images.copyPlane(target, 3, 10, 2, packed, 0, 3, 2, true)
        assertEquals(5.toByte(), target.get(5))
        assertEquals(0.toByte(), target.get(6))
    }

    @Test fun actualCodecVfrTrimRotationAndLookahead() {
        val source = fixture()
        val output = fresh("trim")
        val seen = mutableListOf<Long>()
        try {
            val result = VideoPipeline.render(context, Uri.fromFile(source), output, 30, 151,
                FrameEffect { previous, current, next ->
                    if (seen.isEmpty()) assertEquals(current.presentationTimeUs, previous.presentationTimeUs)
                    assertTrue(previous.presentationTimeUs <= current.presentationTimeUs)
                    assertTrue(next.presentationTimeUs >= current.presentationTimeUs)
                    seen += current.presentationTimeUs
                    current.data // Explicit test-only identity callback.
                })
            // The frame that began at 0 ms remains visible at the 30 ms trim start.
            // It is emitted once at output time zero before the next decoded source frame.
            assertEquals(listOf(30_000L, 40_000L, 100_000L, 150_000L), seen)
            assertEquals(4L, result.frameCount)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = extractor.getTrackFormat(0)
                assertEquals(96, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(64, format.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(90, format.getInteger(MediaFormat.KEY_ROTATION))
                extractor.selectTrack(0)
                val pts = mutableListOf<Long>()
                while (extractor.sampleTime >= 0) { pts += extractor.sampleTime; extractor.advance() }
                assertEquals(listOf(0L, 10_000L, 70_000L, 120_000L), pts)
            } finally { extractor.release() }
            // Decode the completed MP4 too: a muxed track alone is not proof of playable output.
            val reader = openExtractor(context, Uri.fromFile(output))
            try {
                VideoDecoder(reader, 0, reader.getTrackFormat(0), 0, Long.MAX_VALUE, RenderGuard { false }).use {
                    var count = 0
                    while (it.next() != null) count++
                    assertEquals(4, count)
                }
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun cancellationInsideEffectDeletesPartialOutput() {
        val source = fixture()
        val output = fresh("cancel")
        var cancel = false
        try {
            try {
                VideoPipeline.render(context, Uri.fromFile(source), output, 0, 500,
                    FrameEffect { _, current, _ -> cancel = true; current.data }, { cancel })
                fail("Expected cancellation")
            } catch (_: CancellationException) { assertFalse(output.exists()) }
        } finally { source.delete(); output.delete() }
    }

    @Test fun cfrOutputDurationEndsWithinOneSourceFrameOfSelection() {
        assertAssetTrimDuration("motion-cut.mp4", startMs = 250, endMs = 1_325)
    }

    @Test fun vfrOutputDurationEndsWithinOneSourceFrameOfSelection() {
        assertAssetTrimDuration("motion-vfr.mp4", startMs = 250, endMs = 1_375)
    }

    @Test fun unsupportedHdrIsRejected() {
        val format = MediaFormat.createVideoFormat("video/hevc", 96, 64)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        try { checkSdr(format); fail("HDR must not be silently converted") }
        catch (_: UnsupportedMediaException) { }
    }

    private fun fixture(): File {
        val file = fresh("source")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(90)
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 96, 64)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
            VideoEncoder(96, 64, format, muxer, null, 0, RenderGuard { false }).use { encoder ->
                for ((index, pts) in listOf(0L, 40_000L, 100_000L, 150_000L, 230_000L).withIndex()) {
                    val pixels = ByteArray(I420Images.size(96, 64)) { 128.toByte() }
                    pixels.fill((32 + index * 30).toByte(), 0, 96 * 64)
                    encoder.frame(pixels, pts)
                }
                encoder.finish(selectedDurationUs = 230_001L)
            }
        } finally { muxer.release() }
        return file
    }

    private fun fresh(label: String) = File(context.cacheDir, "pipeline-$label-${System.nanoTime()}.mp4")

    private fun assertAssetTrimDuration(name: String, startMs: Long, endMs: Long) {
        val source = fresh("asset-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            source.outputStream().use(input::copyTo)
        }
        val output = fresh("duration-$name")
        try {
            VideoPipeline.render(context, Uri.fromFile(source), output, startMs, endMs,
                FrameEffect { _, current, _ -> current.data })
            val selectedDurationUs = (endMs - startMs) * 1_000L
            val toleranceUs = maximumVideoFrameIntervalUs(source)
            val actualDurationUs = videoDurationUs(output)
            println("DURATION_EVIDENCE fixture=$name selectedUs=$selectedDurationUs actualUs=$actualDurationUs toleranceUs=$toleranceUs")
            assertTrue(
                "output duration $actualDurationUs differs from selected $selectedDurationUs by more than " +
                    "one source frame ($toleranceUs us)",
                abs(actualDurationUs - selectedDurationUs) <= toleranceUs,
            )
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun videoDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            return extractor.getTrackFormat(track).getLong(MediaFormat.KEY_DURATION)
        } finally { extractor.release() }
    }

    private fun maximumVideoFrameIntervalUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val timestamps = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                timestamps += extractor.sampleTime
                extractor.advance()
            }
            val maximum = timestamps.sorted().zipWithNext { previous, next -> next - previous }.maxOrNull() ?: 0L
            check(maximum > 0L) { "Fixture must contain at least two video frames" }
            return maximum
        } finally { extractor.release() }
    }
}
