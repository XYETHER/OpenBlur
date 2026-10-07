package dev.motionblur.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable fun SettingsScreen(state: EditorState) {
    RenderKeepAwake(state)
    var choosingQuality by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf(false) }
    var selectedNotice by remember { mutableStateOf("Third-party notices" to "NOTICE.txt") }
    var choosingNotice by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Text("Processing", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Help("GPU · Motion estimation and blur")
                Help("Codec · Auto (device-supported decode and encode)")
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { choosingQuality = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Vector quality"); Help(state.draft.quality) }
                    Text("Change  ›", color = Studio.Muted, style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().toggleable(state.draft.dynamicBlur, role = Role.Checkbox,
                    onValueChange = { state.updateDraft(state.draft.copy(dynamicBlur = it)) })
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.draft.dynamicBlur, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic blur")
                        Help(if (state.draft.dynamicBlur) "Adapts around scene changes." else "Fixed MVTools-style motion blur.")
                    }
                }
            }
        }
        item { EncodingControls(state) }
        item { RenderControls(state) }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Help("Processing stays on this device. Share and Save As send a copy only to the app or location you choose.")
            }
        }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Eyebrow("OpenBlur")
                Spacer(Modifier.height(4.dp))
                Text(version, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { license = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Licenses", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("View  ›", color = Studio.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (choosingQuality) ChoiceDialog("Vector quality", state.draft.quality, listOf("Fast", "Balanced", "Quality", "Ultra quality"),
        "Changes apply to the next export; a preview is reset when its settings change.", { choosingQuality = false }) {
        state.updateDraft(state.draft.copy(quality = it))
    }
    if (license) AlertDialog(onDismissRequest = { license = false }, containerColor = Studio.Raised, title = { Text("Licenses") }, text = {
        Column {
            Box {
                TextButton(onClick = { choosingNotice = true }) { Text("${selectedNotice.first}  ▾") }
                DropdownMenu(expanded = choosingNotice, onDismissRequest = { choosingNotice = false }) {
                    listOf("Third-party notices" to "NOTICE.txt", "OpenBlur / MVTools" to "OpenBlur-LICENSE.txt",
                        "VapourSynth headers" to "VapourSynth-LICENSE.txt", "Inter" to "Inter-LICENSE.txt",
                        "Manrope" to "Manrope-OFL.txt").forEach { notice ->
                        DropdownMenuItem(text = { Text(notice.first) }, onClick = { selectedNotice = notice; choosingNotice = false })
                    }
                }
            }
            Text(remember(selectedNotice.second) { context.assets.open(selectedNotice.second).bufferedReader().use { it.readText() } },
                Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { license = false }) { Text("Close") } })
}
