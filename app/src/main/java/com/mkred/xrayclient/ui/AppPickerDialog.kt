package com.mkred.xrayclient.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mkred.xrayclient.data.AppInfo
import com.mkred.xrayclient.data.InstalledApps

/**
 * Выбор приложений. Изменения копятся локально и применяются одним разом
 * по «Готово» — чтобы VPN-интерфейс не пересоздавался на каждую галочку.
 */
@Composable
fun AppPickerDialog(title: String, initial: Set<String>, onDone: (Set<String>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { apps = InstalledApps.load(context) }

    // Выбранные — сверху, порядок фиксируется при открытии, чтобы список не прыгал при нажатиях
    val shown = remember(apps, query, showSystem) {
        apps.orEmpty()
            .filter { showSystem || !it.system || it.pkg in initial }
            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedByDescending { it.pkg in initial }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Выбрано: ${selected.size}", fontSize = 13.sp, color = C.Muted)
                }
                TextButton(onClick = onCancel) { Text("Отмена", color = C.Muted) }
                TextButton(onClick = { onDone(selected) }) { Text("Готово", color = C.AccentText) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по названию") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text("Системные") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.AccentBg, selectedLabelColor = C.AccentText),
                )
                if (selected.isNotEmpty()) {
                    FilterChip(selected = false, onClick = { selected = emptySet() }, label = { Text("Снять все") })
                }
            }

            if (apps == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.pkg }) { app ->
                        val checked = app.pkg in selected
                        val toggle = { selected = if (checked) selected - app.pkg else selected + app.pkg }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Checkbox, onClick = toggle)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(app.pkg)
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, fontSize = 11.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle() },
                                colors = CheckboxDefaults.colors(checkedColor = C.Accent, checkmarkColor = C.Bg),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = InstalledApps.icon(context, pkg)?.asImageBitmap()
    }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(C.Chip)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
    }
}
