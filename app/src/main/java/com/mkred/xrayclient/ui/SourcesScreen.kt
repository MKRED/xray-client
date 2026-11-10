package com.mkred.xrayclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Profile
import com.mkred.xrayclient.data.Protocol
import com.mkred.xrayclient.data.Stats
import com.mkred.xrayclient.data.Subscription
import com.mkred.xrayclient.service.VpnStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Dim = Color(0xFF6B7280)

/** Вкладка «Источники»: подписки и свои серверы (F2), экран подписки (F2a), экран сервера (F2b). */
@Composable
fun SourcesScreen(vm: MainViewModel, onToggleVpn: () -> Unit, modifier: Modifier = Modifier) {
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }
    var openServer by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = openSub != null || openServer != null) {
        if (openServer != null) openServer = null else openSub = null
    }

    when {
        openServer != null -> ServerDetail(vm, openServer!!, onToggleVpn, onBack = { openServer = null }, modifier = modifier)
        openSub != null -> SubscriptionDetail(
            vm, openSub!!,
            onBack = { openSub = null },
            onOpenServer = { openServer = it },
            modifier = modifier,
        )
        else -> SourcesList(vm, onOpenSub = { openSub = it }, onOpenServer = { openServer = it }, modifier = modifier)
    }
}

// =====================================================================
// F2 · список
// =====================================================================

@Composable
private fun SourcesList(vm: MainViewModel, onOpenSub: (String) -> Unit, onOpenServer: (String) -> Unit, modifier: Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val usable by vm.usable.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var addSub by remember { mutableStateOf(false) }
    var addLink by remember { mutableStateOf(false) }

    val manual = all.filter { it.subscriptionId == null }
    val sources = data.subscriptions.size + if (manual.isNotEmpty()) 1 else 0

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Источники", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SquareButton(onClick = { vm.refreshSubscriptions() }, enabled = !busy && data.subscriptions.isNotEmpty(), description = "Обновить все") {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.AccentText)
                else Icon(Icons.Refresh, contentDescription = null, tint = C.AccentText, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.padding(start = 8.dp)) {
                SquareButton(onClick = { menu = true }, filled = true, description = "Добавить") {
                    Icon(Icons.Plus, contentDescription = null, tint = C.Bg, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Подписка") }, onClick = { menu = false; addSub = true })
                    DropdownMenuItem(text = { Text("Сервер по ссылке") }, onClick = { menu = false; addLink = true })
                    DropdownMenuItem(text = { Text("Вставить из буфера") }, onClick = {
                        menu = false
                        vm.addFromClipboard(readClipboard(context))
                    })
                }
            }
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${usable.size}", "в работе", Modifier.weight(1f), C.AccentText)
            StatTile("${all.size}", "всего серверов", Modifier.weight(1f))
            StatTile("$sources", sourcesWord(sources), Modifier.weight(1f))
        }

        // ---- подписки ----
        SectionLabel("ПОДПИСКИ")
        if (data.subscriptions.isEmpty()) {
            EmptyHint("Подписок пока нет. Нажмите «+», чтобы добавить ссылку на подписку от провайдера или своей панели.")
        } else {
            GroupCard {
                data.subscriptions.forEachIndexed { i, s ->
                    if (i > 0) Divider()
                    val count = all.count { it.subscriptionId == s.id }
                    val active = usable.count { it.subscriptionId == s.id }
                    ListRow(onClick = { onOpenSub(s.id) }) {
                        Monogram(s.name, s.enabled, statusDot = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.enabled) C.Text else C.Muted)
                            val info = s.info?.takeIf { it.total > 0 }
                            when {
                                s.lastError != null -> Text("ошибка: ${s.lastError}", fontSize = 12.sp, color = C.Error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                info != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TrafficBar((info.upload + info.download).toFloat() / info.total, Modifier.weight(1f).height(4.dp))
                                    Text("${formatBytes(info.upload + info.download)} / ${formatBytes(info.total)}", fontSize = 11.sp, color = C.Muted)
                                }
                                else -> Text(
                                    (if (s.enabled) "$active из $count" else serversWord(count)) + " · " + updatedText(s.updatedAt),
                                    fontSize = 12.sp, color = if (s.enabled) C.Muted else Dim,
                                )
                            }
                        }
                        if (!s.enabled) Pill("выкл")
                        Icon(Icons.Chevron, contentDescription = null, tint = Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ---- свои серверы ----
        SectionLabel("МОИ СЕРВЕРЫ")
        if (manual.isEmpty()) {
            EmptyHint("Здесь будут серверы, добавленные по ссылке vless://, vmess://, trojan:// или ss://.")
        } else {
            GroupCard {
                manual.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    val on = usable.any { it.id == p.id }
                    ListRow(onClick = { onOpenServer(p.id) }) {
                        ProtocolBadge(p.protocol, 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) C.Text else C.Muted)
                            Text(securityText(p) + " · " + delayText(delays[p.id]), fontSize = 12.sp, color = if (on) C.Muted else Dim)
                        }
                        if (!on) Pill("выкл")
                        Icon(Icons.Chevron, contentDescription = null, tint = Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Text(
            "«+» вверху — добавить подписку или сервер. Нажмите на строку — откроются серверы, исключения, трафик и действия.",
            fontSize = 12.sp, color = Dim, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }

    if (addSub) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addSub = false },
            title = { Text("Новая подписка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("Ссылка https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addSubscription(url.trim(), name); addSub = false }, enabled = url.trim().startsWith("http")) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addSub = false }) { Text("Отмена") } },
        )
    }
    if (addLink) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addLink = false },
            title = { Text("Добавить сервер") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("vless://, vmess://, trojan://, ss://") },
                    supportingText = { Text("Можно несколько ссылок, каждую с новой строки") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { vm.addLinks(text); addLink = false }, enabled = text.isNotBlank()) { Text("Добавить") } },
            dismissButton = { TextButton(onClick = { addLink = false }) { Text("Отмена") } },
        )
    }
}

// =====================================================================
// F2a · экран подписки
// =====================================================================

@Composable
private fun SubscriptionDetail(
    vm: MainViewModel,
    subId: String,
    onBack: () -> Unit,
    onOpenServer: (String) -> Unit,
    modifier: Modifier,
) {
    val data by vm.data.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val usable by vm.usable.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sub = data.subscriptions.firstOrNull { it.id == subId }
    if (sub == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var rename by remember { mutableStateOf(false) }
    var addWord by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val servers = all.filter { it.subscriptionId == sub.id }
    val active = usable.filter { it.subscriptionId == sub.id }
    val best = active.mapNotNull { p -> delays[p.id]?.takeIf { it > 0 } }.minOrNull()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopBar("Подписка", onBack)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Monogram(sub.name, sub.enabled, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(sub.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(maskUrl(sub.url), fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppSwitch(sub.enabled) { vm.setSubscriptionEnabled(sub.id, it) }
        }
        if (!sub.enabled) {
            Text("Подписка выключена: её серверы обновляются, но не используются для подключения.", fontSize = 12.sp, color = C.Warn)
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${active.size}", "в работе", Modifier.weight(1f), C.AccentText, suffix = "/${servers.size}")
            StatTile(best?.toString() ?: "—", "лучший пинг", Modifier.weight(1f), suffix = if (best != null) " мс" else "")
            StatTile(if (sub.updatedAt > 0) time(sub.updatedAt) else "—", "обновлена", Modifier.weight(1f))
        }

        sub.info?.takeIf { it.total > 0 }?.let { info ->
            val used = info.upload + info.download
            Panel {
                Row {
                    Text("Трафик", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        "${formatBytes(used)} из ${formatBytes(info.total)}" + daysLeft(info.expire),
                        fontSize = 13.sp, color = C.Muted,
                    )
                }
                TrafficBar(used.toFloat() / info.total, Modifier.fillMaxWidth().height(6.dp))
            }
        }
        sub.lastError?.let {
            Text("Последнее обновление не удалось: $it", fontSize = 12.sp, color = C.Error)
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile("Обновить", Icons.Refresh, Modifier.weight(1f), enabled = !busy) { vm.refreshSubscription(sub.id) }
            ActionTile("Переименовать", Icons.Pencil, Modifier.weight(1f)) { rename = true }
            ActionTile("Копировать ссылку", Icons.Copy, Modifier.weight(1f)) {
                copyToClipboard(context, sub.url)
                vm.message.value = "Ссылка на подписку скопирована"
            }
        }

        // ---- исключать по словам ----
        SectionLabel("ИСКЛЮЧАТЬ ПО СЛОВАМ")
        FlowChips {
            sub.excludeKeywords.forEach { w ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A2020))
                        .clickable(role = Role.Button) { vm.setExcludeKeywords(sub.id, (sub.excludeKeywords - w).joinToString(",")) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(w, fontSize = 13.sp, color = C.Error)
                    Text("×", fontSize = 13.sp, color = C.Error)
                }
            }
            Text(
                "+ слово", fontSize = 13.sp, color = C.Muted,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, Color(0xFF3A3F4A)), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { addWord = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // ---- серверы ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("СЕРВЕРЫ · ${servers.size}", Modifier.weight(1f))
            TextButton(onClick = { vm.testProfiles(active) }, enabled = !testing && active.isNotEmpty()) {
                Text(if (testing) "Проверяю…" else "Проверить пинг", color = C.AccentText, fontSize = 13.sp)
            }
        }
        if (servers.isEmpty()) {
            EmptyHint("Серверов нет. Обновите подписку.")
        } else {
            GroupCard {
                servers.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    val byWord = AppStore.excludedByKeyword(p, data)
                    val manualOff = p.id in data.excluded
                    val on = byWord == null && !manualOff
                    ListRow(onClick = { onOpenServer(p.id) }, vertical = 10.dp) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on && sub.enabled) C.Text else Dim)
                            Text(
                                when {
                                    byWord != null -> "скрыт словом «$byWord»"
                                    manualOff -> "выключен вручную"
                                    else -> securityText(p)
                                },
                                fontSize = 11.sp,
                                color = if (byWord != null) C.Warn else if (manualOff) Dim else C.Muted,
                            )
                        }
                        if (on) {
                            val d = delays[p.id]
                            Text(if (d != null && d < 0) "нет ответа" else delayText(d), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = delayColor(d))
                        }
                        AppSwitch(on, enabled = byWord == null, small = true) { vm.setExcluded(listOf(p.id), !it) }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF4A2A2A)),
        ) { Text("Удалить подписку", color = C.Error) }
        Box(Modifier.height(16.dp))
    }

    if (rename) TextInputDialog("Название подписки", sub.name, onDone = { vm.renameSubscription(sub.id, it); rename = false }, onClose = { rename = false })
    if (addWord) TextInputDialog(
        "Исключать серверы, если в названии есть", "",
        hint = "Например: Россия или осталось. Регистр не важен.",
        onDone = { w -> if (w.isNotBlank()) vm.setExcludeKeywords(sub.id, (sub.excludeKeywords + w.trim()).joinToString(",")); addWord = false },
        onClose = { addWord = false },
    )
    if (confirmDelete) ConfirmDialog(
        "Удалить подписку «${sub.name}»?", "Её серверы пропадут из списка. Это нельзя отменить.", "Удалить",
        onConfirm = { confirmDelete = false; vm.removeSubscription(sub.id); onBack() },
        onClose = { confirmDelete = false },
    )
}

// =====================================================================
// F2b · экран сервера
// =====================================================================

@Composable
private fun ServerDetail(vm: MainViewModel, id: String, onToggleVpn: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val usable by vm.usable.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val pings by Stats.pings.collectAsStateWithLifecycle()
    val buckets by Stats.buckets.collectAsStateWithLifecycle()
    val usage by Stats.usage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val p = all.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var rename by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val sub: Subscription? = data.subscriptions.firstOrNull { it.id == p.subscriptionId }
    val isManual = p.subscriptionId == null
    val byWord = AppStore.excludedByKeyword(p, data)
    val on = usable.any { it.id == p.id }
    val current = vpn.profileId == p.id && vpn.status == VpnStatus.CONNECTED

    val since = System.currentTimeMillis() - 24 * 3_600_000L
    val myBuckets = buckets.filter { it.id == p.id && it.hour >= since }
    val okChecks = myBuckets.sumOf { it.ok }
    val totalChecks = okChecks + myBuckets.sumOf { it.fail }
    val uptime = if (totalChecks > 0) okChecks * 100 / totalChecks else null
    val usedSec = usage.filter { it.id == p.id && it.hour >= since }.sumOf { it.seconds.toLong() }
    val pts = pings[p.id].orEmpty().takeLast(60)
    val okPts = pts.filter { it.delay > 0 }.map { it.delay }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current) {
        while (current) { now = System.currentTimeMillis(); delay(1000) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopBar("Сервер", onBack)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProtocolBadge(p.protocol, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isManual) "Мои серверы · добавлен по ссылке" else "Подписка «${sub?.name ?: "?"}»",
                    fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AppSwitch(p.id !in data.excluded, enabled = byWord == null) { vm.setExcluded(listOf(p.id), !it) }
        }
        when {
            byWord != null -> Text("Скрыт словом «$byWord» в настройках подписки.", fontSize = 12.sp, color = C.Warn)
            sub != null && !sub.enabled -> Text("Подписка выключена — сервер сейчас не используется.", fontSize = 12.sp, color = C.Warn)
        }

        if (current) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.AccentBg)
                    .border(BorderStroke(1.dp, C.AccentBorder), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(C.Accent))
                Text("Сейчас подключено через этот сервер", fontSize = 14.sp, color = C.AccentText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatElapsed(now - vpn.connectedAt), fontSize = 12.sp, color = C.Muted)
            }
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = delays[p.id]
            StatTile(
                when { d == null -> "—"; d < 0 -> "нет"; else -> "$d" }, "пинг сейчас", Modifier.weight(1f),
                delayColor(d), suffix = if (d != null && d > 0) " мс" else "",
            )
            StatTile(uptime?.toString() ?: "—", "доступен, 24 ч", Modifier.weight(1f), suffix = if (uptime != null) "%" else "")
            StatTile(hoursText(usedSec), "в работе, 24 ч", Modifier.weight(1f))
        }

        Panel {
            Row {
                Text("Пинг за последние проверки", fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (okPts.isNotEmpty()) Text("ср. ${okPts.average().toLong()} · макс ${okPts.max()}", fontSize = 13.sp, color = C.Muted)
            }
            if (pts.isEmpty()) {
                Text("Проверок ещё не было", fontSize = 12.sp, color = C.Muted)
            } else {
                LineChart(
                    series = listOf(ChartSeries(pts.map { if (it.delay > 0) it.delay.toFloat() else null }, C.AccentText, 60)),
                    failures = pts.indices.filter { pts[it].delay <= 0 },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                if (current) "Подключено" else "Подключиться",
                onClick = {
                    vm.connectTo(p.id)
                    if (vpn.status == VpnStatus.DISCONNECTED) onToggleVpn()
                },
                modifier = Modifier.weight(1f),
                enabled = !current && on,
            )
            OutlinedButton(
                onClick = { vm.testProfiles(listOf(p)) },
                enabled = !testing,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (testing) "Проверяю…" else "Проверить пинг", color = C.Text) }
        }

        SectionLabel("ПАРАМЕТРЫ")
        GroupCard {
            val rows = buildList {
                add("Протокол" to (p.protocol.name.let { if (it == "SHADOWSOCKS") "Shadowsocks" else it } + (p.flow?.let { " · $it" } ?: "")))
                add("Адрес" to "${p.address}:${p.port}")
                if (p.protocol != Protocol.SHADOWSOCKS) add("Транспорт" to p.network)
                add("Безопасность" to when (p.security) { "reality" -> "Reality"; "tls" -> "TLS"; "none" -> "нет"; else -> p.security })
                p.sni?.let { add("SNI" to it) }
                p.fingerprint?.let { add("Отпечаток" to it) }
                p.host?.let { add("Host" to it) }
                (p.path ?: p.serviceName)?.let { add("Путь" to it) }
                p.method?.takeIf { p.protocol == Protocol.SHADOWSOCKS }?.let { add("Шифрование" to it) }
                add((if (p.protocol == Protocol.VLESS || p.protocol == Protocol.VMESS) "UUID" else "Пароль") to maskSecret(p.secret))
            }
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, fontSize = 14.sp, color = C.Muted)
                    Text(v, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isManual) ActionTile("Переименовать", Icons.Pencil, Modifier.weight(1f)) { rename = true }
            ActionTile("Копировать ссылку", Icons.Copy, Modifier.weight(1f)) {
                copyToClipboard(context, p.link)
                vm.message.value = "Ссылка на сервер скопирована"
            }
            ActionTile("QR-код", Icons.Qr, Modifier.weight(1f)) { qr = true }
        }

        if (isManual) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF4A2A2A)),
            ) { Text("Удалить сервер", color = C.Error) }
        } else {
            Text(
                "Сервер из подписки нельзя удалить или переименовать — он вернётся при обновлении. Его можно выключить переключателем вверху.",
                fontSize = 12.sp, color = Dim,
            )
        }
        Box(Modifier.height(16.dp))
    }

    if (rename) TextInputDialog("Название сервера", p.name, onDone = { vm.renameProfile(p.id, it); rename = false }, onClose = { rename = false })
    if (qr) QrDialog(p.name, p.link, onClose = { qr = false })
    if (confirmDelete) ConfirmDialog(
        "Удалить сервер «${p.name}»?", "Его можно будет добавить снова по ссылке.", "Удалить",
        onConfirm = { confirmDelete = false; vm.removeProfile(p.id); onBack() },
        onClose = { confirmDelete = false },
    )
}

// =====================================================================
// общие элементы
// =====================================================================

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
            Icon(Icons.Back, contentDescription = "Назад", tint = C.Text)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SquareButton(
    onClick: () -> Unit,
    description: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) C.Accent else C.Surface)
            .then(if (filled) Modifier else Modifier.border(BorderStroke(1.dp, C.Border), RoundedCornerShape(12.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, color: Color = C.Text, suffix: String = "") {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            if (suffix.isNotEmpty()) Text(suffix, fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(label, fontSize = 11.sp, color = C.Muted, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = modifier.padding(start = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(18.dp)),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F232B)))
}

@Composable
private fun ListRow(onClick: () -> Unit, vertical: androidx.compose.ui.unit.Dp = 14.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Monogram(name: String, enabled: Boolean, size: androidx.compose.ui.unit.Dp = 40.dp, statusDot: Boolean = false) {
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (size > 48.dp) 16.dp else 12.dp))
                .background(if (enabled) C.AccentBg else Color(0xFF1E2128)),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, fontWeight = FontWeight.Bold, fontSize = if (size > 48.dp) 22.sp else 16.sp, color = if (enabled) C.AccentText else Dim)
        }
        if (statusDot) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(C.Surface).padding(2.dp)
                    .clip(CircleShape).background(if (enabled) C.Accent else Color(0xFF4B5160)),
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: Protocol, size: androidx.compose.ui.unit.Dp) {
    val label = when (protocol) {
        Protocol.VLESS -> "VLESS"
        Protocol.VMESS -> "VMESS"
        Protocol.TROJAN -> "TRJ"
        Protocol.SHADOWSOCKS -> "SS"
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(if (size > 48.dp) 16.dp else 12.dp)).background(C.Chip),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (size > 48.dp) 12.sp else 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB9C3D4))
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text, fontSize = 11.sp, color = C.Muted,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Chip).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun TrafficBar(fraction: Float, modifier: Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.clip(RoundedCornerShape(50)),
        color = if (fraction > 0.9f) C.Warn else C.Blue,
        trackColor = C.Border,
        drawStopIndicator = {},
    )
}

@Composable
private fun AppSwitch(checked: Boolean, enabled: Boolean = true, small: Boolean = false, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        modifier = if (small) Modifier.width(52.dp) else Modifier,
        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
    )
}

/** Кнопка-иконка; подпись озвучивается TalkBack. */
@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) C.AccentText else C.Muted, modifier = Modifier.size(22.dp))
    }
}

/** Простой перенос чипов по строкам. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun TextInputDialog(title: String, initial: String, hint: String? = null, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = if (hint != null) { { Text(hint) } } else null,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = C.Error) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

// ---- форматирование и буфер обмена ----

private fun securityText(p: Profile): String = buildList {
    when (p.security) {
        "reality" -> add("Reality")
        "tls" -> add("TLS")
    }
    if (p.protocol != Protocol.SHADOWSOCKS && p.network != "tcp" && p.network != "raw") add(p.network)
    if (isEmpty()) add(p.protocol.name.lowercase())
}.joinToString(" · ")

private fun maskUrl(url: String): String {
    val host = url.substringAfter("://").substringBefore('/')
    val scheme = url.substringBefore("://", "https")
    return "$scheme://$host/••••••"
}

private fun maskSecret(s: String): String = if (s.length <= 6) "••••" else s.take(4) + "…" + "••••"

private fun time(ts: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

private fun updatedText(ts: Long): String {
    if (ts <= 0) return "не загружена"
    val sameDay = Stats.day(ts) == Stats.day()
    return "обновлена " + SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(ts))
}

private fun daysLeft(expire: Long): String {
    if (expire <= 0) return ""
    val days = ((expire * 1000 - System.currentTimeMillis()) / 86_400_000L)
    return if (days >= 0) " · ещё $days дн." else " · истекла"
}

private fun hoursText(sec: Long): String = when {
    sec <= 0 -> "0"
    sec < 3600 -> "${sec / 60} мин"
    else -> String.format(Locale.US, "%.1f ч", sec / 3600.0).replace('.', ',')
}

private fun sourcesWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "источник"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "источника"
    else -> "источников"
}

private fun serversWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "$n сервер"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n сервера"
    else -> "$n серверов"
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("xray", text))
}
