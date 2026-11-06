package com.mkred.xrayclient.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mkred.xrayclient.BuildConfig
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление из GitHub Releases. CI на каждую сборку публикует релиз
 * с тегом v1.0.<номер сборки> и файлом xray-client.apk.
 */
object Updater {

    data class Release(val versionCode: Int, val tag: String, val apkUrl: String, val notes: String)

    suspend fun checkLatest(): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val o = JSONObject(json)
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: error("В релизе $tag нет APK")
            val release = Release(code, tag, apk.getString("browser_download_url"), o.optString("body"))
            AppLog.i("Update", "Последний релиз $tag, установлена сборка ${BuildConfig.VERSION_CODE}")
            release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }.onFailure { AppLog.w("Update", "Проверка обновлений: ${it.message}") }
    }

    /** Скачать APK и открыть системный установщик. */
    suspend fun downloadAndInstall(context: Context, release: Release, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "update.apk")
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                AppLog.i("Update", "Скачан ${release.tag}: ${file.length()} байт")
                withContext(Dispatchers.Main) { install(context, file) }
            }.onFailure { AppLog.w("Update", "Обновление: ${it.message}") }
        }

    private fun install(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Один раз нужно разрешить установку из этого приложения
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error("Разрешите установку обновлений и нажмите «Обновить» ещё раз")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
