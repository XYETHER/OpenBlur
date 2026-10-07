package dev.motionblur.app.gpu

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class GpuVideoPipelineTest {
    @Test
    fun zeroCopyPassthroughProducesReadableMovingVideoAndAac() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "gpu-source-${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "gpu-output-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("motion-audio.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val progress = mutableListOf<Float>()

        try {
            val result = GpuVideoPipeline.render(
                context = context,
                uri = Uri.fromFile(source),
                output = output,
                startMs = 250,
                endMs = 1_250,
                progress = progress::add,
            )

            assertEquals(output, result.file)
            assertTrue(result.frameCount > 2)
            assertTrue(result.firstSourcePtsUs >= 250_000L)
            assertTrue(result.lastSourcePtsUs < 1_250_000L)
            assertTrue(result.audioCopied)
            assertFalse(progress.isEmpty())
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val videoTrack = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val audioTrack = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
                }
                val videoFormat = extractor.getTrackFormat(videoTrack)
                assertEquals(320, videoFormat.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(192, videoFormat.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(
                    0,
                    if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                        videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                    } else {
                        0
                    },
                )
                val videoPts = sampleTimes(extractor, videoTrack)
                assertTrue("Expected moving output with multiple timed frames", videoPts.size > 2)
                assertEquals(0L, videoPts.first())
                assertTrue(videoPts.zipWithNext().all { (before, after) -> after > before })
                val audioPts = sampleTimes(extractor, audioTrack)
                assertFalse("AAC track must contain samples", audioPts.isEmpty())
                assertTrue(audioPts.first() >= 0L)
            } finally {
                extractor.release()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun trimInsideHeldVfrFrameEmitsBoundaryFrameAtZero() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "gpu-vfr-source-${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "gpu-vfr-output-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("motion-vfr.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }

        try {
            val result = GpuVideoPipeline.render(context, Uri.fromFile(source), output, 30, 151)

            assertEquals(2L, result.frameCount)
            assertEquals(30_000L, result.firstSourcePtsUs)
            assertEquals(125_000L, result.lastSourcePtsUs)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val videoTrack = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                assertEquals(listOf(0L, 95_000L), sampleTimes(extractor, videoTrack))
            } finally {
                extractor.release()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun emptyAacSelectionDoesNotCreateEmptyMuxTrack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "gpu-empty-audio-source-${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "gpu-empty-audio-output-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("motion-audio.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }

        try {
            val result = GpuVideoPipeline.render(context, Uri.fromFile(source), output, 1, 2)

            assertFalse(result.audioCopied)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true)
            } finally {
                extractor.release()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun cancellationDeletesPartialOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "gpu-cancel-source-${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "gpu-cancel-output-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("motion-audio.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        var cancel = false

        try {
            try {
                GpuVideoPipeline.render(
                    context,
                    Uri.fromFile(source),
                    output,
                    0,
                    1_000,
                    cancelled = { cancel },
                    progress = { cancel = true },
                )
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(output.exists())
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun preservesRotationMetadataWithoutRotatingPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "gpu-rotation-source-${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "gpu-rotation-output-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("motion-rotated.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }

        try {
            GpuVideoPipeline.render(context, Uri.fromFile(source), output, 0, 250)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val video = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val format = extractor.getTrackFormat(video)
                assertEquals(320, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(192, format.getInteger(MediaFormat.KEY_HEIGHT))
                val original = MediaExtractor()
                try {
                    original.setDataSource(source.absolutePath)
                    val originalVideo = (0 until original.trackCount).single {
                        original.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    assertEquals(original.getTrackFormat(originalVideo).getInteger(MediaFormat.KEY_ROTATION),
                        format.getInteger(MediaFormat.KEY_ROTATION))
                } finally { original.release() }
            } finally {
                extractor.release()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun sampleTimes(extractor: MediaExtractor, track: Int): List<Long> {
        extractor.selectTrack(track)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val times = mutableListOf<Long>()
        while (extractor.sampleTime >= 0L) {
            times += extractor.sampleTime
            extractor.advance()
        }
        extractor.unselectTrack(track)
        return times
    }
}
