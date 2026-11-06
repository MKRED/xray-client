package com.mkred.xrayclient.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Одна линия графика. null в values — разрыв линии (например, сервер не ответил).
 * capacity — сколько точек помещается по ширине; данные прижаты к правому краю.
 */
class ChartSeries(val values: List<Float?>, val color: Color, val capacity: Int = values.size)

/** Простой линейный график без осей: базовая линия, пунктир посередине и линии серий. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier,
    /** Индексы точек (в первой серии), где отметить сбой красной точкой внизу. */
    failures: List<Int> = emptyList(),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(C.Border, Offset(0f, h - 1), Offset(w, h - 1), strokeWidth = 1.dp.toPx())
        drawLine(
            Color(0xFF1F232B), Offset(0f, h / 2), Offset(w, h / 2),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )
        val maxV = series.flatMap { s -> s.values.filterNotNull() }.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val top = 6.dp.toPx()
        val usable = h - top - 2.dp.toPx()

        series.forEach { s ->
            val cap = s.capacity.coerceAtLeast(2)
            val offset = cap - s.values.size
            val step = w / (cap - 1)
            val path = Path()
            var drawing = false
            s.values.forEachIndexed { i, v ->
                if (v == null) {
                    drawing = false
                    return@forEachIndexed
                }
                val x = (offset + i) * step
                val y = top + usable * (1f - v / maxV)
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        series.firstOrNull()?.let { s ->
            val cap = s.capacity.coerceAtLeast(2)
            val step = w / (cap - 1)
            val offset = cap - s.values.size
            failures.forEach { i ->
                drawCircle(C.Error, radius = 3.dp.toPx(), center = Offset((offset + i) * step, h - 4.dp.toPx()))
            }
        }
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024L * 1024 -> String.format(Locale.US, "%.0f КБ", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", b / 1048576.0).replace('.', ',')
    else -> String.format(Locale.US, "%.2f ГБ", b / 1073741824.0).replace('.', ',')
}

fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/с"
