package com.mkred.xrayclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.data.Subscription

/** Какие серверы подписки использовать: переключатели и слова-фильтры по названию. */
@Composable
fun SubscriptionFilterDialog(vm: MainViewModel, sub: Subscription, onClose: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val all by vm.profiles.collectAsStateWithLifecycle()
    val servers = all.filter { it.subscriptionId == sub.id }
    var words by remember(sub.id) { mutableStateOf(sub.excludeKeywords.joinToString(", ")) }
    val typed = words.split(',', '\n').map(String::trim).filter(String::isNotEmpty)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы подписки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(sub.name, fontSize = 13.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onClose) { Text("Готово", color = C.AccentText) }
            }

            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text("Исключать, если в названии есть") },
                placeholder = { Text("например: Россия, осталось, трафик") },
                supportingText = { Text("Через запятую, регистр не важен. Работает и после обновления подписки.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (typed != sub.excludeKeywords) {
                TextButton(onClick = { vm.setExcludeKeywords(sub.id, words) }) {
                    Text("Применить фильтр", color = C.AccentText)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setExcluded(servers.map { it.id }, false) }, modifier = Modifier.weight(1f)) {
                    Text("Включить все", color = C.Text)
                }
                OutlinedButton(onClick = { vm.setExcluded(servers.map { it.id }, true) }, modifier = Modifier.weight(1f)) {
                    Text("Выключить все", color = C.Text)
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(servers, key = { it.id }) { p ->
                    val byWord = AppStore.excludedByKeyword(p, data)
                    val manuallyOff = p.id in data.excluded
                    val on = byWord == null && !manuallyOff
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (on) C.Text else C.Muted,
                            )
                            Text(
                                if (byWord != null) "скрыт фильтром «$byWord»" else p.subtitle,
                                fontSize = 12.sp,
                                color = if (byWord != null) C.Warn else C.Muted,
                            )
                        }
                        Switch(
                            checked = on,
                            enabled = byWord == null,
                            onCheckedChange = { checked -> vm.setExcluded(listOf(p.id), !checked) },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
                        )
                    }
                }
            }
        }
    }
}
