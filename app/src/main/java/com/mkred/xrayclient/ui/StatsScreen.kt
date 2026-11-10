package com.mkred.xrayclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.data.SwitchKind
import com.mkred.xrayclient.data.Traffic
import com.mkred.xrayclient.service.VpnStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val speed by Stats.speed.collectAsStateWithLifecycle()
    val session by Stats.session.collectAsStateWithLifecycle()
    val daily by Stats.daily.collectAsStateWithLifecycle()
    val pings by Stats.pings.collectAsStateWithLifecycle()
    val buckets by Stats.buckets.collectAsStateWithLifecycle()
    val switches by Stats.switches.collectAsStateWithLifecycle()
    val usage by Stats.usage.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()

    var periodHours by remember { mutableIntStateOf(24) }
    var showAllServers by remember { mutableStateOf(false) }
    var showAllSwitches by remember { mutableStateOf(false) }
    val connected = vpn.status == VpnStatus.CONNECTED

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Статистика", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Segmented(listOf(24 to "24 ч", 168 to "7 дней"), periodHours) { periodHours = it }
        }

        // ---- скорость ----
        Panel {
            CardHeader("Скорость сейчас", if (connected) "последние 60 с" else "VPN выключен")
            val last = speed.lastOrNull()
            Row {
                BigValue("↓ загрузка", formatSpeed(if (connected) last?.down ?: 0L else 0L), C.AccentText, Modifier.weight(1f))
                BigValue("↑ отдача", formatSpeed(if (connected) last?.up ?: 0L else 0L), C.Blue, Modifier.weight(1f))
            }
            LineChart(
                series = listOf(
                    ChartSeries(speed.map { it.down.toFloat() }, C.Accent, 60),
                    ChartSeries(speed.map { it.up.toFloat() }, C.Blue, 60),
                ),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
        }

        // ---- трафик ----
        Panel {
            CardHeader("Трафик через VPN", null)
            val today = Stats.day()
            val month = today.take(7)
            val monthTotal = daily.filterKeys { it.startsWith(month) }.values.fold(Traffic()) { a, b -> a + b }
            Row {
                SmallValue("Сессия", formatBytes(session.total), Modifier.weight(1f))
                SmallValue("Сегодня", formatBytes(daily[today]?.total ?: 0L), Modifier.weight(1f))
                SmallValue("Месяц", formatBytes(monthTotal.total), Modifier.weight(1f))
            }
            val days = lastDays(7)
            val values = days.map { daily[it.first]?.total ?: 0L }
            val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
            Row(
                Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEachIndexed { i, (key, label) ->
                    val isToday = key == today
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        val frac = values[i].toFloat() / max
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((70 * frac).coerceAtLeast(2f).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(if (isToday) C.Accent else C.AccentBorder),
                        )
                        Text(label, fontSize = 11.sp, color = if (isToday) C.Text else C.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ---- топ серверов по времени подключения ----
        Panel {
            val periodLabel = if (periodHours == 24) "24 ч" else "7 дней"
            CardHeader("Топ серверов за $periodLabel", "время · трафик")
            val since = System.currentTimeMillis() - periodHours * 3_600_000L
            val top = remember(usage, periodHours) { Stats.topServers(since, 5) }
            val totalSec = remember(usage, periodHours) {
                usage.filter { it.hour >= since }.sumOf { it.seconds.toLong() }.coerceAtLeast(1)
            }
            if (top.isEmpty()) {
                Text("Пока не было подключений за этот период.", fontSize = 13.sp, color = C.Muted)
            }
            top.forEachIndexed { i, s ->
                val share = (s.seconds * 100 / totalSec).toInt()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.width(26.dp).height(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (i == 0) C.Accent else C.Chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (i == 0) C.Bg else C.Text)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(s.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$share%", fontSize = 12.sp, color = C.Muted)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(C.Border)) {
                            Box(
                                Modifier.fillMaxWidth(share / 100f).height(6.dp).clip(RoundedCornerShape(50))
                                    .background(if (i == 0) C.Accent else C.AccentBorder),
                            )
                        }
                        Text(
                            "${formatDuration(s.seconds)} · ↓ ${formatBytes(s.traffic.down)} ↑ ${formatBytes(s.traffic.up)}",
                            fontSize = 12.sp, color = C.Muted,
                        )
                    }
                }
            }
        }

        // ---- пинг текущего сервера ----
        Panel {
            val current = AppStore.profile(vpn.profileId)
            CardHeader(
                "Пинг" + (current?.let { " · ${it.name}" } ?: ""),
                "проверки каждые ${data.checkIntervalSec} с",
            )
            val pts = current?.let { pings[it.id] }.orEmpty().takeLast(60)
            if (current == null || pts.isEmpty()) {
                Text(
                    if (current == null) "Подключитесь — здесь появится пинг текущего сервера." else "Данные появятся после первой проверки.",
                    fontSize = 13.sp, color = C.Muted,
                )
            } else {
                val ok = pts.filter { it.delay > 0 }.map { it.delay }
                val loss = (pts.size - ok.size) * 100 / pts.size
                Row {
                    SmallValue("средний", ok.takeIf { it.isNotEmpty() }?.average()?.toLong()?.toString() ?: "—", Modifier.weight(1f), C.AccentText)
                    SmallValue("мин", ok.minOrNull()?.toString() ?: "—", Modifier.weight(1f))
                    SmallValue("макс", ok.maxOrNull()?.toString() ?: "—", Modifier.weight(1f))
                    SmallValue("потери", "$loss%", Modifier.weight(1f), if (loss > 0) C.Warn else C.Text)
                }
                LineChart(
                    series = listOf(ChartSeries(pts.map { if (it.delay > 0) it.delay.toFloat() else null }, C.AccentText, 60)),
                    failures = pts.indices.filter { pts[it].delay <= 0 },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }

        // ---- серверы ----
        Panel {
            CardHeader("Серверы за ${if (periodHours == 24) "24 ч" else "7 дней"}", "доступность · средний пинг")
            val since = System.currentTimeMillis() - periodHours * 3_600_000L
            val names = profiles.associate { it.id to it.name }
            val rows = buckets.filter { it.hour >= since && it.id in names }
                .groupBy { it.id }
                .map { (id, list) ->
                    val ok = list.sumOf { it.ok }
                    val fail = list.sumOf { it.fail }
                    val avg = if (ok > 0) list.sumOf { it.sumDelay } / ok else -1L
                    Triple(names.getValue(id), if (ok + fail > 0) ok * 100 / (ok + fail) else 0, avg)
                }
                .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenBy { if (it.third < 0) Long.MAX_VALUE else it.third })
            if (rows.isEmpty()) {
                Text("Пока нет проверок за этот период.", fontSize = 13.sp, color = C.Muted)
            }
            (if (showAllServers) rows else rows.take(8)).forEach { (name, uptime, avg) ->
                val color = when {
                    uptime >= 90 -> C.Accent
                    uptime >= 50 -> C.Warn
                    else -> C.Error
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            "$uptime% · ${if (avg > 0) "$avg мс" else "—"}",
                            fontSize = 13.sp, color = if (uptime < 50) C.Error else C.Muted,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(C.Border)) {
                        Box(Modifier.fillMaxWidth(uptime / 100f).height(6.dp).clip(RoundedCornerShape(50)).background(color))
                    }
                }
            }
            if (rows.size > 8) {
                TextButton(onClick = { showAllServers = !showAllServers }) {
                    Text(if (showAllServers) "Свернуть" else "Показать все (${rows.size})", color = C.AccentText)
                }
            }
        }

        // ---- переключения ----
        Panel {
            CardHeader("Переключения", if (switches.isNotEmpty()) "всего ${switches.size}" else null)
            if (switches.isEmpty()) Text("Пока не было.", fontSize = 13.sp, color = C.Muted)
            (if (showAllSwitches) switches else switches.take(5)).forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(formatTime(e.ts), fontSize = 12.sp, color = C.Muted, modifier = Modifier.width(48.dp).padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (e.kind) {
                                SwitchKind.CONNECT -> "Подключение · ${e.to}"
                                SwitchKind.NETWORK -> e.to
                                else -> "${e.from ?: "—"} → ${e.to}"
                            },
                            fontSize = 13.sp,
                        )
                        val (bg, fg) = when (e.kind) {
                            SwitchKind.FAIL, SwitchKind.SLOW, SwitchKind.FREEZE -> Color(0xFF3A2020) to C.Error
                            SwitchKind.NETWORK -> Color(0xFF2A2418) to C.Warn
                            SwitchKind.FASTER, SwitchKind.PRIORITY -> C.AccentBg to C.AccentText
                            else -> C.Chip to Color(0xFFB9C3D4)
                        }
                        Text(
                            e.reason, fontSize = 11.sp, color = fg,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (switches.size > 5) {
                TextButton(onClick = { showAllSwitches = !showAllSwitches }) {
                    Text(if (showAllSwitches) "Свернуть" else "Показать все (${switches.size})", color = C.AccentText)
                }
            }
        }

        TextButton(onClick = { Stats.clear() }, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Очистить статистику", color = C.Muted)
        }
    }
}

@Composable
private fun CardHeader(title: String, hint: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        hint?.let { Text(it, fontSize = 12.sp, color = C.Muted) }
    }
}

@Composable
private fun BigValue(label: String, value: String, labelColor: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = labelColor)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallValue(label: String, value: String, modifier: Modifier, color: Color = C.Text) {
    Column(modifier) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = C.Muted)
    }
}

@Composable
private fun Segmented(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) C.Chip else C.Surface)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(label, fontSize = 12.sp, color = if (sel) C.Text else C.Muted, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Последние n дней: ключ yyyy-MM-dd и короткое название дня недели. */
private fun lastDays(n: Int): List<Pair<String, String>> {
    val cal = Calendar.getInstance()
    val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayFmt = SimpleDateFormat("EE", Locale.forLanguageTag("ru"))
    cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
    return (0 until n).map {
        val d = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 1)
        keyFmt.format(d) to dayFmt.format(d).lowercase().take(2)
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return when {
        h > 0 -> "$h ч $m мин"
        m > 0 -> "$m мин"
        else -> "$sec с"
    }
}

private fun formatTime(ts: Long): String {
    val isToday = Stats.day(ts) == Stats.day()
    return SimpleDateFormat(if (isToday) "HH:mm" else "dd.MM", Locale.US).format(Date(ts))
}
