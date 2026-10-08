package dev.motionblur.app.ui

import android.content.ClipData
import android.content.Intent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.WeakHashMap

// Animated tab transitions can briefly have two owners of the same view.
private val awakeOwners = WeakHashMap<View, Pair<Int, Boolean>>()

@Composable fun RenderKeepAwake(state: EditorState) {
    val view = LocalView.current
    val running = state.isRendering
    DisposableEffect(view, running) {
        if (running) {
            val previous = awakeOwners[view] ?: (0 to view.keepScreenOn)
            awakeOwners[view] = (previous.first + 1) to previous.second
            view.keepScreenOn = true
        }
        onDispose {
            if (running) awakeOwners[view]?.let { previous ->
                if (previous.first == 1) {
                    view.keepScreenOn = previous.second
                    awakeOwners.remove(view)
                } else awakeOwners[view] = (previous.first - 1) to previous.second
            }
        }
    }
}

@Composable fun RenderControls(state: EditorState) {
    if (state.source == null && state.latestExport == null && state.outputMessage == null &&
        state.renderState !is RenderState.Running && state.renderState !is RenderState.Failed) return
    val context = LocalContext.current
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val path = pendingSave
        pendingSave = null
        if (uri != null && path != null) state.saveExport(uri, File(path))
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        when (val render = state.renderState) {
            is RenderState.Running -> {
                Text(if (render.cancelling) "Cancelling…" else if (render.request.kind == RenderKind.PREVIEW) "Rendering preview…" else "Exporting video…")
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { render.progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Help("${(render.progress * 100).toInt()}%", Modifier.weight(1f))
                    TextButton(onClick = state::cancelRender, enabled = !render.cancelling) { Text("Cancel render") }
                }
                if (render.cancelling) Help("Finishing cleanup…")
            }
            is RenderState.Failed -> {
                Help(render.message)
                TextButton(onClick = state::retryRender) { Text(if (render.request.kind == RenderKind.PREVIEW) "Retry preview" else "Retry export") }
            }
            else -> Unit
        }
        if (state.source != null) Action("Export video", state::exportVideo, Modifier.fillMaxWidth(), enabled = !state.isRendering && !state.opening)
        state.latestExport?.let { file ->
            Spacer(Modifier.height(12.dp))
            Text("Latest export", style = MaterialTheme.typography.titleMedium)
            Help("Tap Save As to keep a copy in your files or gallery.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    try {
                        check(file.isFile && file.length() > 0)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("OpenBlur video", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share video"))
                    } catch (_: Exception) { state.reportOutput("Could not share this export. Try Save As instead.") }
                }) { Text("Share") }
                TextButton(onClick = {
                    try {
                        check(file.isFile && file.length() > 0)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            clipData = ClipData.newRawUri("OpenBlur video", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) { state.reportOutput("No video player could open this export. Try Share or Save As.") }
                }) { Text("Play export") }
                TextButton(onClick = {
                    pendingSave = file.absolutePath
                    try { saveAs.launch(file.name) }
                    catch (_: Exception) { pendingSave = null; state.reportOutput("No document provider could open. Your export is safe in OpenBlur.") }
                }, enabled = !state.saving && pendingSave == null) { Text(if (state.saving) "Saving…" else "Save As") }
            }
        }
        state.outputMessage?.let { Help(it) }
    }
}
