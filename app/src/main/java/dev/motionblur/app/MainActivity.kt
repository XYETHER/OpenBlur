package dev.motionblur.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.motionblur.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 8, 11)
        window.navigationBarColor = android.graphics.Color.rgb(7, 8, 11)
        setContent { OpenBlurApp() }
    }
}

@Composable private fun OpenBlurApp(state: EditorState = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { state.open(it) }
    val import = { picker.launch(arrayOf("video/*")) }
    OpenBlurTheme {
        RenderKeepAwake(state)
        Scaffold(containerColor = Studio.Ink, topBar = { ProductHeader() }, bottomBar = {
            NavigationDock(tab) { tab = it }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = tab, transitionSpec = {
                    fadeIn(tween(180)) + slideInHorizontally(tween(210)) { it / 14 } togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(130)) { -it / 20 })
                }, label = "workspace") { active ->
                    if (active == 1) SettingsScreen(state)
                    else if (state.source == null) EmptyEditor(state, import)
                    else EditorScreen(state, import)
                }
            }
        }
    }
}

@Composable private fun ProductHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("OPENBLUR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
        }
    }
}

@Composable private fun NavigationDock(selected: Int, select: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().background(Studio.Surface, Studio.Pill).border(1.dp, Studio.Line, Studio.Pill).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Studio", "Settings").forEachIndexed { index, label ->
                val fill by animateColorAsState(if (index == selected) Studio.Panel else Studio.Surface, label = "$label dock")
                Row(Modifier.weight(1f).heightIn(min = 46.dp).background(fill, Studio.Pill)
                    .selectable(index == selected, role = Role.Tab, onClick = { select(index) }).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    NavGlyph(index, index == selected)
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = if (index == selected) Studio.Text else Studio.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable private fun NavGlyph(index: Int, selected: Boolean) {
    val color = if (selected) Studio.Text else Studio.Muted
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        if (index == 0) {
            drawRoundRect(color, style = Stroke(stroke))
            drawLine(color, Offset(size.width * .32f, 0f), Offset(size.width * .32f, size.height), stroke)
            drawLine(color, Offset(size.width * .68f, 0f), Offset(size.width * .68f, size.height), stroke)
        } else repeat(3) { i ->
            val y = size.height * (i + .5f) / 3
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
            drawCircle(color, 2.dp.toPx(), Offset(size.width * (if (i == 1) .7f else .3f), y))
        }
    }
}

@Composable private fun EmptyEditor(state: EditorState, onImport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Import a clip", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Help("Choose a video from your device.")
        }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Studio.Raised, Studio.Shape), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(62.dp)) {
                        drawRoundRect(Studio.Edge, style = Stroke(1.5.dp.toPx()))
                        drawCircle(Studio.Text, 4.dp.toPx(), Offset(size.width * .5f, size.height * .5f))
                        drawLine(Studio.Text, Offset(size.width * .28f, size.height * .5f), Offset(size.width * .72f, size.height * .5f), 1.dp.toPx())
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Add video", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Help("Stays on your device.")
                Spacer(Modifier.height(16.dp))
                Action("Import video", onImport, Modifier.fillMaxWidth(), enabled = !state.opening)
            }
        }
        if (state.latestExport != null) item { RenderControls(state) }
        if (state.opening) item { OpeningVideo() }
        state.error?.let { error -> item { Help(error) } }
    }
}

@Composable fun OpeningVideo() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text("Opening video…")
}
