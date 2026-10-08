package dev.motionblur.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.motionblur.app.logic.EditorDraft
import dev.motionblur.app.logic.Timeline
import dev.motionblur.app.render.RenderEngine
import dev.motionblur.app.render.RenderSupervisor
import dev.motionblur.app.media.UnsupportedMediaException
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class RenderKind { PREVIEW, EXPORT }

data class RenderRequest(
    val id: String, val revision: Long, val kind: RenderKind, val sourceUri: Uri,
    val startMs: Long, val endMs: Long, val draft: EditorDraft,
)

data class RenderArtifact(val file: File, val sourceStartMs: Long, val sourceEndMs: Long)

sealed interface RenderState {
    data object Idle : RenderState
    data class Running(val request: RenderRequest, val progress: Float = 0f, val cancelling: Boolean = false) : RenderState
    data class Failed(val request: RenderRequest, val message: String) : RenderState
    data class Complete(val request: RenderRequest) : RenderState
}

/** Only one worker may own video resources, including during cancellation cleanup. */
class EditorState(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("studio", 0)
    var draft by mutableStateOf(readDraft()); private set
    var source by mutableStateOf<SourceClip?>(null); private set
    var timeline by mutableStateOf<Timeline?>(null); private set
    var preview by mutableStateOf(false); private set
    var previewArtifact by mutableStateOf<RenderArtifact?>(null); private set
    var showProcessedPreview by mutableStateOf(false); private set
    var renderState by mutableStateOf<RenderState>(RenderState.Idle); private set
    val isRendering get() = renderState is RenderState.Running
    var latestExport by mutableStateOf(restoreExport()); private set
    var saving by mutableStateOf(false); private set
    var outputMessage by mutableStateOf<String?>(null); private set
    var opening by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var position by mutableLongStateOf(0L)
    var seekRequest by mutableLongStateOf(0L); private set
    var seekSerial by mutableIntStateOf(0); private set
    private var inspection: Job? = null
    private var generation = 0
    private var revision = 0L
    private var cancellation: AtomicBoolean? = null

    init {
        cleanupPreviews()
        prefs.getString("uri", null)?.let { open(Uri.parse(it), restore = true) }
    }

    fun retainPreviewForPlayback(file: File) {
        previewLeases[file] = (previewLeases[file] ?: 0) + 1
    }

    fun releasePreviewsAfterPlayback(files: Collection<File>) {
        files.forEach { file ->
            val remaining = (previewLeases[file] ?: 1) - 1
            if (remaining == 0) previewLeases.remove(file) else previewLeases[file] = remaining
        }
        cleanupPreviews()
    }

    private fun cleanupPreviews() {
        File(getApplication<Application>().cacheDir, "previews").listFiles()?.forEach { file ->
            if (file.extension == "mp4" && file != previewArtifact?.file && file !in previewLeases) file.delete()
        }
    }

    fun open(uri: Uri?, restore: Boolean = false) {
        if (uri == null) return
        if (isRendering) { error = "Cancel the render before replacing the clip."; return }
        inspection?.cancel()
        invalidatePreview()
        val request = ++generation
        opening = true
        error = null
        inspection = viewModelScope.launch {
            try {
                val clip = withContext(Dispatchers.IO) { inspectSource(getApplication(), uri) }
                ensureActive()
                if (request != generation) return@launch
                require(clip.duration > 0)
                runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val full = Timeline(clip.duration)
                val restored = if (restore) runCatching {
                    full.trim(prefs.getLong("in", 0), prefs.getLong("out", clip.duration))
                        .dragPreview(prefs.getLong("window", 0), 0)
                }.getOrDefault(full) else full
                source = clip
                timeline = restored
                preview = false
                seek(restored.start)
                saveClip()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                error = if (restore) "The saved video cannot be opened. Choose it again to restore source access."
                    else "Could not open this video. Choose another file; your current clip is unchanged."
            } finally { if (request == generation) opening = false }
        }
    }

    fun dismissError() { error = null }
    fun reportOutput(message: String) { outputMessage = message }

    fun updateDraft(value: EditorDraft) {
        val actual = value.copy(
            compute = "GPU",
            fallback = "Never",
            decoder = "Auto (hardware first)",
            encoder = "Auto (hardware first)",
            times = 1,
        )
        if (actual == draft) return
        val processingChanged = !draft.sameRenderSettings(actual)
        draft = actual
        if (processingChanged) invalidatePreview()
        prefs.edit().putString("preset", actual.preset).putFloat("strength", actual.strength)
            .putString("quality", actual.quality).putBoolean("advanced", actual.advanced).putBoolean("dynamicBlur", actual.dynamicBlur)
            .putString("videoCodec", actual.videoCodec.name).putFloat("bitrateMbps", actual.bitrateMbps ?: -1f)
            .putFloat("encodingEffort", actual.encodingEffort ?: -1f)
            .putString("compute", actual.compute).putString("fallback", actual.fallback)
            .putString("decoder", actual.decoder).putString("encoder", actual.encoder).apply()
    }

    fun setTrim(start: Long, end: Long, seekEnd: Boolean = false) {
        val next = timeline?.trim(start, end) ?: return
        if (next == timeline) return
        timeline = next
        invalidatePreview()
        seek(if (seekEnd) next.end - 1 else next.start)
        saveClip()
    }

    fun movePreview(pointer: Long, offset: Long) {
        val next = timeline?.dragPreview(pointer, offset) ?: return
        if (next == timeline) return
        timeline = next
        invalidatePreview()
        seek(next.previewStart)
        saveClip()
    }

    fun togglePreview() {
        preview = !preview
        if (!preview) invalidatePreview()
        else seek(timeline?.previewStart ?: 0)
    }

    fun renderPreview() = startRender(RenderKind.PREVIEW)
    fun exportVideo() = startRender(RenderKind.EXPORT)
    fun retryRender() { (renderState as? RenderState.Failed)?.let { startRender(it.request.kind) } }

    private fun startRender(kind: RenderKind) {
        if (isRendering || opening) return
        val clip = source ?: return
        val t = timeline ?: return
        val request = RenderRequest(UUID.randomUUID().toString(), revision, kind, clip.uri,
            if (kind == RenderKind.PREVIEW) t.previewStart else t.start,
            if (kind == RenderKind.PREVIEW) t.previewStart + t.windowLength else t.end, draft.copy())
        val token = AtomicBoolean(false)
        val lastProgressMs = java.util.concurrent.atomic.AtomicLong(0)
        cancellation = token
        renderState = RenderState.Running(request)
        outputMessage = null
        if (kind == RenderKind.PREVIEW) { preview = true; seek(request.startMs) }
        val app = getApplication<Application>()
        val directory = if (kind == RenderKind.EXPORT) File(app.filesDir, "exports") else File(app.cacheDir, "previews")
        val output = File(directory, "OpenBlur-${request.id}.mp4")
        val partial = File(directory, "OpenBlur-${request.id}.partial")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RenderSupervisor.run(cancelled = { token.get() }) { stopped, heartbeat ->
                        check(!token.get()) { "Cancelled" }
                        check(directory.isDirectory || directory.mkdirs()) { "Output directory unavailable" }
                        try {
                            RenderEngine.render(app, request.sourceUri, partial, request.startMs, request.endMs,
                                request.draft, stopped) { value ->
                                heartbeat()
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (value.isFinite() && !token.get() && (value >= 1f || now - lastProgressMs.get() >= 100)) {
                                    lastProgressMs.set(now)
                                    viewModelScope.launch {
                                        val running = renderState as? RenderState.Running
                                        if (running?.request?.id == request.id && !running.cancelling)
                                            renderState = running.copy(progress = maxOf(running.progress, value.coerceIn(0f, 1f)))
                                    }
                                }
                            }
                            check(!stopped()) { "Cancelled" }
                            check(partial.isFile && partial.length() > 0) { "No video was produced" }
                        } catch (failure: Throwable) { partial.delete(); throw failure }
                    }
                }
                ensureActive()
                // Publication is main-thread atomic with respect to source/trim/settings edits.
                if (token.get() || (kind == RenderKind.PREVIEW && revision != request.revision)) return@launch
                check(!output.exists() && partial.renameTo(output)) { "Could not finalize video" }
                if (kind == RenderKind.EXPORT) {
                    latestExport = output
                    prefs.edit().putString("latestExport", output.name).apply()
                    outputMessage = "Export finished. Tap Save As to keep a copy."
                } else {
                    previewArtifact = RenderArtifact(output, request.startMs, request.endMs)
                    showProcessedPreview = true
                    seek(request.startMs)
                }
                renderState = RenderState.Complete(request)
            } catch (cancelled: CancellationException) { token.set(true); throw cancelled }
            catch (failure: Exception) {
                if (!token.get() && (kind == RenderKind.EXPORT || revision == request.revision))
                    renderState = RenderState.Failed(request, renderFailure(failure))
            } catch (_: LinkageError) {
                if (!token.get() && (kind == RenderKind.EXPORT || revision == request.revision))
                    renderState = RenderState.Failed(request, "The selected renderer could not load on this device. Check the GPU capability and retry.")
            } finally {
                if (!RenderSupervisor.active.get()) partial.delete()
                if (cancellation === token) {
                    cancellation = null
                    if (renderState is RenderState.Running) renderState = RenderState.Idle
                }
            }
        }
    }

    private fun renderFailure(failure: Exception): String {
        val detail = failure.message?.replace(Regex("[\\r\\n\\p{Cntrl}]+"), " ")?.take(240)?.trim()
        return when {
            failure is UnsupportedMediaException -> if (detail?.startsWith("GPU rendering unavailable") == true) detail
                else "Unsupported video: ${detail ?: "Choose another clip."}"
            failure is SecurityException -> "Source access was lost. Choose the video again and retry."
            failure.stackTrace.any { it.className.contains("NativeMotionBlur") } ->
                "Reference MVTools processing failed. ${detail ?: "Try Fast quality or a shorter trim."}"
            failure is java.io.IOException -> "Could not read or write the video. Check available storage and source access, then retry."
            else -> "Could not render this video. ${detail ?: "Check free space and source access, then retry."}"
        }
    }

    fun cancelRender() {
        cancellation?.set(true)
        (renderState as? RenderState.Running)?.let { renderState = it.copy(cancelling = true) }
    }

    private fun invalidatePreview() {
        revision++
        if ((renderState as? RenderState.Running)?.request?.kind == RenderKind.PREVIEW) cancelRender()
        previewArtifact = null
        showProcessedPreview = false
        if (!isRendering) renderState = RenderState.Idle
    }

    fun togglePreviewResult() {
        val result = previewArtifact ?: return
        showProcessedPreview = !showProcessedPreview
        position = position.coerceIn(result.sourceStartMs, result.sourceEndMs - 1)
    }

    fun seek(value: Long) {
        val artifact = previewArtifact.takeIf { preview }
        position = if (artifact != null) value.coerceIn(artifact.sourceStartMs, artifact.sourceEndMs - 1)
            else timeline?.clampPlayhead(value) ?: value.coerceAtLeast(0)
        seekRequest = position
        seekSerial++
    }

    fun saveClip() {
        prefs.edit().putString("uri", source?.uri?.toString()).putLong("in", timeline?.start ?: 0)
            .putLong("out", timeline?.end ?: 0).putLong("window", timeline?.previewStart ?: 0).apply()
    }

    /** A failed document provider never consumes/deletes the durable app-private export. */
    fun saveExport(destination: Uri?, file: File) {
        if (destination == null || saving) return
        saving = true
        outputMessage = "Saving copy…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val exports = File(getApplication<Application>().filesDir, "exports").canonicalFile
                    require(file.canonicalFile.parentFile == exports && file.isFile && file.length() > 0)
                    require(destination != Uri.fromFile(file) && destination.authority != "${getApplication<Application>().packageName}.files") {
                        "Choose a separate destination for the copy"
                    }
                    val resolver = getApplication<Application>().contentResolver
                    val expected = MessageDigest.getInstance("SHA-256")
                    val bytes = file.inputStream().use { input ->
                        requireNotNull(resolver.openOutputStream(destination, "wt")).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n); expected.update(buffer, 0, n); count += n
                            }
                            output.flush()
                            count
                        }
                    }
                    val actual = MessageDigest.getInstance("SHA-256")
                    val readBack = requireNotNull(resolver.openInputStream(destination)).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            actual.update(buffer, 0, n); count += n
                        }
                        count
                    }
                    check(bytes > 0 && readBack == bytes && expected.digest().contentEquals(actual.digest()))
                }
                outputMessage = "Saved copy verified. You can still share this export from OpenBlur."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { outputMessage = "Couldn't save the copy. Your export is safe in OpenBlur; try Save As again." }
            finally { saving = false }
        }
    }

    private fun restoreExport(): File? {
        val dir = File(getApplication<Application>().filesDir, "exports")
        val saved = prefs.getString("latestExport", null)?.let { File(dir, it) }
        return saved?.takeIf { it.parentFile?.canonicalFile == dir.canonicalFile && it.isFile && it.extension == "mp4" && it.length() > 0 }
            ?: dir.listFiles()?.filter { it.isFile && it.extension == "mp4" && it.length() > 0 }?.maxByOrNull { it.lastModified() }
    }

    private fun readDraft(): EditorDraft = runCatching {
        val d = EditorDraft()
        val allowed = setOf("Dynamic Light", "Dynamic Medium", "Dynamic Strong", "Dynamic Extreme", "Custom")
        val preset = prefs.getString("preset", d.preset).takeIf { it in allowed } ?: d.preset
        val restored = if (preset == "Custom") {
            val strength = prefs.getFloat("strength", d.strength)
            d.manualStrength(strength.takeIf { it.isFinite() && it in 10f..200f } ?: d.strength)
        } else d.choosePreset(preset)
        restored.copy(times = 1,
            videoCodec = runCatching { dev.motionblur.app.logic.VideoCodec.valueOf(prefs.getString("videoCodec", "H264")!!) }.getOrDefault(dev.motionblur.app.logic.VideoCodec.H264),
            bitrateMbps = prefs.getFloat("bitrateMbps", -1f).takeIf { it.isFinite() && it in 0.25f..100f },
            encodingEffort = prefs.getFloat("encodingEffort", -1f).takeIf { it.isFinite() && it in 0f..1f },
            quality = prefs.getString("quality", d.quality).takeIf { it in setOf("Fast", "Balanced", "Quality", "Ultra quality") } ?: d.quality,
            advanced = prefs.getBoolean("advanced", false),
            dynamicBlur = prefs.getBoolean("dynamicBlur", true),
            // Old CPU drafts use the GPU now; failed capability checks do not trigger a CPU fallback.
            compute = "GPU",
            fallback = "Never",
            decoder = prefs.getString("decoder", "Auto (hardware first)") ?: "Auto (hardware first)",
            encoder = prefs.getString("encoder", "Auto (hardware first)") ?: "Auto (hardware first)")
    }.getOrDefault(EditorDraft().copy(compute = "GPU", fallback = "Never"))

    override fun onCleared() { cancellation?.set(true); super.onCleared() }

    private companion object {
        // Main-thread only; a file remains leased until its ExoPlayer is fully released.
        val previewLeases = mutableMapOf<File, Int>()
    }
}
