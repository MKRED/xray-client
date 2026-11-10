package com.mkred.xrayclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.mkred.xrayclient.data.SelectionMode
import com.mkred.xrayclient.service.VpnStatus
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: MainViewModel, onToggle: () -> Unit, onOpenServers: () -> Unit, modifier: Modifier = Modifier) {
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val profiles by vm.usable.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val auto by vm.autoStatus.collectAsStateWithLifecycle()

    val on = vpn.status == VpnStatus.CONNECTED
    val connecting = vpn.status == VpnStatus.CONNECTING

    // Текущий сервер: при подключении — активный, иначе — что будет выбрано в ручном режиме
    val shown = AppStore.profile(vpn.profileId)
        ?: AppStore.usableProfile(data.selectedId).takeIf { data.mode == SelectionMode.MANUAL }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) {
        while (on) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Xray VPN", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.refreshSubscriptions() }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Refresh, contentDescription = "Обновить подписки", tint = C.Text)
            }
        }

        update?.let { r ->
            Panel {
                Text("Доступно обновление ${r.tag}", fontWeight = FontWeight.SemiBold)
                UpdateAction(progress, onInstall = { vm.installUpdate() })
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (vpn.status) {
                    VpnStatus.CONNECTED -> "Подключено"
                    VpnStatus.CONNECTING -> "Подключение…"
                    VpnStatus.DISCONNECTED -> "Отключено"
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (on) C.AccentText else C.Text,
            )
            Text(
                when {
                    on -> "В сети ${formatElapsed(now - vpn.connectedAt)}"
                    connecting -> vpn.message ?: ""
                    vpn.message != null -> vpn.message!!
                    profiles.isEmpty() -> "Добавьте подписку или сервер на вкладке «Источники»"
                    else -> "Нажмите, чтобы подключиться"
                },
                fontSize = 14.sp,
                color = if (!on && !connecting && vpn.message != null) C.Error else C.Muted,
            )
        }

        PowerButton(on = on, connecting = connecting, onClick = onToggle)

        Row(
            Modifier.clip(RoundedCornerShape(50)).background(C.AccentBg).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Bolt, contentDescription = null, tint = C.AccentText, modifier = Modifier.size(16.dp))
            Text(modeLabel(data.mode, data.metric == com.mkred.xrayclient.data.Metric.QUALITY), color = C.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        auto?.takeIf { on && data.autoCheck }?.let { a -> AutoStatusChip(a) }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(C.Surface)
                .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onOpenServers)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    shown?.name ?: if (data.mode == SelectionMode.MANUAL) "Сервер не выбран" else "Выбирается автоматически",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shown?.subtitle ?: "Серверов: ${profiles.size}",
                    fontSize = 13.sp, color = C.Muted,
                )
            }
            shown?.let { MetricValue(it, big = true) }
            Icon(Icons.Chevron, contentDescription = null, tint = C.Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PowerButton(on: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val ring = when {
        on -> C.Accent
        connecting -> C.Warn
        else -> Color(0xFF3A3F4A)
    }
    Box(
        Modifier
            .padding(top = 8.dp)
            .size(196.dp)
            .clip(CircleShape)
            .border(3.dp, ring, CircleShape)
            .background(Color(0xFF15181E))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().clip(CircleShape).background(if (on) C.AccentBg else Color(0xFF1C1F26)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (connecting) {
                CircularProgressIndicator(color = C.Warn, modifier = Modifier.size(48.dp))
            } else {
                Icon(Icons.Power, contentDescription = null, tint = if (on) C.AccentText else C.Muted, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    on -> "Отключить"
                    connecting -> "Отмена"
                    else -> "Подключить"
                },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (on) C.AccentText else C.Muted,
            )
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.Bg),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** Кнопка «Обновить» или, пока идёт загрузка, полоса с процентами и мегабайтами. */
@Composable
fun UpdateAction(progress: com.mkred.xrayclient.update.Updater.Progress?, onInstall: () -> Unit) {
    if (progress == null) {
        PrimaryButton("Скачать и установить", onClick = onInstall, modifier = Modifier.fillMaxWidth())
        return
    }
    val f = progress.fraction
    if (f != null) {
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Border)
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Border)
    }
    val mb = { b: Long -> "%.1f".format(b / 1_048_576.0) }
    Text(
        when {
            f != null && f >= 1f -> "Скачано, открываю установщик…"
            f != null -> "Скачивание: ${(f * 100).toInt()}% · ${mb(progress.done)} из ${mb(progress.total)} МБ"
            else -> "Скачивание: ${mb(progress.done)} МБ"
        },
        fontSize = 12.sp, color = C.Muted,
    )
}

fun modeLabel(mode: SelectionMode, quality: Boolean = false) = when (mode) {
    SelectionMode.FASTEST -> if (quality) "Авто · лучшее качество" else "Авто · самый быстрый"
    SelectionMode.PRIORITY -> "Авто · по приоритету"
    SelectionMode.MANUAL -> "Вручную"
}

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}


/** Статус автопроверки под кнопкой: «в норме», «ищем быстрее», «проблема в сети». */
@Composable
private fun AutoStatusChip(a: com.mkred.xrayclient.core.AutoStatus) {
    val kind = a.kind
    val (bg, edge, fg) = when (kind) {
        com.mkred.xrayclient.core.AutoStatus.Kind.OK -> Triple(C.Surface, C.Border, C.Text)
        com.mkred.xrayclient.core.AutoStatus.Kind.SEARCHING -> Triple(Color(0xFF2A2418), Color(0xFF4A3D22), C.Warn)
        com.mkred.xrayclient.core.AutoStatus.Kind.NETWORK -> Triple(C.Surface, C.Border, C.Warn)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a) { while (true) { now = System.currentTimeMillis(); delay(5000) } }
    val ago = ((now - a.at) / 1000).let { if (it < 60) "$it с назад" else "${it / 60} мин назад" }
    Row(
        Modifier
            .clip(RoundedCornerShape(if (kind == com.mkred.xrayclient.core.AutoStatus.Kind.NETWORK) 16.dp else 50.dp))
            .background(bg)
            .border(BorderStroke(1.dp, edge), RoundedCornerShape(if (kind == com.mkred.xrayclient.core.AutoStatus.Kind.NETWORK) 16.dp else 50.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (kind) {
            com.mkred.xrayclient.core.AutoStatus.Kind.OK -> Box(Modifier.size(8.dp).clip(CircleShape).background(C.Accent))
            com.mkred.xrayclient.core.AutoStatus.Kind.SEARCHING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = C.Warn)
            com.mkred.xrayclient.core.AutoStatus.Kind.NETWORK -> Text("!", color = C.Warn, fontWeight = FontWeight.Bold)
        }
        Text(
            if (kind == com.mkred.xrayclient.core.AutoStatus.Kind.OK) "${a.text} · $ago" else a.text,
            fontSize = 13.sp, color = fg,
        )
    }
}
