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
import com.mkred.xrayclient.data.SelectionMode

@Composable
fun ServersScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val profiles by vm.usable.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()

    val priorityMode = data.mode == SelectionMode.PRIORITY

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Серверы", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))

        ModeSwitch(data.mode, vm::setMode, quality = data.metric == com.mkred.xrayclient.data.Metric.QUALITY)

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (data.mode) {
                    SelectionMode.PRIORITY -> "Первый блок с рабочим сервером, внутри — самый быстрый"
                    SelectionMode.MANUAL -> "Нажмите на сервер, чтобы выбрать"
                    SelectionMode.FASTEST ->
                        if (data.metric == com.mkred.xrayclient.data.Metric.QUALITY) "Выбирается сервер с лучшим качеством"
                        else "Выбирается самый быстрый"
                },
                fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.testAll() }, enabled = !testing && profiles.isNotEmpty()) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Проверить", color = C.AccentText)
            }
        }

        if (priorityMode) {
            PriorityBlocksList(vm, Modifier.weight(1f))
        } else LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { _, p ->
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
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (active) "Активен · " else "") +
                                if (data.metric == com.mkred.xrayclient.data.Metric.QUALITY) qualitySubtitle(p) else p.subtitle,
                            fontSize = 12.sp, color = C.Muted, maxLines = 1,
                        )
                    }
                    MetricValue(p)
                    run {
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
                        "Не используется серверов: $hidden (исключены или подписка выключена). Настроить и добавить новые — на вкладке «Источники».",
                        fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

}

@Composable
fun ModeSwitch(mode: SelectionMode, onChange: (SelectionMode) -> Unit, quality: Boolean = false) {
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
            SelectionMode.FASTEST to if (quality) "Лучший" else "Быстрый",
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
