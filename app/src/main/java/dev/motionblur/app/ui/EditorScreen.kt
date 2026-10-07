package dev.motionblur.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.motionblur.app.OpeningVideo
import kotlinx.coroutines.*

@Composable fun EditorScreen(state: EditorState, onImport: () -> Unit) {
    RenderKeepAwake(state)
    val clip = state.source ?: return
    val context = LocalContext.current
    val playback = rememberSourcePlayback(state)
    var frames by remember(clip.uri) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(clip.uri) {
        try { frames = withContext(Dispatchers.IO) { sourceThumbnails(context, clip) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { frames = emptyList() }
    }
    DisposableEffect(clip.uri) { onDispose { frames.forEach { it.recycle() } } }
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(clip.name, style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Help("${clip.width} × ${clip.height}  ·  ${timeLabel(clip.duration)}")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlineAction("Replace", {
                        if (state.isRendering) state.reportOutput("Cancel the render before replacing the clip.")
                        else onImport()
                    })
                }
            }
            if (state.opening) item { OpeningVideo() }
            state.error?.let { error -> item { Help(error) } }
            item { SourceViewport(state, playback) }
            item { TimelineControls(state, frames) }
            item { BlurControls(state) }
            item { RenderControls(state) }
        }
    }
}
