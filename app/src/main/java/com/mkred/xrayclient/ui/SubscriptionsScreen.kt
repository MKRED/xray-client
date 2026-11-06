package com.mkred.xrayclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Подписки", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refreshSubscriptions() }, enabled = !busy && data.subscriptions.isNotEmpty()) {
                Text("Обновить все", color = C.AccentText)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.subscriptions.isEmpty()) {
                item {
                    Text(
                        "Подписок пока нет. Вставьте ссылку на подписку от вашего провайдера или своей панели (Marzban, 3x-ui, Remnawave).",
                        color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(data.subscriptions, key = { it.id }) { s ->
                val count = profiles.count { it.subscriptionId == s.id }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Серверов: $count · " + if (s.updatedAt > 0) "обновлена ${formatDate(s.updatedAt)}" else "не загружена",
                                fontSize = 12.sp, color = C.Muted,
                            )
                        }
                        IconButton(onClick = { vm.refreshSubscription(s.id) }, enabled = !busy) {
                            Icon(Icons.Refresh, contentDescription = "Обновить", tint = C.Text, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { vm.removeSubscription(s.id) }) {
                            Icon(Icons.Close, contentDescription = "Удалить", tint = C.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    s.info?.takeIf { it.total > 0 }?.let { info ->
                        val used = info.upload + info.download
                        LinearProgressIndicator(
                            progress = { (used.toFloat() / info.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = C.Blue,
                            trackColor = C.Border,
                        )
                        Row {
                            Text("${gb(used)} из ${gb(info.total)}", fontSize = 12.sp, color = C.Muted, modifier = Modifier.weight(1f))
                            if (info.expire > 0) Text("до ${formatDate(info.expire * 1000)}", fontSize = 12.sp, color = C.Muted)
                        }
                    }
                    s.lastError?.let { Text("Ошибка: $it", fontSize = 12.sp, color = C.Error) }
                }
            }
            item {
                PrimaryButton("Добавить подписку", onClick = { addDialog = true }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (addDialog) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Новая подписка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("Ссылка https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Название (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.addSubscription(url, name); addDialog = false },
                    enabled = url.startsWith("http"),
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun gb(bytes: Long) = "%.1f ГБ".format(bytes / 1_073_741_824.0)

private fun formatDate(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(ms))
