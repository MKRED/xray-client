package com.mkred.xrayclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mkred.xrayclient.log.Sanitizer

/**
 * Экран после падения: текст ошибки, отправка, сброс статистики, продолжить.
 * Показывается при запуске, если в прошлый раз приложение упало.
 */
@Composable
fun CrashScreen(text: String, onResetStats: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val clean = text.lines().joinToString("\n") { Sanitizer.clean(it) }
    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Приложение упало", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Ниже текст ошибки. Отправьте его разработчику — по нему можно найти причину. " +
                "UUID и ссылки на серверы скрыты.",
            fontSize = 13.sp, color = C.Muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Отправить", onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clean)
                context.startActivity(Intent.createChooser(send, "Отправить ошибку"))
            }, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("crash", clean))
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Скопировать", color = C.Text) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(clean, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = C.Text, softWrap = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResetStats, modifier = Modifier.weight(1f)) { Text("Сбросить статистику", color = C.Warn) }
            TextButton(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Открыть приложение", color = C.AccentText) }
        }
    }
}
