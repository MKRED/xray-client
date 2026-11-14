package com.mkred.xrayclient.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.BuildConfig
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.SplitMode
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import com.mkred.xrayclient.core.XrayCore


/** Разделы настроек (вариант H1: список разделов, каждый — свой экран). */
private enum class SettingsPage(val title: String) {
    METRIC("Оценка серверов"),
    SPEED("Тест и автопроверка скорости"),
    PING("Проверка пинга"),
    APPS("Приложения через VPN"),
    RU("Российские сайты напрямую"),
    LOGS("Логи"),
    ABOUT("О приложении и обновления"),
}

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
    val geoUpdating by vm.geoUpdating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var testUrl by remember(data.testUrl) { mutableStateOf(data.testUrl) }
    var showLog by remember { mutableStateOf(false) }
    var pickApps by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.saveLog(it) }
    }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    val quality = data.metric == Metric.QUALITY
    val current = page
    if (current == null) {
        // ---------------- список разделов ----------------
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))

            GroupLabel("ВЫБОР СЕРВЕРА")
            SettingsGroup {
                SettingsRow(
                    Icons.Chart, IconTone.GREEN, "Оценка серверов",
                    if (quality) "Качество · ${data.qSpeedMax} Мбит/с · ${data.qPingFull}–${data.qPingZero} мс" else "Задержка · только пинг",
                ) { page = SettingsPage.METRIC }
                RowDivider()
                SettingsRow(
                    Icons.Bolt, IconTone.GREEN, "Тест и автопроверка скорости",
                    "Пакет ${data.speedTestMb} МБ · " + when {
                        !quality -> "автопроверка — в режиме «Качество»"
                        data.autoCheck -> "автопроверка вкл"
                        else -> "автопроверка выкл"
                    } + if (quality && data.sweep) " · обход раз в ${data.sweepHours} ч" else "",
                ) { page = SettingsPage.SPEED }
                RowDivider()
                SettingsRow(
                    Icons.Clock, IconTone.GREEN, "Проверка пинга",
                    "Каждые ${data.checkIntervalSec} с · " + data.testUrl.substringAfter("://").substringBefore('/').removePrefix("www."),
                ) { page = SettingsPage.PING }
            }

            GroupLabel("ПОДКЛЮЧЕНИЕ")
            SettingsGroup {
                SettingsRow(
                    Icons.Grid, IconTone.BLUE, "Приложения через VPN",
                    when (data.splitMode) {
                        SplitMode.ALL -> "Все приложения"
                        SplitMode.ONLY_SELECTED -> "Только выбранные · ${data.splitApps.size}"
                        SplitMode.BYPASS_SELECTED -> "Кроме выбранных · ${data.splitApps.size}"
                    },
                ) { page = SettingsPage.APPS }
                RowDivider()
                SettingsRow(
                    null, IconTone.BLUE, "Российские сайты напрямую",
                    if (data.geoUpdatedAt == 0L) "Базы встроенные" else "Базы обновлены ${agoText(data.geoUpdatedAt)}",
                    badgeText = "RU",
                    trailing = {
                        androidx.compose.material3.Switch(
                            checked = data.bypassRu,
                            onCheckedChange = { vm.setBypassRu(it) },
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                        )
                    },
                ) { page = SettingsPage.RU }
                RowDivider()
                SettingsRow(
                    Icons.Shield, IconTone.BLUE, "Постоянная VPN", "Системные настройки Android",
                    trailing = { Icon(Icons.External, contentDescription = null, tint = Color(0xFF6B7280), modifier = Modifier.size(18.dp)) },
                ) { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            GroupLabel("СИСТЕМА")
            SettingsGroup {
                SettingsRow(
                    Icons.Lines, IconTone.GRAY, "Логи",
                    when (data.logLevel) { "warning" -> "Ошибки"; "debug" -> "Подробный"; else -> "Обычный" } +
                        " · " + formatBytes(remember(data.logLevel) { runCatching { com.mkred.xrayclient.log.AppLog.current.length() }.getOrDefault(0L) }),
                ) { page = SettingsPage.LOGS }
                RowDivider()
                SettingsRow(
                    Icons.Info, IconTone.GRAY, "О приложении и обновления",
                    "${BuildConfig.VERSION_NAME} · " + (update?.let { "доступна ${it.tag}" } ?: "последняя версия"),
                    badge = update != null,
                ) { page = SettingsPage.ABOUT }
            }
            Box(Modifier.height(16.dp))
        }
    } else {
        // ---------------- экран раздела ----------------
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = { page = null }) {
                    Icon(Icons.Back, contentDescription = "Назад", tint = C.Text)
                }
                Text(current.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            when (current) {
                SettingsPage.METRIC -> {
        Panel {
            Text("Как оценивать серверы", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Одна метрика для всего приложения: по ней показываются серверы и выбирается лучший", fontSize = 12.sp, color = C.Muted)
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    "Задержка", "Только пинг. Без расхода трафика", data.metric == Metric.LATENCY, Modifier.weight(1f),
                    sample = { Text("48 мс", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.AccentText) },
                ) { vm.setMetric(Metric.LATENCY) }
                MetricCard(
                    "Качество", "Скорость, пинг и стабильность", data.metric == Metric.QUALITY, Modifier.weight(1f),
                    sample = {
                        Text(
                            "85", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.Bg,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Accent).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    },
                ) { vm.setMetric(Metric.QUALITY) }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Из чего складывается качество", fontSize = 12.sp, color = C.Muted)
                WeightRow("Скорость", 1f, C.Accent, "50 балл.")
                WeightRow("Пинг", 0.6f, C.Blue, "30 балл.")
                WeightRow("Стабильность", 0.4f, Purple, "20 балл.")
                Text(
                    "${data.qSpeedMax} Мбит/с и выше — максимум скорости · пинг ${data.qPingFull} мс и ниже — максимум, ${data.qPingZero} мс и выше — ноль · стабильность — доступность за 24 ч. " +
                        "Без замера скорости за последние сутки оценка неполная (≈, максимум 50).",
                    fontSize = 11.sp, color = Color(0xFF6B7280),
                )
            }
        }
        if (data.metric == Metric.QUALITY) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                Text("ШКАЛЫ КАЧЕСТВА", fontSize = 12.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val isDefault = data.qSpeedMax == 50 && data.qPingFull == 50 && data.qPingZero == 500
                TextButton(onClick = { vm.resetQualityLimits() }, enabled = !isDefault) {
                    Text("Сбросить", color = if (isDefault) C.Muted else C.AccentText)
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface)
                    .border(1.dp, C.Border, RoundedCornerShape(18.dp)),
            ) {
                LimitRow(
                    "Скорость", "полный балл от · 50 баллов", C.Accent, data.qSpeedMax, "Мбит/с",
                    onMinus = { vm.setQualityLimits(speedMax = data.qSpeedMax - 10) },
                    onPlus = { vm.setQualityLimits(speedMax = data.qSpeedMax + 10) },
                    canMinus = data.qSpeedMax > 10, canPlus = data.qSpeedMax < 500,
                )
                RowDivider()
                LimitRow(
                    "Пинг: максимум", "полный балл до · 30 баллов", C.Blue, data.qPingFull, "мс",
                    onMinus = { vm.setQualityLimits(pingFull = data.qPingFull - 10) },
                    onPlus = { vm.setQualityLimits(pingFull = data.qPingFull + 10) },
                    canMinus = data.qPingFull > 10, canPlus = data.qPingFull < 300,
                )
                RowDivider()
                LimitRow(
                    "Пинг: ноль", "ноль баллов от", C.Blue.copy(alpha = 0.4f), data.qPingZero, "мс",
                    onMinus = { vm.setQualityLimits(pingZero = data.qPingZero - 50) },
                    onPlus = { vm.setQualityLimits(pingZero = data.qPingZero + 50) },
                    canMinus = data.qPingZero > data.qPingFull + 50, canPlus = data.qPingZero < 3000,
                )
                RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp, 28.dp).clip(RoundedCornerShape(4.dp)).background(Purple))
                    Column(Modifier.weight(1f)) {
                        Text("Стабильность", fontSize = 14.sp)
                        Text("доступность за 24 ч · 20 баллов", fontSize = 11.sp, color = C.Muted)
                    }
                }
            }
            val ex = remember(data.qSpeedMax, data.qPingFull, data.qPingZero) {
                val sp = ((25f / data.qSpeedMax).coerceIn(0f, 1f) * 50).toInt()
                val pg = (((data.qPingZero - 120f) / (data.qPingZero - data.qPingFull)).coerceIn(0f, 1f) * 30).toInt()
                Triple(sp, pg, 19)
            }
            val total = ex.first + ex.second + ex.third
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Пример: 25 Мбит/с · 120 мс · 99% → ${ex.first} + ${ex.second} + ${ex.third}", fontSize = 12.sp, color = C.Muted, modifier = Modifier.weight(1f))
                Text(
                    "$total", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.Bg,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (total >= 80) C.Accent else if (total >= 50) C.Warn else C.Error)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
                }
                SettingsPage.SPEED -> {
        Panel {
            Text("Тест скорости", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Размер файла, который скачивается через сервер. Больше — точнее, но дороже по трафику", fontSize = 12.sp, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "быстро", 5 to "обычно", 10 to "точнее", 25 to "максимум").forEach { (mb, hint) ->
                    val sel = data.speedTestMb == mb
                    Column(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) C.AccentBg else C.Chip)
                            .then(if (sel) Modifier.border(2.dp, C.Accent, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable(role = Role.RadioButton) { vm.setSpeedTestMb(mb) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$mb МБ", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (sel) C.AccentText else C.Text)
                        Text(hint, fontSize = 10.sp, color = C.Muted)
                    }
                }
            }
            Text(
                "Тест запускается вручную на экране подписки или сервера. Подписки с лимитом трафика можно исключить там же.",
                fontSize = 12.sp, color = C.Muted,
            )
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автопроверка скорости", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Следит за текущим сервером и переходит на более быстрый, если этот просел или «замёрз»", fontSize = 12.sp, color = C.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = data.autoCheck && data.metric == Metric.QUALITY,
                    enabled = data.metric == Metric.QUALITY,
                    onCheckedChange = { vm.setAutoCheck(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                )
            }
            if (data.metric != Metric.QUALITY) {
                Text(
                    "Работает только в режиме «Качество» — в режиме «Задержка» серверы оцениваются только по пингу, без тестов и трафика.",
                    fontSize = 12.sp, color = C.Warn,
                )
            }
            if (data.autoCheck && data.metric == Metric.QUALITY) {
                Text("Считать сервер медленным, если ниже", fontSize = 13.sp, color = C.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 5, 10).forEach { v ->
                        val sel = data.slowMbps == v
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) C.AccentBg else C.Chip)
                                .then(if (sel) Modifier.border(2.dp, C.Accent, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable(role = Role.RadioButton) { vm.setSlowMbps(v) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$v Мбит/с", fontSize = 14.sp, color = if (sel) C.AccentText else C.Text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Как это работает", fontSize = 12.sp, color = C.Muted)
                    StepRow("1", C.AccentText, "Идёт загрузка и скорость хорошая — ничего не делаем")
                    StepRow("2", C.AccentText, "Трафика нет — лёгкая проверка раз в 30 с, при выключенном экране раз в 5 мин")
                    StepRow("3", C.Warn, "Медленно или «заморозка» — замер 3 лучших кандидатов, если нужно ещё 3")
                    StepRow("4", C.Warn, "Нашёлся заметно быстрее (×1,5) — переключаемся. Нет — остаёмся, повтор через 10 мин")
                    Text(
                        "≈ 100–150 МБ в сутки · кандидаты меряются пакетом из «Тест скорости» · каждый замер сразу меняет качество сервера · подписки с запретом теста не проверяются · в режиме «Вручную» не работает",
                        fontSize = 11.sp, color = Color(0xFF6B7280),
                    )
                }
            }
        }

        // ---- плановая проверка всех серверов (вариант I2) ----
        val speedRun by vm.speedRun.collectAsStateWithLifecycle()
        val profiles by AppStore.usable.collectAsStateWithLifecycle()
        val sweepCount = remember(profiles, data.subscriptions) { com.mkred.xrayclient.core.SpeedSweep.eligible().size }
        GroupLabel("ПЛАНОВАЯ ПРОВЕРКА ВСЕХ СЕРВЕРОВ")
        SettingsGroup {
            SwitchRow(
                "Обновлять замеры по таймеру",
                if (quality) "Чтобы оценка всех серверов была полной" else "Работает только в режиме «Качество»",
                data.sweep, enabled = quality,
            ) { vm.setSweep(it) }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Как часто", fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 9, 12).forEach { h ->
                        val sel = data.sweepHours == h
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) C.AccentBg else C.Chip)
                                .then(if (sel) Modifier.border(2.dp, C.Accent, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(role = Role.RadioButton) { vm.setSweepHours(h) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$h ч", fontSize = 14.sp, color = if (sel) C.AccentText else C.Text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            RowDivider()
            SwitchRow("На мобильной сети", "Выкл — только по Wi‑Fi", data.sweepMobile) { vm.setSweepMobile(it) }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoLine("Последний обход", if (data.sweepLastAt == 0L) "ещё не было" else "${agoText(data.sweepLastAt)} · ${data.sweepLastCount} серв.")
            val next = com.mkred.xrayclient.core.SpeedSweep.nextAt()
            InfoLine(
                "Следующий",
                when {
                    !data.sweep || !quality -> "выключено"
                    next <= System.currentTimeMillis() -> "при простое"
                    else -> "через ~${((next - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(1)} ч"
                },
            )
            val perRun = sweepCount * data.speedTestMb
            InfoLine("Трафик", "≈ $perRun МБ за обход · ≈ ${perRun * 24 / data.sweepHours} МБ/сут")
            OutlinedButton(
                onClick = {
                    if (!vm.sweepNow()) android.widget.Toast.makeText(context, "Уже идёт тест скорости", android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = speedRun == null && sweepCount > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    speedRun?.let { "Идёт проверка: ${it.index + 1} из ${it.ids.size}" } ?: "Проверить сейчас",
                    color = if (speedRun == null) C.Text else C.Muted,
                )
            }
        }
        Text(
            "Работает при включённом VPN. Проверяются все серверы, кроме исключённых, отключённых подписок и источников с запретом теста; недоступные пропускаются. " +
                "Серверы по одному, пакетом выше. Если начинается загрузка — обход уступает и продолжится позже.",
            fontSize = 12.sp, color = Color(0xFF6B7280),
        )
                }
                SettingsPage.PING -> {
        Panel {
            Text("Проверка серверов во время работы", fontWeight = FontWeight.SemiBold)
            Text("Как часто проверять, не пора ли переключиться", fontSize = 12.sp, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120).forEach { sec ->
                    val sel = data.checkIntervalSec == sec
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) C.AccentBg else C.Chip)
                            .clickable(role = Role.RadioButton) { vm.setInterval(sec) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$sec с", color = if (sel) C.AccentText else C.Text, fontSize = 13.sp)
                    }
                }
            }
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = { Text("Адрес для проверки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (testUrl != data.testUrl) {
                TextButton(onClick = { vm.setTestUrl(testUrl) }) { Text("Сохранить", color = C.AccentText) }
            }
        }
                }
                SettingsPage.APPS -> {
        Panel {
            Text("Приложения через VPN", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SplitMode.ALL to "Все",
                    SplitMode.ONLY_SELECTED to "Только выбранные",
                    SplitMode.BYPASS_SELECTED to "Кроме выбранных",
                ).forEach { (m, label) ->
                    val sel = data.splitMode == m
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) C.AccentBg else C.Chip)
                            .clickable(role = Role.RadioButton) { vm.setSplitMode(m) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = if (sel) C.AccentText else C.Text, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            Text(
                when (data.splitMode) {
                    SplitMode.ALL -> "Весь трафик телефона идёт через VPN."
                    SplitMode.ONLY_SELECTED ->
                        if (data.splitApps.isEmpty()) "Выберите приложения — пока список пуст, через VPN идут все."
                        else "Через VPN идут только выбранные приложения (${data.splitApps.size}), остальные — напрямую."
                    SplitMode.BYPASS_SELECTED ->
                        "Выбранные приложения (${data.splitApps.size}) идут напрямую, остальные — через VPN."
                },
                fontSize = 12.sp, color = C.Muted,
            )
            if (data.splitMode != SplitMode.ALL) {
                OutlinedButton(onClick = { pickApps = true }) {
                    Text("Выбрать приложения (${data.splitApps.size})", color = C.Text)
                }
            }
            Text("Изменения применяются сразу, без отключения VPN.", fontSize = 12.sp, color = C.Muted)
        }
                }
                SettingsPage.RU -> {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Российские сайты — напрямую", fontWeight = FontWeight.SemiBold)
                    Text("Открываются без VPN, даже в приложениях, которые идут через VPN", fontSize = 12.sp, color = C.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = data.bypassRu,
                    onCheckedChange = { vm.setBypassRu(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                )
            }
            Text(
                "Напрямую: домены .ru, .su, .рф, известные российские сервисы и сайты на российских IP. " +
                    "Заблокированные в России сайты всё равно идут через VPN.",
                fontSize = 12.sp, color = C.Muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (data.geoUpdatedAt == 0L) "Базы: встроенные в приложение"
                    else "Базы обновлены ${java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date(data.geoUpdatedAt))}",
                    fontSize = 12.sp, color = C.Muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.updateGeo() }, enabled = !geoUpdating) {
                    Text(if (geoUpdating) "Обновляю…" else "Обновить базы", color = C.AccentText)
                }
            }
        }
                }
                SettingsPage.LOGS -> {
        Panel {
            Text("Логи", fontWeight = FontWeight.SemiBold)
            Text(
                "Приложение, ядро Xray и падения пишутся в один файл. При отправке UUID и ссылки на подписки и серверы скрываются.",
                fontSize = 12.sp, color = C.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("warning" to "Ошибки", "info" to "Обычный", "debug" to "Подробный").forEach { (lvl, label) ->
                    val sel = data.logLevel == lvl
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) C.AccentBg else C.Chip)
                            .clickable(role = Role.RadioButton) { vm.setLogLevel(lvl) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (sel) C.AccentText else C.Text, fontSize = 13.sp)
                    }
                }
            }
            if (data.logLevel == "debug") {
                Text("В подробном режиме в лог попадают адреса сайтов, которые вы открываете.", fontSize = 12.sp, color = C.Warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showLog = true }, modifier = Modifier.weight(1f)) { Text("Открыть", color = C.Text) }
                OutlinedButton(onClick = { vm.shareLog() }, modifier = Modifier.weight(1f)) { Text("Отправить", color = C.Text) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { saveLog.launch("xray-client-log.txt") },
                    modifier = Modifier.weight(1f),
                ) { Text("В файл", color = C.Text) }
                OutlinedButton(onClick = { vm.clearLog() }, modifier = Modifier.weight(1f)) { Text("Очистить", color = C.Muted) }
            }
        }
                }
                SettingsPage.ABOUT -> {
        Panel {
            Text("О приложении", fontWeight = FontWeight.SemiBold)
            Text("Версия ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = C.Muted)
            Text(remember { XrayCore.version() }, fontSize = 12.sp, color = C.Muted)
            Text(
                "github.com/${BuildConfig.UPDATE_REPO}",
                fontSize = 13.sp,
                color = C.AccentText,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    .padding(vertical = 8.dp),
            )
            val r = update
            if (r != null) {
                Text("Доступна версия ${r.tag}", fontSize = 13.sp, color = C.AccentText)
                UpdateAction(progress, onInstall = { vm.installUpdate() })
            } else {
                OutlinedButton(onClick = { vm.checkUpdate() }, enabled = !checking) {
                    Text(if (checking) "Проверяю…" else "Проверить обновления", color = C.Text)
                }
            }
        }
                }
            }
            Box(Modifier.height(16.dp))
        }
    }

    if (showLog) LogViewer(vm, onClose = { showLog = false })

    if (pickApps) {
        AppPickerDialog(
            title = if (data.splitMode == SplitMode.ONLY_SELECTED) "Через VPN" else "Мимо VPN",
            initial = data.splitApps,
            onDone = { vm.setSplitApps(it); pickApps = false },
            onCancel = { pickApps = false },
        )
    }
}

private enum class IconTone(val bg: Color, val fg: Color) {
    GREEN(Color(0xFF1C2A22), Color(0xFF7EE2A8)),
    BLUE(Color(0xFF1E2638), Color(0xFF6EA8FE)),
    GRAY(Color(0xFF232833), Color(0xFFB9C3D4)),
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, fontSize = 12.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface)
            .border(1.dp, C.Border, RoundedCornerShape(18.dp)),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F232B)))
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    tone: IconTone,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badge: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tone.bg), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tone.fg, modifier = Modifier.size(18.dp))
            else Text(badgeText ?: "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tone.fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = C.Muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (badge) Text(
            "новое", fontSize = 11.sp, color = C.AccentText,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(C.AccentBg).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (trailing != null) trailing()
        else Icon(Icons.Chevron, contentDescription = null, tint = Color(0xFF6B7280), modifier = Modifier.size(18.dp))
    }
}

/** Строка шкалы: метка слева, «−  значение  +» справа. */
@Composable
private fun LimitRow(
    label: String,
    sub: String,
    color: Color,
    value: Int,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp, 28.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Text(sub, fontSize = 11.sp, color = C.Muted)
        }
        OutlinedButton(
            onClick = onMinus, enabled = canMinus, modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("−", fontSize = 18.sp, color = if (canMinus) C.Text else C.Muted) }
        Row(Modifier.width(72.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", fontSize = 11.sp, color = C.Muted)
        }
        OutlinedButton(
            onClick = onPlus, enabled = canPlus, modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", fontSize = 18.sp, color = if (canPlus) C.Text else C.Muted) }
    }
}


@Composable
private fun MetricCard(
    title: String,
    desc: String,
    selected: Boolean,
    modifier: Modifier,
    sample: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) C.AccentBg else C.Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Accent else C.Border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        sample()
        Text(desc, fontSize = 11.sp, color = C.Muted)
    }
}

@Composable
private fun WeightRow(label: String, fraction: Float, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(100.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(C.Border)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(50)).background(color))
        }
        Text(value, fontSize = 12.sp, color = C.Muted)
    }
}


@Composable
private fun StepRow(n: String, color: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = if (enabled) C.Text else C.Muted)
            Text(subtitle, fontSize = 12.sp, color = if (enabled) C.Muted else C.Warn)
        }
        androidx.compose.material3.Switch(
            checked = checked && enabled,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp)
    }
}
