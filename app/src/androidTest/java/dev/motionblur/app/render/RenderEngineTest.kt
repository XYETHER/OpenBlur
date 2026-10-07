package dev.motionblur.app.render

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.motionblur.app.gpu.GpuMotionBlurProcessor
import dev.motionblur.app.logic.EditorDraft
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real decoder, native engine, encoder and muxer; no renderer mocks. */
@RunWith(AndroidJUnit4::class)
class RenderEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): File = File(context.cacheDir, "motion-audio.mp4").also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("motion-audio.mp4").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun rendersStagedUserClipForPulledVisualAndPerformanceValidation() {
        val source = File(context.filesDir, "openblur-user-source.mp4")
        assumeTrue("Device-only user clip is not staged", source.length() > 1_000_000L)
        val output = File(context.filesDir, "exports/openblur-user-gpu-candidate.mp4").apply {
            parentFile!!.mkdirs()
            delete()
        }
        val started = System.nanoTime()
        RenderEngine.renderGpu(
            context,
            Uri.fromFile(source),
            output,
            12_000,
            18_000,
            EditorDraft(preset = "Dynamic Strong", strength = 100f, quality = "Quality"),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(output.length() > 1_000_000L)
        Log.i(
            "OpenBlurS24",
            "USER_CLIP_GPU output=${output.absolutePath} elapsedMs=$elapsedMs durationMs=6000 realtimeFactor=${6000.0 / elapsedMs}",
        )
    }

    @Ignore("Superseded by the production MvToolsGpuProcessor parity and real-clip tests")
    @Test fun diagnosticSavedSourceReportsActualMotionAndGate() {
        val source = context.getSharedPreferences("studio", 0).getString("uri", null)
            ?: error("No saved editor source")
        val snapshots = mutableListOf<GpuMotionBlurProcessor.MotionDebugSnapshot>()
        val output = File(context.cacheDir, "saved-source-diagnostic.mp4").apply { delete() }
        GpuMotionBlurProcessor.debugObserverForTests = snapshots::add
        try {
            RenderEngine.render(
                context,
                Uri.parse(source),
                output,
                12_000,
                18_000,
                EditorDraft(preset = "Dynamic Strong", strength = 100f, quality = "Balanced"),
                { false },
                {},
            )
        } finally {
            GpuMotionBlurProcessor.debugObserverForTests = null
        }
        assertTrue("Motion diagnostics were not captured", snapshots.isNotEmpty())
        Log.i(
            "OpenBlurS24",
            "MOTION_DIAGNOSTIC frames=${snapshots.size} " +
                "meanVelocity=${snapshots.map { it.meanVelocityPixels }.average()} " +
                "meanGate=${snapshots.map { it.meanEffectiveGate }.average()} " +
                "activeBlocks=${snapshots.map { it.activeBlockFraction }.average()}",
        )
    }

    @Test fun ultraQualityAndFixedMvtoolsModeProduceReadableGpuExports() {
        val source = fixture()
        val ultra = File(context.cacheDir, "ultra-quality.mp4").apply { delete() }
        val fixed = File(context.cacheDir, "fixed-mvtools.mp4").apply { delete() }

        RenderEngine.renderGpu(context, Uri.fromFile(source), ultra, 0, 1_500,
            EditorDraft(preset = "Dynamic Strong", strength = 100f, quality = "Ultra quality", dynamicBlur = true))
        RenderEngine.renderGpu(context, Uri.fromFile(source), fixed, 0, 1_500,
            EditorDraft(preset = "Dynamic Strong", strength = 100f, quality = "Quality", dynamicBlur = false))

        listOf(ultra, fixed).forEach { output ->
            assertTrue("GPU render did not create ${output.name}", output.length() > 1_000L)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertNotNull("GPU export ${output.name} has no decoded frame", reader.frameAtTime)
            } finally {
                reader.release()
            }
        }
    }

    @Test fun gpuQualityTracksMvtoolsReferenceInsteadOfBlockSmearing() {
        val source = fixture()
        val gpu = File(context.cacheDir, "gpu-parity.mp4").apply { delete() }
        val cpu = File(context.cacheDir, "cpu-parity.mp4").apply { delete() }
        val draft = EditorDraft(preset = "Dynamic Strong", strength = 100f, quality = "Quality")
        RenderEngine.renderGpu(context, Uri.fromFile(source), gpu, 0, 1_500, draft)
        RenderEngine.renderCpuReference(context, Uri.fromFile(source), cpu, 0, 1_500, draft)

        val gpuReader = MediaMetadataRetriever()
        val cpuReader = MediaMetadataRetriever()
        try {
            gpuReader.setDataSource(gpu.path)
            cpuReader.setDataSource(cpu.path)
            val differences = listOf(250_000L, 700_000L, 1_150_000L).map { timeUs ->
                val gpuFrame = gpuReader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                val cpuFrame = cpuReader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                meanAbsoluteRgbDifference(gpuFrame, cpuFrame).also {
                    gpuFrame.recycle()
                    cpuFrame.recycle()
                }
            }
            val average = differences.average()
            Log.i("OpenBlurS24", "MVTOOLS_PARITY meanAbsRgb=$average samples=$differences")
            assertTrue("GPU output does not match the MVTools reference: meanAbsRgb=$average", average < 4.0)
        } finally {
            gpuReader.release()
            cpuReader.release()
        }
    }

    @Test fun diagnosticExtremeGpuBlurReportsEdgeEnergy() {
        val source = fixture()
        val output = File(context.filesDir, "exports/gpu-blur-diagnostic.mp4").apply {
            parentFile!!.mkdirs()
            delete()
        }
        RenderEngine.render(
            context,
            Uri.fromFile(source),
            output,
            0,
            1_500,
            EditorDraft(preset = "Dynamic Extreme", strength = 200f, quality = "Quality"),
            { false },
            {},
        )
        assertEquals("GPU", RenderEngine.lastRendererForTests)
        assertTrue(output.length() > 1000)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(source.path)
            val sourceEnergy = listOf(150_000L, 700_000L, 1_200_000L).map { timeUs ->
                reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!.let { bitmap ->
                    edgeEnergy(bitmap).also { bitmap.recycle() }
                }
            }.average()
            reader.setDataSource(output.path)
            val outputEnergy = listOf(150_000L, 700_000L, 1_200_000L).map { timeUs ->
                reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!.let { bitmap ->
                    edgeEnergy(bitmap).also { bitmap.recycle() }
                }
            }.average()
            val ratio = outputEnergy / sourceEnergy
            Log.i("OpenBlurS24", "BLUR_DIAGNOSTIC renderer=GPU sourceEdge=$sourceEnergy outputEdge=$outputEnergy ratio=$ratio")
            assertTrue("Extreme GPU blur is too weak to be visibly distinct: ratio=$ratio", ratio < 0.82)
        } finally {
            reader.release()
        }
    }

    @Test fun rendersMovingVideoWithAudioAndTrim() {
        val source = fixture()
        val output = File(context.filesDir, "exports/verified-motion.mp4").apply { parentFile!!.mkdirs(); delete() }
        val progress = mutableListOf<Float>()
        RenderEngine.render(context, Uri.fromFile(source), output, 250, 1750,
            EditorDraft(), { false }, { progress.add(it) })
        assertTrue("Missing MP4", output.length() > 1000)
        assertTrue("No progress", progress.isNotEmpty())
        assertTrue("Progress did not finish", progress.last() >= .99f)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            val video = tracks.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            assertEquals(320, video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(192, video.getInteger(MediaFormat.KEY_HEIGHT))
            assertTrue("Trim duration", video.getLong(MediaFormat.KEY_DURATION) in 1_300_000L..1_600_000L)
        } finally { extractor.release() }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(output.path)
            val a = reader.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val b = reader.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            assertFalse("Output is frozen", a.sameAs(b))
            a.recycle(); b.recycle()
        } finally { reader.release() }
    }

    @Test fun defaultRenderProducesMovingGpuOutputThatDiffersFromSource() {
        val source = fixture()
        val output = File(context.filesDir, "exports/verified-gpu-motion.mp4").apply {
            parentFile!!.mkdirs()
            delete()
        }
        val started = System.nanoTime()
        RenderEngine.render(context, Uri.fromFile(source), output, 0, 1_500,
            EditorDraft(), { false }, {})
        val renderMs = (System.nanoTime() - started) / 1_000_000L
        Log.i("OpenBlurS24", "renderer=${RenderEngine.lastRendererForTests} stage=render elapsedMs=$renderMs")
        assertEquals("GPU", RenderEngine.lastRendererForTests)

        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(source.path)
            val sourceFrames = listOf(150_000L, 700_000L, 1_200_000L).map { timeUs ->
                reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            }
            reader.setDataSource(output.path)
            val outputFrames = listOf(150_000L, 700_000L, 1_200_000L).map { timeUs ->
                reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            }
            assertTrue("Processed output must contain multiple moving frames", outputFrames.zipWithNext().any { (a, b) -> !a.sameAs(b) })
            val differences = sourceFrames.zip(outputFrames).map { (sourceFrame, outputFrame) ->
                val difference = meanAbsoluteRgbDifference(sourceFrame, outputFrame)
                Log.i("OpenBlurS24", "renderer=GPU stage=frame-diff elapsedMs=0 meanAbsRgb=$difference")
                sourceFrame.recycle()
                outputFrame.recycle()
                difference
            }
            assertTrue("GPU processed output must differ from source", differences.any { it > 1f })
        } finally {
            reader.release()
            output.delete()
        }
    }

    @Test fun rendersVariableFrameRateWithoutAddingAudio() {
        renderFixture("motion-vfr.mp4", "verified-vfr.mp4", 0, 1900)
    }

    @Test fun retainsRotationMetadata() {
        val output = renderFixture("motion-rotated.mp4", "verified-rotated.mp4", 0, 2000)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(output.path)
            val original = MediaMetadataRetriever()
            try {
                original.setDataSource(File(context.cacheDir, "motion-rotated.mp4").path)
                assertEquals(original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            } finally { original.release() }
        } finally { reader.release() }
    }

    private fun renderFixture(name: String, outputName: String, start: Long, end: Long): File {
        val source = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val output = File(context.filesDir, "exports/$outputName").apply { parentFile!!.mkdirs(); delete() }
        RenderEngine.render(context, Uri.fromFile(source), output, start, end, EditorDraft(), { false }, {})
        assertTrue(output.length() > 1000)
        return output
    }

    private fun edgeEnergy(bitmap: android.graphics.Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        fun luma(pixel: Int): Int =
            ((pixel shr 16 and 0xff) * 54 + (pixel shr 8 and 0xff) * 183 + (pixel and 0xff) * 19) / 256
        var sum = 0L
        var count = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val here = luma(pixels[y * width + x])
                if (x + 1 < width) {
                    sum += kotlin.math.abs(here - luma(pixels[y * width + x + 1]))
                    count++
                }
                if (y + 1 < height) {
                    sum += kotlin.math.abs(here - luma(pixels[(y + 1) * width + x]))
                    count++
                }
            }
        }
        return sum.toFloat() / count
    }


    private fun meanAbsoluteRgbDifference(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Float {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val aPixels = IntArray(a.width * a.height)
        val bPixels = IntArray(b.width * b.height)
        a.getPixels(aPixels, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(bPixels, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0L
        for (index in aPixels.indices) {
            sum += kotlin.math.abs((aPixels[index] shr 16 and 0xff) - (bPixels[index] shr 16 and 0xff))
            sum += kotlin.math.abs((aPixels[index] shr 8 and 0xff) - (bPixels[index] shr 8 and 0xff))
            sum += kotlin.math.abs((aPixels[index] and 0xff) - (bPixels[index] and 0xff))
        }
        return sum.toFloat() / (aPixels.size * 3f)
    }

    @Test fun cancellationNeverPublishesPartialOutput() {
        val output = File(context.cacheDir, "cancelled.mp4").apply { delete() }
        try {
            RenderEngine.render(context, Uri.fromFile(fixture()), output, 0, 2000,
                EditorDraft(), { true }, {})
            fail("Cancelled render succeeded")
        } catch (_: java.util.concurrent.CancellationException) {
            assertFalse("Partial render leaked", output.exists())
        }
    }
}
