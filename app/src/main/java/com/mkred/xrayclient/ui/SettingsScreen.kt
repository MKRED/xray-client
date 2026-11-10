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
import com.mkred.xrayclient.data.Metric
import com.mkred.xrayclient.data.SplitMode
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import com.mkred.xrayclient.core.XrayCore

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
    val geoUpdating by vm.geoUpdating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var testUrl by remember(data.testUrl) { mutableStateOf(data.testUrl) }
    var showLog by remember { mutableStateOf(false) }
    var pickApps by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.saveLog(it) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))

        // ---- метрика ----
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
                    "50 Мбит/с и выше — максимум скорости · пинг 50 мс и ниже — максимум, 500 мс и выше — ноль · стабильность — доступность за 24 ч. " +
                        "Без замера скорости за последние сутки оценка неполная (≈, максимум 50).",
                    fontSize = 11.sp, color = Color(0xFF6B7280),
                )
            }
        }

        // ---- тест скорости ----
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

        // ---- автопроверка скорости ----
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автопроверка скорости", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Следит за текущим сервером и переходит на более быстрый, если этот просел или «замёрз»", fontSize = 12.sp, color = C.Muted)
                }
                androidx.compose.material3.Switch(
                    checked = data.autoCheck,
                    onCheckedChange = { vm.setAutoCheck(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                )
            }
            if (data.autoCheck) {
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
                        "≈ 100–150 МБ в сутки · кандидаты меряются пакетом из «Тест скорости» · подписки с запретом теста не проверяются · в режиме «Вручную» не работает",
                        fontSize = 11.sp, color = Color(0xFF6B7280),
                    )
                }
            }
        }

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

        Panel {
            Text("Постоянная VPN", fontWeight = FontWeight.SemiBold)
            Text(
                "Чтобы VPN включался сам и трафик не шёл мимо него, включите в системных настройках «Постоянная VPN» и «Блокировать соединения без VPN».",
                fontSize = 12.sp, color = C.Muted,
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Открыть настройки VPN", color = C.Text) }
        }

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
        Box(Modifier.height(8.dp))
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
