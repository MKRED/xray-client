package com.mkred.xrayclient.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** QR-код ссылки сервера — чтобы перенести его на другое устройство. */
@Composable
fun QrDialog(title: String, text: String, onClose: () -> Unit) {
    val bmp = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bmp != null) {
                    Image(
                        BitmapPainter(bmp.asImageBitmap()),
                        contentDescription = "QR-код ссылки сервера",
                        modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                    )
                } else {
                    Text("Ссылка слишком длинная для QR-кода", color = C.Warn)
                }
                Text(
                    "Отсканируйте в любом клиенте Xray/v2ray. В ссылке есть ключ доступа — не показывайте её посторонним.",
                    fontSize = 12.sp, color = C.Muted, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть", color = C.AccentText) } },
    )
}
