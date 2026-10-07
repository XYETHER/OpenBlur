package dev.motionblur.app.render

import android.content.Context
import android.net.Uri
import dev.motionblur.app.gpu.GpuVideoPipeline
import dev.motionblur.app.logic.EditorDraft
import dev.motionblur.app.media.FrameEffect
import dev.motionblur.app.media.VideoPipeline
import java.io.File

/** Preview and export share the GPU effect and surface media pipeline. */
object RenderEngine {
    /** Last selected renderer, exposed only so instrumentation can prove the default route. */
    @Volatile
    var lastRendererForTests: String = "UNSET"

    /** Pure route selection used by host tests and by the production default contract. */
    fun defaultRoute(capabilities: RenderBackendCapabilities): RenderBackend =
        RenderBackendPolicy.decide(capabilities).backend

    /** The product default is GPU. It never falls through to CPU when GPU setup fails. */
    fun render(
        context: Context,
        sourceUri: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        draft: EditorDraft,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ) {
        renderGpu(context, sourceUri, output, startMs, endMs, draft, cancelled, progress)
    }

    /** Explicit GPU entry point for callers that want to make the backend choice visible. */
    fun renderGpu(
        context: Context,
        sourceUri: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        draft: EditorDraft,
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ) {
        lastRendererForTests = "GPU"
        GpuVideoPipeline.render(
            context = context,
            uri = sourceUri,
            output = output,
            startMs = startMs,
            endMs = endMs,
            draft = draft,
            cancelled = cancelled,
            progress = progress,
        )
    }

    /** CPU MVTools is retained only as an explicit reference path, never as a preference fallback. */
    fun renderCpuReference(
        context: Context,
        sourceUri: Uri,
        output: File,
        startMs: Long,
        endMs: Long,
        draft: EditorDraft,
        cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ) {
        lastRendererForTests = "CPU_REFERENCE"
        val quality = listOf("Fast", "Balanced", "Quality").indexOf(draft.quality)
        require(quality >= 0) { "Unknown MVTools quality" }
        require(draft.strength.isFinite() && draft.strength in 10f..200f) { "Invalid blur strength" }
        var session: NativeMotionBlur? = null
        try {
            VideoPipeline.render(
                context,
                sourceUri,
                output,
                startMs,
                endMs,
                FrameEffect { previous, current, next ->
                    val engine = session ?: NativeMotionBlur(current.width, current.height, quality, draft.strength)
                        .also { session = it }
                    engine.apply(previous, current, next)
                },
                cancelled,
                progress,
            )
        } finally {
            session?.close()
        }
    }
}
