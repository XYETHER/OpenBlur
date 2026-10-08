package dev.motionblur.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.motionblur.app.R

object Studio {
    val Ink = Color(0xFF07080B)
    val Surface = Color(0xFF101218)
    val Raised = Color(0xFF171A22)
    val Panel = Color(0xFF1C202A)
    val Text = Color(0xFFF3F5FA)
    val Muted = Color(0xFF9BA1B1)
    val Quiet = Color(0xFF9098AA)
    val Line = Color(0xFF282D38)
    val Edge = Color(0xFF444B5A)
    val Green = Color(0xFF8CFF88)
    val Red = Color(0xFFFF5564)
    val Card = RoundedCornerShape(22.dp)
    val Shape = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(100.dp)
}

@Composable fun OpenBlurTheme(content: @Composable () -> Unit) {
    val font = FontFamily(Font(R.font.manrope, FontWeight.Normal), Font(R.font.manrope, FontWeight.Medium),
        Font(R.font.manrope, FontWeight.SemiBold), Font(R.font.manrope, FontWeight.Bold))
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
        fontFamily = font, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Studio.Text, onPrimary = Studio.Ink, secondary = Studio.Text, onSecondary = Studio.Ink,
            background = Studio.Ink, onBackground = Studio.Text, surface = Studio.Surface, onSurface = Studio.Text,
            surfaceVariant = Studio.Raised, onSurfaceVariant = Studio.Muted, primaryContainer = Studio.Panel,
            onPrimaryContainer = Studio.Text, secondaryContainer = Studio.Raised, onSecondaryContainer = Studio.Text,
            outline = Studio.Edge, error = Studio.Red, surfaceTint = Studio.Ink),
        typography = Typography(
            headlineLarge = style(34, 40, FontWeight.Bold, -.7f),
            headlineSmall = style(26, 32, FontWeight.Bold, -.55f),
            titleLarge = style(21, 27, FontWeight.Bold, -.35f),
            titleMedium = style(18, 24, FontWeight.SemiBold, -.2f),
            bodyLarge = style(15, 22, FontWeight.Medium), bodyMedium = style(14, 20), bodySmall = style(12, 17),
            labelLarge = style(14, 20, FontWeight.Bold, .05f),
            labelMedium = style(12, 18, FontWeight.Bold, .25f), labelSmall = style(10, 14, FontWeight.Bold, 1.1f)),
        content = content)
}

@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), modifier,
    style = MaterialTheme.typography.labelSmall, color = Studio.Quiet)
@Composable fun Help(text: String, modifier: Modifier = Modifier) = Text(text, modifier,
    style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
@Composable fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)
@Composable fun Rule() = HorizontalDivider(color = Studio.Line)
@Composable fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.background(Studio.Surface, Studio.Card).border(1.dp, Studio.Line, Studio.Card).padding(16.dp), content = content)
@Composable fun StatusPill(text: String, accent: Color = Studio.Text) = Row(
    Modifier.background(accent.copy(alpha = .12f), Studio.Pill).border(1.dp, accent.copy(alpha = .28f), Studio.Pill)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(5.dp).background(accent, Studio.Pill))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelSmall, color = accent)
}
@Composable fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(
    onClick, modifier.heightIn(min = 50.dp), enabled = enabled, shape = Studio.Pill,
    colors = ButtonDefaults.buttonColors(containerColor = Studio.Text, contentColor = Studio.Ink,
        disabledContainerColor = Studio.Raised, disabledContentColor = Studio.Quiet)) { Text(label) }
@Composable fun OutlineAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = OutlinedButton(
    onClick, modifier.heightIn(min = 46.dp), shape = Studio.Pill, border = BorderStroke(1.dp, Studio.Edge)) { Text(label) }
