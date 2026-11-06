package com.mkred.xrayclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mkred.xrayclient.log.Sanitizer

/** Просмотр последних строк лога: ошибки красным, предупреждения жёлтым. */
@Composable
fun LogViewer(vm: MainViewModel, onClose: () -> Unit) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(reload) {
        lines = vm.logTail().map(Sanitizer::clean)
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Лог", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { reload++ }) { Text("Обновить", color = C.AccentText) }
                TextButton(onClick = { vm.shareLog() }) { Text("Отправить", color = C.AccentText) }
                TextButton(onClick = onClose) { Text("Закрыть", color = C.Text) }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = when {
                            " E " in line || "ПАДЕНИЕ" in line -> C.Error
                            " W " in line -> C.Warn
                            "XC/" in line -> C.Text
                            else -> C.Muted
                        },
                    )
                }
            }
        }
    }
}
