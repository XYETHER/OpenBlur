package dev.motionblur.app.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Push a real AAC+video fixture to target app external-files/pipeline-av-input.mp4 before this test. */
class PipelineAudioTest {
    @Test fun preflightReportsNoAacPacketsInRangeAfterAudioEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = asset(context, "motion-short-audio.mp4")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            }
            val copy = AudioCopy(extractor, track, 800_000L, 1_400_000L, RenderGuard { false })

            assertFalse(copy.hasSelectedSamples)
        } finally {
            extractor.release()
            source.delete()
        }
    }

    @Test fun selectedRangeAfterShortAacOmitsAudioTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = asset(context, "motion-short-audio.mp4")
        val output = File(context.cacheDir, "short-audio-empty-${System.nanoTime()}.mp4")
        try {
            val result = VideoPipeline.render(context, Uri.fromFile(source), output, 800, 1_400,
                FrameEffect { _, current, _ -> current.data })

            assertFalse(result.audioCopied)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertFalse((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                })
            } finally { extractor.release() }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test fun selectedShortAacPacketsPreserveBytesAndFlags() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = asset(context, "motion-short-audio.mp4")
        val output = File(context.cacheDir, "short-audio-copy-${System.nanoTime()}.mp4")
        try {
            val result = VideoPipeline.render(context, Uri.fromFile(source), output, 0, 300,
                FrameEffect { _, current, _ -> current.data })

            assertTrue(result.audioCopied)
            val expected = packets(source).filter { it.ptsUs in 0L until 300_000L }
            val actual = packets(output)
            println("AUDIO_EVIDENCE selectedPackets=${expected.size} outputPackets=${actual.size}")
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (before, after) ->
                assertEquals(before.ptsUs, after.ptsUs)
                assertEquals(before.flags, after.flags)
                assertArrayEquals(before.bytes, after.bytes)
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test fun suppliedAvFixturePreservesAacPacketsAndCommonTimeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.getExternalFilesDir(null), "pipeline-av-input.mp4")
        assumeTrue("External AAC+SDR video fixture not supplied; audio test not executed", source.isFile)
        val output = File(context.cacheDir, "audio-test-${System.nanoTime()}.mp4")
        try {
            val result = VideoPipeline.render(context, Uri.fromFile(source), output, 200, 1200,
                FrameEffect { _, current, _ -> current.data }) // Test-only identity.
            assertTrue(result.audioCopied)
            val expected = packets(source).filter { it.ptsUs in 200_000L until 1_200_000L }
            val actual = packets(output)
            assertFalse(expected.isEmpty())
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (before, after) ->
                assertEquals(before.ptsUs - 200_000L, after.ptsUs)
                assertEquals(before.flags, after.flags)
                assertArrayEquals(before.bytes, after.bytes)
            }
        } finally { output.delete() }
    }

    private data class Packet(val ptsUs: Long, val flags: Int, val bytes: ByteArray)

    private fun packets(file: File): List<Packet> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            extractor.selectTrack(track)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val result = mutableListOf<Packet>()
            val buffer = ByteBuffer.allocate(1_048_576)
            while (extractor.sampleTime >= 0) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                val bytes = ByteArray(size)
                buffer.position(0); buffer.get(bytes)
                result += Packet(extractor.sampleTime, extractor.sampleFlags, bytes)
                extractor.advance()
            }
            return result
        } finally { extractor.release() }
    }

    private fun asset(context: android.content.Context, name: String): File {
        val file = File(context.cacheDir, "asset-$name-${System.nanoTime()}")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use(input::copyTo)
        }
        return file
    }
}
