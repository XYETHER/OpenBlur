package dev.motionblur.app.render

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.motionblur.app.MainActivity
import dev.motionblur.app.ui.EditorState
import dev.motionblur.app.ui.RenderState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the visible controls and a real processed MP4, not a mocked renderer. */
class WorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun previewPlaysSwitchesAndExports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "workflow-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("motion-audio.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        lateinit var state: EditorState
        ui.runOnUiThread {
            state = ViewModelProvider(ui.activity)[EditorState::class.java]
            state.open(Uri.fromFile(source))
        }
        ui.waitUntil(20_000) { !state.opening && state.source?.uri == Uri.fromFile(source) }
        ui.runOnUiThread { state.togglePreview() }
        ui.onNodeWithText("Render preview").performScrollTo().performClick()
        ui.waitUntil(180_000) { state.previewArtifact != null || state.renderState is RenderState.Failed }
        assertNotNull("Real preview failed: ${state.renderState}", state.previewArtifact)
        assertTrue(state.previewArtifact!!.file.length() > 1000)
        ui.onNodeWithContentDescription("Play video").performScrollTo().performClick()
        ui.waitUntil(10_000) { state.position > 500 }
        ui.onNodeWithText("Original", useUnmergedTree = true).performScrollTo().performClick()
        assertFalse(state.showProcessedPreview)
        ui.onNodeWithText("Processed", useUnmergedTree = true).performScrollTo().performClick()
        assertTrue(state.showProcessedPreview)
        ui.onNodeWithText("Replay").performScrollTo().performClick()
        ui.waitUntil(10_000) { state.position in 100..800 }
        val old = state.latestExport
        ui.onNodeWithText("Export video").performScrollTo().performClick()
        ui.waitUntil(180_000) { state.latestExport != old || state.renderState is RenderState.Failed }
        assertNotEquals("Export did not complete: ${state.renderState}", old, state.latestExport)
        assertTrue(state.latestExport!!.length() > 1000)
        ui.onNodeWithText("Save As").performScrollTo().assertIsEnabled()
    }
}
