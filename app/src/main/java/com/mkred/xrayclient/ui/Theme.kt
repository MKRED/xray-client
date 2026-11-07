package com.mkred.xrayclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object C {
    val Bg = Color(0xFF0F1115)
    val Surface = Color(0xFF181B21)
    val Surface2 = Color(0xFF12151A)
    val Border = Color(0xFF262A33)
    val Chip = Color(0xFF232833)
    val Text = Color(0xFFE7E9EE)
    val Muted = Color(0xFF9AA1AD)
    val Accent = Color(0xFF3DDC84)
    val AccentText = Color(0xFF7EE2A8)
    val AccentBg = Color(0xFF1C2A22)
    val AccentBorder = Color(0xFF2E5A40)
    val Warn = Color(0xFFF5B971)
    val Error = Color(0xFFFF8A80)
    val Blue = Color(0xFF6EA8FE)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.Bg,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Chip,
            onSurfaceVariant = C.Muted,
            outline = C.Border,
            error = C.Error,
        ),
        content = content,
    )
}

fun delayColor(ms: Long?): Color = when {
    ms == null -> C.Muted
    ms < 0 -> C.Error
    ms < 150 -> C.AccentText
    else -> C.Warn
}

fun delayText(ms: Long?): String = when {
    ms == null -> "—"
    ms < 0 -> "недоступен"
    else -> "$ms мс"
}

/** Простые контурные иконки, без зависимости от material-icons. */
object Icons {
    val Power = icon("power", "M12 3v9", "M6.3 7.3a8 8 0 1 0 11.4 0")
    val Servers = icon("servers", "M5 4h14a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z",
        "M5 14h14a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z")
    val Link = icon("link", "M10 13a5 5 0 0 0 7.5 0.5l3 -3a5 5 0 0 0 -7 -7l-1.7 1.7",
        "M14 11a5 5 0 0 0 -7.5 -0.5l-3 3a5 5 0 0 0 7 7l1.7 -1.7")
    val Settings = icon("settings", "M4 6h10", "M18 6h2", "M4 18h4", "M12 18h8", "M4 12h2", "M10 12h10",
        "M14 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M8 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0")
    val Refresh = icon("refresh", "M21 12a9 9 0 1 1 -2.6 -6.4", "M21 3v6h-6")
    val Bolt = icon("bolt", "M13 2l-9 12h7l-1 8l9 -12h-7l1 -8z")
    val Up = icon("up", "M6 15l6 -6l6 6")
    val Down = icon("down", "M6 9l6 6l6 -6")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Close = icon("close", "M6 6l12 12", "M18 6l-12 12")
    val Chart = icon("chart", "M4 20v-8", "M10 20v-14", "M16 20v-5", "M22 20v-11", "M2 20h20")
    val Filter = icon("filter", "M3 5h18l-7 8v6l-4 -2v-4z")
    val Target = icon("target", "M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val Globe = icon("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M3 12h18", "M12 3a14 14 0 0 1 0 18", "M12 3a14 14 0 0 0 0 18")
    val Chevron = icon("chevron", "M9 6l6 6l-6 6")

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
