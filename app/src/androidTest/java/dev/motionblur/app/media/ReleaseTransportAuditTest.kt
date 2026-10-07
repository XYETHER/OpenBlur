package dev.motionblur.app.media

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Identity is deliberately test-only: this verifies media transport, not GPU blur quality. */
class ReleaseTransportAuditTest {
    private fun export(asset: String, name: String, start: Long, end: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "audit-source-$name.mp4")
        instrumentation.context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
        val output = File(context.filesDir, "validation/$name.mp4")
        output.parentFile!!.mkdirs(); output.delete()
        try {
            val result = VideoPipeline.render(context, Uri.fromFile(source), output, start, end,
                FrameEffect { _, current, _ -> current.data })
            assertTrue(result.frameCount > 0); assertTrue(output.length() > 0)
        } finally { source.delete() }
    }
    @Test fun cfrAudio() = export("motion-audio.mp4", "transport-cfr", 250, 1750)
    @Test fun heldVfrBoundary() = export("motion-vfr.mp4", "transport-vfr", 250, 1375)
    @Test fun rotatedVideo() = export("motion-rotated.mp4", "transport-rotation", 0, 2000)
    @Test fun rangeAfterAudioEnds() = export("motion-short-audio.mp4", "transport-no-audio", 800, 1400)
}
