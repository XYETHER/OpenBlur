package dev.motionblur.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable fun TimelineControls(state: EditorState, frames: List<Bitmap>) {
    val t = state.timeline ?: return
    val previewBorder by animateColorAsState(if (state.preview) Studio.Green else Studio.Edge, label = "preview border")
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Trim", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(76.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(58.dp).align(Alignment.Center).background(Studio.Raised, Studio.Shape)) {
                frames.forEach { frame -> androidx.compose.foundation.Image(frame.asImageBitmap(), null,
                    Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
            }
            Canvas(Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "Source timeline. Drag trim handles or scrub"
                    progressBarRangeInfo = ProgressBarRangeInfo(state.position.toFloat(), 0f..t.duration.toFloat())
                    setProgress { state.seek(it.toLong()); true }
                }
                .pointerInput(state.source?.uri) { detectTapGestures { state.seek((it.x / size.width * t.duration).toLong()) } }
                .pointerInput(state.source?.uri) {
                    var target = 0; var grab = 0L
                    detectDragGestures(onDragStart = { point ->
                        val current = state.timeline ?: return@detectDragGestures
                        val time = (point.x / size.width * current.duration).toLong()
                        val startX = current.start.toFloat() / current.duration * size.width
                        val endX = current.end.toFloat() / current.duration * size.width
                        val left = abs(point.x - startX); val right = abs(point.x - endX)
                        target = when {
                            minOf(left, right) <= 22.dp.toPx() -> if (left <= right) 1 else 2
                            state.preview && time in current.previewStart..(current.previewStart + current.windowLength) -> 3
                            else -> 4
                        }
                        grab = time - current.previewStart
                    }, onDrag = { change, _ ->
                        change.consume(); val current = state.timeline ?: return@detectDragGestures
                        val time = (change.position.x / size.width * current.duration).toLong().coerceIn(0, current.duration)
                        when (target) {
                            1 -> state.setTrim(time.coerceAtMost(current.end - 1), current.end)
                            2 -> state.setTrim(current.start, time.coerceAtLeast(current.start + 1), seekEnd = true)
                            3 -> state.movePreview(time, grab)
                            4 -> state.seek(time)
                        }
                    }, onDragEnd = { target = 0 }, onDragCancel = { target = 0 })
                }) {
                fun width(ms: Long) = ms.toFloat() / t.duration * size.width
                fun x(ms: Long) = width(ms)
                val top = 9.dp.toPx(); val height = size.height - top * 2
                drawRect(Color.Black.copy(alpha = .72f), Offset(0f, top), Size(x(t.start), height))
                drawRect(Color.Black.copy(alpha = .72f), Offset(x(t.end), top), Size(size.width - x(t.end), height))
                drawRoundRect(Studio.Text, Offset(x(t.start), top), Size(width(t.end - t.start), height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(1.4.dp.toPx()))
                if (state.preview) {
                    val origin = Offset(x(t.previewStart), top + 4.dp.toPx())
                    val window = Size(width(t.windowLength), height - 8.dp.toPx())
                    drawRoundRect(Studio.Red.copy(alpha = .2f), origin, window, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    drawRoundRect(Studio.Red, origin, window, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
                listOf(t.start, t.end).forEach { at ->
                    drawRoundRect(Studio.Text, Offset(x(at) - 4.dp.toPx(), top - 2.dp.toPx()), Size(8.dp.toPx(), height + 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                }
                drawLine(Studio.Text, Offset(x(state.position), 0f), Offset(x(state.position), size.height), 1.5.dp.toPx())
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = state::togglePreview, shape = Studio.Pill, border = BorderStroke(1.dp, previewBorder),
                modifier = Modifier.heightIn(min = 42.dp).semantics { selected = state.preview; stateDescription = if (state.preview) "On" else "Off" }) {
                Text(if (state.preview) "Preview on" else "Preview")
            }
            Spacer(Modifier.width(10.dp))
            Help("${t.windowLength / 1000}s · drag red range")
        }
        if (state.preview) {
            Spacer(Modifier.height(10.dp))
            Help("${timeLabel(t.previewStart)} – ${timeLabel(t.previewStart + t.windowLength)}")
            Action(if (state.previewArtifact == null) "Render preview" else "Render preview again",
                state::renderPreview, Modifier.fillMaxWidth(), enabled = !state.isRendering && !state.opening)
            if (state.previewArtifact != null) Help("Use Original / Processed above to compare the same moment.")
        }
    }
}
