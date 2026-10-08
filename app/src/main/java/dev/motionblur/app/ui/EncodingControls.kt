package dev.motionblur.app.ui

import android.media.MediaCodecList
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.motionblur.app.gpu.encodingEffortSupported
import dev.motionblur.app.gpu.hardwareEncoding
import dev.motionblur.app.logic.VideoCodec
import java.util.Locale

@Composable fun EncodingControls(state: EditorState) {
    val draft = state.draft
    var codecsOpen by remember { mutableStateOf(false) }
    val supportedCodecs = remember {
        VideoCodec.entries.filter { codec -> MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            it.isEncoder && hardwareEncoding(it) && it.supportedTypes.any { type -> type.equals(codec.mime, true) }
        } }
    }
    val speedSupported = remember(draft.videoCodec) { encodingEffortSupported(draft.videoCodec.mime) }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("Encoding", style = MaterialTheme.typography.titleMedium)
        Help(if ((state.renderState as? RenderState.Running)?.request?.kind == RenderKind.EXPORT) "Changes apply to your next export." else "Choose settings for previews and exports.")
        Box {
            TextButton(onClick = { codecsOpen = true }) { Text("Encoder: ${draft.videoCodec.label}  ▾") }
            DropdownMenu(expanded = codecsOpen, onDismissRequest = { codecsOpen = false }) {
                VideoCodec.entries.forEach { codec ->
                    DropdownMenuItem(text = { Text(codec.label) }, enabled = codec in supportedCodecs, onClick = {
                        state.updateDraft(draft.copy(videoCodec = codec, encodingEffort = null)); codecsOpen = false
                    })
                }
            }
        }
        if (VideoCodec.H265 !in supportedCodecs) Help("This phone doesn't support H.265 encoding.")
        Row(Modifier.fillMaxWidth()) {
            Text("Bitrate", Modifier.weight(1f))
            Text(draft.bitrateMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "Automatic")
        }
        TextButton(onClick = { state.updateDraft(draft.copy(bitrateMbps = null)) }) { Text("Use automatic bitrate") }
        Slider(value = draft.bitrateMbps ?: 10f, onValueChange = { state.updateDraft(draft.copy(bitrateMbps = it)) }, valueRange = 0.25f..100f)
        Help("Higher bitrate makes larger files with less compression. Automatic chooses the bitrate for you.")
        Spacer(Modifier.height(12.dp))
        Text("Encoding speed")
        if (speedSupported) {
            TextButton(onClick = { state.updateDraft(draft.copy(encodingEffort = null)) }) {
                Text(if (draft.encodingEffort == null) "Device default ✓" else "Use device default")
            }
            Slider(value = draft.encodingEffort ?: 0.5f, onValueChange = { state.updateDraft(draft.copy(encodingEffort = it)) }, valueRange = 0f..1f)
            Row(Modifier.fillMaxWidth()) { Text("Faster", Modifier.weight(1f)); Text("Higher quality") }
            Help("Higher quality may take longer. Speed depends on your phone.")
        } else {
            Help("This encoder runs at a fixed speed.")
            if (draft.encodingEffort != null) TextButton(onClick = { state.updateDraft(draft.copy(encodingEffort = null)) }) { Text("Use device default") }
        }
    }
}
