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
import com.mkred.xrayclient.data.SplitMode
import com.mkred.xrayclient.core.XrayCore

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdate.collectAsStateWithLifecycle()
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
