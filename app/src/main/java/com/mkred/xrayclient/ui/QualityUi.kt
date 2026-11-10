package com.mkred.xrayclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.core.Quality
import com.mkred.xrayclient.core.QualityInfo
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Stats
import java.util.Locale

val Purple = Color(0xFFB794F6)

fun qualityColor(q: QualityInfo): Color = when {
    !q.complete -> Color(0xFFB9C3D4)
    q.score >= 80 -> C.Accent
    q.score >= 50 -> C.Warn
    q.score > 0 -> C.Error
    else -> Color(0xFF6B7280)
}

fun formatMbps(v: Float): String =
    if (v >= 10) "${v.toInt()} Мбит/с" else String.format(Locale.US, "%.1f Мбит/с", v).replace('.', ',')

/** Текущая метрика сервера: «48 мс» или значок качества. Сам следит за данными. */
@Composable
fun MetricValue(p: Profile, big: Boolean = false) {
    val data by AppStore.data.collectAsStateWithLifecycle()
    val delays by AppStore.delays.collectAsStateWithLifecycle()
    if (data.metric == Metric.QUALITY) {
        val speeds by Stats.speeds.collectAsStateWithLifecycle()
        val buckets by Stats.buckets.collectAsStateWithLifecycle()
        QualityPill(Quality.of(p, delays, speeds, buckets), big)
    } else {
        val d = delays[p.id]
        Text(delayText(d), fontSize = if (big) 14.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = delayColor(d))
    }
}

/** Метрика текстом: «48 мс» или «85» / «≈38». */
@Composable
fun metricText(p: Profile): String {
    val data by AppStore.data.collectAsStateWithLifecycle()
    val delays by AppStore.delays.collectAsStateWithLifecycle()
    if (data.metric != Metric.QUALITY) return delayText(delays[p.id])
    val speeds by Stats.speeds.collectAsStateWithLifecycle()
    val buckets by Stats.buckets.collectAsStateWithLifecycle()
    return "качество " + Quality.of(p, delays, speeds, buckets).label
}

/** Подпись под названием сервера в режиме качества: «74 Мбит/с · 48 мс». */
@Composable
fun qualitySubtitle(p: Profile): String {
    val delays by AppStore.delays.collectAsStateWithLifecycle()
    val speeds by Stats.speeds.collectAsStateWithLifecycle()
    val sp = speeds[p.id]?.lastOrNull()?.mbps?.takeIf { it > 0f }
    val d = delays[p.id]
    val speedPart = sp?.let { formatMbps(it) } ?: "скорость не измерялась"
    return "$speedPart · ${delayText(d)}"
}

@Composable
fun QualityPill(q: QualityInfo, big: Boolean = false) {
    val color = qualityColor(q)
    val shape = RoundedCornerShape(if (big) 10.dp else 9.dp)
    val m = Modifier.widthIn(min = if (big) 44.dp else 40.dp).clip(shape)
    Box(
        if (q.complete && q.score > 0) m.background(color)
        else if (!q.complete && q.score > 0) m.border(BorderStroke(1.dp, Color(0xFF5A6070)), shape)
        else m.background(Color(0xFF22252C)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            q.label,
            fontSize = if (big) 15.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (q.complete && q.score > 0) C.Bg else color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Кольцо качества. */
@Composable
fun QualityRing(q: QualityInfo, size: Dp = 104.dp) {
    val color = qualityColor(q)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.1f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(C.Border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (q.score > 0) drawArc(
                color, -90f, 360f * q.score / 100f, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(q.label, fontSize = (size.value * 0.28f).sp, fontWeight = FontWeight.Bold)
            Text(if (q.complete) "из 100" else "из 50", fontSize = 11.sp, color = C.Muted)
        }
    }
}

/** Строка разбивки: «Скорость  74 Мбит/с  37 / 50» и полоса. */
@Composable
fun QualityPart(label: String, value: String, pts: Int, max: Int, color: Color, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("$pts / $max", fontSize = 12.sp, color = C.Muted, modifier = Modifier.widthIn(min = 52.dp), textAlign = TextAlign.End)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = color, trackColor = C.Border, drawStopIndicator = {},
        )
    }
}

fun agoText(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        m < 24 * 60 -> "${m / 60} ч назад"
        else -> "${m / (24 * 60)} дн. назад"
    }
}
