package com.mkred.xrayclient.core

import android.content.Context
import com.mkred.xrayclient.BuildConfig
import com.mkred.xrayclient.data.AppStore
import com.mkred.xrayclient.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Базы для обхода российских сайтов: урезанные geoip/geosite от runetfreedom
 * (категории ru, private / category-ru, tld-ru, ru-available-only-inside, ru-blocked).
 *
 * Встроенные лежат в assets APK — ядро само найдёт их там, если в filesDir файлов нет.
 * Свежие скачиваются из релиза «rules» репозитория (его раз в сутки обновляет CI)
 * и кладутся в filesDir, перекрывая встроенные.
 */
object GeoRules {
    const val IP_FILE = "ru-geoip.dat"
    const val SITE_FILE = "ru-geosite.dat"
    private const val TAG = "GeoRules"

    private fun url(file: String) = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/rules/$file"

    suspend fun update(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Сначала качаем оба во временные файлы, чтобы не остаться с половиной баз
            val tmp = listOf(IP_FILE, SITE_FILE).map { name ->
                val f = File(context.cacheDir, "$name.tmp")
                Net.request(url(name), readTimeout = 30_000) { conn ->
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                }
                check(f.length() > 1000) { "$name: файл подозрительно мал (${f.length()} байт)" }
                name to f
            }
            tmp.forEach { (name, f) ->
                val dst = File(context.filesDir, name)
                if (!f.renameTo(dst)) {
                    f.copyTo(dst, overwrite = true)
                    f.delete()
                }
            }
            AppStore.update { it.copy(geoUpdatedAt = System.currentTimeMillis()) }
            AppLog.i(TAG, "Базы обновлены: ${tmp.joinToString { "${it.first} ${File(context.filesDir, it.first).length()} байт" }}")
        }.onFailure { AppLog.w(TAG, "Не удалось обновить базы: ${it.message}") }
    }

    /** Скачанные базы старше [days] дней (или ещё не скачивались). */
    fun isStale(days: Int = 3): Boolean =
        System.currentTimeMillis() - AppStore.data.value.geoUpdatedAt > days * 86_400_000L
}
