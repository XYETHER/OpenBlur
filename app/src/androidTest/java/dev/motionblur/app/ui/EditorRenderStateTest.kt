package dev.motionblur.app.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real RenderEngine integration. Requires the arm64 native library and a codec-capable device. */
@RunWith(AndroidJUnit4::class)
class EditorRenderStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var state: EditorState
    private lateinit var fixture: File

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun newState(): EditorState = main {
        val store = ViewModelStore().also { stores.add(it) }
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EditorState::class.java]
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!main(predicate)) {
            if (SystemClock.elapsedRealtime() >= deadline) fail(message)
            SystemClock.sleep(40)
        }
    }

    @Before fun openFixture() {
        fixture = File(app.cacheDir, "ui-fixture-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("motion-audio.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        state = newState()
        main { state.updateDraft(state.draft.copy(videoCodec = dev.motionblur.app.logic.VideoCodec.H264, bitrateMbps = null, encodingEffort = null)) }
        main { state.open(Uri.fromFile(fixture)) }
        await("Source did not open") { !state.opening }
        assertNotNull(main { state.source })
        main { state.setTrim(250, 1750) }
    }

    @After fun clear() {
        main { stores.forEach { it.clear() } }
        fixture.delete()
    }

    private fun completeExport(): File {
        main { state.exportVideo() }
        await("Export did not stop") { !state.isRendering }
        assertTrue("Export failed: ${main { state.renderState }}", main { state.renderState is RenderState.Complete })
        return main { state.latestExport!! }.also { assertTrue(it.length() > 0) }
    }

    @Test fun previewProducesMovingMp4AndComparisonKeepsSourceClock() {
        main { state.renderPreview() }
        val snapshot = main { (state.renderState as RenderState.Running).request }
        assertEquals(RenderKind.PREVIEW, snapshot.kind)
        assertEquals(250L, snapshot.startMs)
        assertEquals(1750L, snapshot.endMs)
        await("Preview did not stop") { !state.isRendering }
        val artifact = main { state.previewArtifact }
        assertNotNull("Preview failed: ${main { state.renderState }}", artifact)
        assertEquals(250L, artifact!!.sourceStartMs)
        assertEquals(1750L, artifact.sourceEndMs)
        assertTrue(artifact.file.extension == "mp4" && artifact.file.length() > 0)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(artifact.file.path)
            val first = reader.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val second = reader.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            assertFalse("Preview must move, not repeat a still", first.sameAs(second))
            first.recycle(); second.recycle()
        } finally { reader.release() }
        main {
            state.seek(900)
            assertEquals(900L, state.position)
            state.togglePreviewResult()
            assertFalse(state.showProcessedPreview)
            assertEquals(900L, state.position)
            state.togglePreviewResult()
            assertTrue(state.showProcessedPreview)
            assertEquals(900L, state.position)
            state.seek(Long.MAX_VALUE)
            assertEquals(1749L, state.position)
            state.updateDraft(state.draft.choosePreset("Dynamic Strong"))
            assertNull(state.previewArtifact)
            assertFalse(state.showProcessedPreview)
        }
    }

    @Test fun repeatedExportsAreUniqueAndRestoreAfterViewModelRecreation() {
        val first = completeExport()
        val second = completeExport()
        assertNotEquals(first.path, second.path)
        assertTrue(first.isFile)
        val restored = newState()
        assertEquals(second.canonicalPath, main { restored.latestExport!!.canonicalPath })
        assertFalse(second.parentFile!!.listFiles()!!.any { it.extension == "partial" })
    }

    @Test fun settingEditCancelsSnapshotWithoutPublishingOrReplacingExport() {
        val durable = completeExport()
        val bytes = durable.readBytes()
        main {
            state.renderPreview()
            val snapshot = (state.renderState as RenderState.Running).request
            val before = snapshot.draft
            state.updateDraft(before.choosePreset(if (before.preset == "Dynamic Light") "Dynamic Strong" else "Dynamic Light"))
            assertEquals(before, snapshot.draft)
            assertTrue((state.renderState as RenderState.Running).cancelling)
            state.exportVideo() // Must not start another worker while cancellation drains.
            assertEquals(snapshot.id, (state.renderState as RenderState.Running).request.id)
        }
        await("Cancelled worker did not release") { !state.isRendering }
        main { assertNull(state.previewArtifact); assertEquals(durable, state.latestExport) }
        assertArrayEquals(bytes, durable.readBytes())
        completeExport() // A fresh genuine render still succeeds after cancellation.
    }

    @Test fun replacingSourceDuringRenderIsRejectedAndCancelPreservesExport() {
        val durable = completeExport()
        main {
            state.exportVideo()
            state.open(Uri.parse("content://invalid/replacement"))
            assertEquals(Uri.fromFile(fixture), state.source!!.uri)
            assertNotNull(state.error)
            state.cancelRender()
        }
        await("Cancelled export did not stop") { !state.isRendering }
        assertEquals(durable, main { state.latestExport })
        assertTrue(durable.isFile)
    }

    @Test fun saveFailureRetainsExportAndSuccessfulCopyIsVerified() {
        val durable = completeExport()
        main { state.saveExport(Uri.parse("content://missing.document.provider/no-file"), durable) }
        await("Failed save did not finish") { !state.saving }
        assertTrue(durable.isFile)
        assertTrue(main { state.outputMessage!!.contains("safe") })
        val copy = File(app.cacheDir, "ui-saved-${UUID.randomUUID()}.mp4")
        try {
            main { state.saveExport(Uri.fromFile(copy), durable) }
            await("Save did not finish") { !state.saving }
            assertTrue(main { state.outputMessage!!.contains("verified") })
            assertArrayEquals(durable.readBytes(), copy.readBytes())
            main { state.saveExport(null, durable) }
            assertTrue(durable.isFile)
        } finally { copy.delete() }
    }
    @Test fun customStrengthSurvivesViewModelRecreation() {
        main { state.updateDraft(state.draft.manualStrength(73f)) }
        val restored = newState()
        assertEquals("Custom", main { restored.draft.preset })
        assertEquals(73f, main { restored.draft.strength }, 0f)
        main { state.updateDraft(state.draft.choosePreset("Dynamic Medium")) }
    }

    @Test fun unsupportedGpuReportsReasonWithoutPublishing() {
        val unsupported = try {
            dev.motionblur.app.gpu.GpuBackendProbe().use { false }
        } catch (_: dev.motionblur.app.media.UnsupportedMediaException) { true }
        org.junit.Assume.assumeTrue("This gate requires an unsupported GPU", unsupported)
        val previous = main { state.latestExport }
        main { state.exportVideo() }
        await("Unsupported GPU did not stop") { !state.isRendering }
        assertTrue(main { state.renderState is RenderState.Failed })
        assertTrue(main { (state.renderState as RenderState.Failed).message.contains("OpenGL ES 3.1") })
        assertEquals(previous, main { state.latestExport })
        assertFalse(File(app.filesDir, "exports").listFiles()?.any { it.extension == "partial" } ?: false)
    }

    @Test fun openingAdvancedAndEditingNextExportDoNotCancelCurrentRequest() {
        main {
            state.exportVideo()
            val request = (state.renderState as RenderState.Running).request
            state.updateDraft(state.draft.copy(advanced = !state.draft.advanced))
            assertFalse((state.renderState as RenderState.Running).cancelling)
            state.updateDraft(state.draft.copy(bitrateMbps = 8f, videoCodec = dev.motionblur.app.logic.VideoCodec.H265))
            val running = state.renderState as RenderState.Running
            assertEquals(request, running.request)
            assertFalse(running.cancelling)
            assertNull(request.draft.bitrateMbps)
            assertEquals(dev.motionblur.app.logic.VideoCodec.H264, request.draft.videoCodec)
        }
        await("Export did not stop") { !state.isRendering }
        // Unsupported emulators must report a real error rather than silently cancelling the request.
        assertTrue(main { state.renderState is RenderState.Complete || state.renderState is RenderState.Failed })
        main { state.updateDraft(state.draft.copy(videoCodec = dev.motionblur.app.logic.VideoCodec.H264, bitrateMbps = null)) }
    }

    @Test fun encodingSettingsRestoreWithoutLosingAutomaticDefaults() {
        main { state.updateDraft(state.draft.copy(videoCodec = dev.motionblur.app.logic.VideoCodec.H265, bitrateMbps = 8f, encodingEffort = .75f)) }
        val restored = newState()
        assertEquals(dev.motionblur.app.logic.VideoCodec.H265, main { restored.draft.videoCodec })
        assertEquals(8f, main { restored.draft.bitrateMbps!! }, 0f)
        assertEquals(.75f, main { restored.draft.encodingEffort!! }, 0f)
        main { state.updateDraft(state.draft.copy(videoCodec = dev.motionblur.app.logic.VideoCodec.H264, bitrateMbps = null, encodingEffort = null)) }
    }

}
