package com.mkred.xrayclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.core.Blocks
import com.mkred.xrayclient.core.NetworkState
import com.mkred.xrayclient.data.AppData
import com.mkred.xrayclient.data.BlockType
import com.mkred.xrayclient.data.PriorityBlock
import com.mkred.xrayclient.data.Profile
import java.util.UUID

private enum class BlockStatus(val label: String, val color: Color) {
    ACTIVE("АКТИВЕН", C.AccentText),
    OK("ГОТОВ", C.Muted),
    NO_WORKING("НЕТ РАБОЧИХ", C.Error),
    NOT_TESTED("НЕ ПРОВЕРЕН", C.Muted),
    EMPTY("НЕТ СЕРВЕРОВ", C.Warn),
    SKIPPED("ПРОПУЩЕН · НЕ МОБИЛЬНАЯ СЕТЬ", C.Warn),
}

/** Список блоков режима «Приоритет» (E1). */
@Composable
fun PriorityBlocksList(vm: MainViewModel, modifier: Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val usable by vm.usable.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<PriorityBlock, Boolean>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val mobile = remember(data) { NetworkState.isMobile() }

    val blocks = Blocks.withAny(data)
    val activeIdx = blocks.indexOfFirst { b ->
        vpn.profileId != null && Blocks.skipReason(b, mobile) == null &&
            Blocks.candidates(b, data, usable).any { it.id == vpn.profileId }
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(blocks, key = { _, b -> b.id }) { i, b ->
            val cands = Blocks.candidates(b, data, usable)
            val working = cands.filter { Blocks.passes(b, delays[it.id]) }.let { w ->
                if (data.metric == com.mkred.xrayclient.data.Metric.QUALITY) w.sortedByDescending { com.mkred.xrayclient.core.Quality.of(it, delays).score }
                else w.sortedBy { delays[it.id] }
            }
            val status = when {
                Blocks.skipReason(b, mobile) != null -> BlockStatus.SKIPPED
                i == activeIdx -> BlockStatus.ACTIVE
                cands.isEmpty() -> BlockStatus.EMPTY
                working.isNotEmpty() -> BlockStatus.OK
                cands.all { delays[it.id] != null } -> BlockStatus.NO_WORKING
                else -> BlockStatus.NOT_TESTED
            }
            BlockCard(
                index = i,
                block = b,
                title = Blocks.title(b, data, all),
                status = status,
                cands = cands,
                working = working,
                delays = delays,
                activeId = vpn.profileId,
                canUp = !Blocks.isAny(b) && i > 0,
                canDown = !Blocks.isAny(b) && i < data.blocks.lastIndex,
                onUp = { vm.moveBlock(b.id, -1) },
                onDown = { vm.moveBlock(b.id, 1) },
                onClick = if (Blocks.isAny(b)) null else ({ editing = b to false }),
            )
        }
        item {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Добавить блок", color = C.Text)
            }
        }
        item {
            Text(
                "Нажмите на блок, чтобы изменить или удалить его. «Любой рабочий» всегда последний. " +
                    "На блок выше возвращаемся после 2 успешных проверок подряд.",
                fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }

    if (adding) {
        AddBlockDialog(onPick = { type ->
            adding = false
            editing = PriorityBlock(
                id = UUID.randomUUID().toString().take(8),
                type = type,
                subscriptionId = data.subscriptions.firstOrNull()?.id,
            ) to true
        }, onClose = { adding = false })
    }
    editing?.let { (b, isNew) ->
        BlockEditorDialog(vm, b, isNew, onClose = { editing = null })
    }
}

@Composable
private fun BlockCard(
    index: Int,
    block: PriorityBlock,
    title: String,
    status: BlockStatus,
    cands: List<Profile>,
    working: List<Profile>,
    delays: Map<String, Long>,
    activeId: String?,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val active = status == BlockStatus.ACTIVE
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) C.AccentBg else C.Surface)
            .border(BorderStroke(1.dp, if (active) C.AccentBorder else C.Border), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (active) C.Accent else C.Chip),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (active) C.Bg else C.Text)
            }
            Column(Modifier.weight(1f)) {
                val typeLabel = if (Blocks.isAny(block)) "ЗАКРЕПЛЁН" else block.type.title.uppercase()
                Text("$typeLabel · ${status.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = status.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (status == BlockStatus.NO_WORKING || status == BlockStatus.SKIPPED) C.Muted else C.Text,
                )
            }
            if (!Blocks.isAny(block)) {
                IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Up, contentDescription = "Выше", tint = if (canUp) C.Muted else C.Border, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Down, contentDescription = "Ниже", tint = if (canDown) C.Muted else C.Border, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (active && working.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                working.take(3).forEach { p ->
                    val isCur = p.id == activeId
                    Row {
                        Text(p.name, fontSize = 13.sp, color = if (isCur) C.Text else C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            metricText(p) + if (isCur) " ✓" else "",
                            fontSize = 13.sp, color = if (isCur) C.AccentText else C.Muted,
                            fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (working.size > 3) Text("ещё ${working.size - 3}", fontSize = 12.sp, color = C.Muted)
            }
        } else {
            val best = working.firstOrNull()
            Text(
                when {
                    cands.isEmpty() -> "Ни один сервер не подходит"
                    best != null -> "${serversWord(cands.size)} · лучший: ${metricText(best)}"
                    status == BlockStatus.NO_WORKING -> "${serversWord(cands.size)} · все недоступны, блок пропущен"
                    else -> "${serversWord(cands.size)} · нажмите «Проверить»"
                },
                fontSize = 13.sp, color = C.Muted,
            )
        }

        val chips = buildList {
            block.maxPingMs?.let { add("только если пинг < $it мс") }
            if (block.mobileOnly) add("только в мобильной сети")
        }
        if (chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach {
                    Text(
                        it, fontSize = 11.sp, color = Color(0xFFB9C3D4),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Chip).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

private fun serversWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "сервер"
        m10 in 2..4 && m100 !in 12..14 -> "сервера"
        else -> "серверов"
    }
    return "$n $w"
}

// ---- E2: выбор типа ----

@Composable
private fun AddBlockDialog(onPick: (BlockType) -> Unit, onClose: () -> Unit) {
    val items = listOf(
        Triple(BlockType.SUB_KEYWORDS, "Из одной подписки — только серверы, в названии которых есть нужные слова", Icons.Filter),
        Triple(BlockType.SUBSCRIPTION, "Все включённые серверы подписки", Icons.Link),
        Triple(BlockType.SERVER, "Один выбранный сервер из выбранной подписки", Icons.Target),
        Triple(BlockType.ALL_KEYWORDS, "Например, все «Нидерланды» от любых провайдеров", Icons.Globe),
        Triple(BlockType.MOST_STABLE, "Топ-N по доступности за 24 ч из статистики", Icons.Chart),
        Triple(BlockType.MANUAL_SERVERS, "Добавленные вручную по ссылке, без подписки", Icons.Plus),
    )
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color(0xFF15181E))
                    .clickable(enabled = false) { }
                    .systemBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(RoundedCornerShape(50)).background(C.Border))
                Text("Новый блок", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Добавится в конец, перед «Любой рабочий». Потом его можно переместить.", fontSize = 13.sp, color = C.Muted)
                items.forEach { (type, desc, icon) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(C.Surface)
                            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { onPick(type) }
                            .heightIn(min = 64.dp)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.AccentBg), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = C.AccentText, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(type.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(desc, fontSize = 12.sp, color = C.Muted)
                        }
                    }
                }
            }
        }
    }
}

// ---- E3: настройка блока ----

@Composable
private fun BlockEditorDialog(vm: MainViewModel, initial: PriorityBlock, isNew: Boolean, onClose: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val usable by vm.usable.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    var b by remember { mutableStateOf(initial) }
    var wordsText by remember { mutableStateOf(initial.keywords.joinToString(", ")) }

    val needsSub = b.type in setOf(BlockType.SUB_KEYWORDS, BlockType.SUBSCRIPTION, BlockType.SERVER)
    val needsWords = b.type == BlockType.SUB_KEYWORDS || b.type == BlockType.ALL_KEYWORDS
    val preview = Blocks.candidates(b, data, usable).sortedBy { delays[it.id]?.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
    val valid = (!needsSub || b.subscriptionId != null) &&
        (!needsWords || b.keywords.isNotEmpty()) &&
        (b.type != BlockType.SERVER || b.serverId != null)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Отмена", color = C.Muted) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(b.type.title.uppercase(), fontSize = 10.sp, color = C.AccentText, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (isNew) "Новый блок" else "Настройка блока", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { vm.saveBlock(b); onClose() }, enabled = valid) {
                    Text("Сохранить", color = if (valid) C.AccentText else C.Muted)
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (needsSub) {
                    SubscriptionPicker(data, b.subscriptionId) { b = b.copy(subscriptionId = it, serverId = null) }
                }

                if (needsWords) {
                    OutlinedTextField(
                        value = wordsText,
                        onValueChange = { t ->
                            wordsText = t
                            b = b.copy(keywords = t.split(',', '\n').map(String::trim).filter(String::isNotEmpty))
                        },
                        label = { Text("В названии есть любое из слов") },
                        placeholder = { Text("Нидерланды, Германия") },
                        supportingText = { Text("Через запятую, регистр не важен. Исключённые серверы сюда не попадут.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (b.type == BlockType.MOST_STABLE) {
                    Text("Сколько серверов брать", fontSize = 13.sp, color = C.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10).forEach { n ->
                            ChoiceChip("Топ-$n", b.topN == n, Modifier.weight(1f)) { b = b.copy(topN = n) }
                        }
                    }
                    Text("Нужно хотя бы 3 проверки сервера за сутки — данные берутся из статистики.", fontSize = 12.sp, color = C.Muted)
                }

                if (b.type == BlockType.SERVER) {
                    val subServers = all.filter { it.subscriptionId == b.subscriptionId }
                    Text("Сервер", fontSize = 13.sp, color = C.Muted)
                    if (subServers.isEmpty()) Text("В подписке нет серверов", fontSize = 13.sp, color = C.Warn)
                    subServers.forEach { p ->
                        val excluded = usable.none { it.id == p.id }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.RadioButton) { b = b.copy(serverId = p.id) }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = b.serverId == p.id, onClick = { b = b.copy(serverId = p.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = C.Accent),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (excluded) C.Muted else C.Text)
                                if (excluded) Text("исключён — в блоке не будет работать", fontSize = 11.sp, color = C.Warn)
                            }
                            Text(delayText(delays[p.id]), fontSize = 13.sp, color = delayColor(delays[p.id]))
                        }
                    }
                }

                if (b.type != BlockType.SERVER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подходят: ${serversWord(preview.size)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.testProfiles(preview) }, enabled = !testing && preview.isNotEmpty()) {
                            Text(if (testing) "Проверяю…" else "Проверить пинг", color = C.AccentText)
                        }
                    }
                    if (preview.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface)
                                .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp)),
                        ) {
                            preview.take(30).forEach { p ->
                                val d = delays[p.id]
                                val ok = Blocks.passes(b, d)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                                    Text(p.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (ok || d == null) C.Text else C.Muted, modifier = Modifier.weight(1f))
                                    Text(
                                        if (d != null && d > 0 && !ok) "${d} мс > порога" else delayText(d),
                                        fontSize = 13.sp,
                                        color = if (d != null && d > 0 && !ok) C.Warn else delayColor(d),
                                    )
                                }
                            }
                            if (preview.size > 30) Text("ещё ${preview.size - 30}", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(14.dp))
                        }
                    }
                }

                // Условия блока
                Panel {
                    Text("Условия блока", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Только если пинг ниже порога", fontSize = 14.sp)
                            Text("Серверы медленнее порога не считаются рабочими", fontSize = 12.sp, color = C.Muted)
                        }
                        Switch(
                            checked = b.maxPingMs != null,
                            onCheckedChange = { b = b.copy(maxPingMs = if (it) 150 else null) },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                        )
                    }
                    if (b.maxPingMs != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100, 150, 200, 300).forEach { ms ->
                                ChoiceChip("$ms мс", b.maxPingMs == ms, Modifier.weight(1f)) { b = b.copy(maxPingMs = ms) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Только в мобильной сети", fontSize = 14.sp)
                            Text("На Wi-Fi блок пропускается", fontSize = 12.sp, color = C.Muted)
                        }
                        Switch(
                            checked = b.mobileOnly,
                            onCheckedChange = { b = b.copy(mobileOnly = it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                        )
                    }
                }

                if (!isNew) {
                    OutlinedButton(
                        onClick = { vm.deleteBlock(b.id); onClose() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF4A2A2A)),
                    ) { Text("Удалить блок", color = C.Error) }
                }
                Box(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SubscriptionPicker(data: AppData, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Подписка", fontSize = 13.sp, color = C.Muted)
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                val name = data.subscriptions.firstOrNull { it.id == selected }?.name ?: "Выберите подписку"
                Text(name, color = C.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Down, contentDescription = null, tint = C.Muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (data.subscriptions.isEmpty()) {
                    DropdownMenuItem(text = { Text("Подписок нет") }, onClick = { open = false }, enabled = false)
                }
                data.subscriptions.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name + if (!s.enabled) " (выключена)" else "") },
                        onClick = { onSelect(s.id); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) C.AccentBg else C.Chip)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) C.AccentText else C.Text)
    }
}
