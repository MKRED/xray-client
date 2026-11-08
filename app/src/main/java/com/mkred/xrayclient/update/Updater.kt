package com.mkred.xrayclient.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mkred.xrayclient.BuildConfig
import com.mkred.xrayclient.core.Net
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Обновление из GitHub Releases. CI на каждую сборку публикует релиз
 * с тегом v1.0.<номер сборки> и файлом xray-client.apk.
 */
object Updater {

    /** Ход загрузки: сколько скачано и сколько всего (-1, если сервер не сообщил размер). */
    data class Progress(val done: Long, val total: Long) {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class Release(val versionCode: Int, val tag: String, val apkUrl: String, val notes: String)

    suspend fun checkLatest(): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val release = runCatching { fromApi() }
                .recoverCatching { e ->
                    AppLog.w("Update", "API GitHub недоступен (${e.message}), пробую страницу релизов")
                    fromReleasesPage()
                }.getOrThrow()
            AppLog.i("Update", "Последний релиз ${release.tag}, установлена сборка ${BuildConfig.VERSION_CODE}")
            release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }.onFailure { AppLog.w("Update", "Проверка обновлений: ${it.message}") }
    }

    private fun fromApi(): Release {
        val json = Net.request(
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            headers = mapOf("Accept" to "application/vnd.github+json"),
        ) { it.inputStream.bufferedReader().use { r -> r.readText() } }
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?: error("В релизе $tag нет APK")
        return Release(codeOf(tag), tag, apk.getString("browser_download_url"), o.optString("body"))
    }

    /** Запасной путь: github.com/…/releases/latest перенаправляет на страницу последнего тега. */
    private fun fromReleasesPage(): Release {
        val repo = BuildConfig.UPDATE_REPO
        val finalUrl = Net.request("https://github.com/$repo/releases/latest") { it.url.toString() }
        val tag = finalUrl.substringAfterLast("/tag/", "").ifEmpty { error("Не удалось узнать последнюю версию") }
        return Release(codeOf(tag), tag, "https://github.com/$repo/releases/download/$tag/xray-client.apk", "")
    }

    private fun codeOf(tag: String) = tag.substringAfterLast('.').toIntOrNull() ?: 0

    /** Скачать APK и открыть системный установщик. */
    suspend fun downloadAndInstall(context: Context, release: Release, onProgress: (Progress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "update.apk")
                Net.request(release.apkUrl, readTimeout = 30_000) { conn ->
                    val total = conn.contentLengthLong
                    onProgress(Progress(0, total))
                    var lastReport = 0L
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                // Не чаще чем раз в 256 КБ, чтобы не дёргать интерфейс
                                if (done - lastReport >= 256 * 1024) {
                                    lastReport = done
                                    onProgress(Progress(done, total))
                                }
                            }
                        }
                    }
                }
                onProgress(Progress(file.length(), file.length()))
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
}
