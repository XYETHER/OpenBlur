package dev.motionblur.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable fun BlurControls(state: EditorState) {
    val draft = state.draft
    val levels = listOf("Light" to "Dynamic Light", "Medium" to "Dynamic Medium", "Strong" to "Dynamic Strong", "Extreme" to "Dynamic Extreme")
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dynamic blur", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            levels.forEach { (label, preset) -> DynamicLevel(label, draft.preset == preset, Modifier.weight(1f)) {
                state.updateDraft(draft.choosePreset(preset))
            } }
        }
        Spacer(Modifier.height(12.dp))
        Help("Adapts around cuts.")
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().background(Studio.Raised, Studio.Shape)
            .toggleable(draft.advanced, role = Role.Checkbox, onValueChange = { state.updateDraft(draft.copy(advanced = it)) })
            .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.advanced, onCheckedChange = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Advanced", style = MaterialTheme.typography.bodyMedium)
                Help(draft.quality)
            }
            Text(if (draft.advanced) "–" else "+", style = MaterialTheme.typography.titleMedium, color = Studio.Muted)
        }
        if (draft.advanced) {
            Spacer(Modifier.height(10.dp))
            DynamicBlurToggle(state)
            Spacer(Modifier.height(10.dp))
            AdvancedPanel(state)
        }
    }
}

@Composable private fun DynamicLevel(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Studio.Text else Studio.Panel, label = "$label fill")
    val outline by animateColorAsState(if (selected) Studio.Text else Studio.Edge, label = "$label outline")
    val scale by animateFloatAsState(if (selected) 1.02f else 1f, spring(stiffness = 620f), label = "$label scale")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 50.dp).background(fill, Studio.Shape)
        .border(1.dp, outline, Studio.Shape).selectable(selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Studio.Ink else Studio.Text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun AdvancedPanel(state: EditorState) {
    var choosing by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().background(Studio.Panel, Studio.Shape).selectable(false, role = Role.Button, onClick = { choosing = true })
        .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow("Vector quality")
            Text(state.draft.quality, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Change  ›", style = MaterialTheme.typography.labelMedium, color = Studio.Muted)
    }
    if (choosing) ChoiceDialog("Vector quality", state.draft.quality, listOf("Fast", "Balanced", "Quality", "Ultra quality"),
        "Balanced is the recommended default.", { choosing = false }) { state.updateDraft(state.draft.copy(quality = it)) }
}

@Composable private fun DynamicBlurToggle(state: EditorState) {
    Row(Modifier.fillMaxWidth().background(Studio.Panel, Studio.Shape)
        .toggleable(state.draft.dynamicBlur, role = Role.Checkbox, onValueChange = { state.updateDraft(state.draft.copy(dynamicBlur = it)) })
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state.draft.dynamicBlur, onCheckedChange = null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Dynamic blur", style = MaterialTheme.typography.bodyMedium)
            Help(if (state.draft.dynamicBlur) "Adapts around scene changes." else "Uses fixed blur settings.")
        }
    }
}

@Composable fun ChoiceDialog(title: String, value: String, choices: List<String>, helper: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Studio.Raised, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Help(helper)
            choices.forEach { choice -> Row(Modifier.fillMaxWidth().selectable(choice == value, role = Role.RadioButton,
                onClick = { onChoose(choice); onDismiss() }).padding(vertical = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(choice == value, onClick = null); Spacer(Modifier.width(10.dp)); Text(choice)
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
