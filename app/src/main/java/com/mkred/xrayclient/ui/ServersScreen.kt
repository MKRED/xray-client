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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.SelectionMode

@Composable
fun ServersScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val profiles by vm.usable.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }

    // Пересчитываем порядок при изменении данных
    val ordered = remember(data, profiles) { AppStore.orderedProfiles() }
    val priorityMode = data.mode == SelectionMode.PRIORITY

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Серверы", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))

        ModeSwitch(data.mode, vm::setMode)

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (data.mode) {
                    SelectionMode.PRIORITY -> "Порядок: первый рабочий сверху"
                    SelectionMode.MANUAL -> "Нажмите на сервер, чтобы выбрать"
                    SelectionMode.FASTEST -> "Выбирается самый быстрый"
                },
                fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.testAll() }, enabled = !testing && profiles.isNotEmpty()) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Проверить", color = C.AccentText)
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(ordered, key = { _, p -> p.id }) { i, p ->
                val active = p.id == vpn.profileId
                val selected = data.mode == SelectionMode.MANUAL && p.id == data.selectedId
                val highlight = active || selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (highlight) C.AccentBg else C.Surface)
                        .border(BorderStroke(1.dp, if (highlight) C.AccentBorder else C.Border), RoundedCornerShape(14.dp))
                        .clickable(enabled = data.mode == SelectionMode.MANUAL, role = Role.Button) { vm.selectServer(p.id) }
                        .heightIn(min = 56.dp)
                        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (priorityMode) {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (active) C.Accent else C.Chip),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (active) C.Bg else C.Text)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (active) "Активен · " else "") + p.subtitle,
                            fontSize = 12.sp, color = C.Muted, maxLines = 1,
                        )
                    }
                    val d = delays[p.id]
                    Text(delayText(d), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = delayColor(d))
                    if (priorityMode) {
                        Column {
                            IconButton(onClick = { vm.move(p.id, -1) }, enabled = i > 0, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Up, contentDescription = "Выше", tint = C.Muted, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.move(p.id, 1) }, enabled = i < ordered.lastIndex, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Down, contentDescription = "Ниже", tint = C.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        val fromSub = p.subscriptionId != null
                        IconButton(onClick = {
                            if (fromSub) vm.setExcluded(listOf(p.id), true) else vm.removeProfile(p.id)
                        }) {
                            Icon(
                                Icons.Close,
                                contentDescription = if (fromSub) "Исключить" else "Удалить",
                                tint = C.Muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            val hidden = all.size - profiles.size
            if (hidden > 0) {
                item {
                    Text(
                        "Не используется серверов: $hidden (исключены или подписка выключена). Настроить — на вкладке «Подписки».",
                        fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { addDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 0.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Добавить ссылку", color = C.Text)
                }
            }
        }
    }

    if (addDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Добавить сервер") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("vless://, vmess://, trojan://, ss://") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.addLinks(text); addDialog = false }, enabled = text.isNotBlank()) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ModeSwitch(mode: SelectionMode, onChange: (SelectionMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(BorderStroke(1.dp, C.Border), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            SelectionMode.FASTEST to "Быстрый",
            SelectionMode.PRIORITY to "Приоритет",
            SelectionMode.MANUAL to "Вручную",
        ).forEach { (m, label) ->
            val sel = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) C.Chip else C.Surface)
                    .clickable(role = Role.Tab) { onChange(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) C.Text else C.Muted)
            }
        }
    }
}
